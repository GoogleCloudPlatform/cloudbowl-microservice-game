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

package cloudpit

import java.net.URL
import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.ConsumerMessage.CommittableMessage
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscription, Subscriptions}
import akka.stream.scaladsl.{Sink, Source}
import cloudpit.Events.{ArenaDimsAndPlayers, PlayersRefresh}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer, UUIDDeserializer, UUIDSerializer}

object Kafka {

  def bootstrapServers(implicit actorSystem: ActorSystem): String = {
    actorSystem.settings.config.getString("kafka.bootstrap.servers")
  }

  def producerSettings[K, V](keySerializer: Serializer[K], valueSerializer: Serializer[V])(implicit actorSystem: ActorSystem): ProducerSettings[K, V] = {
    ProducerSettings(actorSystem, keySerializer, valueSerializer).withBootstrapServers(bootstrapServers)
  }

  def consumerSettings[K, V](keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V])(implicit actorSystem: ActorSystem): ConsumerSettings[K, V] = {
    ConsumerSettings(actorSystem, keyDeserializer, valueDeserializer).withBootstrapServers(bootstrapServers)
  }

  def sink[K, V](implicit actorSystem: ActorSystem, keySerializer: Serializer[K], valueSerializer: Serializer[V]): Sink[ProducerRecord[K, V], _] = {
    Producer.plainSink(producerSettings(keySerializer, valueSerializer))
  }

  // todo: partitions
  def source[K, V](groupId: String, topic: String, maybeOffset: Option[Long] = None)(implicit actorSystem: ActorSystem, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Source[ConsumerRecord[K, V], _] = {
    val subscription = maybeOffset.fold[Subscription](Subscriptions.topics(topic)) { offset =>
      Subscriptions.assignmentWithOffset(new TopicPartition(topic, 0), offset)
    }

    val settings = consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId) //.withProperties(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> resetConfig)
    Consumer.plainSource(settings, subscription)
  }

  def committableSource[K, V](groupId: String, topic: String)(implicit actorSystem: ActorSystem, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Source[CommittableMessage[K, V], _] = {
    val settings = consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId).withProperties(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "latest")
    Consumer.committableSource(settings, Subscriptions.topics(topic))
  }

}

object Topics {

  val playersRefresh = "playersRefresh"
  val viewerPing = "viewerPing"
  val arenaUpdate = "arenaUpdate"

}

object KafkaSerialization {

  import upickle.default._

  implicit val urlReadWriter: ReadWriter[URL] = readwriter[String].bimap(_.toString, new URL(_)) // todo: read can fail
  implicit val directionReadWriter: ReadWriter[Direction.Direction] = macroRW
  implicit val playerStateReadWriter: ReadWriter[PlayerState] = macroRW
  implicit val playerReadWriter: ReadWriter[Player] = macroRW


  implicit val arenaPathDeserializer: Deserializer[Arena.Path] = new StringDeserializer
  implicit val playersRefreshDeserializer: Deserializer[PlayersRefresh.type] = (_: String, data: Array[Byte]) => {
    readBinary[PlayersRefresh.type](data)
  }
  implicit val arenaDimsAndPlayersDeserializer: Deserializer[ArenaDimsAndPlayers] = (_: String, data: Array[Byte]) => {
    readBinary[ArenaDimsAndPlayers](data)
  }
  implicit val uuidDeserializer: Deserializer[UUID] = new UUIDDeserializer


  implicit val uuidSerializer: Serializer[UUID] = new UUIDSerializer
  implicit val arenaPathSerializer: Serializer[Arena.Path] = new StringSerializer
  implicit val arenaDimsAndPlayersSerializer: Serializer[ArenaDimsAndPlayers] = (_: String, data: ArenaDimsAndPlayers) => {
    writeBinary[ArenaDimsAndPlayers](data)
  }
  implicit val playersRefreshSerializer: Serializer[PlayersRefresh.type] = (_: String, data: PlayersRefresh.type) => {
    writeBinary[PlayersRefresh.type](data)
  }

}
