# Two stages: Gradle builds the jar, a JRE-only image runs it. The build stage's
# JDK, Gradle and dependency cache never reach the image that is deployed.
FROM eclipse-temurin:25-jdk AS build

WORKDIR /src

# The wrapper and build scripts first, then the dependencies in their own
# layer, so a source edit does not re-download Gradle or any library.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null

COPY src src
# The suite runs against an embedded Postgres and belongs in CI or on a
# developer's machine (`./gradlew test`), not in every image build.
RUN ./gradlew --no-daemon --quiet bootJar


FROM eclipse-temurin:25-jre

# Non-root, as UID 1000 -- the Ubuntu base ships a user of its own on that UID,
# which is replaced rather than reused so the name means something here.
RUN userdel --remove ubuntu 2>/dev/null || true \
    && useradd --uid 1000 --create-home --shell /bin/bash app

WORKDIR /app
COPY --from=build /src/build/libs/ziantsabit-be-*.jar app.jar
USER app

EXPOSE 8000

# Flyway runs the migrations as part of startup, so there is no entrypoint
# script any more: a fresh container creates its tables before it serves.
# The heap is sized from the container's memory limit rather than the host's.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
