/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudpit

import java.io.{File, FileNotFoundException}
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.contrib.PassThroughFlow
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import cloudpit.Events.{ArenaDimsAndPlayers, ArenaUpdate, PlayersRefresh, ViewerEvent, ViewerJoin, ViewerLeave}
import cloudpit.KafkaSerialization._
import cloudpit.Persistence.FileIO
import cloudpit.Services.DevPlayerService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.ahc.{StandaloneAhcWSClient, StandaloneAhcWSResponse}
import play.api.libs.ws.{WSRequestExecutor, WSRequestFilter}

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

object Battle extends App {

  implicit val actorSystem = ActorSystem()

  implicit val ec = actorSystem.dispatcher

  val wsClient = StandaloneAhcWSClient()

  // no consumer group partitioning
  val groupId = UUID.randomUUID().toString

  // todo: swappable
  val io = new FileIO(new File("/tmp/battle"))

  val viewersExisting = io.restore[Set[UUID]](Topics.viewerEvents).recover {
    case _: FileNotFoundException => 0L -> Map.empty[Arena.Path, Set[UUID]]
  }

  type InitOrRecord = Either[(Arena.Path, Set[UUID]), ConsumerRecord[ViewerEvent.Key, ViewerEvent.Value]]

  // todo: periodic persistence
  val saveViewersFlow = Flow[(Option[Long], Arena.Path, Set[UUID])].mapAsync(1) { case (maybeOffset, arena, viewers) =>
    maybeOffset.fold(Future.successful(())) { offset =>
      io.save(Topics.viewerEvents, arena, offset, viewers)
    }
  }

  def arenaPathFromInitOrRecord(initOrRecord: InitOrRecord): Arena.Path = {
    initOrRecord match {
      case Left(init) => init._1
      case Right(record) => record.value()
    }
  }

  def updateViewers(maybeState: Option[(Option[Long], Arena.Path, Set[UUID])], initOrRecord: InitOrRecord): Option[(Option[Long], Arena.Path, Set[UUID])] = {
    initOrRecord match {
      case Left(arena -> init) =>
        Some((None, arena, init))
      case Right(record) =>
        val arena = record.value()
        val viewers = maybeState.map(_._3).getOrElse(Set.empty[UUID])
        val (viewerId, eventType) = record.key()

        val updatedViewers = eventType match {
          case ViewerJoin => viewers + viewerId
          case ViewerLeave => viewers - viewerId
        }

        Some((Some(record.offset()), arena, updatedViewers))
    }
  }

  // Get any existing projections
  // Based on the latest offset of those projections, start there in the stream
  // Transform the existing projections & events in the stream into a form that can be concatenated
  // Group the streams by the arena
  // Fold the existing projections with events
  // Remove the initial empty emit since `scan` emits with the initial value
  // Save the updated projection
  // Ultimately emitting an event for each arena from a projection and any viewer updates following
  val viewersSource = Source.future(viewersExisting).flatMapConcat { case (initOffset, initViewers) =>
    val viewerEventsSource = Kafka.source[ViewerEvent.Key, ViewerEvent.Value](groupId, Topics.viewerEvents, Some(initOffset + 1))

    Source(initViewers)
      .map[InitOrRecord](Left(_))
      .concat(viewerEventsSource.map[InitOrRecord](Right(_)))
      .groupBy(Int.MaxValue, arenaPathFromInitOrRecord)
      .scan(Option.empty[(Option[Long], Arena.Path, Set[UUID])])(updateViewers)
      .mapConcat(_.toList)
      .via(PassThroughFlow(saveViewersFlow, Keep.left))
      .map(viewers => viewers._2 -> viewers._3) // drop the offset
      .alsoTo(Sink.foreach(println)) // todo: debugging
      .mergeSubstreams
  }

  // todo: swappable
  val playerService = new DevPlayerService

  val playersRefreshSource = Kafka.source[Arena.Path, PlayersRefresh.type](groupId, Topics.playersRefresh).mapAsync(Int.MaxValue) { record =>
    playerService.fetch(record.key()).map(record.key() -> _)
  }

  type ViewersOrPlayers = Either[(Arena.Path, Set[UUID]), (Arena.Path, Set[Player])]
  type MaybeViewersAndMaybePlayers = (Arena.Path, Option[Set[UUID]], Option[Set[Player]])
  type ViewersAndPlayers = (Arena.Path, Set[UUID], Set[Player])

  def updatePlayers(arenaViewersAndPlayers: Option[MaybeViewersAndMaybePlayers], viewersOrPlayers: ViewersOrPlayers): Future[Option[MaybeViewersAndMaybePlayers]] = {
    viewersOrPlayers.fold({ case (arena, viewers) =>
      // We have the viewers, if we don't have the players, fetch them
      arenaViewersAndPlayers.flatMap(_._3).map(Future.successful).getOrElse {
        playerService.fetch(arena)
      } map { players =>
        Some((arena, Some(viewers), Some(players)))
      }
    }, { case (arena, players) =>
      // We have the players, and maybe the viewers
      Future.successful {
        Some((arena, arenaViewersAndPlayers.flatMap(_._2), Some(players)))
      }
    })
  }

