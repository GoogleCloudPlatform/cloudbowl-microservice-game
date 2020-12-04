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

package services

import akka.actor.ActorSystem
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import play.api.Configuration
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._

import scala.util.Try


class PlayersSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  lazy implicit val actorSystem = ActorSystem()
  lazy implicit val ec = actorSystem.dispatcher

  lazy val wsClient = AhcWSClient()

  // todo: this requires a spreadsheet with specific values
  "googlesheets" must {
    "work" in {
      val config = new GoogleSheetPlayersConfig(Configuration(actorSystem.settings.config))
      if (config.isConfigured) {
        val googleSheetPlayers = new GoogleSheetPlayers(config, wsClient)

        val arenaConfigAndPlayers = await(googleSheetPlayers.fetch("test"))
        arenaConfigAndPlayers.players must not be empty
      }
      else {
        cancel("required config not set")
      }
    }
  }

  "github" must {
    "work" in {
      val config = Configuration(actorSystem.settings.config)
      val gitHub = new GitHub(config, wsClient)
      if (gitHub.isConfigured) {
        val gitHubPlayers = new GitHubPlayers(gitHub, config)
        val arenaConfigAndPlayers = await(gitHubPlayers.fetch("test"))
        arenaConfigAndPlayers.players must not be empty
      }
      else {
        cancel("required config not set")
      }
    }
  }

  override def afterAll(): Unit = {
    Try(wsClient.close())
    await(actorSystem.terminate())
  }

}
