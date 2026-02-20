FROM gradle:8.12-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN gradle dependencies --no-daemon || true
COPY src ./src
RUN gradle buildFatJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S kidsync && adduser -S kidsync -G kidsync
COPY --from=build /app/build/libs/*-all.jar app.jar
RUN mkdir -p /app/data/blobs /app/data/snapshots && chown -R kidsync:kidsync /app
USER kidsync

ENV KIDSYNC_DB_PATH=/app/data/kidsync.db
ENV KIDSYNC_BLOB_PATH=/app/data/blobs
ENV KIDSYNC_SNAPSHOT_PATH=/app/data/snapshots
ENV KIDSYNC_HOST=0.0.0.0
ENV KIDSYNC_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