  def arenaPathFromViewerOrPlayers(viewersOrPlayers: ViewersOrPlayers): Arena.Path = {
    viewersOrPlayers match {
      case Left((arena, _)) => arena
      case Right((arena, _)) => arena
    }
  }

  def onlyArenasWithViewersAndPlayers(maybeArenaViewersAndPlayers: Option[MaybeViewersAndMaybePlayers]): scala.collection.immutable.Iterable[ViewersAndPlayers] = {
    val maybe = for {
      arenaViewersAndPlayers <- maybeArenaViewersAndPlayers
      viewers <- arenaViewersAndPlayers._2
      players <- arenaViewersAndPlayers._3
    } yield (arenaViewersAndPlayers._1, viewers, players)

    maybe.toList
  }

  // Emits with the initial state of viewers & players, and then emits whenever the viewers or players change
  val viewersAndPlayersSource = viewersSource
    .map(Left(_))
    .merge(playersRefreshSource.map(Right(_)))
    .groupBy(Int.MaxValue, arenaPathFromViewerOrPlayers)
    .scanAsync(Option.empty[MaybeViewersAndMaybePlayers])(updatePlayers)
    .mapConcat(onlyArenasWithViewersAndPlayers)
  //.mergeSubstreams

  case class ResponseWithDuration(response: play.shaded.ahc.org.asynchttpclient.Response, duration: FiniteDuration) extends StandaloneAhcWSResponse(response)

  val timingRequestFilter = WSRequestFilter { requestExecutor =>
    WSRequestExecutor { request =>
      val startTime = System.nanoTime()
      requestExecutor(request).map { response =>
        val endTime = System.nanoTime()
        val duration = FiniteDuration(endTime - startTime, TimeUnit.NANOSECONDS)
        ResponseWithDuration(response.underlying[play.shaded.ahc.org.asynchttpclient.Response], duration)
      }
    }
  }

  // always returns a successful future
  //
  // todo: score
  // POST
  //
  // {
  //   "_links": {
  //     "self": {
  //       "href": "http://foo.com"
  //      }
  //   },
  //   "arena": {
  //     "http://foo.com": {
  //       "x": 1,
  //       "y": 2,
  //       "direction": "N",
  //       "wasHit": false
  //     }
  //   }
  // }
  //
  def playerMove(arena: Map[Player.Service, PlayerState], player: Player): Future[Option[(Move, FiniteDuration)]] = {
    val json = Json.obj(
      "_links" -> Json.obj(
        "self" -> Json.obj(
          "href" -> player.service
        )
      ),
      "arena" -> Json.obj(
        "dims" -> Arena.dimensions(arena.keys.size),
        "state" -> Json.toJson(arena)
      )
    )

    wsClient.url(player.service).withRequestFilter(timingRequestFilter).post(json).collect {
      case response: ResponseWithDuration =>
        response.status match {
          case Status.OK =>
            for {
              command <- response.body.toCharArray.headOption
              move <- Move.parse(command)
            } yield move -> response.duration
          case _ =>
            None
        }
    } recoverWith {
      case _ => Future.successful(None)
    }
  }

  def addPlayerToArena(arena: Map[Player.Service, PlayerState], players: Set[Player], player: Player.Service): Map[Player.Service, PlayerState] = {
    val dimensions = Arena.dimensions(players.size)

    val board = for {
      x <- 0 to dimensions._1
      y <- 0 to dimensions._2
    } yield x -> y

    val taken = arena.values.map(player => player.x -> player.y)

    val open = board.diff(taken.toSeq)

    val spot = Random.shuffle(open).head

    arena.updated(player, PlayerState(spot._1, spot._2, Direction.random, false))
  }

  def forward(playerState: PlayerState, num: Int): (Int, Int) = {
    playerState.direction match {
      case Direction.N => (playerState.x, playerState.y - num)
      case Direction.W => (playerState.x - num, playerState.y)
      case Direction.S => (playerState.x, playerState.y + num)
      case Direction.E => (playerState.x + num, playerState.y)
    }
  }

  def isPlayerInPosition(position: (Int, Int))(player: (Player.Service, PlayerState)): Boolean = {
    player._2.x == position._1 && player._2.y == position._2
  }

  def movePlayerForward(arena: Map[Player.Service, PlayerState], player: Player.Service, playerState: PlayerState): Map[Player.Service, PlayerState] = {
    val dimensions = Arena.dimensions(arena.keys.size)

    val newTentativePosition = forward(playerState, 1)

    val isOtherPlayerInPosition = arena.exists(isPlayerInPosition(newTentativePosition))

    val isOutOfBounds = newTentativePosition._1 < 0 || newTentativePosition._1 > dimensions._1 - 1 ||
      newTentativePosition._2 < 0 || newTentativePosition._2 > dimensions._2 - 1

    if (isOtherPlayerInPosition || isOutOfBounds)
      arena
    else
      arena.updated(player, playerState.copy(x = newTentativePosition._1, y = newTentativePosition._2))
  }

