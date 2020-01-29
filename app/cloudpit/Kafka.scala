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
import cloudpit.Events.{PlayersRefresh, ViewerEvent, ViewerEventType, ViewerJoin, ViewerLeave}
import org.apache.commons.compress.utils.IOUtils
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer, UUIDDeserializer, UUIDSerializer}

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
  implicit val arenasReadWriter: ReadWriter[Arenas] = macroRW
  //implicit val arenasUpdateReadWriter: ReadWriter[ArenasUpdate] = macroRW
  implicit val playerReadWriter: ReadWriter[Player] = macroRW
  //implicit val playerJoinReadWriter: ReadWriter[PlayerJoin] = macroRW
  //implicit val playerLeaveReadWriter: ReadWriter[PlayerLeave] = macroRW
  //implicit val playerEventReadWriter: ReadWriter[PlayerEvent] = ReadWriter.merge(playerJoinReadWriter, playerLeaveReadWriter)
  //implicit val viewerJoinReadWriter: ReadWriter[ViewerJoin] = macroRW
  //implicit val viewerLeaveReadWriter: ReadWriter[ViewerLeave] = macroRW
  //implicit val viewerEventReadWriter: ReadWriter[ViewerEvent] = ReadWriter.merge(viewerJoinReadWriter, viewerLeaveReadWriter)
  implicit val viewerEventTypeReadWriter: ReadWriter[ViewerEventType] = macroRW
  //implicit val viewerEventKeyReadWriter: ReadWriter[ViewerEvent.Key] = macroRW


  implicit val intDeserializer: Deserializer[Int] = (_: String, data: Array[Byte]) => {
    readBinary[Int](data)
  }
  implicit val arenaPathDeserializer: Deserializer[Arena.Path] = new StringDeserializer
  implicit val uuidDeserializer: Deserializer[UUID] = new UUIDDeserializer
  implicit val viewerEventKeyDeserializer: Deserializer[ViewerEvent.Key] = (_: String, data: Array[Byte]) => {
    readBinary[ViewerEvent.Key](data)
  }

  implicit val playersRefreshDeserializer: Deserializer[PlayersRefresh.type] = (_: String, data: Array[Byte]) => {
    readBinary[PlayersRefresh.type](data)
  }

  /*
  implicit val playerEventDeserializer: Deserializer[PlayerEvent] = (_: String, data: Array[Byte]) => {
    readBinary[PlayerEvent](data)
  }
   */
  /*
  implicit val viewerEventDeserializer: Deserializer[ViewerEvent] = (_: String, data: Array[Byte]) => {
    readBinary[ViewerEvent](data)
  }
   */
  implicit val mapPlayerPlayerStateDeserializer: Deserializer[Map[Player, PlayerState]] = (_: String, data: Array[Byte]) => {
    readBinary[Map[Player, PlayerState]](data)
  }


  implicit val intSerializer: Serializer[Int] = (_: String, data: Int) => {
    writeBinary[Int](data)
  }
  implicit val arenaPathSerializer: Serializer[Arena.Path] = new StringSerializer
  implicit val uuidSerializer: Serializer[UUID] = new UUIDSerializer
  implicit val arenaStateSerializer: Serializer[Arenas] = (_: String, data: Arenas) => {
    writeBinary[Arenas](data)
  }

  /*
  implicit val playerJoinSerializer: Serializer[PlayerJoin] = (_: String, data: PlayerJoin) => {
    writeBinary[PlayerJoin](data)
  }
  implicit val playerLeaveSerializer: Serializer[PlayerLeave] = (_: String, data: PlayerLeave) => {
    writeBinary[PlayerLeave](data)
  }
   */
  /*
  implicit val viewerJoinSerializer: Serializer[ViewerJoin] = (_: String, data: ViewerJoin) => {
    writeBinary[ViewerJoin](data)
  }
  implicit val viewerLeaveSerializer: Serializer[ViewerLeave] = (_: String, data: ViewerLeave) => {
    writeBinary[ViewerLeave](data)
  }
   */
  implicit val mapPlayerPlayerStateSerializer: Serializer[Map[Player, PlayerState]] = (_: String, data: Map[Player, PlayerState]) => {
    writeBinary[Map[Player, PlayerState]](data)
  }

  implicit val viewerJoinSerializer: Serializer[ViewerJoin.type] = (_: String, data: ViewerJoin.type) => {
    writeBinary[ViewerJoin.type](data)
  }

  implicit val viewerLeaveSerializer: Serializer[ViewerLeave.type] = (_: String, data: ViewerLeave.type) => {
    writeBinary[ViewerLeave.type](data)
  }

  implicit val viewerEventTypeSerializer: Serializer[ViewerEventType] = (_: String, data: ViewerEventType) => {
    writeBinary[ViewerEventType](data)
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
