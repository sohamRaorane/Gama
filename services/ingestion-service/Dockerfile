# Ingestion Service image.
#
# Build context MUST be the repository root (see docker-compose.yml), because
# ingestion-service depends on the sibling module services/mesh-crypto
# (HybridCryptoService). The Dockerfile therefore builds that module into the
# image's local Maven repository before packaging the service itself.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# --- mesh-crypto module (dependency of ingestion-service) ---
COPY services/mesh-crypto/pom.xml services/mesh-crypto/pom.xml
RUN mvn -B -f services/mesh-crypto/pom.xml dependency:go-offline
COPY services/mesh-crypto/src services/mesh-crypto/src
RUN mvn -B -f services/mesh-crypto/pom.xml install -DskipTests

# --- ingestion-service module ---
COPY services/ingestion-service/pom.xml services/ingestion-service/pom.xml
RUN mvn -B -f services/ingestion-service/pom.xml dependency:go-offline
COPY services/ingestion-service/src services/ingestion-service/src
RUN mvn -B -f services/ingestion-service/pom.xml clean package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/services/ingestion-service/target/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
