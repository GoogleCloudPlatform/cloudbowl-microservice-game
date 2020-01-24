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

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import cloudpit.Events.{ArenasUpdate, PlayerEvent, PlayerJoin, PlayerLeave, Players, ViewerEvent, ViewerJoin, ViewerLeave, Viewers}
import cloudpit.KafkaSerialization._
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.ahc.StandaloneAhcWSClient
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

  val playerSource = Kafka.source[Arena.Path, PlayerEvent](groupId, Topics.players)

  val viewerSource = Kafka.source[Arena.Path, ViewerEvent](groupId, Topics.viewers)

  val arenaSink = Kafka.sink[Arena.Path, Map[Player, PlayerState]]


  val initViewers = Viewers(Map.empty)
  val initPlayers = Players(Map.empty)
  val initArenasUpdate = ArenasUpdate(Map.empty)

  val playersSource = playerSource.scan(initPlayers) { case (players, record) =>
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

  val viewersSource = viewerSource.scan(initViewers) { case (viewers, record) =>
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

  def performArenasUpdate(arenasUpdate: ArenasUpdate, playersViewers: ((Players, Viewers), NotUsed)): Future[ArenasUpdate] = {
    val ((players, viewers), _) = playersViewers
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
    .zipLatest(Source.repeat(NotUsed))
    .scanAsync(initArenasUpdate)(performArenasUpdate)
    .throttle(1, 1.second)

  playersSource
    .zipLatest(viewersSource)
    .via(arenasFlow)
    .mapConcat(arenasUpdateToProducerRecord)
    .to(arenaSink)
    .run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}
