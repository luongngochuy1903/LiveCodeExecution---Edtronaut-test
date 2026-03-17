#!/bin/sh
# docker-entrypoint-worker.sh
#
# Runs as root to:
#   1. Fix docker socket GID mismatch between host and container
#   2. Pre-pull sandbox images
#   3. Drop to workeruser via gosu and start Spring Boot
set -e

DOCKER_SOCKET=/var/run/docker.sock

# ── Fix docker socket permissions ──────────────────────────────────────────
if [ -S "$DOCKER_SOCKET" ]; then
    DOCKER_GID=$(stat -c '%g' "$DOCKER_SOCKET")
    echo "[entrypoint] Docker socket found, GID=$DOCKER_GID"

    # Create group with that GID if it doesn't exist
    if ! getent group "$DOCKER_GID" > /dev/null 2>&1; then
        groupadd -g "$DOCKER_GID" dockerhost
        echo "[entrypoint] Created group dockerhost with GID=$DOCKER_GID"
    fi

    DOCKER_GROUP=$(getent group "$DOCKER_GID" | cut -d: -f1)
    usermod -aG "$DOCKER_GROUP" workeruser
    echo "[entrypoint] Added workeruser to group: $DOCKER_GROUP"
else
    echo "[entrypoint] WARNING: $DOCKER_SOCKET not found — sandbox executions will fail!"
    echo "[entrypoint] Make sure docker.sock is mounted in docker-compose.yml"
fi

# ── Pre-pull sandbox images ─────────────────────────────────────────────────
echo "[entrypoint] Pre-pulling sandbox images (this may take a moment on first run)..."
docker pull python:3.12-slim > /dev/null 2>&1 \
    && echo "[entrypoint] python:3.12-slim ready" \
    || echo "[entrypoint] WARNING: could not pull python:3.12-slim"

docker pull node:20-slim > /dev/null 2>&1 \
    && echo "[entrypoint] node:20-slim ready" \
    || echo "[entrypoint] WARNING: could not pull node:20-slim"

# ── Ensure temp dir is writable ─────────────────────────────────────────────
mkdir -p /tmp/ces-sandbox
chown workeruser:workergroup /tmp/ces-sandbox
chmod 755 /tmp/ces-sandbox

# ── Drop to workeruser and start the app ────────────────────────────────────
echo "[entrypoint] Starting worker as workeruser..."
exec gosu workeruser java \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=60.0 \
  -jar /app/app.jar \
  --server.port=8081 \
  --app.worker.enabled=true \
  --app.execution.temp-dir=/tmp/ces-sandbox \
  --springdoc.swagger-ui.enabled=false
