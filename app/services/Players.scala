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

import java.net.URL

import akka.parboiled2.util.Base64
import javax.inject.Inject
import models.Arena.{ArenaConfigAndPlayers, Path}
import models.{Arena, Player}
import play.api.Configuration
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.WSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

trait Players {
  def fetch(arena: Arena.Path): Future[ArenaConfigAndPlayers]
}

class DevPlayers extends Players {

  def fetch(arena: Arena.Path): Future[ArenaConfigAndPlayers] = {
    Future.successful {
      if (arena == "empty") {
        ArenaConfigAndPlayers(arena, "Empty", "2728", Set.empty)
      }
      else {
        val players = Set.fill(Random.nextInt(10) + 1) {
          val name = Random.alphanumeric.take(6).mkString
          val service = s"http://localhost:8080/$name"
          val img = new URL(s"https://api.adorable.io/avatars/285/$name.png")
          Player(service, name, img)
        }

        ArenaConfigAndPlayers(arena, arena.capitalize, "2728", players)
      }
    }
  }

}

class GoogleSheetPlayersConfig @Inject()(configuration: Configuration) {
  val maybePrivateKeyId = configuration.getOptional[String]("players.sheet.privateKeyId").filter(_.nonEmpty)
  val maybePrivateKey = configuration.getOptional[String]("players.sheet.privateKey").filter(_.nonEmpty).map(_.replace("\\n", "\n"))
  val maybeClientEmail = configuration.getOptional[String]("players.sheet.clientEmail").filter(_.nonEmpty)

  val maybePsk = configuration.getOptional[String]("players.sheet.psk").filter(_.nonEmpty)

  val maybeId = configuration.getOptional[String]("players.sheet.id").filter(_.nonEmpty)
  val maybeName = configuration.getOptional[String]("players.sheet.name").filter(_.nonEmpty)

  val isConfigured = maybePrivateKeyId.isDefined && maybePrivateKey.isDefined && maybeClientEmail.isDefined && maybeId.isDefined && maybeName.isDefined
}

// todo: name & emoji code
class GoogleSheetPlayers @Inject()(googleSheetPlayersConfig: GoogleSheetPlayersConfig, wsClient: WSClient)(implicit ec: ExecutionContext) extends Players {

  import java.time.Clock

  import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

  val privateKeyId = googleSheetPlayersConfig.maybePrivateKeyId.get
  val privateKey = googleSheetPlayersConfig.maybePrivateKey.get

  val clientEmail = googleSheetPlayersConfig.maybeClientEmail.get
  val id = googleSheetPlayersConfig.maybeId.get
  val name = googleSheetPlayersConfig.maybeName.get

  implicit val clock: Clock = Clock.systemUTC

  def fetch(arena: Arena.Path): Future[ArenaConfigAndPlayers] = {
    val header = JwtHeader(JwtAlgorithm.RS256).withKeyId(privateKeyId)
    val claim = JwtClaim().by(clientEmail).about(clientEmail).to("https://sheets.googleapis.com/").issuedNow.expiresIn(60L)
    val token = Jwt.encode(header, claim, privateKey)

    val url = s"https://sheets.googleapis.com/v4/spreadsheets/$id/values/$name"
    wsClient.url(url).withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token").get().flatMap { response =>
      response.status match {
        case Status.OK =>
          Future.fromTry {
            Try {
              val players = (response.body[JsValue] \ "values").as[Seq[Seq[String]]].drop(1).flatMap {
                case Seq(name, service, pic, thatArena) if arena == thatArena && name.nonEmpty && service.nonEmpty && pic.nonEmpty =>
                  Some(Player(service, name, new URL(pic)))
                case _ =>
                  None
              }
              ArenaConfigAndPlayers(arena, arena.capitalize, "1f351", players.toSet)
            }
          }
        case _ =>
          Future.failed(new Exception(response.body))
      }
    }
  }
}


class GitHubPlayers @Inject()(gitHub: GitHub, wsClient: WSClient)(implicit ec: ExecutionContext) extends Players {

  override def fetch(arena: Path): Future[ArenaConfigAndPlayers] = {
    gitHub.ok[JsValue](s"repos/${gitHub.maybeOrgRepo.get}/contents/$arena.json").flatMap { body =>
      Future.fromTry {
        Try {
          val json = Base64.rfc2045().decode((body \ "content").as[String])
          val arenaConfig = Json.parse(json)
          val name = (arenaConfig \ "name").as[String]
          val emojiCode = (arenaConfig \ "emoji_code").as[String]
          val players = (arenaConfig \ "players").as[Seq[JsObject]].map { player =>
            val name = (player \ "name").as[String]
            val url = (player \ "url").as[String]

            val maybeGitHubUser = (player \ "github_user").asOpt[String]

            // todo: linkedin, gravatar, etc
            val pic = new URL(
              maybeGitHubUser.map { gitHubUser =>
                s"https://github.com/$gitHubUser.png?size=285"
              }.getOrElse {
                s"https://api.adorable.io/avatars/285/$name.png"
              }
            )

            Player(url, name, pic)
          }

          ArenaConfigAndPlayers(arena, name, emojiCode, players.toSet)
        }
      }
    }
  }

}
