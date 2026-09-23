#!/usr/bin/env bash
# Regenerates the Delta Lake fixtures under example/delta/ from the scripts beside this file.
#
#   docs/fixtures/delta/run.sh dplain ddv      # one or more, by script name without .sql
#
# Each script runs in its own spark-sql on apache/spark:3.5.4-java17 with the released
# delta-spark_2.12-3.2.1 and delta-storage-3.2.1 jars from lakelab's cache, against a fresh
# warehouse at /wh, so a table named t lands at /wh/t and is copied to example/delta/t.
# Everything the script prints — the rows its SELECTs return, DESCRIBE HISTORY, table_changes —
# is kept as <name>.out beside the script: that output is the oracle the tests read, written by
# the engine and not by this app.
#
# Traps, each met once:
#   - --entrypoint bash, or the image's entrypoint swallows the arguments.
#   - --master local[1], or a small INSERT writes one file per row and a DELETE matching every row
#     of a file removes the file outright instead of writing what the fixture is for.
#   - --user 0: the image's own uid 185 cannot write the mounted warehouse, and the caller's uid
#     has no passwd entry in the image, which Hadoop's login refuses ("invalid null input: name").
#     Docker Desktop maps the bind mount's files to the caller whatever uid wrote them.
set -euo pipefail
cd "$(dirname "$0")/../../.."
CACHE=~/code/spark-kit/lakelab/.cache
for name in "$@"; do
  wh=$(mktemp -d)
  script=docs/fixtures/delta/$name.sql
  docker run --rm --entrypoint bash --user 0 \
    -v "$wh:/wh" \
    -v "$CACHE/delta-spark_2.12-3.2.1.jar:/opt/delta-spark.jar:ro" \
    -v "$CACHE/delta-storage-3.2.1.jar:/opt/delta-storage.jar:ro" \
    -v "$PWD/$script:/tmp/script.sql:ro" \
    apache/spark:3.5.4-java17 \
    -c "cd /tmp && /opt/spark/bin/spark-sql --master 'local[1]' \
          --jars /opt/delta-spark.jar,/opt/delta-storage.jar \
          --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
          --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog \
          --conf spark.sql.warehouse.dir=/wh \
          --conf spark.databricks.delta.retentionDurationCheck.enabled=false \
          --conf spark.ui.enabled=false \
          --conf spark.sql.session.timeZone=UTC \
          -f /tmp/script.sql 2>/tmp/err.log || { tail -40 /tmp/err.log >&2; exit 1; }" \
    > "docs/fixtures/delta/$name.out"
  for t in $(ls "$wh"); do
    rm -rf "example/delta/$t" && mkdir -p example/delta && cp -R "$wh/$t" "example/delta/$t"
  done
  rm -rf "$wh"
done
