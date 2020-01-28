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

import scala.concurrent.Future
import scala.util.Random

object Services {

  trait PlayerService {
    def fetch(arena: Arena.Path): Future[Set[Player]]
  }

  class DevPlayerService extends PlayerService {

    def fetch(arena: Arena.Path): Future[Set[Player]] = {
      Future.successful {
        if (arena == "empty")
          Set.empty
        else
          Set.fill(Random.nextInt(10) + 1) {
            val name = Random.alphanumeric.take(6).mkString
            val service = new URL(s"http://localhost:9000/$name")
            Player(service.toString, name, service)
          }
      }
    }

  }

}
