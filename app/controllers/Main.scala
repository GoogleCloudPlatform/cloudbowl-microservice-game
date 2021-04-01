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

import java.util.UUID
import akka.actor.{Actor, ActorSystem, Kill, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import com.google.inject.AbstractModule

import javax.inject.{Inject, Singleton}
import models.{Arena, Direction, Player, PlayerState}
import models.Arena.{ArenaConfig, ArenaState, Path, PathedPlayers}
import models.Events._
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.{Configuration, Environment}
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.WSClient
import play.api.mvc.InjectedController

import java.net.URL
import java.time.ZonedDateTime
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class Main @Inject()(query: Query, joinTemplate: views.html.join, wsClient: WSClient, configuration: Configuration)
                    (implicit actorSystem: ActorSystem, ec: ExecutionContext) extends InjectedController {

  val avatarBaseUrl = configuration.get[String]("avatar.base.url")

  val viewerEventSink = Arena.KafkaConfig.SinksAndSources.viewerEventSink
  val scoresResetSink = Arena.KafkaConfig.SinksAndSources.scoresResetSink
  val playerUpdateSink = Arena.KafkaConfig.SinksAndSources.playerUpdateSink

  def index(arena: Arena.Path) = Action { implicit request =>
    Ok(views.html.index(arena))
  }

  def join(arena: Arena.Path) = Action { implicit request =>
    Ok(joinTemplate(arena, None, None, None, None, None))
  }

  def joinValidate(arena: Arena.Path) = Action.async(parse.formUrlEncoded) { implicit request =>
    val maybeName = request.body.get("name").flatMap(_.headOption).filter(_.nonEmpty)
    val maybeUrl = request.body.get("url").flatMap(_.headOption).filter(_.nonEmpty)
    val maybeGithubUsername = request.body.get("githubUsername").flatMap(_.headOption).filter(_.nonEmpty)
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

    // todo: prevent duplicate github users
    val githubUserInvalidFuture = maybeGithubUsername.fold[Future[Option[String]]](Future.successful(None)) { githubUsername =>
      wsClient.url(s"https://github.com/$githubUsername.png").get().map { response =>
        if (response.status == OK) {
          None
        }
        else {
          Some("GitHub username was not found")
        }
      }
    }

    for {
      urlInvalid <- urlInvalidFuture
      githubUserInvalid <- githubUserInvalidFuture
    } yield {
      (maybeAction, maybeName, maybeUrl, urlInvalid, githubUserInvalid) match {
        case (Some("add"), Some(name), Some(service), None, None) =>
          // send
          val pic = maybeGithubUsername.map { gitHubUser =>
            s"https://github.com/$gitHubUser.png"
          }.getOrElse {
            s"$avatarBaseUrl/285/$name.png"
          }

          val player = Player(service, name, new URL(pic))
          val record = new ProducerRecord[Path, PlayerUpdate](Arena.KafkaConfig.Topics.playerUpdate, arena, PlayerJoin(player))
          Source.single(record).to(playerUpdateSink).run()
          Redirect(controllers.routes.Main.index(arena))
        case _ =>
          Ok(joinTemplate(arena, maybeName, maybeUrl, urlInvalid, maybeGithubUsername, githubUserInvalid))
      }
    }
  }

  def updates(arena: Arena.Path, uuid: UUID) = Action {
    // todo: one global source and broadcast to all viewers

    val ping = new ProducerRecord(Arena.KafkaConfig.Topics.viewerPing, arena, uuid)

    // wait one second to start otherwise there seems to be no demand yet,
    // resulting in the first tick being 15 seconds after the connection opens
    val viewerPingSource = Source.tick(1.second, 15.seconds, ping).alsoTo(viewerEventSink)

    val arenaUpdates: Source[EventSource.Event, _] = {
      val arenaUpdateSource = Arena.KafkaConfig.SinksAndSources.arenaUpdateSource(uuid.toString)
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

  def scoresReset(arena: Arena.Path) = Action.async {
    Source.single(new ProducerRecord(Arena.KafkaConfig.Topics.scoresReset, arena, ScoresReset)).runWith(scoresResetSink).map { _ =>
      Ok
    }
  }

}

@Singleton
class Query @Inject()(implicit actorSystem: ActorSystem) {

  class PlayerUpdateActor extends Actor {
    val allPlayers = scala.collection.mutable.Map.empty[Path, Set[Player]]

    override def receive = {
      case PathedPlayers(path, players) =>
        allPlayers.update(path, players)
      case path: Path =>
        sender() ! allPlayers.getOrElse(path, Set.empty)
    }
  }

  val groupId = UUID.randomUUID().toString

  val playerUpdateActorRef = actorSystem.actorOf(Props(new PlayerUpdateActor))
  val playerUpdate = apps.Sources.playerUpdate(groupId).to(Sink.actorRef(playerUpdateActorRef, Kill, _ => Kill)).run()
}

class StartModule extends AbstractModule {
  override def configure() = {
    bind(classOf[Query]).asEagerSingleton()
  }
}