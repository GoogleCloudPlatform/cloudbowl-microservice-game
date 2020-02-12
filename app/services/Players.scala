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

import javax.inject.Inject
import models.{Arena, Player}
import play.api.Configuration
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.JsValue
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Random, Try}

trait Players {
  def fetch(arena: Arena.Path): Future[(Arena.Name, Set[Player])]
}

class DevPlayers extends Players {

  def fetch(arena: Arena.Path): Future[(Arena.Name, Set[Player])] = {
    Future.successful {
      if (arena == "empty") {
        ("Empty", Set.empty)
      }
      else {
        val players = Set.fill(Random.nextInt(10) + 1) {
          val name = Random.alphanumeric.take(6).mkString
          val service = s"http://localhost:8080/$name"
          val img = new URL(s"https://api.adorable.io/avatars/285/$name.png")
          Player(service, name, img)
        }

        (arena, players)
      }
    }
  }

}

class GoogleSheetPlayersConfig @Inject()(configuration: Configuration) {
  val maybePrivateKeyId = configuration.getOptional[String]("players.sheet.privateKeyId").filter(_.nonEmpty)
  val maybePrivateKey = configuration.getOptional[String]("players.sheet.privateKey").filter(_.nonEmpty).map(_.replaceAllLiterally("\\n", "\n"))
  val maybeClientEmail = configuration.getOptional[String]("players.sheet.clientEmail").filter(_.nonEmpty)

  val maybePsk = configuration.getOptional[String]("players.sheet.psk").filter(_.nonEmpty)

  val maybeId = configuration.getOptional[String]("players.sheet.id").filter(_.nonEmpty)
  val maybeName = configuration.getOptional[String]("players.sheet.name").filter(_.nonEmpty)

  val isConfigured = maybePrivateKeyId.isDefined && maybePrivateKey.isDefined && maybeClientEmail.isDefined && maybeId.isDefined && maybeName.isDefined
}

class GoogleSheetPlayers @Inject()(googleSheetPlayersConfig: GoogleSheetPlayersConfig, wsClient: StandaloneWSClient)(implicit ec: ExecutionContext) extends Players {

  import java.time.Clock

  import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

  val privateKeyId = googleSheetPlayersConfig.maybePrivateKeyId.get
  val privateKey = googleSheetPlayersConfig.maybePrivateKey.get

  val clientEmail = googleSheetPlayersConfig.maybeClientEmail.get
  val id = googleSheetPlayersConfig.maybeId.get
  val name = googleSheetPlayersConfig.maybeName.get


  implicit val clock: Clock = Clock.systemUTC

  val header = JwtHeader(JwtAlgorithm.RS256).withKeyId(privateKeyId)
  val claim = JwtClaim().by(clientEmail).about(clientEmail).to("https://sheets.googleapis.com/").issuedNow.expiresIn(60L)
  val token = Jwt.encode(header, claim, privateKey)


  def fetch(arena: Arena.Path): Future[(Arena.Name, Set[Player])] = {
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
              arena -> players.toSet
            }
          }
        case _ =>
          Future.failed(new Exception(response.body))
      }
    }
  }
}

