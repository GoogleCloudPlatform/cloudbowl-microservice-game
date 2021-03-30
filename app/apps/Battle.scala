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

import akka.actor.ActorSystem
import akka.stream.scaladsl.MergeHub
import models.{Arena, Player}
import models.Arena.{ArenaState, KafkaConfig, Pathed, PathedArenaRefresh, PathedPlayers, PathedScoresReset}
import models.Events.{ArenaUpdate, PlayerJoin, PlayerLeave}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.libs.ws.WSClient
import play.api.{Configuration, Environment}
import play.api.libs.ws.ahc.AhcWSClient

import scala.concurrent.{ExecutionContextExecutor, TimeoutException}
import scala.concurrent.duration._

// todo: we could go back to using an external store for the state since there will be a brief jostling when the server starts

object Battle extends App {

  private implicit val actorSystem: ActorSystem = ActorSystem()

  private implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher

  private implicit val wsClient: WSClient = AhcWSClient()

  private implicit val config: Configuration = play.api.Configuration.load(Environment.simple())

  val groupId = "battle"

  val arenaUpdateSink = KafkaConfig.SinksAndSources.arenaUpdateSink

  val viewerEventsSource = KafkaConfig.SinksAndSources.viewerPingSource(groupId).map(_.key())

  val playerUpdateSource = KafkaConfig.SinksAndSources.playerUpdateSource(groupId)

  val scoresResetSource = KafkaConfig.SinksAndSources.scoresResetSource(groupId).map { record =>
    PathedScoresReset(record.key())
  }

  def arenaUpdateToProducerRecord(arenaUpdate: ArenaUpdate): ProducerRecord[Arena.Path, ArenaUpdate] = {
    new ProducerRecord(KafkaConfig.Topics.arenaUpdate, arenaUpdate.arenaState.config.path, arenaUpdate)
  }

  val sink = MergeHub.source[Pathed](16)
    .groupBy(Int.MaxValue, _.path, allowClosedSubstreamRecreation = true)
    .scanAsync(Option.empty[ArenaState])(Arena.processArenaEvent)
    .collect { case Some(s) => s }
    //.filter(_.config.players.nonEmpty)
    .map(Arena.arenaStateToArenaUpdate)
    .map(arenaUpdateToProducerRecord)
    .to(arenaUpdateSink)
    .run()

  // if the substream is idle for 30 seconds, it closes
  // takes any rate of viewer pings and turns it into a constant rate of 1 per second
  val viewersSource = viewerEventsSource
    .groupBy(Int.MaxValue, identity, allowClosedSubstreamRecreation = true)
    .idleTimeout(30.seconds)
    .map { path =>
      Some(PathedArenaRefresh(path))
    }
    .expand(Iterator.continually(_))
    .throttle(1, 1.second)
    .recover {
      // turns the cancel into completion just to avoid the logging of the error
      case _: TimeoutException =>
        None
    }
    .collect { case Some(s) => s }
    .to(sink)
    .run()

  val playerUpdate = playerUpdateSource
    .groupBy(Int.MaxValue, _.key(), allowClosedSubstreamRecreation = true)
    .scan(Option.empty[(Arena.Path, Set[Player])]) { (maybePlayers, record) =>
      val players = maybePlayers.fold(Set.empty[Player])(_._2)
      val updatedPlayers = record.value() match {
        case PlayerJoin(player) => players + player
        case PlayerLeave(service) => players.filterNot(_.service == service)
      }
      Some(record.key() -> updatedPlayers)
    }
    .collect {
      case Some((path, players)) => PathedPlayers(path, players)
    }
    .to(sink)
    .run()

  val scoresReset = scoresResetSource
    .to(sink)
    .run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}

