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

import akka.Done
import akka.actor.ActorSystem
import akka.kafka.scaladsl.Consumer.Control
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.{Config, ConfigValueFactory}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, Serializer}

import scala.concurrent.Future
import scala.util.Try

object Kafka {

  def authConfig(implicit actorSystem: ActorSystem): Config = {
    val baseConfig = actorSystem.settings.config

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

  private def consumerConfig(implicit actorSystem: ActorSystem): Config = {
    authConfig.withFallback(actorSystem.settings.config.getConfig(ConsumerSettings.configPath))
  }

  private def producerConfig(implicit actorSystem: ActorSystem): Config = {
    authConfig.withFallback(actorSystem.settings.config.getConfig(ProducerSettings.configPath))
  }

  private def producerSettings[K, V](keySerializer: Serializer[K], valueSerializer: Serializer[V])(implicit actorSystem: ActorSystem): ProducerSettings[K, V] = {
    ProducerSettings(producerConfig, keySerializer, valueSerializer)
  }

  private def consumerSettings[K, V](keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V])(implicit actorSystem: ActorSystem): ConsumerSettings[K, V] = {
    ConsumerSettings(consumerConfig, keyDeserializer, valueDeserializer)
  }

  def sink[K, V](implicit actorSystem: ActorSystem, keySerializer: Serializer[K], valueSerializer: Serializer[V]): Sink[ProducerRecord[K, V], Future[Done]] = {
    Producer.plainSink(producerSettings(keySerializer, valueSerializer))
  }

  def source[K, V](groupId: String, topic: String, resetConfig: String = "latest")(implicit actorSystem: ActorSystem, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Source[ConsumerRecord[K, V], Control] = {
    val subscription = Subscriptions.topics(topic)
    val settings = consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId).withProperties(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> resetConfig)
    //Consumer.plainPartitionedSource(settings, subscription).flatMapMerge(Int.MaxValue, _._2)
    Consumer.plainSource(settings, subscription)
  }

}
