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
1. In the dev Kafka event producer app have a viewer join the `foo` arena:
    ```
    foo/viewerjoin
    ```
1. Watch the dev Kafka event viewer

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

- Persist arenas
- Fan-out battle
