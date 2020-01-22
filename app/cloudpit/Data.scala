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

import scala.util.Random

case class Arena(path: Arena.Path)

case class Player(service: Player.Service, name: String, pic: URL)

case class PlayerState(x: Int, y: Int, direction: Direction.Direction, wasHit: Boolean)

// todo: scores
// todo: on player join, reset scores
case class Arenas(arenaPlayers: Map[Arena.Path, Map[Player.Service, PlayerState]])

object Arena {
  implicit val jsWrites = Json.writes[Arena]
  type Path = String

  val throwDistance = 3

  val fullness = 0.15
  val aspectRatio = 4/3

  def dimensions(numPlayers: Int): (Int, Int) = {
    val volume = numPlayers / fullness
    val width = Math.round(Math.sqrt(volume * aspectRatio)).intValue()
    val height = width * aspectRatio
    width -> height
  }
}

object Player {
  type Service = String
}

// todo: encode the circular laws in types
object Direction {
  sealed trait Direction

  case object N extends Direction
  case object W extends Direction
  case object S extends Direction
  case object E extends Direction

  implicit val nJsonWrites = Json.writes[N.type]
  implicit val wJsonWrites = Json.writes[W.type]
  implicit val sJsonWrites = Json.writes[S.type]
  implicit val eJsonWrites = Json.writes[E.type]
  implicit val jsonWrites = Json.writes[Direction]

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

  def random: Direction = {
    Random.shuffle(Seq(N, W, S, E)).head
  }
}

object PlayerState {
  implicit val jsonWrites = Json.writes[PlayerState]
}

sealed abstract class Move(val command: Char)

case object Forward extends Move('F')
case object TurnRight extends Move('R')
case object TurnLeft extends Move('L')
case object Throw extends Move('T')

object Move {
  def parse(command: Char): Option[Move] = {
    if (command == Forward.command)
      Some(Forward)
    else if (command == TurnRight.command)
      Some(TurnRight)
    else if (command == TurnLeft.command)
      Some(TurnLeft)
    else if (command == Throw.command)
      Some(Throw)
    else
      None
  }
}
