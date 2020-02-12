/*
 * Copyright 2018 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hello

import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import io.micronaut.runtime.Micronaut
import io.reactivex.Single

fun main() {
    Micronaut.run(WebApp::class.java)
}

data class Self(val href: String)

data class Links(val self: Self)

data class PlayerState(val x: Int, val y: Int, val direction: String, val wasHit: Boolean, val score: Int)

data class Arena(val dims: List<Int>, val state: Map<String, PlayerState>)

data class ArenaUpdate(val _links: Links, val arena: Arena)

@Controller
class WebApp {

    @Post(uris = ["/", "/{+path}"])
    fun index(@Body maybeArenaUpdate: Single<ArenaUpdate>): Single<String> {
        return maybeArenaUpdate.map { arenaUpdate ->
            println(arenaUpdate)
            listOf("F", "R", "L", "T").random()
        }
    }

}
