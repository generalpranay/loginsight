# syntax=docker/dockerfile:1.6

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY common/pom.xml         common/pom.xml
COPY telemetry/pom.xml      telemetry/pom.xml
COPY anomaly/pom.xml        anomaly/pom.xml
COPY storage/pom.xml        storage/pom.xml
COPY ingestion/pom.xml      ingestion/pom.xml
COPY api/pom.xml            api/pom.xml
RUN mvn -B -ntp -pl api -am dependency:go-offline || true

COPY common/src      common/src
COPY telemetry/src   telemetry/src
COPY anomaly/src     anomaly/src
COPY storage/src     storage/src
COPY ingestion/src   ingestion/src
COPY api/src         api/src
RUN mvn -B -ntp -pl api -am clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S -G app -u 1000 app
WORKDIR /app
COPY --from=build /workspace/api/target/api-1.0.0-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080 9464
ENTRYPOINT ["java","-XX:+UseZGC","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
