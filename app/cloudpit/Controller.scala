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

import akka.actor.ActorSystem
import cloudpit.Events._
import cloudpit.KafkaSerialization._
import javax.inject.{Inject, Singleton}
import play.api.http.ContentTypes
import play.api.libs.EventSource
import play.api.libs.json.Json
import play.api.mvc.InjectedController

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class Controller @Inject()(implicit actorSystem: ActorSystem, ec: ExecutionContext) extends InjectedController {

  if (false) {
    (ec, actorSystem)
  }

  def index(arena: Arena.Path) = Action {
    Ok(views.html.index(arena))
  }

  def updates(arena: Arena.Path) = Action {
    val arenaUpdateSource = Kafka.committableSource[Arena.Path, ArenaDimsAndPlayers](UUID.randomUUID().toString, Topics.arenaUpdate)
    val arenaSource = arenaUpdateSource.filter(_.record.key() == arena).map { message =>
      Json.toJson(message.record.value())
    }.via(EventSource.flow)

    Ok.chunked(arenaSource).as(ContentTypes.EVENT_STREAM)
  }


}
