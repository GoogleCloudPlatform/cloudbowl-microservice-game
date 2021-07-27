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

import akka.Done
import akka.actor.{ActorSystem, Props}
import akka.kafka.scaladsl.Consumer.Control
import akka.stream.scaladsl.{Sink, Source}
import models.Direction.Direction
import models.Events.{ArenaUpdate, PlayerJoin, PlayerLeave, PlayerUpdate, ScoresReset}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization._
import play.api.Logger
import play.api.http.Status
import play.api.libs.json.{JsObject, JsString, Json, Writes}
import play.api.libs.ws.ahc.{AhcWSResponse, StandaloneAhcWSResponse}
import play.api.libs.ws.{StandaloneWSResponse, WSClient, WSRequestExecutor, WSRequestFilter}
import services.{Chaos, Kafka}

import java.net.URL
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.DurationConverters._
import scala.util.Random

case class Player(service: Player.Service, name: String, pic: URL)

case class PlayerState(x: Int, y: Int, direction: Direction.Direction, wasHit: Boolean, score: Int, hitBy: Set[Player.Service] = Set.empty, responseTime: Option[FiniteDuration] = None)


object Arena {

  private val logger = Logger("Arena").logger

  object KafkaConfig {

    object Topics {
      val playerUpdate = "player-update"
      val arenaConfig = "arena-config"
      val viewerPing = "viewer-ping"
      val arenaUpdate = "arena-update"
      val scoresReset = "scores-reset"
    }

    object Serialization {

      import upickle.default._

      implicit val urlReadWriter: ReadWriter[URL] = readwriter[String].bimap(_.toString, new URL(_)) // todo: read can fail
      implicit val zonedDateTimeReadWriter: ReadWriter[ZonedDateTime] = readwriter[String].bimap(_.toString, ZonedDateTime.parse(_))
      implicit val directionReadWriter: ReadWriter[Direction.Direction] = macroRW
      implicit val playerStateReadWriter: ReadWriter[PlayerState] = macroRW
      implicit val playerReadWriter: ReadWriter[Player] = macroRW
      implicit val playerJoinReadWriter: ReadWriter[PlayerJoin] = macroRW
      implicit val playerLeaveReadWriter: ReadWriter[PlayerLeave] = macroRW
      implicit val playerUpdateReadWriter: ReadWriter[PlayerUpdate] = macroRW
      implicit val arenaConfigReadWriter: ReadWriter[ArenaConfig] = macroRW
      implicit val dimensionsReadWriter: ReadWriter[Dimensions] = macroRW
      implicit val arenaStateReadWriter: ReadWriter[ArenaState] = macroRW
      implicit val arenaUpdateReadWriter: ReadWriter[ArenaUpdate] = macroRW


      implicit val arenaPathDeserializer: Deserializer[Arena.Path] = new StringDeserializer
      implicit val playerJoinDeserializer: Deserializer[PlayerJoin] = (_: String, data: Array[Byte]) => readBinary[PlayerJoin](data)
      implicit val playerLeaveDeserializer: Deserializer[PlayerLeave] = (_: String, data: Array[Byte]) => readBinary[PlayerLeave](data)
      implicit val playerUpdateDeserializer: Deserializer[PlayerUpdate] = (_: String, data: Array[Byte]) => readBinary[PlayerUpdate](data)
      implicit val arenaConfigDeserializer: Deserializer[ArenaConfig] = (_: String, data: Array[Byte]) => readBinary[ArenaConfig](data)
      implicit val arenaUpdateDeserializer: Deserializer[ArenaUpdate] = (_: String, data: Array[Byte]) => readBinary[ArenaUpdate](data)
      implicit val uuidDeserializer: Deserializer[UUID] = new UUIDDeserializer
      implicit val scoresResetDeserializer: Deserializer[ScoresReset.type] = (_: String, data: Array[Byte]) => readBinary[ScoresReset.type](data)

