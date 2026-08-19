# Build and run the Beacon API.
#
# Built from the repository root rather than api/, because the image needs the
# clipped OSM extract in data/osm/ alongside the source.

# Resolve the clipped OSM extract.
#
# data/ is gitignored, so a build from a git checkout has no extract in its
# context. This stage takes it from the context when present (local builds) and
# otherwise downloads it from OSM_EXTRACT_URL, which is how a host that builds
# from the repository gets it. data/osm carries a .gitkeep so the directory is
# always in the context: without it, COPY fails on a missing path before this
# stage can report anything useful.
FROM alpine:3.20 AS osm
ARG OSM_EXTRACT_URL=""
WORKDIR /osm
COPY data/osm/ ./local/
RUN set -eu;     if [ -f ./local/nyc.osm.pbf ]; then       echo "Using the extract from the build context";       mv ./local/nyc.osm.pbf ./nyc.osm.pbf;     elif [ -n "$OSM_EXTRACT_URL" ]; then       echo "Downloading the extract from $OSM_EXTRACT_URL";       apk add --no-cache curl >/dev/null;       curl -fsSL "$OSM_EXTRACT_URL" -o ./nyc.osm.pbf;     else       echo "ERROR: no OSM extract available." >&2;       echo "Provide data/osm/nyc.osm.pbf in the build context, or pass" >&2;       echo "--build-arg OSM_EXTRACT_URL=<url to nyc.osm.pbf>." >&2;       exit 1;     fi;     test -s ./nyc.osm.pbf

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
COPY --from=osm /osm/nyc.osm.pbf /opt/beacon/osm/nyc.osm.pbf

# The graph lives on a mounted volume: importing 1.1M edges takes about half a
# minute, and a persisted cache turns later boots into a load instead.
RUN mkdir -p /data && chown -R beacon:beacon /data /opt/beacon
USER beacon

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=5 \
  CMD wget -qO- "http://127.0.0.1:${PORT:-8080}/actuator/health" || exit 1

ENTRYPOINT ["java", "-jar", "/opt/beacon/api.jar"]
