# Build and run the Beacon API.
#
# Built from the repository root rather than api/, because the image needs the
# clipped OSM extract in data/osm/ alongside the source.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Wrapper and build files first, so dependency resolution is cached separately
# from source changes.
COPY api/gradlew api/gradlew
COPY api/gradle api/gradle
COPY api/build.gradle api/settings.gradle api/
RUN cd api && chmod +x gradlew && ./gradlew dependencies --no-daemon -q || true
COPY api/src api/src
RUN cd api && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre AS runtime

# The graph is memory-hungry to import; see fly.toml for the machine size.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
ENV BEACON_OSM_PATH=/opt/beacon/osm/nyc.osm.pbf
ENV BEACON_GRAPH_PATH=/data/graph-cache

RUN useradd --system --create-home --uid 10001 beacon
WORKDIR /opt/beacon

COPY --from=build /src/api/build/libs/*.jar /opt/beacon/api.jar
# The five-borough extract, roughly 90 MB. Baked in so the container can
# rebuild its graph without reaching out to Geofabrik at boot.
COPY data/osm/nyc.osm.pbf /opt/beacon/osm/nyc.osm.pbf

# The graph lives on a mounted volume: importing 1.1M edges takes about half a
# minute, and a persisted cache turns later boots into a load instead.
RUN mkdir -p /data && chown -R beacon:beacon /data /opt/beacon
USER beacon

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/opt/beacon/api.jar"]
