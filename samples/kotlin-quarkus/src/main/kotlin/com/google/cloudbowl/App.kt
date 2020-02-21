package com.google.cloudbowl

import java.util.Random
import javax.ws.rs.Consumes
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.core.MediaType

@Path("/{s:.*}")
class App {
    data class Self(val href: String)

    data class Links(val self: Self)

    data class PlayerState(val x: Int, val y: Int, val direction: String, val wasHit: Boolean, val score: Int)

    data class Arena(val dims: List<Int>, val state: Map<String, PlayerState>)

    data class ArenaUpdate(val _links: Links, val arena: Arena)

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    fun index(arenaUpdate: ArenaUpdate?): String {
        println(arenaUpdate)
        val commands = arrayOf("F", "R", "L", "T")
        val i = Random().nextInt(4)
        return commands[i]
    }
}
