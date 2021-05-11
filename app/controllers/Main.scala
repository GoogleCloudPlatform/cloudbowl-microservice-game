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

package controllers

import akka.NotUsed
import akka.actor.{Actor, ActorRef, ActorSystem, Kill, Props}
import akka.pattern.ask
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{BroadcastHub, Keep, Sink, Source}
import akka.util.Timeout
import com.google.inject.AbstractModule
import models.Arena.{ArenaConfig, ArenaState, PathedArenaConfig, PathedPlayers}
import models.Events._
import models.{Arena, Direction, Player, PlayerState}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.Configuration
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController

import java.net.URL
import java.time.ZonedDateTime
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

// todo: possible race condition - for join & admin, players & arena config may not have been received when the form is submitted
class Main @Inject()(query: Query, summaries: Summaries, wsClient: WSClient, configuration: Configuration)
                    (joinTemplate: views.html.join, adminTemplate: views.html.admin, homeTemplate: views.html.home)
                    (implicit actorSystem: ActorSystem, ec: ExecutionContext) extends InjectedController {

  private val avatarBaseUrl = configuration.get[String]("avatar.base.url")

  private val maybeAdminPassword = configuration.getOptional[String]("admin.password")

  private val viewerEventSink = Arena.KafkaConfig.SinksAndSources.viewerEventSink
  private val scoresResetSink = Arena.KafkaConfig.SinksAndSources.scoresResetSink
  private val playerUpdateSink = Arena.KafkaConfig.SinksAndSources.playerUpdateSink
  private val arenaConfigSink = Arena.KafkaConfig.SinksAndSources.arenaConfigSink

  // each instance gets it's own groupId so that everyone receives all the updates
  private val groupId = UUID.randomUUID().toString

  private val arenaUpdateSource = Arena.KafkaConfig.SinksAndSources.arenaUpdateSource(groupId)
    .buffer(2, OverflowStrategy.dropTail)
    .toMat(BroadcastHub.sink(bufferSize = 2))(Keep.right).run()

  private val avatarSessionKey = "avatar"

  private val avatarSessionNotFound = "Your avatar was not set by Adventure. Please go back into Adventure, visit the Cloud Dome, and re-enter the Rainbow Rumpus."

  def home(maybeAvatar: Option[String]) = Action { implicit request =>
    maybeAvatar.filter(_.nonEmpty).fold {
      Ok(homeTemplate(request))
    } { avatar =>
      Redirect(routes.Main.home(None)).addingToSession(avatarSessionKey -> avatar)
    }
  }

  def index(arena: Arena.Path) = Action { implicit request =>
    Ok(views.html.index(arena))
  }

  def join(arena: Arena.Path) = Action { implicit request =>
    request.session.get(avatarSessionKey).fold {
      BadRequest(avatarSessionNotFound)
    } { _ =>
      Ok(joinTemplate(arena, None, None, None))
    }
  }

  def joinValidate(arena: Arena.Path) = Action.async(parse.formUrlEncoded) { implicit request =>
    request.session.get(avatarSessionKey).fold {
      Future.successful {
        BadRequest(avatarSessionNotFound)
      }
    } { avatar =>
      val maybeName = request.body.get("name").flatMap(_.headOption).filter(_.nonEmpty)
      val maybeUrl = request.body.get("url").flatMap(_.headOption).filter(_.nonEmpty)
      val maybeAction = request.body.get("action").flatMap(_.headOption)

      val urlInvalidFuture = maybeUrl.fold[Future[Option[String]]](Future.successful(Some("url is empty"))) { url =>
        if (!url.startsWith("https://")) {
          Future.successful(Some("url must use https"))
        }
        else {
          def host(s: String): String = {
            s.stripPrefix("http://").stripPrefix("https://").takeWhile(_ != '/').takeWhile(_ != ':').toLowerCase
          }

          query.playerUpdateActorRef.ask(arena)(Timeout(10.seconds)).mapTo[Set[Player]].flatMap { players =>
            val playerExists = players.exists { player =>
              host(player.service) == host(url)
            }

            if (!playerExists) {
              val player = Player(url, "test", new URL(s"$avatarBaseUrl/285/test.png"))
              val playerState = PlayerState(0, 0, Direction.N, false, 0, Set.empty, None)
              val arenaState = ArenaState(ArenaConfig("test", "test", "2728"), Map(player -> playerState), ZonedDateTime.now())
              val json = Arena.playerJson(arenaState, player)
              wsClient.url(url).post(json).map { response =>
                if (response.status != OK) {
                  Some("Microservice did not return status 200")
                }
                else if ((response.body != "F") && (response.body != "T") && (response.body != "L") && (response.body != "R")) {
                  Some("Microservice did not return a valid response")
                }
                else {
                  None
                }
              }
            }
            else {
              Future.successful(Some("Player with that hostname already exists in the arena"))
            }
          }
        }
      }

      urlInvalidFuture.map { urlInvalid =>
        (maybeAction, maybeName, maybeUrl, urlInvalid) match {
          case (Some("add"), Some(name), Some(service), None) =>
            val pic = s"$avatarBaseUrl/$avatar.png"
            val player = Player(service, name, new URL(pic))
            val record = new ProducerRecord[Arena.Path, PlayerUpdate](Arena.KafkaConfig.Topics.playerUpdate, arena, PlayerJoin(player))
            Source.single(record).to(playerUpdateSink).run()
            Redirect(controllers.routes.Main.index(arena))
          case _ =>
            Ok(joinTemplate(arena, maybeName, maybeUrl, urlInvalid))
        }
      }
    }
  }

  def updates(arena: Arena.Path, uuid: UUID) = Action {
    val ping = new ProducerRecord(Arena.KafkaConfig.Topics.viewerPing, arena, uuid)

    // wait one second to start otherwise there seems to be no demand yet,
    // resulting in the first tick being 15 seconds after the connection opens
    val viewerPingSource = Source.tick(1.second, 15.seconds, ping).alsoTo(viewerEventSink)

    val arenaUpdates: Source[EventSource.Event, _] = {
      arenaUpdateSource.filter(_.key() == arena).map { message =>
        Json.toJson(message.value())
      }.via(EventSource.flow[JsValue])
    }

    // merging these makes it so when the SSE closes, the ping source is closed too
    // note that the close doesn't happen for a little while after the connection actually closes
    val source = arenaUpdates.map(Left(_)).merge(viewerPingSource.map(Right(_))).collect {
      case Left(arenaUpdate) => arenaUpdate
    }

    Ok.chunked(source).as(ContentTypes.EVENT_STREAM)
  }

  def summary(uuid: UUID) = Action.async {
    summaries.source(uuid).map { summaries =>
      val source = summaries.map(Json.toJson[Map[Arena.Path, Summary]]).via(EventSource.flow[JsValue])
      Ok.chunked(source).as(ContentTypes.EVENT_STREAM)
    }
  }

  def scoresReset(arena: Arena.Path) = Action.async {
    Source.single(new ProducerRecord(Arena.KafkaConfig.Topics.scoresReset, arena, ScoresReset)).runWith(scoresResetSink).map { _ =>
      Ok
    }
  }

  // todo: remove players
  def admin(arena: Arena.Path) = Action.async { implicit request =>
    query.arenaConfigActorRef.ask(arena)(Timeout(10.seconds)).mapTo[Option[ArenaConfig]].map { arenaConfig =>
      Ok(adminTemplate(arena, maybeAdminPassword.isDefined, None, None, arenaConfig.map(_.name), arenaConfig.map(_.emojiCode), None, None))
    }
  }

  def adminValidate(arena: Arena.Path) = Action.async(parse.formUrlEncoded) { implicit request =>
    val maybeName = request.body.get("name").flatMap(_.headOption).filter(_.nonEmpty)
    val maybeEmoji = request.body.get("emoji").flatMap(_.headOption).filter(_.nonEmpty)
    val maybeProvidedAdminPassword = request.body.get("adminPassword").flatMap(_.headOption).filter(_.nonEmpty)
    val maybeInstructions = request.body.get("instructions").flatMap(_.headOption).flatMap { url =>
      Try(new URL(url)).toOption
    }

    // to Either[Error, EmojiCode]
    val emojiInvalidFuture = maybeEmoji.fold[Future[Option[String]]](Future.successful(None)) { emoji =>
      val emojiCode = emoji.toLowerCase
      wsClient.url(s"https://noto-website-2.storage.googleapis.com/emoji/emoji_u$emojiCode.png").get().map { response =>
        if (response.status == OK) {
          None
        }
        else {
          Some("Emoji not found")
        }
      }
    }

    val adminPasswordInvalid = maybeAdminPassword.flatMap { adminPassword =>
      Option.unless(maybeProvidedAdminPassword.contains(adminPassword))("Incorrect Admin Password")
    }

    emojiInvalidFuture.map { emojiInvalid =>
      (maybeName, maybeEmoji, emojiInvalid, adminPasswordInvalid, maybeInstructions) match {
        case (Some(name), Some(emoji), None, None, instructions) =>
          val arenaConfig = ArenaConfig(arena, name, emoji.toLowerCase, instructions)
          val record = new ProducerRecord[Arena.Path, ArenaConfig](Arena.KafkaConfig.Topics.arenaConfig, arena, arenaConfig)
          Source.single(record).to(arenaConfigSink).run()
          // todo: when there are no players, the arena does not load
          Redirect(controllers.routes.Main.index(arena))

        case _ =>
          Ok(adminTemplate(arena, maybeAdminPassword.isDefined, maybeAdminPassword, adminPasswordInvalid, maybeName, maybeEmoji, emojiInvalid, maybeInstructions))
      }
    }
  }

}