      implicit val uuidSerializer: Serializer[UUID] = new UUIDSerializer
      implicit val arenaPathSerializer: Serializer[Arena.Path] = new StringSerializer
      implicit val playerJoinSerializer: Serializer[PlayerJoin] = (_: String, data: PlayerJoin) => writeBinary[PlayerJoin](data)
      implicit val playerLeaveSerializer: Serializer[PlayerLeave] = (_: String, data: PlayerLeave) => writeBinary[PlayerLeave](data)
      implicit val playerUpdateSerializer: Serializer[PlayerUpdate] = (_: String, data: PlayerUpdate) => writeBinary[PlayerUpdate](data)
      implicit val arenaConfigSerializer: Serializer[ArenaConfig] = (_: String, data: ArenaConfig) => writeBinary[ArenaConfig](data)
      implicit val arenaUpdateSerializer: Serializer[ArenaUpdate] = (_: String, data: ArenaUpdate) => writeBinary[ArenaUpdate](data)
      implicit val scoresResetSerializer: Serializer[ScoresReset.type] = (_: String, data: ScoresReset.type) => writeBinary[ScoresReset.type](data)
    }

    object SinksAndSources {
      import Serialization._

      def viewerEventSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, UUID], Future[Done]] = {
        Kafka.sink[Arena.Path, UUID]
      }

