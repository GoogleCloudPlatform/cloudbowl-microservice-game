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

import java.io.{File, FileNotFoundException}
import java.net.URL
import java.util.UUID

import akka.actor.ActorSystem
import akka.kafka.scaladsl.{Consumer, Producer}
import akka.kafka.{ConsumerSettings, ProducerSettings, Subscription, Subscriptions}
import akka.stream.scaladsl.{Sink, Source}
import cloudpit.Events.{ArenaDimsAndPlayers, PlayersRefresh, ViewerEvent, ViewerEventType, ViewerJoin, ViewerLeave}
import org.apache.commons.compress.utils.IOUtils
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}

import scala.concurrent.Future

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
      Subscriptions.assignmentWithOffset(new TopicPartition(Topics.viewerEvents, 0), offset)
    }

    val settings = consumerSettings(keyDeserializer, valueDeserializer).withGroupId(groupId) //.withProperties(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> resetConfig)
    Consumer.plainSource(settings, subscription)
  }

}

object Topics {

  val playersRefresh = "playersRefresh"
  val viewerEvents = "viewerEvents"
  val arenaUpdate = "arenaUpdate"

}

object KafkaSerialization {

  import upickle.default._

  implicit val urlReadWriter: ReadWriter[URL] = readwriter[String].bimap(_.toString, new URL(_)) // todo: read can fail
  implicit val directionReadWriter: ReadWriter[Direction.Direction] = macroRW
  implicit val playerStateReadWriter: ReadWriter[PlayerState] = macroRW
  implicit val playerReadWriter: ReadWriter[Player] = macroRW
  implicit val viewerEventTypeReadWriter: ReadWriter[ViewerEventType] = macroRW


  implicit val arenaPathDeserializer: Deserializer[Arena.Path] = new StringDeserializer
  implicit val viewerEventKeyDeserializer: Deserializer[ViewerEvent.Key] = (_: String, data: Array[Byte]) => {
    readBinary[ViewerEvent.Key](data)
  }
  implicit val playersRefreshDeserializer: Deserializer[PlayersRefresh.type] = (_: String, data: Array[Byte]) => {
    readBinary[PlayersRefresh.type](data)
  }
  implicit val arenaDimsAndPlayersDeserializer: Deserializer[ArenaDimsAndPlayers] = (_: String, data: Array[Byte]) => {
    readBinary[ArenaDimsAndPlayers](data)
  }


  implicit val arenaPathSerializer: Serializer[Arena.Path] = new StringSerializer
  implicit val arenaDimsAndPlayersSerializer: Serializer[ArenaDimsAndPlayers] = (_: String, data: ArenaDimsAndPlayers) => {
    writeBinary[ArenaDimsAndPlayers](data)
  }
  implicit val viewerEventJoinKeySerializer: Serializer[(UUID, ViewerJoin.type)] = (_: String, data: (UUID, ViewerJoin.type)) => {
    writeBinary[(UUID, ViewerJoin.type)](data)
  }
  implicit val viewerEventLeaveKeySerializer: Serializer[(UUID, ViewerLeave.type)] = (_: String, data: (UUID, ViewerLeave.type)) => {
    writeBinary[(UUID, ViewerLeave.type)](data)
  }
  implicit val playersRefreshSerializer: Serializer[PlayersRefresh.type] = (_: String, data: PlayersRefresh.type) => {
    writeBinary[PlayersRefresh.type](data)
  }

}

object Persistence {

  import java.io.{BufferedInputStream, BufferedOutputStream, FileInputStream, FileOutputStream}

  import upickle.default._

  import scala.util.Try

  trait IO {
    def save[T](topic: String, key: String, offset: Long, t: T)(implicit writer: Writer[T]): Future[Unit]

    def restore[T](topic: String)(implicit reader: Reader[T]): Future[(Long, Map[String, T])]
  }

  class FileIO(dir: File) extends IO {
    if (!dir.exists())
      dir.mkdirs()

    override def save[T](topic: String, key: String, offset: Long, t: T)(implicit writer: Writer[T]): Future[Unit] = {
      Future.fromTry {
        Try {
          val topicDir = new File(dir, topic)
          topicDir.mkdirs()

          val tmpFile = new File(topicDir, s"$key.${UUID.randomUUID().toString}")

          val byteArray = writeBinary(offset -> t)
          val bos = new BufferedOutputStream(new FileOutputStream(tmpFile))
          bos.write(byteArray)
          bos.close()

          val permFile = new File(topicDir, key)
          if (permFile.exists())
            permFile.delete()

          val worked = tmpFile.renameTo(permFile)
          if (worked)
            Future.successful(())
          else
            Future.failed(new Exception(s"Could not write $permFile"))
        }
      }
    }

    override def restore[T](topic: String)(implicit reader: Reader[T]): Future[(Long, Map[String, T])] = {
      val topicDir = new File(dir, topic)

      if (topicDir.exists()) {
        Future.fromTry {
          Try {
            val files = topicDir.listFiles().filter(_.isFile)

            files.foldLeft(0L -> Map.empty[String, T]) { case ((currentOffset, fileContents), file) =>
              val fis = new FileInputStream(file)
              val inputStream = new BufferedInputStream(fis)
              val bytes = IOUtils.toByteArray(inputStream)
              inputStream.close()
              fis.close()

              val (offset, t) = readBinary[(Long, T)](bytes)

              val newOffset = Math.max(currentOffset, offset)
              newOffset -> fileContents.updated(file.getName, t)
            }
          }
        }
      }
      else {
        Future.failed(new FileNotFoundException(s"$topicDir not found"))
      }
    }
  }

}
