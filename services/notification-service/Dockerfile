# syntax=docker/dockerfile:1.6
# Expects `mvn -DskipTests package` to have been run from the repo root before `docker compose build`.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY services/notification-service/target/ledgerpay-notification-service.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
