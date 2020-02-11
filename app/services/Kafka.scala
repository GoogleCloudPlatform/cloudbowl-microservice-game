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
import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}
import models.Events.{ArenaDimsAndPlayers, PlayersRefresh}
import models.{Arena, Direction, Player, PlayerState}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer, UUIDDeserializer, UUIDSerializer}

import scala.util.Try

object Kafka {

  def config: Config = {
    val baseConfig = ConfigFactory.load()

    val maybeApiKey = Try(baseConfig.getString("kafka.cluster.api.key")).toOption
    val maybeApiSecret = Try(baseConfig.getString("kafka.cluster.api.secret")).toOption

    (maybeApiKey, maybeApiSecret) match {
      case (Some(apiKey), Some(apiSecret)) =>
        val saslJaasConfig = s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$apiKey" password="$apiSecret";"""
        baseConfig
          .withValue("kafka-clients.security.protocol", ConfigValueFactory.fromAnyRef("SASL_SSL"))
          .withValue("kafka-clients.sasl.mechanism", ConfigValueFactory.fromAnyRef("PLAIN"))
          .withValue("kafka-clients.sasl.jaas.config", ConfigValueFactory.fromAnyRef(saslJaasConfig))
      case _ =>
        baseConfig
    }
  }

  def consumerConfig(implicit actorSystem: ActorSystem): Config = {
    config.withFallback(actorSystem.settings.config.getConfig(ConsumerSettings.configPath))
  }

  def producerConfig(implicit actorSystem: ActorSystem): Config = {
    config.withFallback(actorSystem.settings.config.getConfig(ProducerSettings.configPath))
  }

  def producerSettings[K, V](keySerializer: Serializer[K], valueSerializer: Serializer[V])(implicit actorSystem: ActorSystem): ProducerSettings[K, V] = {
    ProducerSettings(producerConfig, keySerializer, valueSerializer)
  }

  def consumerSettings[K, V](keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V])(implicit actorSystem: ActorSystem): ConsumerSettings[K, V] = {
    ConsumerSettings(consumerConfig, keyDeserializer, valueDeserializer)
  }

  def sink[K, V](implicit actorSystem: ActorSystem, keySerializer: Serializer[K], valueSerializer: Serializer[V]): Sink[ProducerRecord[K, V], _] = {
    Producer.plainSink(producerSettings(keySerializer, valueSerializer))
  }

  // todo: partitions
  def source[K, V](groupId: String, topic: String)(implicit actorSystem: ActorSystem, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Source[ConsumerRecord[K, V], _] = {
    val subscription = Subscriptions.topics(topic)
    val settings = consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId) //.withProperties(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> resetConfig)
    Consumer.plainSource(settings, subscription)
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
