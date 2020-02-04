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
import akka.stream.scaladsl.{Sink, Source}
import cloudpit.Events._
import cloudpit.KafkaSerialization._
import javax.inject.{Inject, Singleton}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.InjectedController

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

@Singleton
class Controller @Inject()(implicit actorSystem: ActorSystem, ec: ExecutionContext) extends InjectedController {

  val viewerEventSink: Sink[ProducerRecord[UUID, Arena.Path], _] = Kafka.sink[UUID, Arena.Path]

  // wtf compiler
  if (false) {
    (ec, actorSystem)
  }
  // eowtf

  def index(arena: Arena.Path) = Action {
    Ok(views.html.index(arena))
  }

  def updates(arena: Arena.Path, uuid: UUID) = Action {
    // todo: one global source and broadcast to all viewers

    val viewerPingSource = Source.repeat(NotUsed).map[ProducerRecord[UUID, Arena.Path]] { _ =>
      new ProducerRecord(Topics.viewerPing, uuid, arena)
    }.throttle(1, 15.seconds).alsoTo(viewerEventSink)

    val arenaUpdates: Source[EventSource.Event, _] = {
      val arenaUpdateSource = Kafka.committableSource[Arena.Path, ArenaDimsAndPlayers](UUID.randomUUID().toString, Topics.arenaUpdate)
      arenaUpdateSource.filter(_.record.key() == arena).map { message =>
        Json.toJson(message.record.value())
      }.via(EventSource.flow[JsValue])
    }

    val source = arenaUpdates.map(Left(_)).merge(viewerPingSource.map(Right(_))).collect {
      case Left(arenaUpdate) => arenaUpdate
    }

    Ok.chunked(source).as(ContentTypes.EVENT_STREAM)
  }

}
