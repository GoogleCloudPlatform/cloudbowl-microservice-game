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

import akka.actor.{Actor, PoisonPill}
import akka.event.Logging
import akka.stream.Materializer
import models.Arena.{ArenaState, playerJson}
import models.{Direction, Player, PlayerState}
import play.api.http.{ContentTypes, HeaderNames}
import play.api.libs.json.JsString
import play.api.libs.ws.StandaloneWSClient
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object Chaos {

  class Flood(player: Player, arenaState: ArenaState) extends Actor {
    private val log = Logging(context.system, this)
    private val numRequests: Int = 100
    private val threadPool = Executors.newFixedThreadPool(numRequests)
    private implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(threadPool)
    private val client: StandaloneWSClient = StandaloneAhcWSClient()(Materializer(context))
    private val json = playerJson(arenaState, player)

    override def receive: Receive = {
      case Flood.Send =>
        log.info(s"Flooding ${player.service}")

        val reqs = Future.sequence {
          Seq.fill(numRequests)(client.url(player.service).post(json))
        }

        reqs.onComplete { _ =>
          self ! PoisonPill
        }
    }

    override def postStop(): Unit = {
      client.close()
      threadPool.shutdown()
    }
  }

  object Flood {
    case object Send
  }

  class Bad(player: Player, arenaState: ArenaState) extends Actor {
    private val log = Logging(context.system, this)

    private implicit val ec: ExecutionContext = context.dispatcher

    private val client: StandaloneWSClient = StandaloneAhcWSClient()(Materializer(context))

    private val additionalField = { () =>
      log.info(s"Sending json with additional field to: ${player.service}")

      val json = playerJson(arenaState, player) + ("bwahahaha" -> JsString("what cha gonna do?"))

      client.url(player.service).post(json)
    }

    private val missingField = { () =>
      log.info(s"Sending json with missing field to: ${player.service}")

      val json = playerJson(arenaState, player) - "_links"

      client.url(player.service).post(json)
    }

    private val invalidJson = { () =>
      log.info(s"Sending invalid json to: ${player.service}")

      val s = "dis not json"

      client.url(player.service).withHttpHeaders(HeaderNames.CONTENT_TYPE -> ContentTypes.JSON).post(s)
    }

    /*
    private val longBodyDelay = { () =>
      log.info(s"Sending body after a long delay to: ${player.service}")

      val json = playerJson(arenaState, player)

      // todo

      client.url(player.service).post(json)
    }
     */

    private val largeBody = { () =>
      log.info(s"Sending large json body to: ${player.service}")

      val state = Seq.fill(10000) {
        val name = Random.alphanumeric.take(16).mkString
        Player(player.service + s"?$name", name, player.pic) -> PlayerState(0, 0, Direction.random, false, 0)
      }.toMap

      val largeArenaState = arenaState.copy(state = state)

      val json = playerJson(largeArenaState, player)

      client.url(player.service).post(json)
    }

    override def receive: Receive = {
      case Bad.Send =>
        val f = Random.shuffle(Set(additionalField, missingField, invalidJson, largeBody)).head
        f().onComplete { result =>
          log.info(s"Response form ${player.service} was: $result")
          self ! PoisonPill
        }
    }

    override def postStop(): Unit = {
      client.close()
    }

  }

  object Bad {
    case object Send
  }

}
