#!/bin/sh
set -eu

# Railway mounts volumes as root. Its RAILWAY_RUN_UID=0 override lets this
# entrypoint repair ownership before the JVM returns to the image's user.
if [ "$(id -u)" = "0" ]; then
    install -d -o beacon -g beacon /data /data/graph-cache
    chown -R beacon:beacon /data
    exec runuser -u beacon --preserve-environment -- \
        java -jar /opt/beacon/api.jar "$@"
fi

exec java -jar /opt/beacon/api.jar "$@"
