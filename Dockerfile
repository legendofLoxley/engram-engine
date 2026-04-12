# ─── Stage 1: Build ───────────────────────────────────────────────────────────
# eclipse-temurin:25 matches jvmToolchain(25) in build.gradle.kts.
# The Kotlin compiler targets JVM 21 bytecode, so the runtime stage can use JRE 21.
FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app

# Copy dependency manifests first for layer caching
COPY build.gradle.kts settings.gradle.kts gradle.properties gradlew ./
COPY gradle/ gradle/

# Resolve dependencies (cached unless build files change)
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon --quiet || true

# Copy source and build fat jar
COPY src/ src/
RUN ./gradlew shadowJar --no-daemon

# ─── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /app/build/libs/engram-engine.jar .

EXPOSE 8080

ENV PORT=8080

# ArcadeDB and Ktor JVM requirements, plus container-aware memory limits
ENTRYPOINT ["java", \
  "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
  "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED", \
  "--add-opens", "java.base/java.nio.channels.spi=ALL-UNNAMED", \
  "-Dpolyglot.engine.WarnInterpreterOnly=false", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "engram-engine.jar"]
