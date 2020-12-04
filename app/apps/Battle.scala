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

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{MergeHub, Source}
import models.Arena
import models.Arena.{ArenaState, KafkaConfig, Pathed, PathedPlayersRefresh, PathedScoresReset}
import models.Events.{ArenaDimsAndPlayers, ArenaUpdate}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.Environment
import play.api.libs.ws.ahc.AhcWSClient

import scala.concurrent.duration._

// todo: we could go back to using an external store for the state since there will be a brief jostling when the server starts

object Battle extends App {

  implicit val actorSystem = ActorSystem()

  implicit val ec = actorSystem.dispatcher

  implicit val wsClient = AhcWSClient()

  implicit val config = play.api.Configuration.load(Environment.simple())

  val groupId = "battle"

  val arenaUpdateSink = KafkaConfig.SinksAndSources.arenaUpdateSink

  val viewerEventsSource = KafkaConfig.SinksAndSources.viewerPingSource(groupId).map(_.key())

  val playersRefreshSource = KafkaConfig.SinksAndSources.playersRefreshSource(groupId).map { record =>
    PathedPlayersRefresh(record.key())
  }

  val scoresResetSource = KafkaConfig.SinksAndSources.scoresResetSource(groupId).map { record =>
    PathedScoresReset(record.key())
  }

  def arenaUpdateToProducerRecord(arenaUpdate: ArenaUpdate): ProducerRecord[Arena.Path, ArenaDimsAndPlayers] = {
    new ProducerRecord(KafkaConfig.Topics.arenaUpdate, arenaUpdate.path, arenaUpdate.arenaDimsAndPlayers)
  }

  val sink = MergeHub.source[Pathed](16)
    .groupBy(Int.MaxValue, _.path, true)
    .scanAsync(Option.empty[ArenaState])(Arena.processArenaEvent)
    .mapConcat(_.toSeq)
    .filter(_.config.players.nonEmpty)
    .map(Arena.arenaStateToArenaUpdate)
    .map(arenaUpdateToProducerRecord)
    .to(arenaUpdateSink)
    .run()

  val arenaRefresh = viewerEventsSource
    .groupBy(Int.MaxValue, identity)
    .map(Left(_))
    .merge(Source.repeat(NotUsed).throttle(1, 1.second).map(Right(_)))
    .statefulMapConcat(Arena.hasViewers)
    .to(sink)
    .run()

  val playersRefresh = playersRefreshSource
    .to(sink)
    .run()

  val scoresReset = scoresResetSource
    .to(sink)
    .run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}
