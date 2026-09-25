# Build stage: compile and package the Spring Boot fat jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Resolve dependencies first so this layer is cached across source-only changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src

# GTFS.zip is tracked with Git LFS. If the build checkout only has the ~130-byte
# pointer file, GtfsImportService would fail on first boot - fail the build instead.
RUN test "$(stat -c %s src/main/resources/static/GTFS.zip)" -gt 1000000 \
    || (echo "GTFS.zip is a Git LFS pointer, not the real archive. Fetch LFS content or commit the zip directly." && exit 1)

RUN mvn -B -q package -DskipTests

# Runtime stage
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV SPRING_PROFILES_ACTIVE=prod
# Size the heap from the container limit (Render instances are small).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70"

# Render injects PORT; application-prod.properties binds server.port to it.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
