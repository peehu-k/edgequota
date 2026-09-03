# Multi-stage build: compile with Maven+JDK, ship a minimal JRE runtime image.
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
# Warm the dependency cache (just JUnit, but keeps rebuilds fast when only source changes).
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=build /build/target/edgequota.jar /app/edgequota.jar

# HTTP gateway port and UDP gossip port.
EXPOSE 8080/tcp
EXPOSE 7946/udp

ENTRYPOINT ["java", "-jar", "/app/edgequota.jar"]
