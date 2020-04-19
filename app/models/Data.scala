/*
 * Copyright 2020 Google LLC
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

package models

import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.{Sink, Source}
import akka.{Done, NotUsed}
import models.Direction.Direction
import models.Events.{ArenaDimsAndPlayers, ArenaUpdate, PlayersRefresh}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.http.Status
import play.api.libs.json.{JsString, Json, Writes}
import play.api.libs.ws.ahc.{AhcWSResponse, StandaloneAhcWSResponse}
import play.api.libs.ws.{StandaloneWSResponse, WSClient, WSRequestExecutor, WSRequestFilter}
import play.api.{Configuration, Logger}
import services.{DevPlayers, GitHub, GitHubPlayers, GoogleSheetPlayers, GoogleSheetPlayersConfig, Kafka, Players, Topics}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

case class Player(service: Player.Service, name: String, pic: URL)

case class PlayerState(x: Int, y: Int, direction: Direction.Direction, wasHit: Boolean, score: Int, hitBy: Set[Player.Service] = Set.empty, responseTime: Option[FiniteDuration] = None)


object Arena {

  val logger = Logger("Arena").logger

  object KafkaSinksAndSources {
    import services.KafkaSerialization._

    def viewerEventSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, UUID], Future[Done]] = {
      Kafka.sink[Arena.Path, UUID]
    }

    def playersRefreshSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, PlayersRefresh.type], Future[Done]] = {
      Kafka.sink[Arena.Path, PlayersRefresh.type]
    }

    def arenaUpdateSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, ArenaDimsAndPlayers], Future[Done]] = {
      Kafka.sink[Arena.Path, ArenaDimsAndPlayers]
    }


    def viewerPingSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, UUID], Control] = {
      Kafka.source[Arena.Path, UUID](groupId, Topics.viewerPing)
    }

    def playersRefreshSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, PlayersRefresh.type], Control] = {
      Kafka.source[Arena.Path, PlayersRefresh.type](groupId, Topics.playersRefresh)
    }

    def arenaUpdateSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, ArenaDimsAndPlayers], Control] = {
      Kafka.source[Arena.Path, ArenaDimsAndPlayers](groupId, Topics.arenaUpdate)
    }

  }

  // config
  val throwDistance: Int = 3
  val fullness: Double = 0.15
  val aspectRatio: Double = 4.0 / 3.0
  // config


  type Name = String
  type Path = String
  type EmojiCode = String

  // todo: refactor to case classes
  case class PathedViewers(path: Path, viewers: Set[UUID])
  case class PathedPlayers(path: Path, players: Set[Player])
  case class ArenaConfigAndPlayers(path: Path, name: Name, emojiCode: EmojiCode, players: Set[Player])
  type ViewersOrPlayers = Either[PathedViewers, ArenaConfigAndPlayers]
  case class MaybeViewersAndMaybePlayers(path: Path, maybeViewers: Option[Set[UUID]], maybeArenaConfigAndPlayers: Option[ArenaConfigAndPlayers])
  case class ViewersAndPlayers(path: Path, name: Name, emojiCode: EmojiCode, viewers: Set[UUID], players: Set[Player])
  case class ArenaState(path: Path, name: Name, emojiCode: EmojiCode, viewers: Set[UUID], players: Set[Player], playerStates: Map[Player.Service, PlayerState])

  case class ResponseWithDuration(response: StandaloneWSResponse, duration: FiniteDuration) extends StandaloneAhcWSResponse(response.underlying[play.shaded.ahc.org.asynchttpclient.Response])

  case class Position(x: Int, y: Int)
  case class Dimensions(width: Int, height: Int)

  def calcDimensions(numPlayers: Int): Dimensions = {
    val volume = numPlayers / fullness
    val width = Math.round(Math.sqrt(volume * aspectRatio)).intValue()
    val height = width / aspectRatio
    Dimensions(width, height.toInt)
  }

  // keeps track of the viewers
  // if the message is a ping, we make sure the UUID is in the list of viewers
  // if the message is not a ping, we check for viewers who have not pinged in 30 seconds and remove them
  // only emits if the viewers has changed
  def viewersUpdate(): Either[ConsumerRecord[Path, UUID], NotUsed] => scala.collection.immutable.Iterable[PathedViewers] = {
    var maybeArena: Option[Path] = None
    val viewers = scala.collection.mutable.Map[UUID, Long]()

    val noChanges = Seq.empty[PathedViewers]

    {
      case Left(record) =>
        maybeArena = Some(record.key())

        if (viewers.contains(record.value())) {
          viewers.update(record.value(), System.currentTimeMillis())
          noChanges
        }
        else {
          viewers += record.value() -> System.currentTimeMillis()
          Seq(PathedViewers(record.key(), viewers.keySet.toSet))
        }

      case Right(_) =>
        maybeArena.fold(noChanges) { arena =>
          val now = System.currentTimeMillis()
          val viewersToPurge = viewers.filter { case (_, lastPing) =>
            lastPing < (now - (1000 * 30))
          }

          if (viewersToPurge.isEmpty) {
            noChanges
          }
          else {
            viewers --= viewersToPurge.keys
            Seq(PathedViewers(arena, viewers.keySet.toSet))
          }
        }
    }
  }

  // todo: better
  def playerService(implicit ec: ExecutionContext, actorSystem: ActorSystem, wsClient: WSClient): Players = {
    val googleSheetPlayersConfig = new GoogleSheetPlayersConfig(Configuration(actorSystem.settings.config))
    val gitHub = new GitHub(Configuration(actorSystem.settings.config), wsClient)
    if (googleSheetPlayersConfig.isConfigured)
      new GoogleSheetPlayers(googleSheetPlayersConfig, wsClient)
    else if (gitHub.isConfigured)
      new GitHubPlayers(gitHub, wsClient)
    else
      new DevPlayers
  }

  def updatePlayers(arenaViewersAndPlayers: Option[MaybeViewersAndMaybePlayers], viewersOrPlayers: ViewersOrPlayers)(implicit ec: ExecutionContext, actorSystem: ActorSystem, wsClient: WSClient): Future[Option[MaybeViewersAndMaybePlayers]] = {
    viewersOrPlayers.fold({ pathedViewers =>
      // We have the viewers, if we don't have the players, fetch them
      arenaViewersAndPlayers.flatMap(_.maybeArenaConfigAndPlayers).map(Future.successful).getOrElse {
        playerService.fetch(pathedViewers.path)
      } map { arenaConfigAndPlayers =>
        Some(MaybeViewersAndMaybePlayers(pathedViewers.path, Some(pathedViewers.viewers), Some(arenaConfigAndPlayers)))
      } recover {
        case t: Throwable =>
          logger.error(s"Could not fetch arena config and players: ${pathedViewers.path}", t)
          Option.empty[MaybeViewersAndMaybePlayers]
      }
    }, { arenaConfigAndPlayers =>
      // We have the players, and maybe the viewers
      Future.successful {
        Some(MaybeViewersAndMaybePlayers(arenaConfigAndPlayers.path, arenaViewersAndPlayers.flatMap(_.maybeViewers), Some(arenaConfigAndPlayers)))
      }
    })
  }

  def arenaPathFromViewerOrPlayers(viewersOrPlayers: ViewersOrPlayers): Path = {
    viewersOrPlayers match {
      case Left(PathedViewers(path, _)) => path
      case Right(ArenaConfigAndPlayers(path, _, _, _)) => path
    }
  }

  def onlyArenasWithViewersAndPlayers(maybeArenaViewersAndPlayers: Option[MaybeViewersAndMaybePlayers]): scala.collection.immutable.Iterable[ViewersAndPlayers] = {
    val maybe = for {
      arenaViewersAndPlayers <- maybeArenaViewersAndPlayers
      viewers <- arenaViewersAndPlayers.maybeViewers
      ArenaConfigAndPlayers(_, name, emojiCode, players) <- arenaViewersAndPlayers.maybeArenaConfigAndPlayers
    } yield ViewersAndPlayers(arenaViewersAndPlayers.path, name, emojiCode, viewers, players)

    maybe.toList
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
  def playerMoveWs(arena: Map[Player.Service, PlayerState], player: Player)(implicit ec: ExecutionContext, wsClient: WSClient): Future[Option[(Move, FiniteDuration)]] = {
    import PlayerState.jsonWrites

    val dims = calcDimensions(arena.keys.size)

    val json = Json.obj(
      "_links" -> Json.obj(
        "self" -> Json.obj(
          "href" -> player.service
        )
      ),
      "arena" -> Json.obj(
        "dims" -> Array(dims.width, dims.height),
        "state" -> Json.toJson(arena)
      )
    )

    // todo: it'd be nice to not reinit this every time
    val timingRequestFilter = WSRequestFilter { requestExecutor =>
      WSRequestExecutor { request =>
        val startTime = System.nanoTime()
        requestExecutor(request).map { response =>
          val endTime = System.nanoTime()
          val duration = FiniteDuration(endTime - startTime, TimeUnit.NANOSECONDS)
          ResponseWithDuration(response, duration)
        }
      }
    }

    wsClient.url(player.service).withRequestFilter(timingRequestFilter).withRequestTimeout(1.second).post(json).collect {
      case AhcWSResponse(response: ResponseWithDuration) =>
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
      case _ =>
        Future.successful(None)
    }
  }

  def addPlayerToArena(arena: Map[Player.Service, PlayerState], players: Set[Player], player: Player.Service): Map[Player.Service, PlayerState] = {
    val dimensions = calcDimensions(players.size)

    val board = for {
      x <- 0 until dimensions.width
      y <- 0 until dimensions.height
    } yield x -> y

    val taken = arena.values.map(player => player.x -> player.y)

    val open = board.diff(taken.toSeq)

    val spot = Random.shuffle(open).head

    arena.updated(player, PlayerState(spot._1, spot._2, Direction.random, false, 0, Set.empty))
  }

  def forward(playerState: PlayerState, num: Int): Position = {
    playerState.direction match {
      case Direction.N => Position(playerState.x, playerState.y - num)
      case Direction.W => Position(playerState.x - num, playerState.y)
      case Direction.S => Position(playerState.x, playerState.y + num)
      case Direction.E => Position(playerState.x + num, playerState.y)
    }
  }

  def isPlayerInPosition(position: Position)(player: (Player.Service, PlayerState)): Boolean = {
    player._2.x == position.x && player._2.y == position.y
  }

  def movePlayerForward(arena: Map[Player.Service, PlayerState], player: Player.Service, playerState: PlayerState): Map[Player.Service, PlayerState] = {
    val dimensions = calcDimensions(arena.keys.size)

    val newTentativePosition = forward(playerState, 1)

    val isOtherPlayerInPosition = arena.exists(isPlayerInPosition(newTentativePosition))

    val isOutOfBounds = newTentativePosition.x < 0 || newTentativePosition.x > dimensions.width - 1 ||
      newTentativePosition.y < 0 || newTentativePosition.y > dimensions.height - 1

    if (isOtherPlayerInPosition || isOutOfBounds)
      arena
    else
      arena.updated(player, playerState.copy(x = newTentativePosition.x, y = newTentativePosition.y))
  }

  def playerThrow(arena: Map[Player.Service, PlayerState], player: Player.Service, playerState: PlayerState): Map[Player.Service, PlayerState] = {
    (1 to throwDistance).foldLeft(arena -> false) { case ((current, hit), distance) =>
      if (hit) {
        current -> true
      }
      else {
        val target = forward(playerState, distance)
        val maybeHitPlayer = current.find(isPlayerInPosition(target))
        maybeHitPlayer.fold(current -> false) { case (hitPlayer, hitPlayerState) =>
          if (playerState.hitBy.contains(hitPlayer)) {
            // this player can't hit a player who already hit them
            current -> true
          }
          else {
            val updatedPlayerStates = current
              .updated(hitPlayer, hitPlayerState.copy(wasHit = true, score = hitPlayerState.score - 1, hitBy = hitPlayerState.hitBy + player))
              .updated(player, playerState.copy(score = playerState.score + 1))

            updatedPlayerStates -> true
          }
        }
      }
    }._1
  }

  def performMoves(currentArena: Map[Player.Service, PlayerState])
                  (moves: Map[Player.Service, (Move, FiniteDuration)]): Map[Player.Service, PlayerState] = {

    val movesByShortest = moves.toSeq.sortBy(_._2._2)

    val arenaWithResetHits = currentArena.view.mapValues(_.copy(wasHit = false, hitBy = Set.empty, responseTime = None)).toMap

    movesByShortest.foldLeft(arenaWithResetHits) { case (arena, (player, (move, responseTime))) =>
      arena.get(player).fold(arena) { currentPlayerState =>
        val currentPlayerStateUpdatedWithResponseTime = currentPlayerState.copy(responseTime = Some(responseTime))
        val arenaWithUpdatedResponseTime = arena.updated(player, currentPlayerStateUpdatedWithResponseTime)

        move match {
          case TurnLeft =>
            val newPlayerState = currentPlayerStateUpdatedWithResponseTime.copy(direction = Direction.left(currentPlayerStateUpdatedWithResponseTime.direction))
            arenaWithUpdatedResponseTime.updated(player, newPlayerState)
          case TurnRight =>
            val newPlayerState = currentPlayerStateUpdatedWithResponseTime.copy(direction = Direction.right(currentPlayerStateUpdatedWithResponseTime.direction))
            arenaWithUpdatedResponseTime.updated(player, newPlayerState)
          case Forward =>
            movePlayerForward(arenaWithUpdatedResponseTime, player, currentPlayerStateUpdatedWithResponseTime)
          case Throw =>
            playerThrow(arenaWithUpdatedResponseTime, player, currentPlayerStateUpdatedWithResponseTime)
        }
      }
    }
  }

  def updateArena(current: ArenaState)
                 (playerMove: (Map[Player.Service, PlayerState], Player) => Future[Option[(Move, FiniteDuration)]])
                 (implicit ec: ExecutionContext): Future[ArenaState] = {

    val stateWithGonePlayersRemoved = current.playerStates.view.filterKeys(current.players.map(_.service).contains)

    val readyArena = current.players.foldLeft(stateWithGonePlayersRemoved.toMap) { case (currentState, player) =>
      currentState.get(player.service).fold {
        addPlayerToArena(currentState, current.players, player.service)
      } { playerState =>
        currentState.updated(player.service, playerState)
      }
    }

    // wtf
    implicit def moveDurationOrdering[A <: (Move, FiniteDuration)]: Ordering[A] = {
      Ordering.by((_: A)._2)
    }

    if (false) {
      moveDurationOrdering
    }
    // wtf

    val playerMovesFutures = current.players.map { player =>
      playerMove(readyArena, player).map(player.service -> _)
    }

    val playerMovesFuture = Future.sequence(playerMovesFutures).map { playerMoves =>
      // if the player didn't make a move, remove it from the moves that need to be performed
      playerMoves.toMap.collect {
        case (k, Some(v)) => k -> v
      }
    }

    val updatedArena = playerMovesFuture.map(performMoves(readyArena))

    updatedArena.map(ArenaState(current.path, current.name, current.emojiCode, current.viewers, current.players, _))
  }

  def arenaStateToArenaUpdate(arenaState: ArenaState): ArenaUpdate = {
    val playersStates = arenaState.players.flatMap { player =>
      arenaState.playerStates.get(player.service).map(player -> _)
    }.toMap

    ArenaUpdate(arenaState.path, ArenaDimsAndPlayers(arenaState.name, arenaState.emojiCode, calcDimensions(arenaState.players.size), playersStates))
  }

  def performArenaUpdate(maybeArenaState: Option[ArenaState], data: (ViewersAndPlayers, NotUsed))(implicit ec: ExecutionContext, wsClient: WSClient): Future[Option[ArenaState]] = {
    val (viewersAndPlayers, _) = data

    val arenaState = maybeArenaState.flatMap { arenaState =>
      if (arenaState.players == viewersAndPlayers.players) {
        // update the viewers
        Some(ArenaState(viewersAndPlayers.path, viewersAndPlayers.name, viewersAndPlayers.emojiCode, viewersAndPlayers.viewers, viewersAndPlayers.players, arenaState.playerStates))
      }
      else {
        // if the players changes, reinit the playersState
        Option.empty
      }
    } getOrElse {
      ArenaState(viewersAndPlayers.path, viewersAndPlayers.name, viewersAndPlayers.emojiCode, viewersAndPlayers.viewers, viewersAndPlayers.players, Map.empty[Player.Service, PlayerState])
    }

    updateArena(arenaState)(playerMoveWs).map(Some(_))
  }

}



object Player {
  type Service = String
  implicit val urlWrites = Writes[URL](url => JsString(url.toString))
  implicit val playerWrites = Json.writes[Player]
}

// todo: encode the circular laws in types
object Direction {

  sealed trait Direction

  case object N extends Direction

  case object W extends Direction

  case object S extends Direction

  case object E extends Direction

  implicit val jsonWrites = Writes[Direction] {
    case N => JsString("N")
    case W => JsString("W")
    case S => JsString("S")
    case E => JsString("E")
  }

  def left(direction: Direction): Direction = {
    direction match {
      case N => W
      case W => S
      case S => E
      case E => N
    }
  }

  def right(direction: Direction): Direction = {
    left(left(left(direction)))
  }

  def random: Direction = {
    Random.shuffle(Seq(N, W, S, E)).head
  }
}

object PlayerState {
  import play.api.libs.json._
  import play.api.libs.functional.syntax._

  implicit val jsonWrites: Writes[PlayerState] = (
      (__ \ "x").write[Int] ~
      (__ \ "y").write[Int] ~
      (__ \ "direction").write[Direction] ~
      (__ \ "wasHit").write[Boolean] ~
      (__ \ "score").write[Int]
    ) { playerState: PlayerState =>
    (playerState.x, playerState.y, playerState.direction, playerState.wasHit, playerState.score)
  }
}

sealed abstract class Move(val command: Char)

case object Forward extends Move('F')

case object TurnRight extends Move('R')

case object TurnLeft extends Move('L')

case object Throw extends Move('T')

object Move {
  def parse(command: Char): Option[Move] = {
    if (command == Forward.command)
      Some(Forward)
    else if (command == TurnRight.command)
      Some(TurnRight)
    else if (command == TurnLeft.command)
      Some(TurnLeft)
    else if (command == Throw.command)
      Some(Throw)
    else
      None
  }
}
