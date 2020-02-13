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
    ./sbt "runMain apps.Battle"
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
    You can send commands like
    ```
    ARENA/viewerjoin
    ARENA/playersrefresh
    ```
1. Start the Viewer web app
    ```
    ./sbt run
    ```
    Check out the *foo* arena: [http://localhost:9000/foo](http://localhost:9000/foo)

# TODO

- Arena Peach or Something else
- Battle hot looping
- Persist arenas
- Fan-out battle