      def playerUpdateSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, PlayerUpdate], Future[Done]] = {
        Kafka.sink[Arena.Path, PlayerUpdate]
      }

      def arenaConfigSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, ArenaConfig], Future[Done]] = {
        Kafka.sink[Arena.Path, ArenaConfig]
      }

      def arenaUpdateSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, ArenaUpdate], Future[Done]] = {
        Kafka.sink[Arena.Path, ArenaUpdate]
      }

      def scoresResetSink(implicit actorSystem: ActorSystem): Sink[ProducerRecord[Arena.Path, ScoresReset.type], Future[Done]] = {
        Kafka.sink[Arena.Path, ScoresReset.type]
      }


      def viewerPingSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, UUID], Control] = {
        Kafka.source[Arena.Path, UUID](groupId, Topics.viewerPing)
      }

      def playerUpdateSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, PlayerUpdate], Control] = {
        Kafka.source[Arena.Path, PlayerUpdate](groupId, Topics.playerUpdate, "earliest")
      }

      def arenaConfigSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, ArenaConfig], Control] = {
        Kafka.source[Arena.Path, ArenaConfig](groupId, Topics.arenaConfig, "earliest")
      }

      def arenaUpdateSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, ArenaUpdate], Control] = {
        Kafka.source[Arena.Path, ArenaUpdate](groupId, Topics.arenaUpdate)
      }

      def scoresResetSource(groupId: String)(implicit actorSystem: ActorSystem): Source[ConsumerRecord[Arena.Path, ScoresReset.type], Control] = {
        Kafka.source[Arena.Path, ScoresReset.type](groupId, Topics.scoresReset)
      }
    }

  }

  // config
  val throwDistance: Int = 3
  val fullness: Double = 0.15
  val aspectRatio: Double = 4.0 / 3.0
  val resetLimit: FiniteDuration = 5.minutes
  // config


  type Name = String
  type Path = String
  type EmojiCode = String

  sealed trait Pathed {
    val path: Path
  }

  case class PathedArenaRefresh(path: Path) extends Pathed
  case class PathedScoresReset(path: Path) extends Pathed
  case class PathedPlayers(path: Path, players: Set[Player]) extends Pathed
  case class PathedArenaConfig(path: Path, arenaConfig: ArenaConfig) extends Pathed

  case class ArenaConfig(path: Path, name: Name, emojiCode: EmojiCode, instructions: Option[URL] = None, joinable: Boolean = true, floodInterval: FiniteDuration = Duration.Zero, badInterval: FiniteDuration = Duration.Zero)
  case class ArenaState(config: ArenaConfig, state: Map[Player, PlayerState], startTime: ZonedDateTime) {
    val dims: Dimensions = calcDimensions(state.keys.size)

    lazy val json: JsObject = Json.obj(
      "dims" -> Array(dims.width, dims.height),
      "state" -> Json.toJson(
        state.map { case (player, state) =>
          player.service -> state
        }
      )
    )
  }

  case class ResponseWithDuration(response: StandaloneWSResponse, duration: FiniteDuration) extends StandaloneAhcWSResponse(response.underlying[play.shaded.ahc.org.asynchttpclient.Response])

  case class Position(x: Int, y: Int)
  case class Dimensions(width: Int, height: Int)

  def calcDimensions(numPlayers: Int): Dimensions = {
    val volume = numPlayers / fullness
    val width = Math.round(Math.sqrt(volume * aspectRatio)).intValue()
    val height = width / aspectRatio
    Dimensions(width, height.toInt)
  }

  // todo: could be Either[ArenaState, (Option[ArenaConfig], Option[Set[Player]])]
  case class ArenaParts(config: Option[ArenaConfig], state: Option[ArenaState], players: Set[Player])

  def freshArenaState(arenaState: ArenaState): ArenaState = {
    val board = for {
      x <- 0 until arenaState.dims.width
      y <- 0 until arenaState.dims.height
    } yield x -> y

    val newState = arenaState.state.foldLeft(arenaState.state) { case (updatedState, (player, _)) =>
      val taken = updatedState.values.map(player => player.x -> player.y)
      val open = board.diff(taken.toSeq)
      val spot = Random.shuffle(open).head
      val newPlayerState = PlayerState(spot._1, spot._2, Direction.random, false, 0, Set.empty)

      updatedState.updated(player, newPlayerState)
    }

    ArenaState(arenaState.config, newState, ZonedDateTime.now())
  }

  def processArenaEvent(arenaParts: ArenaParts, pathed: Pathed)
                       (implicit ec: ExecutionContext, wsClient: WSClient, actorSystem: ActorSystem): Future[ArenaParts] = {

    val updatedArenaParts = pathed match {
      case PathedArenaConfig(_, arenaConfig) =>
        arenaParts.copy(config = Some(arenaConfig), state = None)

      case PathedPlayers(_, players) =>
        arenaParts.copy(players = players, state = None)

      case PathedArenaRefresh(_) =>
        arenaParts

      case PathedScoresReset(_) =>
        arenaParts.state.fold(arenaParts) { arenaState =>
          val resetAgo = java.time.Duration.between(arenaState.startTime, ZonedDateTime.now()).toScala
          if (resetAgo.gt(resetLimit)) {
            arenaParts.copy(state = Some(freshArenaState(arenaState)))
          }
          else {
            arenaParts
          }
        }
    }

    val updatedArenaState = updatedArenaParts match {
      case ArenaParts(Some(arenaConfig), None, arenaPlayers) =>
        val playerStates = arenaPlayers.map { player =>
          player -> PlayerState(0, 0, Direction.N, false, 0, Set.empty)
        }.toMap
        val arenaState = freshArenaState(ArenaState(arenaConfig, playerStates, ZonedDateTime.now()))
        performArenaUpdate(arenaState)
      case ArenaParts(_, Some(arenaState), _) =>
        performArenaUpdate(arenaState)
      case _ =>
        Future.successful(None)
    }

    updatedArenaState.map { maybeUpdatedState =>
      updatedArenaParts.copy(state = maybeUpdatedState)
    }
  }

  def playerJson(arenaState: ArenaState, player: Player): JsObject = {
    Json.obj(
      "_links" -> Json.obj(
        "self" -> Json.obj(
          "href" -> player.service
        )
      ),
      "arena" -> arenaState.json
    )
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
  def playerMoveWs(arenaState: ArenaState, player: Player)(implicit ec: ExecutionContext, wsClient: WSClient): Future[Option[(Move, FiniteDuration)]] = {
    val json = playerJson(arenaState, player)

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


  def forward(playerState: PlayerState, num: Int): Position = {
    playerState.direction match {
      case Direction.N => Position(playerState.x, playerState.y - num)
      case Direction.W => Position(playerState.x - num, playerState.y)
      case Direction.S => Position(playerState.x, playerState.y + num)
      case Direction.E => Position(playerState.x + num, playerState.y)
    }
  }

  def isPlayerInPosition(position: Position)(player: (Player, PlayerState)): Boolean = {
    player._2.x == position.x && player._2.y == position.y
  }

  def movePlayerForward(arenaState: ArenaState, player: Player, playerState: PlayerState): ArenaState = {
    val newTentativePosition = forward(playerState, 1)

    val isOtherPlayerInPosition = arenaState.state.exists(isPlayerInPosition(newTentativePosition))

    val isOutOfBounds = newTentativePosition.x < 0 || newTentativePosition.x > arenaState.dims.width - 1 ||
      newTentativePosition.y < 0 || newTentativePosition.y > arenaState.dims.height - 1

    if (isOtherPlayerInPosition || isOutOfBounds)
      arenaState
    else
      ArenaState(arenaState.config, arenaState.state.updated(player, playerState.copy(x = newTentativePosition.x, y = newTentativePosition.y)), arenaState.startTime)
  }

  def playerThrow(arenaState: ArenaState, player: Player, playerState: PlayerState): ArenaState = {
    val updatedArena = (1 to throwDistance).foldLeft(arenaState.state -> false) { case ((current, hit), distance) =>
      if (hit) {
        current -> true
      }
      else {
        val target = forward(playerState, distance)
        val maybeHitPlayer = current.find(isPlayerInPosition(target))
        maybeHitPlayer.fold(current -> false) { case (hitPlayer, hitPlayerState) =>
          if (playerState.hitBy.contains(hitPlayer.service)) {
            // this player can't hit a player who already hit them
            current -> true
          }
          else {
            val updatedPlayerStates = current
              .updated(hitPlayer, hitPlayerState.copy(wasHit = true, score = hitPlayerState.score - 1, hitBy = hitPlayerState.hitBy + player.service))
              .updated(player, playerState.copy(score = playerState.score + 1))

            updatedPlayerStates -> true
          }
        }
      }
    }

    ArenaState(arenaState.config, updatedArena._1, arenaState.startTime)
  }

  def performMoves(arenaState: ArenaState)
                  (moves: Map[Player, (Move, FiniteDuration)]): ArenaState = {

    val movesByShortest = moves.toSeq.sortBy(_._2._2)

    val arenaWithResetHits = ArenaState(arenaState.config, arenaState.state.view.mapValues(_.copy(wasHit = false, hitBy = Set.empty, responseTime = None)).toMap, arenaState.startTime)

    movesByShortest.foldLeft(arenaWithResetHits) { case (arena, (player, (move, responseTime))) =>
      arena.state.get(player).fold(arena) { currentPlayerState =>
        val currentPlayerStateUpdatedWithResponseTime = currentPlayerState.copy(responseTime = Some(responseTime))
        val arenaWithUpdatedResponseTime = ArenaState(arenaState.config, arena.state.updated(player, currentPlayerStateUpdatedWithResponseTime), arenaState.startTime)

        move match {
          case TurnLeft =>
            val newPlayerState = currentPlayerStateUpdatedWithResponseTime.copy(direction = Direction.left(currentPlayerStateUpdatedWithResponseTime.direction))
            ArenaState(arenaState.config, arenaWithUpdatedResponseTime.state.updated(player, newPlayerState), arenaState.startTime)
          case TurnRight =>
            val newPlayerState = currentPlayerStateUpdatedWithResponseTime.copy(direction = Direction.right(currentPlayerStateUpdatedWithResponseTime.direction))
            ArenaState(arenaState.config, arenaWithUpdatedResponseTime.state.updated(player, newPlayerState), arenaState.startTime)
          case Forward =>
            movePlayerForward(arenaWithUpdatedResponseTime, player, currentPlayerStateUpdatedWithResponseTime)
          case Throw =>
            playerThrow(arenaWithUpdatedResponseTime, player, currentPlayerStateUpdatedWithResponseTime)
        }
      }
    }
  }

  def lucky(duration: FiniteDuration): Boolean = {
    (duration != Duration.Zero) && (Random.nextLong(duration.toSeconds) == 0)
  }

  def updateArena(current: ArenaState)
                 (playerMove: (ArenaState, Player) => Future[Option[(Move, FiniteDuration)]])
                 (implicit ec: ExecutionContext, actorSystem: ActorSystem): Future[ArenaState] = {

    val playerMovesFutures = current.state.keys.map { player =>
      val flood = lucky(current.config.floodInterval)
      val bad = lucky(current.config.badInterval)

      if (flood) {
        actorSystem.actorOf(Props(new Chaos.Flood(player, current))) ! Chaos.Flood.Send
      }

      if (bad) {
        actorSystem.actorOf(Props(new Chaos.Bad(player, current))) ! Chaos.Bad.Send
      }

      playerMove(current, player).map(player -> _)
    }

    val playerMovesFuture = Future.sequence(playerMovesFutures).map { playerMoves =>
      // if the player didn't make a move, remove it from the moves that need to be performed
      playerMoves.toMap.collect {
        case (k, Some(v)) => k -> v
      }
    }

    playerMovesFuture.map(performMoves(current))
  }

  def arenaStateToArenaUpdate(arenaState: ArenaState): ArenaUpdate = {
    val canResetIn = resetLimit - java.time.Duration.between(arenaState.startTime, ZonedDateTime.now()).toScala
    ArenaUpdate(arenaState, canResetIn)
  }

  def performArenaUpdate(arenaState: ArenaState)
                        (implicit ec: ExecutionContext, wsClient: WSClient, actorSystem: ActorSystem): Future[Option[ArenaState]] = {
    updateArena(arenaState)(playerMoveWs).map(Some(_))
  }

}

object Player {
  type Service = String
  implicit val urlWrites: Writes[URL] = Writes[URL](url => JsString(url.toString))
  implicit val playerWrites: Writes[Player] = Json.writes[Player]

  type NameInvalid = Option[String]
  type ServiceInvalid = Option[String]
  type GithubUserInvalid = Option[String]

  def validate(maybeName: Option[String], maybeUrl: Option[URL], maybeGithubUsername: Option[String])
              (profanity: Profanity, avatarBaseUrl: String)
              (fetchPlayers: => Future[Set[Player]])
              (validateGithubUser: String => Future[Option[String]])
              (validateService: URL => Future[Option[String]])
              (implicit executionContext: ExecutionContext): Future[Either[(NameInvalid, ServiceInvalid, GithubUserInvalid), Player]] = {

    val nameOrError = maybeName.fold[Either[String, String]](Left("Name must not be empty")) { name =>
      if (name.length > 64) {
        Left("Name must be less than 65 characters")
      }
      else if ("[^a-zA-Z0-9\\s]".r.findFirstIn(name).isDefined) {
        Left("Name must only contain letters, numbers, and spaces")
      }
      else if (profanity.matches(name)) {
        Left("Name contains invalid words")
      }
      else {
        Right(name)
      }
    }

    val maybeGithubPicUrl = maybeGithubUsername.map { githubUsername =>
      s"https://avatars.githubusercontent.com/$githubUsername"
    }

    val githubUserInvalidFuture = maybeGithubPicUrl.fold[Future[Option[String]]](Future.successful(None)) { pic =>
      validateGithubUser(pic).flatMap { invalid =>
        invalid.fold[Future[Option[String]]] {
          fetchPlayers.map { players =>
            players.find(_.pic.toString.toLowerCase == pic.toLowerCase).map(_ => "Player with that GitHub username exists")
          }
        } { error =>
          Future.successful(Some(error))
        }
      }
    }

    val serviceOrErrorFuture = maybeUrl.fold[Future[Either[String, URL]]](Future.successful(Left("url is empty"))) { url =>
      if (url.getProtocol != "https") {
        Future.successful(Left("url must use https"))
      }
      else {
        fetchPlayers.flatMap { players =>
          val serviceExists = players.exists { player =>
            new URL(player.service).getHost.toLowerCase == url.getHost.toLowerCase
          }

          if (serviceExists) {
            Future.successful(Left("Player with that hostname already exists in the arena"))
          }
          else {
            validateService(url).map(_.toLeft(url))
          }
        }
      }
    }

    for {
      githubUserInvalid <- githubUserInvalidFuture
      serviceOrError <- serviceOrErrorFuture
    } yield {
      // todo: this is kinda fugly

      nameOrError.fold({ nameInvalid =>
        Left((Some(nameInvalid), serviceOrError.left.toOption, githubUserInvalid))
      }, { name =>
        serviceOrError.fold({ serviceInvalid =>
          Left((None, Some(serviceInvalid), githubUserInvalid))
        }, { service =>
          githubUserInvalid.fold[Either[(Option[String], Option[String], Option[String]), Player]] {
            val pic = maybeGithubPicUrl.getOrElse(s"$avatarBaseUrl/285/$name.png")
            val player = Player(service.toString, name, new URL(pic))
            Right(player)
          } { githubUserError =>
            Left((None, None, Some(githubUserError)))
          }
        })
      })
    }
  }

}

// todo: encode the circular laws in types
object Direction {

  sealed trait Direction

  case object N extends Direction

  case object W extends Direction

  case object S extends Direction

  case object E extends Direction

  implicit val jsonWrites: Writes[Direction] = Writes[Direction] {
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
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

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
