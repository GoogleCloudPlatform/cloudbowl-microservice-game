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

import java.net.URL

import models.{Arena, Player}

import scala.concurrent.Future
import scala.util.Random

object Services {

  trait PlayerService {
    def fetch(arena: Arena.Path): Future[(Arena.Name, Set[Player])]
  }

  class DevPlayerService extends PlayerService {

    def fetch(arena: Arena.Path): Future[(Arena.Name, Set[Player])] = {
      Future.successful {
        if (arena == "empty") {
          ("Empty", Set.empty)
        }
        else {
          val players = Set.fill(Random.nextInt(10) + 1) {
            val name = Random.alphanumeric.take(6).mkString
            val service = s"http://localhost:9000/$name"
            val img = new URL(s"https://picsum.photos/seed/$name/200")
            Player(service, name, img)
          }

          (arena, players)
        }
      }
    }

  }

}
