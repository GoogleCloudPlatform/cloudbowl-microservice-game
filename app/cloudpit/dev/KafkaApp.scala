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

package cloudpit.dev

import java.io.{File, FileOutputStream}
import java.net.URL
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import cloudpit.Events.{PlayerEvent, PlayerJoin, PlayerLeave, ViewerEvent, ViewerJoin, ViewerLeave}
import cloudpit.KafkaSerialization._
import cloudpit.{Arena, Kafka, Player, Topics}
import com.dimafeng.testcontainers.KafkaContainer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer

import scala.io.StdIn

object KafkaApp extends App {

  val container = KafkaContainer()
  container.start()

  sys.addShutdownHook {
    container.stop()
  }

  val destination = new File("target/scala-2.12/classes/application.properties")
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

  val playersSource = Kafka.source[Arena.Path, PlayerEvent](UUID.randomUUID().toString, Topics.players)
  val viewersSource = Kafka.source[Arena.Path, ViewerEvent](UUID.randomUUID().toString, Topics.viewers)

  playersSource.merge(viewersSource).runForeach(println)
}

object KafkaProducerApp extends App {

  private implicit val actorSystem = ActorSystem()

  Iterator.continually {
    println("Command:")
    StdIn.readLine()
  } foreach { line =>

    def player(path: String): Player = {
      val service = new URL(s"http://localhost:9000/$path")
      Player(service.toString, path, service)
    }

    def send[E](topic: String, arena: Arena.Path, event: E)(implicit serializer: Serializer[E]) {
      val record = new ProducerRecord(topic, arena, event)
      println("sending" -> record)
      Source.single(record).to(Kafka.sink[Arena.Path, E]).run()
    }

    line.split("/") match {
      case Array(arena, "playerjoin", path) =>
        send(Topics.players, arena, PlayerJoin(arena, player(path)))
      case Array(arena, "playerleave", path) =>
        send(Topics.players, arena, PlayerLeave(arena, player(path)))

      case Array(arena, "viewerjoin") =>
        send(Topics.viewers, arena, ViewerJoin(arena))
      case Array(arena, "viewerleave") =>
        send(Topics.viewers, arena, ViewerLeave(arena))

      case _ =>
        println(s"Invalid command: $line")
    }

  }

}
