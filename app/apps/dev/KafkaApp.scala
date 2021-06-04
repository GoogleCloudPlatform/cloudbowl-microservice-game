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

import akka.actor.ActorSystem
import akka.stream.scaladsl.Source
import com.dimafeng.testcontainers.KafkaContainer
import models.Arena.ArenaConfig
import models.Arena.KafkaConfig.Serialization._
import models.Arena.KafkaConfig._
import models.Events.{PlayerJoin, PlayerLeave, ScoresReset}
import models.Player
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.Serializer
import services.Kafka

import java.io.{File, FileOutputStream}
import java.net.URL
import java.nio.file.Files
import java.util.{Properties, UUID}
import scala.concurrent.duration._
import scala.io.StdIn
import scala.jdk.CollectionConverters._
import scala.util.Random

object KafkaApp extends App {

  val container = KafkaContainer()
  container.start()

  val adminClientProps = new Properties()
  adminClientProps.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)
  val adminClient = AdminClient.create(adminClientProps)
  val viewerPingTopic = new NewTopic(Topics.viewerPing, 5, 1.toShort)
  val playersUpdateTopic = new NewTopic(Topics.playerUpdate, 5, 1.toShort)
  val arenaConfigTopic = new NewTopic(Topics.arenaConfig, 5, 1.toShort)
  val arenaUpdateTopic = new NewTopic(Topics.arenaUpdate, 5, 1.toShort)
  adminClient.createTopics(List(viewerPingTopic, playersUpdateTopic, arenaConfigTopic, arenaUpdateTopic).asJavaCollection)

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

  val viewerEventsSource = SinksAndSources.viewerPingSource(groupId)

  val playerUpdateSource = SinksAndSources.playerUpdateSource(groupId)

  val arenaConfigSource = SinksAndSources.arenaConfigSource(groupId)

  val arenaUpdateSource = SinksAndSources.arenaUpdateSource(groupId)

  viewerEventsSource.merge(playerUpdateSource).merge(arenaUpdateSource).merge(arenaConfigSource).runForeach(println)
}

object KafkaProducerApp extends App {

  private implicit val actorSystem = ActorSystem()
  private implicit val ec = actorSystem.dispatcher

  private val avatarBaseUrl = actorSystem.settings.config.getString("avatar.base.url")

  Iterator.continually {
    println("Command:")
    StdIn.readLine()
  } foreach { line =>

    def send[K, V](topic: String, key: K, value: V)(implicit keySerializer: Serializer[K], valueSerializer: Serializer[V]): Unit = {
      val record = new ProducerRecord(topic, key, value)
      println("sending" -> record)
      Source.single(record).to(Kafka.sink[K, V]).run()
    }

    def playerJoin(arena: String) = {
      val name = Random.alphanumeric.take(6).mkString
      val service = s"http://localhost:8080/$name"
      val img = new URL(s"$avatarBaseUrl/285/$name.png")
      val player = Player(service, name, img)
      send(Topics.playerUpdate, arena, PlayerJoin(player))
    }

    if (line.nonEmpty) {
      line.split("/") match {
        case Array(arena, "create") =>
          val arenaConfig = ArenaConfig(arena, arena, "2728", Some(new URL("https://github.com/cloudbowl/arenas")))
          send(Topics.arenaConfig, arena, arenaConfig)

        case Array(arena, "playerjoin") =>
          playerJoin(arena)

        case Array(arena, "playerfill", numString) =>
          (1 to numString.toInt).foreach( _ => playerJoin(arena))

        case Array(arena, "playerleave", name) =>
          val service = s"http://localhost:8080/$name"
          send(Topics.playerUpdate, arena, PlayerLeave(service))

        case Array(arena, "viewerjoin") =>
          val uuid = UUID.randomUUID()
          println(s"viewer $uuid joining for 1 minute")
          val cancelable = actorSystem.scheduler.scheduleAtFixedRate(Duration.Zero, 15.seconds) { () =>
            send(Topics.viewerPing, arena, uuid)
          }
          actorSystem.scheduler.scheduleOnce(1.minute)(cancelable.cancel())

        case Array(arena, "scoresreset") =>
          send(Topics.scoresReset, arena, ScoresReset)

        case _ =>
          println(s"Invalid command: $line")
      }
    }

  }

}
