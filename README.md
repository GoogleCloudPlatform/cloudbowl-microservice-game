Cloud Pit
---------


Run Locally:
1. Make sure you have docker installed and running
1. Start the db
    ```
    ./sbt "runMain cloudpit.dev.PostgresApp"
    ```
1. Start the app
    ```
    ./sbt ~run
    ```

```
./sbt "runMain cloudpit.dev.KafkaApp"

./sbt "runMain cloudpit.dev.KafkaConsumerApp"

```
