# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Use the official Maven image bundled with JDK 21.
# Build context is the project root so we can access shared-protos/ for the
# protobuf-maven-plugin (pom.xml points to ${basedir}/../shared-protos).
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /app

# Copy the proto contract first — it's needed at compile time
COPY shared-protos/ ./shared-protos/

# Copy the POM separately so Maven dependency resolution is cached
COPY control-plane/pom.xml ./control-plane/pom.xml
RUN cd control-plane && mvn dependency:go-offline -B -q

# Copy source and compile
COPY control-plane/src/ ./control-plane/src/
RUN cd control-plane && mvn clean package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Minimal JRE 21 image — no compiler, no Maven, no unnecessary tools.
FROM eclipse-temurin:21-jre-alpine AS final

WORKDIR /app

# Run as a non-privileged user
ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser
USER appuser

COPY --from=build /app/control-plane/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
