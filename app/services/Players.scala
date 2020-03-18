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

import java.io.StringReader
import java.net.URL

import akka.parboiled2.util.Base64
import javax.inject.Inject
import models.Arena.ArenaConfigAndPlayers
import models.{Arena, Player}
import org.testcontainers.shaded.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.testcontainers.shaded.org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import play.api.Configuration
import play.api.http.{HeaderNames, HttpVerbs, Status}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.duration._
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
  val maybePrivateKey = configuration.getOptional[String]("players.sheet.privateKey").filter(_.nonEmpty).map(_.replaceAllLiterally("\\n", "\n"))
  val maybeClientEmail = configuration.getOptional[String]("players.sheet.clientEmail").filter(_.nonEmpty)

  val maybePsk = configuration.getOptional[String]("players.sheet.psk").filter(_.nonEmpty)

  val maybeId = configuration.getOptional[String]("players.sheet.id").filter(_.nonEmpty)
  val maybeName = configuration.getOptional[String]("players.sheet.name").filter(_.nonEmpty)

  val isConfigured = maybePrivateKeyId.isDefined && maybePrivateKey.isDefined && maybeClientEmail.isDefined && maybeId.isDefined && maybeName.isDefined
}

// todo: name & emoji code
class GoogleSheetPlayers @Inject()(googleSheetPlayersConfig: GoogleSheetPlayersConfig, wsClient: StandaloneWSClient)(implicit ec: ExecutionContext) extends Players {

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

class GitHubPlayersConfig @Inject()(configuration: Configuration) {
  val maybeOrgRepo = configuration.getOptional[String]("players.github.orgrepo").filter(_.nonEmpty)

  val maybeAppId = configuration.getOptional[String]("players.github.app.id").filter(_.nonEmpty)

  val maybePrivateKey = configuration.getOptional[String]("players.github.app.private-key").filter(_.nonEmpty)

  val maybePsk = configuration.getOptional[String]("players.github.psk").filter(_.nonEmpty)

  val isConfigured = maybeOrgRepo.isDefined && maybePrivateKey.isDefined

  lazy val maybeKeyPair = {
    maybePrivateKey.map { privateKey =>
      val stringReader = new StringReader(privateKey)
      val pemParser = new PEMParser(stringReader)
      val pemObject = pemParser.readObject()
      new JcaPEMKeyConverter().getKeyPair(pemObject.asInstanceOf[PEMKeyPair])
    }
  }
}

class GitHubPlayers @Inject()(gitHubPlayersConfig: GitHubPlayersConfig, wsClient: StandaloneWSClient)(implicit ec: ExecutionContext) extends Players {

  import java.time.Clock

  import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

  val privateKey = gitHubPlayersConfig.maybeKeyPair.get.getPrivate

  implicit val clock: Clock = Clock.systemUTC

  if (false) {
    ec
  }

  // get the first install, the get an access token for that install
  lazy val accessTokenFuture: Future[String] = {
    val header = JwtHeader(JwtAlgorithm.RS256)
    val claim = JwtClaim(issuer = Some(gitHubPlayersConfig.maybeAppId.get)).issuedNow.expiresIn(10.minutes.toSeconds)
    val token = Jwt.encode(header, claim, privateKey)

    wsClient
      .url("https://api.github.com/app/installations")
      .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token", HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json")
      .get()
      .flatMap { installationsResponse =>

        installationsResponse.status match {
          case Status.OK =>
            Future.fromTry {
              Try {
                (installationsResponse.body[JsValue].as[Seq[JsObject]].head \ "id").as[Long]
              }
            } flatMap { installId =>
                wsClient
                  .url(s"https://api.github.com/app/installations/$installId/access_tokens")
                  .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"Bearer $token", HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json")
                  .execute(HttpVerbs.POST)
                  .flatMap { response =>
                    response.status match {
                      case Status.CREATED =>
                        Future.fromTry {
                          Try {
                            (response.body[JsValue] \ "token").as[String]
                          }
                        }
                      case _ =>
                        Future.failed(new Exception("Could not get access token"))
                    }
                  }
              }
          case _ =>
            Future.failed(new Exception("Could not get a GitHub App install"))
        }
      }
  }

  def fetch(arena: Arena.Path): Future[ArenaConfigAndPlayers] = {
    accessTokenFuture.flatMap { token =>
      val path = s"${gitHubPlayersConfig.maybeOrgRepo.get}/contents/$arena.json"
      val url = s"https://api.github.com/repos/$path"

      wsClient
        .url(url)
        .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"token $token", HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json")
        .get()
        .flatMap { response =>
          response.status match {
            case Status.OK =>
              Future.fromTry {
                Try {
                  //println(response.body[JsValue])
                  val json = Base64.rfc2045().decode((response.body[JsValue] \ "content").as[String])
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
            case _ =>
              Future.failed(new Exception(s"Could not retrieve or parse file: github.com/$path"))
          }
        }
    }
  }

}
