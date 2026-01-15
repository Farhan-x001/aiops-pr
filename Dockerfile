# Minimal Dockerfile: expect the project to produce a single executable Spring Boot jar
# Design: keep image small and simple for demo purposes

FROM eclipse-temurin:17-jdk-alpine

ARG JAR_FILE=target/*.jar
COPY ${JAR_FILE} /app/app.jar

EXPOSE 8080

ENTRYPOINT ["java","-jar","/app/app.jar"]
