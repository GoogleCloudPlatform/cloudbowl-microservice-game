CloudBowl - Sample - Java Quarkus
---------------------------------

To make changes, edit the `src/main/java/com/google/cloudbowl/App.java` file.

Run Locally:
```
./mvnw quarkus:dev
```

[![Run on Google Cloud](https://deploy.cloud.run/button.svg)](https://deploy.cloud.run)

Containerize & Run Locally:
```
export PROJECT_ID=YOUR_GCP_PROJECT_ID
pack build --builder=gcr.io/buildpacks/builder gcr.io/$PROJECT_ID/cloudbowl-samples-java-quarkus
docker run -it -ePORT=8080 -p8080:8080 gcr.io/$PROJECT_ID/cloudbowl-samples-java-quarkus
```

