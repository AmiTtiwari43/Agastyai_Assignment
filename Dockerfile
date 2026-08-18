# Stage 1: Build the application using Maven and JDK 21
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache maven dependencies first
COPY pom.xml .
RUN mvn dependency:go-offline -B || true

# Copy source code and build production jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# Stage 2: Minimal, secure runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Run as non-root user
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-XX:+UseG1GC", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
