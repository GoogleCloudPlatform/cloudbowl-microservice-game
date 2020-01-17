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

import java.util.Properties

import org.apache.kafka.streams.{KafkaStreams, StreamsConfig}
import org.apache.kafka.streams.scala.ImplicitConversions._
import org.apache.kafka.streams.scala.StreamsBuilder
import org.apache.kafka.streams.scala.kstream.Materialized

object Battle extends App {
  import org.apache.kafka.streams.scala.Serdes._

  val propsInputStream = getClass.getClassLoader.getResourceAsStream("application.properties")
  val props = new Properties()
  props.load(propsInputStream)
  propsInputStream.close()
  props.put(StreamsConfig.APPLICATION_ID_CONFIG, "wordcount-application")

  val builder = new StreamsBuilder
  val textLines = builder.stream[String, String]("TextLinesTopic")
  val wordCounts = textLines
    .flatMapValues(textLine => textLine.toLowerCase.split("\\W+"))
    .groupBy((_, word) => word)
    .count()(Materialized.as("counts-store"))

  wordCounts.toStream.to("WordsWithCountsTopic")

  val streams = new KafkaStreams(builder.build(), props)
  streams.start()


  sys.ShutdownHookThread {
    streams.close()
  }

  // get arena state


  // get viewer state & subscribe


  // get player state & subscribe





}
