Cloud Bowl Sample - Kotlin Micronaut
------------------------------------

To make changes, edit the `src/main/kotlin/hello/WebApp.kt` file.

Run Locally:
```
./gradlew -t run
```

Visit: [http://localhost:8080](http://localhost:8080)

Deploy:

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Containerize & Run Locally:
```
export PROJECT_ID=YOUR_GCP_PROJECT_ID
pack build --builder=gcr.io/buildpacks/builder gcr.io/$PROJECT_ID/cloudbowl-samples-kotlin-micronaut
docker run -it -ePORT=8080 -p8080:8080 gcr.io/$PROJECT_ID/cloudbowl-samples-kotlin-micronaut
```

