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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.kafka.Subscriptions
import akka.kafka.scaladsl.Consumer
import akka.stream.contrib.PassThroughFlow
import akka.stream.{FlowShape, Graph}
import akka.stream.scaladsl.{Broadcast, BroadcastHub, Flow, GraphDSL, Keep, Partition, PartitionHub, Sink, Source, ZipWith}
import cloudpit.Events.{ArenaViewers, PlayersRefresh, ViewerEvent, ViewerJoin, ViewerLeave}
import cloudpit.KafkaSerialization._
import cloudpit.Persistence.FileIO
import cloudpit.Services.DevPlayerService
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.common.TopicPartition
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.{WSRequestExecutor, WSRequestFilter}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.{Await, Future}
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

  def arenaPath(initOrRecord: InitOrRecord): Arena.Path = {
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
  val viewersSource = Source.future(viewersExisting).flatMapConcat { case (initOffset, initViewers) =>
    val viewerEventsSource = Kafka.source[ViewerEvent.Key, ViewerEvent.Value](groupId, Topics.viewerEvents, Some(initOffset + 1))

    Source(initViewers)
      .map[InitOrRecord](Left(_))
      .concat(viewerEventsSource.map[InitOrRecord](Right(_)))
      .groupBy(100, arenaPath)
      .scan(Option.empty[(Option[Long], Arena.Path, Set[UUID])])(updateViewers)
      .mapConcat(_.toList)
      .via(PassThroughFlow(saveViewersFlow, Keep.left))
      .mergeSubstreams
  }

  viewersSource.runForeach(println)

  /*
    groupBy(100, ) .scan(Option.empty[(Arena.Path, Set[UUID])]) { case (current, (maybeInit, maybeMessage)) =>
    current.fold()
  }

   */

    /*
  val viewersSource = initViewersSource.zipLatest(viewerEventsSource).groupBy(100, { case ((initArena, _), _) =>
    initArena
  })

     */

    /*
    .scan(Option.empty[(Arena.Path, Set[UUID])]) { case (maybeCurrent, ((arena, viewers), record)) =>
    maybeCurrent.getOrElse(arena -> viewers)
    updateViewers()
  }

     */

  /*
  // nicely this emits even if there are no new messages to read because concat emits for the future completion
  // : Source[(Arena.Path, Set[UUID]), NotUsed]
  val viewersSource =  .flatMapConcat { case (lastOffset, viewers) =>
    val source = Consumer.plainSource(viewerEventsConsumerSettings, Subscriptions.assignmentWithOffset(new TopicPartition(Topics.viewerEvents, 0), lastOffset + 1)) // todo: partition?

    source
      .map(arenaViewersFlow(viewers))
      .via(PassThroughFlow(saveViewersFlow, Keep.right))
      .map(_._1)
      .mapConcat(identity)
  } //.mapConcat(identity).groupBy(100, _._1) //.mapConcat(identity) //.alsoTo(Sink.foreach(viewers => println("viewers: " -> viewers)))
  */

  //val initViewersSource = Source.future(viewersExisting).mapConcat(_._2).groupBy(100, _._1)

  //val viewerEventsSource = Consumer.plainSource(viewerEventsConsumerSettings, Subscriptions.assignmentWithOffset(new TopicPartition(Topics.viewerEvents, 0), 0 + 1))

  //initViewersSource.zipLatest(viewerEventsSource).map

  //viewersSource.to(Sink.foreach(println)).run()

  /*
  //import scala.language.existentials



  val viewersSource = Kafka.source[ViewerEvent.Key, ViewerEvent.Value]("foo", Topics.viewerEvents).groupBy(100, _.value()).scan(Set.empty[UUID])(updateViewers).mergeSubstreams

  viewersSource.to(Sink.foreach(println)).run()
   */

  val playerSource = Kafka.source[Arena.Path, PlayersRefresh.type](groupId, Topics.playersRefresh)

  // todo: swappable
  val playerService = new DevPlayerService

  def playersSource(arena: Arena.Path): Source[Set[Player], NotUsed] = {
    val initPlayersSource = Source.future(playerService.fetch(arena))

    val updatedPlayersSource = Kafka.source[Arena.Path, PlayersRefresh.type](arena, Topics.playersRefresh).mapAsync(100) { record =>
      playerService.fetch(record.key())
    }

    initPlayersSource.concat(updatedPlayersSource)
  }


  /*
  val playerSource = Kafka.source[Arena.Path, PlayerEvent](groupId, Topics.players)

  val viewerEventsSource = Kafka.source[Arena.Path, ViewerEvent](groupId, Topics.viewerEvents)

  val arenaSink = Kafka.sink[Arena.Path, Map[Player, PlayerState]]

  val viewersSource = Kafka.source[Arena.Path, Int](groupId, Topics.viewers)
  val viewersSink = Kafka.sink[Arena.Path, Int]

  val initViewers = Viewers(Map.empty)
  val initPlayers = Players(Map.empty)
  val initArenasUpdate = ArenasUpdate(Map.empty)


  val playersSource = Source.single(initPlayers).  .fold() { players =>
    playerSource.fold(players) { case (players, record) =>
      val arena = record.key()
      val event = record.value()

      val currentPlayers = players.players.getOrElse(arena, Set.empty)
      val updatedPlayers = event match {
        case PlayerJoin(_, player) =>
          currentPlayers + player
        case PlayerLeave(_, player) =>
          currentPlayers - player
      }

      Players(players.players.updated(arena, updatedPlayers))
    }
  }
   */

  /*
  val viewers = viewersSource.fold(initViewers) { case (viewers, record) =>
    val arena = record.key()
    val numViewers = record.value()
    viewers.copy(viewerCount = viewers.viewerCount.updated(arena, numViewers))
  }

  val viewersSource = Source.single(initViewers).flatMapConcat { viewers =>
    viewerSource.fold(viewers) { case (viewers, record) =>
      val arena = record.key()
      val event = record.value()

      val currentViewers = viewers.viewerCount.getOrElse(arena, 0)
      val updatedViewers = event match {
        case ViewerJoin(_) =>
          currentViewers + 1
        case ViewerLeave(_) if currentViewers > 0 =>
          currentViewers - 1
        case ViewerLeave(_) =>
          0
      }

      Viewers(viewers.viewerCount.updated(arena, updatedViewers))
    }
  }.alsoTo(Sink.foreach(println))
   */

  /*
  val timingRequestFilter = WSRequestFilter { requestExecutor =>
    WSRequestExecutor { request =>
      requestExecutor(request)
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
      "arena" -> Json.toJson(arena)
    )

    wsClient.url(player.service).withRequestFilter(timingRequestFilter).post(json).map { response =>
      response.status match {
        case Status.OK =>
          for {
            command <- response.body.toCharArray.headOption
            move <- Move.parse(command)
          } yield move -> Random.nextInt(5000).millis // todo: request time
        case _ =>
          None
      }
    } recoverWith {
      case _ => Future.successful(None)
    }
  }

  def addPlayerToArena(arena: Map[Player.Service, PlayerState], players: Players, player: Player.Service): Map[Player.Service, PlayerState] = {
    val dimensions = Arena.dimensions(players.players.size)

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

  def isPlayerInPosition(position: (Int, Int))(player: (Player.Service,PlayerState)): Boolean = {
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


  def updateArenas(players: Players, viewers: Viewers, arenas: Arenas): Future[Arenas] = {
    val updatedArenas = viewers.viewerCount.filter(_._2 > 0).map { case (arena, _) =>
      val playersInArena = players.players.getOrElse(arena, Set.empty)
      val currentArena = arenas.arenaPlayers.getOrElse(arena, Map.empty)
                           .view.filterKeys(playersInArena.map(_.service).contains).toMap // filter out players who have left

      val readyArena = playersInArena.foldLeft(currentArena) { case (thisArena, player) =>
        thisArena.get(player.service).fold {
          addPlayerToArena(currentArena, players, player.service)
        } { playerState =>
          thisArena.updated(player.service, playerState.copy(wasHit = false))
        }
      }

      // wtf
      implicit def moveDurationOrdering[A <: (Move, FiniteDuration)]: Ordering[A] = {
        Ordering.by((_:A)._2)
      }

      if (false) {
        moveDurationOrdering
      }
      // eowtf

      val playerMovesFutures = playersInArena.map { player =>
        playerMove(readyArena, player).map(player.service -> _)
      }

      val playerMovesFuture = Future.sequence(playerMovesFutures).map { playerMoves =>
        // if the player didn't make a move, remove it from the moves that need to be performed
        playerMoves.toMap.collect {
          case (k, Some(v)) => k -> v
        }
      }

      val updatedArena = playerMovesFuture.map(performMoves(readyArena))

      updatedArena.map(arena -> _)
    }

    Future.sequence(updatedArenas).map { arenas =>
      Arenas(arenas.toMap)
    }
  }

  def performArenasUpdate(data: (((Players, Viewers), ArenasUpdate), NotUsed)): Future[ArenasUpdate] = {
    val (((players, viewers), arenasUpdate), _) = data
    // todo: cleanup
    // transform ArenasUpdate to Arenas
    val currentArenas = Arenas {
      arenasUpdate.arenas.map { case (arenaPath, arena) =>
        arenaPath -> arena.map { case (player, playerState) =>
          player.service -> playerState
        }
      }
    }

    val arenasFuture = updateArenas(players, viewers, currentArenas)

    // todo: cleanup
    // transform Arenas to ArenaUpdate
    arenasFuture.map { arenas =>
      ArenasUpdate {
        arenas.arenaPlayers.map { case (arenaPath, arena) =>
          arenaPath -> arena.flatMap { case (playerService, playerState) =>
            for {
              playersInArena <- players.players.get(arenaPath)
              player <- playersInArena.find(_.service == playerService)
            } yield player -> playerState
          }
        }
      }
    }
  }

  def arenasUpdateToProducerRecord(arenasUpdate: ArenasUpdate): scala.collection.immutable.Iterable[ProducerRecord[Arena.Path, Map[Player, PlayerState]]] = {
    arenasUpdate.arenas.map { case (arenaPath, arena) =>
      new ProducerRecord(Topics.arenasUpdate, arenaPath, arena)
    }
  }

  val arenasFlow = Flow[(Players, Viewers)]
    .zipLatest(Source.single(initArenasUpdate))
    .zipLatest(Source.repeat(NotUsed))
    .mapAsync(100)(performArenasUpdate)
    .throttle(1, 1.second)
    .alsoTo(Sink.foreach(println))
  */

  /*
  playersSource
    .zipLatest(viewersSource)
    .via(arenasFlow)
    .mapConcat(arenasUpdateToProducerRecord)
    .to(arenaSink)
    .run()
   */

  //playersSource.runForeach(println)

  /*
  def playersInArena(arenaViewers: ArenaViewers): Source[Seq[(Arena.Path, Set[Player])], NotUsed] = {
    val playersSources = arenaViewers.map { case (arena, viewers) =>
      if (viewers.nonEmpty)
        playersSource(arena).map(arena -> _)
      else
        Source.empty
    }

    Source.zipN(playersSources.toSeq)
  }
   */

  /*
  val viewers = Source.empty[(Arena.Path, Set[UUID])]
  def viewersToPlayers(arena: Arena.Path, viewers: Set[UUID]): Source[(Arena.Path, Set[UUID], Set[Player]), NotUsed] = {
    playersSource(arena).map((arena, viewers, _))
  }
  val players = Source.empty[(Arena.Path, Set[Player])]
  val arenas = viewers.flatMapMerge(100, viewersToPlayers)
   */

  //viewersSource.groupBy(100, _._1)
  //viewersSource.groupBy(100, _._1).to(Sink.foreach(println)).run()

  //viewersSource.runForeach(println)

  // todo: problem with this approach is that we refresh the players every time the viewers change
  //val arenasWithViewers = viewersSource.flatMapMerge(100, playersInArena)

  //arenasWithViewers.runForeach(println)

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}
