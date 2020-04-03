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

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import models.Arena
import models.Events._
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.InjectedController
import services.{GitHub, GoogleSheetPlayersConfig, Topics}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class Main @Inject()(googleSheetPlayersConfig: GoogleSheetPlayersConfig, gitHub: GitHub)(implicit actorSystem: ActorSystem, ec: ExecutionContext) extends InjectedController {

  // wtf compiler
  if (false) {
    ec
  }
  // eowtf

  val viewerEventSink = Arena.KafkaSinksAndSources.viewerEventSink
  val playersRefreshSink = Arena.KafkaSinksAndSources.playersRefreshSink

  def index(arena: Arena.Path) = Action {
    Ok(views.html.index(arena))
  }

  def updates(arena: Arena.Path, uuid: UUID) = Action {
    // todo: one global source and broadcast to all viewers

    val viewerPingSource = Source.repeat {
      new ProducerRecord(Topics.viewerPing, arena, uuid)
    }.throttle(1, 15.seconds).alsoTo(viewerEventSink)

    val arenaUpdates: Source[EventSource.Event, _] = {
      val arenaUpdateSource = Arena.KafkaSinksAndSources.arenaUpdateSource(uuid.toString)
      arenaUpdateSource.filter(_.key() == arena).map { message =>
        Json.toJson(message.value())
      }.via(EventSource.flow[JsValue])
    }

    val source = arenaUpdates.map(Left(_)).merge(viewerPingSource.map(Right(_))).collect {
      case Left(arenaUpdate) => arenaUpdate
    }

    Ok.chunked(source).as(ContentTypes.EVENT_STREAM)
  }

  // this endpoint expects the webhook call from google sheets
  def playersRefresh(arena: Arena.Path) = Action.async { request =>
    if (request.headers.get(AUTHORIZATION).contains(googleSheetPlayersConfig.maybePsk.get)) {
      val record = new ProducerRecord(Topics.playersRefresh, arena, PlayersRefresh)
      Source.single(record).runWith(playersRefreshSink).map { _ =>
        NoContent
      }
    }
    else {
      Future.successful(Unauthorized)
    }
  }

  // this endpoint expects the webhook call from github
  def gitHubPlayersRefresh() = Action.async(parse.json) { request =>
    val maybeHubSignature = request.headers.get("X-Hub-Signature")

    val authorized = gitHub.maybePsk.fold(true) { psk =>
      maybeHubSignature.fold(false) { hubSignature =>
        val signingKey = new SecretKeySpec(psk.getBytes(), "HmacSHA1")
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(signingKey)
        val signature = mac.doFinal(request.body.toString().getBytes)

        "sha1=" + signature.map("%02x".format(_)).mkString == hubSignature
      }
    }

    if (authorized) {
      val arenas = (request.body \ "commits").as[Seq[JsObject]].foldLeft(Set.empty[String]) { case (arenasChanged, commit) =>
        val filesChanged = (commit \ "added").as[Set[String]] ++ (commit \ "removed").as[Set[String]] ++ (commit \ "modified").as[Set[String]]
        arenasChanged ++ filesChanged.collect {
          case s if s.endsWith(".json") => s.stripSuffix(".json")
        }
      }

      val messages = arenas.map { arena =>
        new ProducerRecord(Topics.playersRefresh, arena, PlayersRefresh)
      }

      Source(messages).runWith(playersRefreshSink).map { _ =>
        Ok
      }
    }
    else {
      Future.successful(Unauthorized)
    }
  }

}
