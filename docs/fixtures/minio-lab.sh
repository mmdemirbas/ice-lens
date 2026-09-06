#!/usr/bin/env bash
# A local S3 to test remote reading against.
#
#   docs/fixtures/minio-lab.sh up      start MinIO and upload the checked-in fixtures
#   docs/fixtures/minio-lab.sh down    stop and remove it
#   docs/fixtures/minio-lab.sh status  say whether it is up and what is in it
#
# What it is for: `RemoteTableTest` opens the SAME table twice — once from
# `example/iceberg/default/mor` on disk and once from `s3://warehouse/db/mor` — and requires the
# two models to agree. That is the only check that says the object-storage path reads a real table
# correctly rather than merely returning bytes, and it needs an S3 to run against.
#
# The credentials below are MinIO's documented defaults and are deliberately hard-coded: this
# container is bound to 127.0.0.1 only, holds nothing but copies of files already in this
# repository, and is thrown away by `down`. Do not point this script at anything else.
set -euo pipefail

CONTAINER=icelens-minio
PORT=9000
CONSOLE=9001
USER=minioadmin
PASS=minioadmin
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

case "${1:-up}" in
  up)
    if ! docker info >/dev/null 2>&1; then
      echo "Docker is not running. Start it and try again." >&2; exit 1
    fi
    if [ -z "$(docker ps -q -f name="^${CONTAINER}$")" ]; then
      docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
      # Loopback only. This has no authentication worth the name and must not face a network.
      docker run -d --name "$CONTAINER" \
        -p 127.0.0.1:${PORT}:9000 -p 127.0.0.1:${CONSOLE}:9001 \
        -e MINIO_ROOT_USER="$USER" -e MINIO_ROOT_PASSWORD="$PASS" \
        minio/minio server /data --console-address ":9001" >/dev/null
      printf 'waiting for MinIO'
      for _ in $(seq 1 60); do
        if curl -fsS "http://127.0.0.1:${PORT}/minio/health/live" >/dev/null 2>&1; then break; fi
        printf .; sleep 1
      done
      echo
    fi

    # Seed through the mc client inside the container, so this needs no S3 client on the host.
    docker exec "$CONTAINER" mkdir -p /seed
    docker cp "$REPO/example/iceberg/default/mor" "$CONTAINER:/seed/mor"
    docker cp "$REPO/core/src/test/resources/paimon-fixtures/." "$CONTAINER:/seed/paimon"
    docker exec "$CONTAINER" mc alias set local "http://127.0.0.1:9000" "$USER" "$PASS" >/dev/null
    docker exec "$CONTAINER" mc mb --ignore-existing local/warehouse >/dev/null
    docker exec "$CONTAINER" mc mirror --overwrite --quiet /seed/mor    local/warehouse/db/mor    >/dev/null
    docker exec "$CONTAINER" mc mirror --overwrite --quiet /seed/paimon local/warehouse/db/paimon >/dev/null

    echo "MinIO is up on http://127.0.0.1:${PORT} (console ${CONSOLE}), key ${USER}/${PASS}"
    echo "  s3://warehouse/db/mor     — the Iceberg 'mor' fixture"
    echo "  s3://warehouse/db/paimon  — the Paimon fixtures"
    echo "Run the remote tests with:  ./gradlew :core:test --tests '*Remote*'"
    ;;
  down) docker rm -f "$CONTAINER" >/dev/null 2>&1 && echo "removed $CONTAINER" || echo "not running" ;;
  status)
    if [ -n "$(docker ps -q -f name="^${CONTAINER}$")" ]; then
      echo "up:"; docker exec "$CONTAINER" mc ls --recursive local/warehouse 2>/dev/null | head -5
    else
      echo "down"
    fi ;;
  *) echo "usage: $0 {up|down|status}" >&2; exit 2 ;;
esac
