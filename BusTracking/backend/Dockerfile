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

# Run as an unprivileged user. ./data holds the learned-routes file (RouteLearner),
# so it must be writable by that user.
RUN groupadd --system app && useradd --system --gid app --home-dir /app app \
    && mkdir -p /app/data && chown -R app:app /app
COPY --from=build --chown=app:app /app/target/*.jar app.jar
USER app

ENV SPRING_PROFILES_ACTIVE=prod
# Size the heap from the container limit (Render instances are small), and exit on
# OutOfMemoryError so the platform restarts a clean process instead of a broken one.
# SerialGC keeps the whole max heap committed, so the heap share must leave room for
# metaspace, code cache and native memory: at 512 MB, 40% (~205 MB) fits the ~95 MB
# GTFS index plus the app, while 55% got the instance OOM-killed by the platform.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40 -XX:MaxMetaspaceSize=150m -XX:ReservedCodeCacheSize=40m -XX:TieredStopAtLevel=1 -Xss512k -XX:MaxDirectMemorySize=32m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

# Render injects PORT; application-prod.properties binds server.port to it.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