@Singleton
class Query @Inject()(implicit actorSystem: ActorSystem) {

  private val groupId = UUID.randomUUID().toString

  class PlayerUpdateActor extends Actor {
    val allPlayers = scala.collection.mutable.Map.empty[Arena.Path, Set[Player]]

    override def receive = {
      case PathedPlayers(path, players) =>
        allPlayers.update(path, players)
      case path: Arena.Path =>
        sender() ! allPlayers.getOrElse(path, Set.empty)
    }
  }

  val playerUpdateActorRef: ActorRef = actorSystem.actorOf(Props(new PlayerUpdateActor))
  private val playerUpdate = apps.Sources.playerUpdate(groupId).to(Sink.actorRef(playerUpdateActorRef, Kill, _ => Kill)).run()

  class ArenaConfigActor extends Actor {
    val arenaConfigs = scala.collection.mutable.Map.empty[Arena.Path, ArenaConfig]

    override def receive = {
      case PathedArenaConfig(path, arenaConfig) =>
        arenaConfigs.update(path, arenaConfig)
      case Query.ArenaConfigs =>
        sender() ! arenaConfigs.toMap
      case path: Arena.Path =>
        sender() ! arenaConfigs.get(path)
    }
  }

  val arenaConfigActorRef: ActorRef = actorSystem.actorOf(Props(new ArenaConfigActor))
  private val arenaConfig = apps.Sources.arenaConfig(groupId).to(Sink.actorRef(arenaConfigActorRef, Kill, _ => Kill)).run()

}