  def playerThrow(arena: Map[Player.Service, PlayerState], player: Player.Service, playerState: PlayerState): Map[Player.Service, PlayerState] = {
    (1 to Arena.throwDistance).foldLeft(arena -> false) { case ((current, hit), distance) =>
      if (hit) {
        current -> true
      }
      else {
        val target = forward(playerState, distance)
        val maybeHitPlayer = current.find(isPlayerInPosition(target))
        maybeHitPlayer.fold(current -> false) { case (hitPlayer, hitPlayerState) =>
          current.updated(hitPlayer, hitPlayerState.copy(wasHit = true)) -> true
        }
      }
    }._1
  }

  def performMoves(currentArena: Map[Player.Service, PlayerState])
                  (moves: Map[Player.Service, (Move, FiniteDuration)]): Map[Player.Service, PlayerState] = {

    val movesByShortest = moves.toSeq.sortBy(_._2._2)

    movesByShortest.foldLeft(currentArena) { case (arena, (player, (move, _))) =>
      arena.get(player).fold(arena) { currentPlayerState =>
        move match {
          case TurnLeft =>
            val newPlayerState = currentPlayerState.copy(direction = Direction.left(currentPlayerState.direction))
            arena.updated(player, newPlayerState)
          case TurnRight =>
            val newPlayerState = currentPlayerState.copy(direction = Direction.right(currentPlayerState.direction))
            arena.updated(player, newPlayerState)
          case Forward =>
            movePlayerForward(arena, player, currentPlayerState)
          case Throw =>
            playerThrow(arena, player, currentPlayerState)
        }
      }
    }
  }

  type ArenaState = (Arena.Path, Set[UUID], Set[Player], Map[Player.Service, PlayerState])

  def updateArena(current: ArenaState): Future[ArenaState] = {
    val (arena, viewers, players, state) = current

    val stateWithGonePlayersRemoved = state.view.filterKeys(players.map(_.service).contains)

    val readyArena = players.foldLeft(stateWithGonePlayersRemoved.toMap) { case (currentState, player) =>
      currentState.get(player.service).fold {
        addPlayerToArena(currentState, players, player.service)
      } { playerState =>
        currentState.updated(player.service, playerState.copy(wasHit = false))
      }
    }

    // wtf
    implicit def moveDurationOrdering[A <: (Move, FiniteDuration)]: Ordering[A] = {
      Ordering.by((_: A)._2)
    }

    if (false) {
      moveDurationOrdering
    }
    // eowtf

    val playerMovesFutures = players.map { player =>
      playerMove(readyArena, player).map(player.service -> _)
    }

    val playerMovesFuture = Future.sequence(playerMovesFutures).map { playerMoves =>
      // if the player didn't make a move, remove it from the moves that need to be performed
      playerMoves.toMap.collect {
        case (k, Some(v)) => k -> v
      }
    }

    val updatedArena = playerMovesFuture.map(performMoves(readyArena))

    updatedArena.map((arena, viewers, players, _))
  }

  def arenaStateToArenaUpdate(arenaState: ArenaState): ArenaUpdate = {
    val playersStates = arenaState._3.flatMap { player =>
      arenaState._4.get(player.service).map(player -> _)
    }.toMap

    arenaState._1 -> (Arena.dimensions(arenaState._3.size) -> playersStates)
  }

  def performArenaUpdate(maybeArenaState: Option[ArenaState], data: (ViewersAndPlayers, NotUsed)): Future[Option[ArenaState]] = {
    val (viewersAndPlayers, _) = data

    val arenaState = maybeArenaState.flatMap { case (_, _, currentPlayers, playersState) =>
      if (currentPlayers == viewersAndPlayers._3)
      // update the viewers
        Some((viewersAndPlayers._1, viewersAndPlayers._2, viewersAndPlayers._3, playersState))
      else
      // if the players changes, reinit the playersState
        Option.empty
    } getOrElse {
      (viewersAndPlayers._1, viewersAndPlayers._2, viewersAndPlayers._3, Map.empty[Player.Service, PlayerState])
    }

    updateArena(arenaState).map(Some(_))
  }

  // todo: currently no persistence of ArenaState so it is lost on restart
  val arenaUpdateFlow = Flow[ViewersAndPlayers]
    .zipLatest(Source.repeat(NotUsed))
    .filter(_._1._2.nonEmpty) // only arenas with viewers
    .scanAsync(Option.empty[ArenaState])(performArenaUpdate)
    .mapConcat(_.toList)
    .throttle(1, 1.second)
    .map(arenaStateToArenaUpdate)
    .alsoTo(Sink.foreach(println)) // todo: debugging


  def arenaUpdateToProducerRecord(arenaUpdate: ArenaUpdate): ProducerRecord[Arena.Path, ArenaDimsAndPlayers] = {
    new ProducerRecord(Topics.arenaUpdate, arenaUpdate._1, arenaUpdate._2)
  }

  val arenaUpdateSink = Kafka.sink[Arena.Path, ArenaDimsAndPlayers]

  viewersAndPlayersSource
    .via(arenaUpdateFlow)
    .map(arenaUpdateToProducerRecord)
    .to(arenaUpdateSink)
    .run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}
