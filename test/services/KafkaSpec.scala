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

import java.util.{Properties, UUID}

import akka.actor.ActorSystem
import akka.stream.scaladsl.{Keep, Sink, Source}
import com.dimafeng.testcontainers.KafkaContainer
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, NewTopic}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpec}
import play.api.libs.ws.ahc.AhcWSClient
import play.api.test.Helpers._

import scala.jdk.CollectionConverters._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Try


class KafkaSpec extends WordSpec with MustMatchers with BeforeAndAfterAll {

  lazy val config: Config = {
    ConfigFactory.load().withValue("kafka-clients.bootstrap.servers", ConfigValueFactory.fromAnyRef(container.bootstrapServers))
  }

  lazy implicit val actorSystem = ActorSystem("test-actor-system", config)
  lazy implicit val ec = actorSystem.dispatcher

  lazy val wsClient = AhcWSClient()

  lazy val container = {
    val kafkaContainer = KafkaContainer()
    kafkaContainer.start()
    kafkaContainer
  }

  lazy val adminClient = {
    val props = new Properties()
    props.setProperty(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, container.bootstrapServers)

    AdminClient.create(props)
  }

  "two consumers in the same group" must {
    "only receive messages for their partition" in {
      implicit val stringDeserializer: StringDeserializer = new StringDeserializer
      implicit val stringSerializer: StringSerializer = new StringSerializer
      val topic = "test"

      val newTopic = new NewTopic(topic, 5, 1.toShort)
      adminClient.createTopics(List(newTopic).asJavaCollection)

      val sink = Kafka.sink[String, String]
      val source1 = Kafka.source[String, String]("test", topic)
      val source2 = Kafka.source[String, String]("test", topic)

      val (control1, futureRecords1) = source1.toMat(Sink.seq)(Keep.both).run()
      val (control2, futureRecords2) = source2.toMat(Sink.seq)(Keep.both).run()

      // wait for the consumers to fully subscribe otherwise the records are sent before the subscription is
      // ready, and the default is for them to start at the end of the stream so the records are missed
      Try(await(Future.never))

      val futureSend = Source(1 to 10).map(i => new ProducerRecord(topic, i.toString, UUID.randomUUID().toString)).runWith(sink)
      await(futureSend)

      // wait for the messages to be delivered to the consumers
      Try(await(Future.never))

      val (records1, records2) = await {
        for {
          _ <- Future.sequence(Seq(control1.shutdown(), control2.shutdown()))
          records1 <- futureRecords1
          records2 <- futureRecords2
        } yield (records1, records2)
      } (1.minute)

      records1 must not be empty
      records2 must not be empty
      records1.intersect(records2) must be (empty)
    }
  }

  override def afterAll(): Unit = {
    container.stop()
    Try(wsClient.close())
    await(actorSystem.terminate())
  }

}
