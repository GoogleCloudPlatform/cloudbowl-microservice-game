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

package apps

import java.util.UUID

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Source}
import models.Arena
import models.Arena.{ArenaState, MaybeViewersAndMaybePlayers, ViewersAndPlayers}
import models.Events.{ArenaDimsAndPlayers, ArenaUpdate}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.libs.ws.ahc.StandaloneAhcWSClient
import services.KafkaSerialization._
import services.{Kafka, Topics}

import scala.concurrent.duration._

object Battle extends App {

  implicit val actorSystem = ActorSystem()

  implicit val ec = actorSystem.dispatcher

  implicit val wsClient = StandaloneAhcWSClient()

  // no consumer group partitioning
  val groupId = UUID.randomUUID().toString


  val viewerEventsSource = Kafka.source[UUID, Arena.Path](UUID.randomUUID().toString, Topics.viewerPing)

  val tick = Source.repeat(NotUsed).throttle(1, 15.seconds).map(Right(_))

  // todo: we could go back to using an external store for the state since there will be a brief jostling when the server starts
  val viewersSource = viewerEventsSource
    .groupBy(Int.MaxValue, _.value())
    .map(Left(_))
    .merge(tick)
    .statefulMapConcat(Arena.viewersUpdate)
    .mergeSubstreams

  // todo: currently no persistence of ArenaState so it is lost on restart
  val arenaUpdateFlow = Flow[ViewersAndPlayers]
    .zipLatest(Source.repeat(NotUsed))
    .filter(_._1.viewers.nonEmpty) // only arenas with viewers
    .filter(_._1.players.nonEmpty) // only arenas with players
    .scanAsync(Option.empty[ArenaState])(Arena.performArenaUpdate)
    .mapConcat(_.toList)
    .throttle(1, 1.second)
    .map(Arena.arenaStateToArenaUpdate)

  def arenaUpdateToProducerRecord(arenaUpdate: ArenaUpdate): ProducerRecord[Arena.Path, ArenaDimsAndPlayers] = {
    new ProducerRecord(Topics.arenaUpdate, arenaUpdate.path, arenaUpdate.arenaDimsAndPlayers)
  }

  val arenaUpdateSink = Kafka.sink[Arena.Path, ArenaDimsAndPlayers]

  // Emits with the initial state of viewers & players, and then emits whenever the viewers or players change
  val viewersAndPlayersSource = viewersSource
    .map(Left(_))
    .merge(Arena.playersRefreshSource(groupId).map(Right(_)))
    .groupBy(Int.MaxValue, Arena.arenaPathFromViewerOrPlayers)
    .scanAsync(Option.empty[MaybeViewersAndMaybePlayers])(Arena.updatePlayers)
    .mapConcat(Arena.onlyArenasWithViewersAndPlayers)
  //.mergeSubstreams

  viewersAndPlayersSource
    .log("viewersAndPlayers")
    .via(arenaUpdateFlow)
    .log("arenaUpdate")
    .map(arenaUpdateToProducerRecord)
    .to(arenaUpdateSink)
    .run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}
