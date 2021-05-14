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
import models.Arena._
import models.Events.{ArenaUpdate, PlayerJoin, PlayerLeave}
import models.{Arena, Player}
import org.apache.kafka.clients.producer.ProducerRecord
import play.api.libs.ws.WSClient
import play.api.libs.ws.ahc.AhcWSClient
import play.api.{Configuration, Environment}

import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, TimeoutException}

object Battle extends App {

  private implicit val actorSystem: ActorSystem = ActorSystem()

  private implicit val ec: ExecutionContextExecutor = actorSystem.dispatcher

  private implicit val wsClient: WSClient = AhcWSClient()

  private implicit val config: Configuration = play.api.Configuration.load(Environment.simple())

  val groupId = "battle"
  val instanceId = UUID.randomUUID().toString

  val arenaUpdateSink = KafkaConfig.SinksAndSources.arenaUpdateSink

  // pings are sharded across consumers
  val viewerEventsSource = KafkaConfig.SinksAndSources.viewerPingSource(groupId).map(_.key())

  def arenaUpdateToProducerRecord(arenaUpdate: ArenaUpdate): ProducerRecord[Arena.Path, ArenaUpdate] = {
    new ProducerRecord(KafkaConfig.Topics.arenaUpdate, arenaUpdate.arenaState.config.path, arenaUpdate)
  }

  // aggregates arena config, player join/leave, viewer ping, and score reset events
  val sink = MergeHub.source[Pathed](16)
    .groupBy(Int.MaxValue, _.path, allowClosedSubstreamRecreation = true)
    .scanAsync(ArenaParts(None, None, Set.empty))(Arena.processArenaEvent)
    .collect { case ArenaParts(_, Some(state), _) => state }
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

  // All (scoreReset, playerUpdate, arenaConfig) events are sent to all consumers since we don't have a good way to
  // subscribe to only the events for the arena this consumer instance is handling
  KafkaConfig.SinksAndSources.scoresResetSource(instanceId).map(r => PathedScoresReset(r.key())).to(sink).run()
  Sources.playerUpdate(instanceId).to(sink).run()
  Sources.arenaConfig(instanceId).to(sink).run()

  actorSystem.registerOnTermination {
    wsClient.close()
  }

}

object Sources {

  def playerUpdate(groupId: String)(implicit actorSystem: ActorSystem) = {
    val playerUpdateSource = KafkaConfig.SinksAndSources.playerUpdateSource(groupId)

    playerUpdateSource
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
  }

  def arenaConfig(groupId: String)(implicit actorSystem: ActorSystem) = {
    val arenaConfigSource = KafkaConfig.SinksAndSources.arenaConfigSource(groupId)

    arenaConfigSource.map { record =>
      PathedArenaConfig(record.key(), record.value())
    }
  }

}