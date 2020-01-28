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

import java.nio.file.Files

import cloudpit.Persistence.{FileIO, IO}
import org.scalatest.{MustMatchers, WordSpec}

import scala.concurrent.Await
import scala.concurrent.duration._

class KafkaSpec extends WordSpec with MustMatchers {

  "write / read" must {
    "work" in {
      val tmpDir = Files.createTempDirectory("io").toFile
      val io = new FileIO(tmpDir)
      Await.result(io.save("foo", "one", 41, "asdf"), 5.seconds)
      Await.result(io.save("foo", "two", 42, "zxcv"), 5.seconds)
      val (offset, data) = Await.result(io.restore[String]("foo"), 5.seconds)

      offset must equal (42)
      data("one") must equal ("asdf")
      data("two") must equal ("zxcv")
    }
  }

}
