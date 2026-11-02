FROM docker:29.4.0-cli@sha256:2efe7c8e806b35050def6ba1ba0ddad67b521a0a6d37f4910b3d88f11b495d03 AS docker-cli
FROM maven:3.9.9-eclipse-temurin-17@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:17-jre-jammy@sha256:8993f1aed8b25fcea7a7047a7949c1866fa558fc6830d938c22c4f13b26be9d7
RUN apt-get update && apt-get install -y --no-install-recommends python3 && rm -rf /var/lib/apt/lists/*
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
RUN useradd --system --uid 10001 --create-home banker
WORKDIR /app
COPY --from=build /build/target/pocket-banker-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir /app/local-data && chown -R banker:banker /app
USER banker
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8000
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
