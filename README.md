Cloud Bowl
----------

A game where microservices battle each other in a giant real-time bowl.


Run Locally:
1. Make sure you have docker installed and running
1. Start Kafka
    ```
    ./sbt "runMain apps.dev.KafkaApp"
    ```
1. Start the Battle
    ```
    ./sbt "runMain cloudpit.Battle"
    ```
1. Start the apps.dev Kafka event viewer
    ```
    ./sbt "runMain apps.dev.KafkaConsumerApp"
    ```
1. Start the sample service
    ```
    cd samples/scala-play
    ./sbt run
    ```
1. Start the apps.dev Kafka event producer
    ```
    ./sbt "runMain apps.dev.KafkaProducerApp"
    ```
1. In the apps.dev Kafka event producer app have a viewer join the `foo` arena:
    ```
    foo/viewerjoin
    ```
1. Watch the apps.dev Kafka event viewer

Dev Kafka Event Producer Command Structure:
```
ARENA/viewerjoin
ARENA/playersrefresh
```

1. Start the Viewer web app
```
./sbt "run 8080"
```

# TODO

- Battle hot looping
- Persist arenas
- Fan-out battle
