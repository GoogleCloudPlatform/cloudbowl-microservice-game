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
import java.time.Clock

import javax.inject.{Inject, Singleton}
import org.testcontainers.shaded.org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.testcontainers.shaded.org.bouncycastle.openssl.{PEMKeyPair, PEMParser}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}
import play.api.Configuration
import play.api.http.{HeaderNames, HttpVerbs, Status}
import play.api.libs.json.{JsObject, JsValue}
import play.api.libs.ws.{BodyReadable, WSClient}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@Singleton
class GitHub @Inject()(configuration: Configuration, wsClient: WSClient)(implicit ec: ExecutionContext) {
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

  implicit val clock: Clock = Clock.systemUTC

  // get the first install, the get an access token for that install
  // todo: we could fail the future instead of the .get's
  lazy val accessTokenFuture: Future[String] = {
    val privateKey = maybeKeyPair.get.getPrivate
    val header = JwtHeader(JwtAlgorithm.RS256)
    val claim = JwtClaim(issuer = Some(maybeAppId.get)).issuedNow.expiresIn(10.minutes.toSeconds)
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

  def ok[A: BodyReadable](path: String): Future[A] = {
    accessTokenFuture.flatMap { token =>
      val url = s"https://api.github.com/$path"

      wsClient
        .url(url)
        .withHttpHeaders(HeaderNames.AUTHORIZATION -> s"token $token", HeaderNames.ACCEPT -> "application/vnd.github.machine-man-preview+json")
        .get()
        .flatMap { response =>
          response.status match {
            case Status.OK =>
              Future.fromTry {
                Try {
                  response.body[A]
                }
              }
            case _ =>
              Future.failed(new Exception(s"Could not retrieve or parse $url"))
          }
        }
    }
  }

}

