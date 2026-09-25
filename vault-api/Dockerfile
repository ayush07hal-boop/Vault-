# Build from the repository root:  docker build -f vault-api/Dockerfile -t vault-api .
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY vault-proto/ vault-proto/
COPY storage-node/ storage-node/
COPY vault-api/ vault-api/
RUN mvn -q -B -pl vault-api -am package -DskipTests

FROM eclipse-temurin:21-jre
COPY --from=build /src/vault-api/target/vault-api-*.jar /app/vault-api.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/vault-api.jar"]
