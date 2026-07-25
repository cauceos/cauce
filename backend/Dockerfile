# Builds the cauce-api executable jar and packages it on a JRE-only image.
#
# Used by the quickstart compose profile (docker compose --profile quickstart up),
# so the only tool a visitor needs is Docker — no local JDK. Regular development
# keeps using `./gradlew :cauce-api:bootRun` against the infra-only compose stack.
#
# Tests are NOT run here: the integration tests need Testcontainers (Docker inside
# the build), so the image build compiles and packages only. CI runs the tests.

# --- Build stage -------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Warm the Gradle distribution in its own layer so Dockerfile/source edits do not
# re-download it. The wrapper pins the Gradle version.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
RUN chmod +x gradlew && ./gradlew --version --no-daemon

# Full source. Dependency downloads happen inside this step; a source change
# re-runs it (acceptable for a one-shot quickstart build).
COPY . .
RUN chmod +x gradlew && ./gradlew :cauce-api:bootJar --no-daemon

# The libs dir may also contain the -plain.jar; pick the executable boot jar.
RUN find cauce-api/build/libs -name '*.jar' ! -name '*-plain.jar' \
        -exec cp {} /workspace/app.jar \;

# --- Runtime stage -----------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S cauce && adduser -S cauce -G cauce
USER cauce
WORKDIR /app

COPY --from=build /workspace/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
