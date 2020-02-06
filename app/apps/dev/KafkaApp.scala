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

package apps.dev

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import models.Events.{ArenaDimsAndPlayers, PlayersRefresh}
import services.KafkaSerialization._
import com.dimafeng.testcontainers.KafkaContainer
import models.Arena
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer
import services.{Kafka, Topics}

import scala.concurrent.duration._
import scala.io.StdIn

object KafkaApp extends App {

  val container = KafkaContainer()
  container.start()

  sys.addShutdownHook {
    container.stop()
  }

  val destination = new File("target/scala-2.13/classes/application.properties")
  destination.delete()
  Files.createDirectories(destination.getParentFile.toPath)

  val props = new Properties()
  props.setProperty("kafka.bootstrap.servers", container.bootstrapServers)

  val fos = new FileOutputStream(destination)

  props.store(fos, null)

  fos.close()

  Thread.currentThread().join()

}

object KafkaConsumerApp extends App {
  private implicit val actorSystem = ActorSystem()

  val viewerEventsSource = Kafka.source[UUID, Arena.Path](UUID.randomUUID().toString, Topics.viewerPing)

  val playersRefreshSource = Kafka.source[Arena.Path, PlayersRefresh.type](UUID.randomUUID().toString, Topics.playersRefresh)

  val arenaUpdateSource = Kafka.source[Arena.Path, ArenaDimsAndPlayers](UUID.randomUUID().toString, Topics.arenaUpdate)

  viewerEventsSource.merge(playersRefreshSource).merge(arenaUpdateSource).runForeach(println)
}

object KafkaProducerApp extends App {

  private implicit val actorSystem = ActorSystem()
  private implicit val ec = actorSystem.dispatcher

  Iterator.continually {
    println("Command:")
    StdIn.readLine()
  } foreach { line =>

    def send[K, V](topic: String, key: K, value: V)(implicit keySerializer: Serializer[K], valueSerializer: Serializer[V]): Unit = {
      val record = new ProducerRecord(topic, key, value)
      println("sending" -> record)
      Source.single(record).to(Kafka.sink[K, V]).run()
    }

    if (line.nonEmpty) {
      line.split("/") match {
        case Array(arena, "playersrefresh") =>
          send(Topics.playersRefresh, arena, PlayersRefresh)

        case Array(arena, "viewerjoin") =>
          val uuid = UUID.randomUUID()
          println(s"viewer $uuid joining for 1 minute")
          val cancelable = actorSystem.scheduler.scheduleAtFixedRate(Duration.Zero, 15.seconds) { () =>
            send(Topics.viewerPing, uuid, arena)
          }
          actorSystem.scheduler.scheduleOnce(1.minute)(cancelable.cancel())

        case _ =>
          println(s"Invalid command: $line")
      }
    }

  }

}
