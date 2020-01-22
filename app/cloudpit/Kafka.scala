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

import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscriptions}
import akka.stream.scaladsl.{Sink, Source}
import cloudpit.Events.{PlayerEvent, PlayerJoin, PlayerLeave, ViewerEvent, ViewerJoin, ViewerLeave}
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}

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

  def source[K, V](groupId: String, topic: String)(implicit actorSystem: ActorSystem, keyDeserializer: Deserializer[K], valueDeserializer: Deserializer[V]): Source[ConsumerRecord[K, V], _] = {
    val subscriptions = Subscriptions.topics(topic)
    Consumer.plainSource(consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId), subscriptions)
  }

}

object Topics {

  val players = "players"
  val viewers = "viewers"

}

object KafkaSerialization {
  import upickle.default._

  implicit val urlReadWriter: ReadWriter[URL] = readwriter[String].bimap(_.toString, new URL(_)) // todo: read can fail
  implicit val directionReadWriter: ReadWriter[Direction.Direction] = macroRW
  implicit val playerStateReadWriter: ReadWriter[PlayerState] = macroRW
  implicit val arenaStateReadWriter: ReadWriter[Arenas] = macroRW
  implicit val playerReadWriter: ReadWriter[Player] = macroRW
  implicit val playerJoinReadWriter: ReadWriter[PlayerJoin] = macroRW
  implicit val playerLeaveReadWriter: ReadWriter[PlayerLeave] = macroRW
  implicit val playerEventReadWriter: ReadWriter[PlayerEvent] = ReadWriter.merge(playerJoinReadWriter, playerLeaveReadWriter)
  implicit val viewerJoinReadWriter: ReadWriter[ViewerJoin] = macroRW
  implicit val viewerLeaveReadWriter: ReadWriter[ViewerLeave] = macroRW
  implicit val viewerEventReadWriter: ReadWriter[ViewerEvent] = ReadWriter.merge(viewerJoinReadWriter, viewerLeaveReadWriter)

  implicit val arenaPathDeserializer: Deserializer[Arena.Path] = new StringDeserializer
  implicit val playerEventDeserializer: Deserializer[PlayerEvent] = (_: String, data: Array[Byte]) => {
    readBinary[PlayerEvent](data)
  }
  implicit val viewerEventDeserializer: Deserializer[ViewerEvent] = (_: String, data: Array[Byte]) => {
    readBinary[ViewerEvent](data)
  }

  implicit val arenaPathSerializer: Serializer[Arena.Path] = new StringSerializer
  implicit val arenaStateSerializer: Serializer[Arenas] = (_: String, data: Arenas) => {
    writeBinary[Arenas](data)
  }
  implicit val playerJoinSerializer: Serializer[PlayerJoin] = (_: String, data: PlayerJoin) => {
    writeBinary[PlayerJoin](data)
  }
  implicit val playerLeaveSerializer: Serializer[PlayerLeave] = (_: String, data: PlayerLeave) => {
    writeBinary[PlayerLeave](data)
  }
  implicit val viewerJoinSerializer: Serializer[ViewerJoin] = (_: String, data: ViewerJoin) => {
    writeBinary[ViewerJoin](data)
  }
  implicit val viewerLeaveSerializer: Serializer[ViewerLeave] = (_: String, data: ViewerLeave) => {
    writeBinary[ViewerLeave](data)
  }

}
