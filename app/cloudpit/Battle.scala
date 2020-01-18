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
import cloudpit.Events.{PlayerEvent, PlayerJoin, PlayerLeave, Players, ViewerEvent, ViewerJoin, ViewerLeave, Viewers}
import cloudpit.KafkaSerialization._

object Battle extends App {

  private implicit val actorSystem = ActorSystem()

  // no consumer group partitioning
  val groupId = UUID.randomUUID().toString

  val playerSource = Kafka.source[Arena.Path, PlayerEvent](groupId, Topics.players)

  val viewerSource = Kafka.source[Arena.Path, ViewerEvent](groupId, Topics.viewers)

  val arenaSink = Kafka.sink[Arena.Path, Arena]


  val initViewers = Viewers(Map.empty)
  val initPlayers = Players(Map.empty)

  val playersSource = playerSource.scan(initPlayers) { case (players, record) =>
    val arena = Arena(record.key())
    val event = record.value()

    val currentPlayers = players.players.getOrElse(arena, Set.empty)
    val updatedPlayers = event match {
      case PlayerJoin(_, player) =>
        currentPlayers + player
      case PlayerLeave(_, player) =>
        currentPlayers - player
    }

    Players(players.players.updated(arena, updatedPlayers))
  }

  val viewersSource = viewerSource.scan(initViewers) { case (viewers, record) =>
    val arena = Arena(record.key())
    val event = record.value()

    val currentViewers = viewers.viewerCount.getOrElse(arena, 0)
    val updatedViewers = event match {
      case ViewerJoin(_) =>
        currentViewers + 1
      case ViewerLeave(_) =>
        if (currentViewers > 0)
          currentViewers - 1
        else
          0
    }

    Viewers(viewers.viewerCount.updated(arena, updatedViewers))
  }

  playersSource.zipLatest(viewersSource).runForeach(println)

  // get arena state


  // get viewer state & subscribe


  // get player state & subscribe


}
