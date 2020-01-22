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

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.scalatestplus.play.PlaySpec
import org.scalatestplus.play.guice.GuiceOneAppPerTest
import play.api.Application
import play.api.db.Database
import play.api.db.evolutions.Evolutions
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers._

class DBSpec extends PlaySpec with GuiceOneAppPerTest with ForAllTestContainer {

  override val container = PostgreSQLContainer()

  implicit override def fakeApplication(): Application = {
    GuiceApplicationBuilder().configure(
      "db.default.url" -> container.jdbcUrl,
      "db.default.username" -> container.username,
      "db.default.password" -> container.password,
    ).build()
  }

  private def dao = app.injector.instanceOf[DAO]
  private def database = app.injector.instanceOf[Database]

  /*
  "arenas" must {
    "by empty by default" in Evolutions.withEvolutions(database) {
      await(dao.arenas()) mustBe empty
    }
  }
   */

}
