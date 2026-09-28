#!/usr/bin/env bash
# A local HDFS to test WebHDFS reading against — the MinIO lab's twin.
#
#   docs/fixtures/hdfs-lab.sh up      start a one-node HDFS and upload the checked-in fixtures
#   docs/fixtures/hdfs-lab.sh down    stop and remove it
#   docs/fixtures/hdfs-lab.sh status  say whether it is up and what is in it
#
# What it is for: `WebHdfsTableTest` opens the SAME tables twice — from `example/` on disk and from
# `webhdfs://127.0.0.1:9870/warehouse/db/...` — and requires the two to agree, the way
# `RemoteTableTest` does for S3 against `minio-lab.sh`.
#
# One container runs the namenode and the datanode. Three settings are the ones worth knowing:
#
# - `dfs.datanode.hostname=localhost`. A WebHDFS OPEN is answered by the namenode with a 307 to a
#   datanode, named by the hostname the datanode registered with; a container's own hostname does
#   not resolve on the host, so the datanode registers as `localhost` and its HTTP port is
#   published on the host's loopback. A real cluster's datanodes resolve and need none of this.
# - `fs.defaultFS=hdfs://icelens-hdfs:8020`, the container's own hostname, so the datanode reaches
#   the namenode at the container's address rather than 127.0.0.1 — which is the address a client
#   on the same Docker network is handed for the datanode.
# - `dfs.permissions.enabled=true` (the default, stated): `/warehouse/private` is 700 and owned by
#   `hadoop`, so a read as any other user is an AccessControlException — the refusal a test needs.
#
# Loopback only: WebHDFS under simple authentication believes whatever `user.name` it is sent, so
# this must never face a network. It holds nothing but copies of files already in this repository.
set -euo pipefail

CONTAINER=icelens-hdfs
HTTP=9870
DATANODE_HTTP=9864
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

case "${1:-up}" in
  up)
    if ! docker info >/dev/null 2>&1; then
      echo "Docker is not running. Start it and try again." >&2; exit 1
    fi
    if [ -z "$(docker ps -q -f name="^${CONTAINER}$")" ]; then
      docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
      docker run -d --name "$CONTAINER" --hostname "$CONTAINER" \
        -p 127.0.0.1:${HTTP}:9870 -p 127.0.0.1:${DATANODE_HTTP}:9864 \
        -e "CORE-SITE.XML_fs.defaultFS=hdfs://${CONTAINER}:8020" \
        -e "HDFS-SITE.XML_dfs.replication=1" \
        -e "HDFS-SITE.XML_dfs.permissions.enabled=true" \
        -e "HDFS-SITE.XML_dfs.datanode.hostname=localhost" \
        -e "HDFS-SITE.XML_dfs.namenode.rpc-bind-host=0.0.0.0" \
        -e "HDFS-SITE.XML_dfs.namenode.http-bind-host=0.0.0.0" \
        -e "HDFS-SITE.XML_dfs.namenode.name.dir=/tmp/hdfs/name" \
        -e "HDFS-SITE.XML_dfs.datanode.data.dir=/tmp/hdfs/data" \
        apache/hadoop:3.4.1 \
        bash -c 'hdfs namenode -format -force -nonInteractive >/tmp/format.log 2>&1 &&
                 (hdfs namenode >/tmp/namenode.log 2>&1 &) && exec hdfs datanode' >/dev/null
      printf 'waiting for HDFS'
      for _ in $(seq 1 90); do
        # Out of safe mode with a datanode live is what a write needs. Captured first rather than
        # piped: under pipefail, grep -q closing the pipe early fails the docker exec with SIGPIPE.
        mode="$(docker exec "$CONTAINER" hdfs dfsadmin -safemode get 2>/dev/null || true)"
        report="$(docker exec "$CONTAINER" hdfs dfsadmin -report 2>/dev/null || true)"
        if [[ "$mode" == *OFF* && "$report" == *"Live datanodes (1)"* ]]; then break; fi
        printf .; sleep 1
      done
      echo
    fi

    # Seed through the hdfs client inside the container, so this needs no Hadoop on the host. The
    # staging directory is cleared as root: docker cp writes as the host's owner, which the image's
    # own user cannot remove on a second `up`.
    docker exec -u root "$CONTAINER" rm -rf /tmp/seed
    docker exec -u root "$CONTAINER" mkdir -p /tmp/seed
    docker cp "$REPO/example/iceberg/default/mor" "$CONTAINER:/tmp/seed/mor"
    docker cp "$REPO/example/paimon/db.db/dv" "$CONTAINER:/tmp/seed/dv"
    docker cp "$REPO/example/delta/dplain" "$CONTAINER:/tmp/seed/dplain"
    docker exec "$CONTAINER" hdfs dfs -mkdir -p /warehouse/db /warehouse/private
    for table in mor dv dplain; do
      docker exec "$CONTAINER" hdfs dfs -rm -r -f -skipTrash "/warehouse/db/$table" >/dev/null
      docker exec "$CONTAINER" hdfs dfs -put "/tmp/seed/$table" "/warehouse/db/$table"
    done
    docker exec "$CONTAINER" hdfs dfs -rm -r -f -skipTrash /warehouse/private/mor >/dev/null
    docker exec "$CONTAINER" hdfs dfs -put /tmp/seed/mor /warehouse/private/mor
    docker exec "$CONTAINER" hdfs dfs -chmod 700 /warehouse/private
    # hdfsw was written into this lab by Spark (docs/fixtures/hdfsw.sql) and records its
    # hdfs:// paths, so it goes back to the path it was written at.
    docker cp "$REPO/example/iceberg/default/hdfsw" "$CONTAINER:/tmp/seed/hdfsw"
    docker exec "$CONTAINER" hdfs dfs -mkdir -p /warehouse/spark/default
    docker exec "$CONTAINER" hdfs dfs -rm -r -f -skipTrash /warehouse/spark/default/hdfsw >/dev/null
    docker exec "$CONTAINER" hdfs dfs -put /tmp/seed/hdfsw /warehouse/spark/default/hdfsw

    echo "HDFS is up: WebHDFS on http://127.0.0.1:${HTTP}, the datanode on ${DATANODE_HTTP}, files owned by 'hadoop'"
    echo "  webhdfs://127.0.0.1:${HTTP}/warehouse/db/mor     — the Iceberg 'mor' fixture"
    echo "  webhdfs://127.0.0.1:${HTTP}/warehouse/db/dv      — the Paimon 'dv' fixture"
    echo "  webhdfs://127.0.0.1:${HTTP}/warehouse/db/dplain  — the Delta 'dplain' fixture"
    echo "  webhdfs://127.0.0.1:${HTTP}/warehouse/private    — 700, readable as 'hadoop' only"
    echo "  webhdfs://127.0.0.1:${HTTP}/warehouse/spark/default/hdfsw — written here by Spark, hdfs:// paths recorded"
    echo "Run the tests with:  ./gradlew :core:test --tests '*WebHdfs*'"
    ;;
  down) docker rm -f "$CONTAINER" >/dev/null 2>&1 && echo "removed $CONTAINER" || echo "not running" ;;
  status)
    if [ -n "$(docker ps -q -f name="^${CONTAINER}$")" ]; then
      echo "up:"; docker exec "$CONTAINER" hdfs dfs -ls -R /warehouse 2>/dev/null | head -8
    else
      echo "down"
    fi ;;
  *) echo "usage: $0 {up|down|status}" >&2; exit 2 ;;
esac
