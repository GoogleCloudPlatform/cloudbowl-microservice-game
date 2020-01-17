# --- !Ups
CREATE EXTENSION citext;

CREATE TYPE direction AS ENUM ('N', 'W', 'S', 'E');

CREATE TABLE arena (
    path citext PRIMARY KEY
);

CREATE TABLE player (
    service citext PRIMARY KEY,
    name text NOT NULL,
    pic citext NOT NULL
);

CREATE TABLE arena_player (
    arena citext REFERENCES arena(path),
    player citext REFERENCES player(service),
    x int NOT NULL,
    y int NOT NULL,
    direction direction NOT NULL
);

CREATE TABLE hit (
    giver citext REFERENCES player(service),
    taker citext REFERENCES player(service),
    timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

# --- !Downs

DROP TABLE hit;
DROP TABLE arena_player;
DROP TABLE player;
DROP TABLE arena;
DROP EXTENSION citext;
