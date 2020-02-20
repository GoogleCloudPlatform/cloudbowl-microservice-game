package com.google.cloudbowl;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import java.util.Random;


@Path("/{s:.*}")
public class App {

    static class Self {
        public String href;
    }

    static class Links {
        public Self self;
    }

    static class PlayerState {
        public Integer x;
        public Integer y;
        public String direction;
        public Boolean wasHit;
        public Integer score;
    }

    static class Arena {
        public List<Integer> dims;
        public Map<String, PlayerState> state;
    }

    static class ArenaUpdate {
        public Links _links;
        public Arena arena;
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    @Consumes(MediaType.APPLICATION_JSON)
    public String index(ArenaUpdate arenaUpdate) {
        System.out.println(arenaUpdate);
        String[] commands = new String[]{"F", "R", "L", "T"};
        int i = new Random().nextInt(4);
        return commands[i];
    }

}
