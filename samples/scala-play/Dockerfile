FROM gcr.io/distroless/java:8-debug AS build

WORKDIR /src
COPY . .

RUN ["ln", "-s", "/busybox/env", "/usr/bin/env"]

RUN ["java", "-jar", "sbt-launch.jar", "stage"]


FROM gcr.io/distroless/java:8

COPY --from=build /src/target/universal/stage /app

ENTRYPOINT ["java", "-jar", "/app/lib/cloudpit-scala-play.cloudpit-scala-play-0.1.0-SNAPSHOT-launcher.jar"]
