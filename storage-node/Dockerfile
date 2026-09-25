# Build from the repository root:  docker build -f storage-node/Dockerfile -t vault-storage-node .
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY vault-proto/ vault-proto/
COPY storage-node/ storage-node/
COPY vault-api/pom.xml vault-api/pom.xml
RUN mvn -q -B -pl storage-node -am package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/storage-node/target/storage-node-*-shaded.jar /app/storage-node.jar
ENV NODE_PORT=9090 \
    DATA_DIR=/data
VOLUME /data
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/app/storage-node.jar"]
