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
import java.util.Properties

import com.dimafeng.testcontainers.PostgreSQLContainer

object PostgresApp extends App {

  val container = PostgreSQLContainer()
  container.start()

  sys.addShutdownHook {
    container.stop()
  }

  val destination = new File("target/scala-2.12/classes/application.properties")
  destination.delete()
  Files.createDirectories(destination.getParentFile.toPath)

  val prop = new Properties()
  prop.setProperty("db.default.url", container.jdbcUrl)
  prop.setProperty("db.default.username", container.username)
  prop.setProperty("db.default.password", container.password)

  val fos = new FileOutputStream(destination)

  prop.store(fos, null)

  fos.close()

  Thread.currentThread().join()

}
