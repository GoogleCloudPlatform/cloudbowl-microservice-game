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
    TODO: player backends
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


Testing:

For GitHub Player backend:

1. Create a GitHub App with perm *Contents - Read-Only*
1. Generate a private key
1. `export GITHUB_APP_PRIVATE_KEY=$(cat ~/somewhere/your-integration.2017-02-07.private-key.pem)`
1. `export GITHUB_APP_ID=YOUR_NUMERIC_GITHUB_APP_ID`
1. `export GITHUB_ORGREPO=cloudbowl/arenas`
1. Run the tests:
    ```
    ./sbt test
    ```

For Google Sheets Player backend:
1. TODO


Prod:
1. TODO


# TODO

- Arena Peach or Something else
- Battle hot looping
- Persist arenas
- Fan-out battle
- Response times in player list
- Local dev instructions
