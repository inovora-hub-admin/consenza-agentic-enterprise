# Stage 1: Build Jar
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal Execution Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S agentic && adduser -S agentic -G agentic
USER agentic:agentic

COPY --from=builder /app/target/*.jar app.jar
EXPOSE 5443
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]