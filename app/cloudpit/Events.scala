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

object Events {

  sealed trait ViewerEvent {
    val arena: Arena
  }

  final case class ViewerJoin(arena: Arena) extends ViewerEvent
  final case class ViewerLeave(arena: Arena) extends ViewerEvent


  sealed trait PlayerEvent {
    val arena: Arena
    val player: Player
  }

  final case class PlayerJoin(arena: Arena, player: Player) extends PlayerEvent
  final case class PlayerLeave(arena: Arena, player: Player) extends PlayerEvent


  final case class ArenaUpdate(arenaPlayers: Set[ArenaPlayer])


  case class Viewers(viewerCount: Map[Arena, Int])

  case class Players(players: Map[Arena, Set[Player]])



}
