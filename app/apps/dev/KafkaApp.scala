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

package apps.dev

import java.io.{File, FileOutputStream}
import java.nio.file.Files
import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import com.dimafeng.testcontainers.KafkaContainer
import models.Arena
import models.Events.PlayersRefresh
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer
import services.KafkaSerialization._
import services.{Kafka, Topics}

import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._

object KafkaApp extends App {

  val container = KafkaContainer()
  container.start()

  val adminClientProps = new Properties()
  adminClientProps.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)
  val adminClient = AdminClient.create(adminClientProps)
  val viewerPingTopic = new NewTopic(Topics.viewerPing, 5, 1.toShort)
  val playersRefreshTopic = new NewTopic(Topics.playersRefresh, 5, 1.toShort)
  val arenaUpdateTopic = new NewTopic(Topics.arenaUpdate, 5, 1.toShort)
  adminClient.createTopics(List(viewerPingTopic, playersRefreshTopic, arenaUpdateTopic).asJavaCollection)

  sys.addShutdownHook {
    container.stop()
  }

  val destination = new File("target/scala-2.13/classes/application.properties")
  destination.delete()
  Files.createDirectories(destination.getParentFile.toPath)

  val props = new Properties()
  props.setProperty("kafka-clients.bootstrap.servers", container.bootstrapServers)

  val fos = new FileOutputStream(destination)

  props.store(fos, null)

  fos.close()

  Thread.currentThread().join()

}

object KafkaConsumerApp extends App {
  private implicit val actorSystem = ActorSystem()

  val groupId = UUID.randomUUID().toString

  val viewerEventsSource = Arena.KafkaSinksAndSources.viewerPingSource(groupId)

  val playersRefreshSource = Arena.KafkaSinksAndSources.playersRefreshSource(groupId)

  val arenaUpdateSource = Arena.KafkaSinksAndSources.arenaUpdateSource(groupId)

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
            send(Topics.viewerPing, arena, uuid)
          }
          actorSystem.scheduler.scheduleOnce(1.minute)(cancelable.cancel())

        case _ =>
          println(s"Invalid command: $line")
      }
    }

  }

}
