package hello

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Mono
import java.util.*

@SpringBootApplication
class KotlinApplication {

	@Bean
	fun routes() = router {
		GET("/**") {
			val response: Mono<String> = Mono.fromCallable {
				val commands = arrayOf("F", "R", "L", "T")
				val i = Random().nextInt(4)
				commands[i]
			}
			ServerResponse.ok().body(response)
		}
	}
}

fun main(args: Array<String>) {
	runApplication<KotlinApplication>(*args)
}

data class ArenaUpdate(val _links: Links?, val arena: Arena)
data class PlayerState(val x: Int?, val y: Int?, val direction: String?, val score: Int?, val wasHit: Boolean?)
data class Links(val self: Self? = null)
data class Self(val href: String?)
data class Arena(val dims: List<Int>?, val state: Map<String, PlayerState>?)