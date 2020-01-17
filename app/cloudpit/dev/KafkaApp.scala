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
import java.nio.file.Files
import java.time.Duration
import java.util.{Properties, UUID}

import scala.collection.JavaConverters._
import com.dimafeng.testcontainers.KafkaContainer
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.scala.kstream.Materialized
import org.apache.kafka.streams.state.QueryableStoreTypes

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
  props.setProperty("bootstrap.servers", container.bootstrapServers)

  val fos = new FileOutputStream(destination)

  props.store(fos, null)

  fos.close()

  Thread.currentThread().join()

}

object KafkaConsumerApp extends App {

  val props = new Properties
  val propsInputStream = getClass.getClassLoader.getResourceAsStream("application.properties")
  props.load(propsInputStream)
  props.setProperty("group.id", "test")
  props.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  props.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
  propsInputStream.close()

  val consumer = new KafkaConsumer[String, String](props)
  consumer.subscribe(Seq("TextLinesTopic", "WordsWithCountsTopic").asJava)

  while (true) {
    val records = consumer.poll(Duration.ofMillis(100))
    records.forEach { record =>
      println(s"topic = ${record.topic}, offset = ${record.offset}, key = ${record.key}, value = ${record.value}")
    }
  }

}

object KafkaProducerApp extends App {

  val props = new Properties
  val propsInputStream = getClass.getClassLoader.getResourceAsStream("application.properties")
  props.load(propsInputStream)
  props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  propsInputStream.close()

  val producer = new KafkaProducer[String, String](props)

  println("Send messages")
  Iterator.continually(StdIn.readLine("> ")).takeWhile(_.nonEmpty).foreach { line =>
    producer.send(new ProducerRecord[String, String]("TextLinesTopic", UUID.randomUUID().toString, line))
  }

  println("closing time")
  producer.close()

}

object KafkaKtableApp extends App {
  import org.apache.kafka.streams.scala.ImplicitConversions._
  import org.apache.kafka.streams.scala.Serdes._

  val props = new Properties
  val propsInputStream = getClass.getClassLoader.getResourceAsStream("application.properties")
  props.load(propsInputStream)
  props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  props.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
  propsInputStream.close()

  val builder = new StreamsBuilder
  val ktable = builder.table[String, Long]("WordsWithCountsTopic", Materialized.as[String, Long, ]("counts-store"))
  val streams = new KafkaStreams(builder.build(), props)

  sys.ShutdownHookThread {
    streams.close()
  }

  streams.start()

  while (true) {
    val view = streams.store(ktable.queryableStoreName, QueryableStoreTypes.keyValueStore)
    println(view)
  }

}
