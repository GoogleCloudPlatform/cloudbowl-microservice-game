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

package models

import java.net.URL
import java.time.ZonedDateTime
import models.Arena.{ArenaConfig, ArenaState}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.BeforeAndAfterAll
import play.api.test.Helpers._

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.concurrent.duration._


class DataSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private implicit val ec: ExecutionContextExecutor = scala.concurrent.ExecutionContext.global

  "wasHit" must {
    "get sent to services" in {

      val player = Player("http://foo", "foo", new URL("http://foo"))
      val playerState = PlayerState(0, 0, Direction.S, true, 0)
      val initState = ArenaState(ArenaConfig("test", "test", "test"), Map(player -> playerState), ZonedDateTime.now())

      // if wasHit was true, then move forward, otherwise do nothing
      val newState = await {
        Arena.updateArena(initState) { (arenaState, _) =>
          Future.successful {
            if (arenaState.state(player).wasHit) {
              Some(Forward -> Duration.Zero)
            }
            else {
              None
            }
          }
        }
      }

      newState.state(player).y must equal (1)
    }

    "be accurate" in {
      val player1 = Player("http://foo", "foo", new URL("http://foo"))
      val player2 = Player("http://bar", "bar", new URL("http://bar"))
      val player1State = PlayerState(0, 0, Direction.S, true, 0)
      val player2State = PlayerState(0, 1, Direction.N, false, 0)

      val initState = ArenaState(ArenaConfig("test", "test", "test"), Map(player1 -> player1State, player2 -> player2State), ZonedDateTime.now())

      // if wasHit was true, then move forward, otherwise do nothing
      val newState = await {
        Arena.updateArena(initState) { (_, player) =>
          Future.successful {
            if (player == player1) {
              Some(Throw -> Duration.Zero)
            }
            else {
              None
            }
          }
        }
      }

      newState.state(player1).wasHit must equal (false)
      newState.state(player2).wasHit must equal (true)
    }
  }

  "two players moving" must {
    "work" in {
      val player1 = Player("http://foo", "foo", new URL("http://foo"))
      val player2 = Player("http://bar", "bar", new URL("http://bar"))
      val player1State = PlayerState(0, 0, Direction.E, false, 0)
      val player2State = PlayerState(0, 1, Direction.E, false, 0)

      val initState = ArenaState(ArenaConfig("test", "test", "test"), Map(player1 -> player1State, player2 -> player2State), ZonedDateTime.now())

      // if wasHit was true, then move forward, otherwise do nothing
      val newState = await {
        Arena.updateArena(initState) { (_, player) =>
          Future.successful {
            Some(Forward -> 1.second)
          }
        }
      }

      newState.state(player1).x must equal (1)
      newState.state(player1).y must equal (0)
      newState.state(player2).x must equal (1)
      newState.state(player2).y must equal (1)
    }
  }

  "two players throwing at each other" must {
    "only award the player with the lowest latency" in {
      val player1 = Player("http://foo", "foo", new URL("http://foo"))
      val player2 = Player("http://bar", "bar", new URL("http://bar"))
      val player1State = PlayerState(0, 0, Direction.S, false, 0)
      val player2State = PlayerState(0, 1, Direction.N, false, 0)

      val initState = ArenaState(ArenaConfig("test", "test", "test"), Map(player1 -> player1State, player2 -> player2State), ZonedDateTime.now())

      // if wasHit was true, then move forward, otherwise do nothing
      val newState = await {
        Arena.updateArena(initState) { (_, player) =>
          Future.successful {
            val latency = if (player == player1) {
              1.seconds
            }
            else {
              2.seconds
            }

            Some(Throw -> latency)
          }
        }
      }

      newState.state(player1).score must equal (1)
      newState.state(player1).wasHit must equal (false)
      newState.state(player2).score must equal (-1)
      newState.state(player2).wasHit must equal (true)
    }
  }

  "a player throwing" must {
    "must not hit more than one other player" in {
      val player1 = Player("http://foo", "foo", new URL("http://foo"))
      val player2 = Player("http://bar", "bar", new URL("http://bar"))
      val player3 = Player("http://baz", "baz", new URL("http://baz"))
      val player1State = PlayerState(0, 0, Direction.S, false, 0)
      val player2State = PlayerState(0, 1, Direction.N, false, 0)
      val player3State = PlayerState(0, 2, Direction.N, false, 0)

      val playerStates = Map(player1 -> player1State, player2 -> player2State, player3 -> player3State)

      val initState = ArenaState(ArenaConfig("test", "test", "test"), playerStates, ZonedDateTime.now())

      // if wasHit was true, then move forward, otherwise do nothing
      val newState = await {
        Arena.updateArena(initState) { (_, player) =>
          Future.successful {
            if (player == player1) {
              Some(Throw -> Duration.Zero)
            }
            else {
              None
            }
          }
        }
      }

      newState.state(player1).wasHit must equal (false)
      newState.state(player2).wasHit must equal (true)
      newState.state(player3).wasHit must equal (false)
    }
  }

  "freshArenaState" must {
    "work" in {
      val player1 = Player("http://foo", "foo", new URL("http://foo"))
      val player2 = Player("http://bar", "bar", new URL("http://bar"))
      val player1State = PlayerState(0, 0, Direction.S, true, 1)
      val player2State = PlayerState(0, 1, Direction.N, true, -1)

      val playerStates = Map(player1 -> player1State, player2 -> player2State)

      val initState = ArenaState(ArenaConfig("test", "test", "test"), playerStates, ZonedDateTime.now())

      val updatedState = Arena.freshArenaState(initState)

      updatedState.state.size must equal (2)
      updatedState.state(player1).wasHit must equal (false)
      updatedState.state(player2).wasHit must equal (false)
      updatedState.state(player1).score must equal (0)
      updatedState.state(player2).score must equal (0)
    }
  }

}