object Query {
  case object ArenaConfigs
}

@Singleton
class Summaries @Inject()(query: Query)(implicit actorSystem: ActorSystem) {

  private implicit val ec = ExecutionContext.global

  private val groupId = UUID.randomUUID().toString

  private val viewerEventSink = Arena.KafkaConfig.SinksAndSources.viewerEventSink

  val _source: Source[Map[Arena.Path, Summary], NotUsed] = {
    Arena.KafkaConfig.SinksAndSources.arenaUpdateSource(groupId).conflateWithSeed { record =>
      Map(record.key() -> arenaUpdateToSummary(record.value()))
    } { case (summaries, record) =>
      summaries.updated(record.key(), arenaUpdateToSummary(record.value()))
    }.throttle(1, 1.second).buffer(2, OverflowStrategy.dropTail).toMat(BroadcastHub.sink(bufferSize = 2))(Keep.right).run()
  }

  // sends a viewer ping to all the arenas so we get an ArenaUpdate
  def source(uuid: UUID): Future[Source[Map[Arena.Path, Summary], NotUsed]] = {
    query.arenaConfigActorRef.ask(Query.ArenaConfigs)(Timeout(10.seconds)).mapTo[Map[Arena.Path, ArenaConfig]].map { arenaConfigs =>
      val pings = arenaConfigs.keySet.map { path =>
        new ProducerRecord(Arena.KafkaConfig.Topics.viewerPing, path, uuid)
      }

      Source(pings).to(viewerEventSink).run()

      _source
    }
  }

}


class StartModule extends AbstractModule {
  override def configure() = {
    bind(classOf[Query]).asEagerSingleton()
    bind(classOf[Summaries]).asEagerSingleton()
  }
}