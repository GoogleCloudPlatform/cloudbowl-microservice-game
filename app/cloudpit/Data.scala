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

import java.net.URL

import play.api.libs.json.Json

case class Arena(path: Arena.Path)

case class Player(service: Player.Service, name: String, pic: URL)

case class ArenaPlayer(arenaPath: Arena.Path, playerService: Player.Service, x: Int, y: Int, direction: Direction)

object Arena {
  implicit val jsWrites = Json.writes[Arena]
  type Path = String
}

object Player {
  type Service = String
}

sealed trait Direction
case object N extends Direction
case object W extends Direction
case object S extends Direction
case object E extends Direction

// todo: encode the circular laws in types
object Direction {
  def left(direction: Direction): Direction = {
      direction match {
        case N => W
        case W => S
        case S => E
        case E => N
      }
  }

  def right(direction: Direction): Direction = {
    left(left(left(direction)))
  }
}
