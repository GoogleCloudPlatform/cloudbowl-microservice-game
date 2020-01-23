Cloud Pit
---------

Run Locally:
1. Make sure you have docker installed and running
1. Start Kafka
    ```
    ./sbt "runMain cloudpit.dev.KafkaApp"
    ```
1. Start the Battle
    ```
    ./sbt "runMain cloudpit.Battle"
    ```
1. Start the dev Kafka event viewer
    ```
    ./sbt "runMain cloudpit.dev.KafkaConsumerApp"
    ```
1. Start the sample service
    ```
    cd samples/scala-play
    ./sbt run
    ```
1. Start the dev Kafka event producer
    ```
    ./sbt "runMain cloudpit.dev.KafkaProducerApp"
    ```
1. In the dev Kafka event producer app enter these commands to start a battle:
    ```
    foo/playerjoin/asdf
    foo/playerjoin/foo
    foo/viewerjoin
    ```
1. Watch the dev Kafka event viewer

Dev Kafka Event Producer Command Structure:
```
ARENA/EVENT <- where EVENT = viewerjoin | viewerleave
ARENA/EVENT/PLAYER <- where EVENT = playerjoin | playerleave
```

# TODO

- Request timing
- Persist players
- Persist viewers
- Persist arenas
- Fan-out battle
