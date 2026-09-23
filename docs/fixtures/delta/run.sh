#!/usr/bin/env bash
# Regenerates the Delta Lake fixtures under example/delta/ from the scripts beside this file.
#
#   docs/fixtures/delta/run.sh dplain ddv      # one or more, by script name without .sql
#
# A line `--! <command>` in a script splits it: the SQL before it runs in one spark-sql, the
# command runs in the container's shell, and the SQL after it in a fresh spark-sql. That is how a
# table is copied on disk before a procedure runs on the original (`--! cp -R /wh/t /wh/tb`), and
# how a log file is aged past a retention (`--! touch -d 2020-01-01 …`) — the two things SQL
# cannot do and an oracle for a destructive procedure needs.
#
# Each script runs in its own spark-sql on apache/spark:3.5.4-java17 with the released
# delta-spark_2.12-3.2.1, delta-storage-3.2.1 and delta-iceberg_2.12-3.2.1 jars from lakelab's
# cache (the last is UniForm's converter, with Iceberg shaded inside it, and changes nothing for
# a table that does not enable it; Maven Central's SHA-1 c7172268…), against a fresh
# warehouse at /wh, so a table named t lands at /wh/t and is copied to example/delta/t.
# Everything the script prints — the rows its SELECTs return, DESCRIBE HISTORY, table_changes —
# is kept as <name>.out beside the script: that output is the oracle the tests read, written by
# the engine and not by this app.
#
# Traps, each met once:
#   - --entrypoint bash, or the image's entrypoint swallows the arguments.
#   - --master local[1], or a small INSERT writes one file per row and a DELETE matching every row
#     of a file removes the file outright instead of writing what the fixture is for.
#   - UniForm commits its Iceberg metadata through the Hive metastore under a metastore lock, and
#     spark-sql's embedded Derby one has no transaction tables ("Table/View 'NEXT_LOCK_ID' does
#     not exist" in acquireLock). The Iceberg shaded into delta-iceberg 3.2.1 predates the no-lock
#     commit (no NoLock class; iceberg.engine.hive.lock-enabled=false changes nothing), so a UniForm
#     script first creates them with Hive's TxnDbUtil.prepDb from a spark-shell in /tmp, where the
#     metastore_db the spark-sql phases open lives.
#   - --user 0: the image's own uid 185 cannot write the mounted warehouse, and the caller's uid
#     has no passwd entry in the image, which Hadoop's login refuses ("invalid null input: name").
#     Docker Desktop maps the bind mount's files to the caller whatever uid wrote them.
set -euo pipefail
cd "$(dirname "$0")/../../.."
CACHE=~/code/spark-kit/lakelab/.cache
SPARK_SQL="/opt/spark/bin/spark-sql --master 'local[1]' \
  --jars /opt/delta-spark.jar,/opt/delta-storage.jar,/opt/delta-iceberg.jar \
  --conf spark.sql.extensions=io.delta.sql.DeltaSparkSessionExtension \
  --conf spark.sql.catalog.spark_catalog=org.apache.spark.sql.delta.catalog.DeltaCatalog \
  --conf spark.sql.warehouse.dir=/wh \
  --conf spark.databricks.delta.retentionDurationCheck.enabled=false \
  --conf spark.ui.enabled=false \
  --conf spark.sql.session.timeZone=UTC"
for name in "$@"; do
  wh=$(mktemp -d)
  fx=$(mktemp -d)
  script=docs/fixtures/delta/$name.sql
  # The driver: one spark-sql per SQL phase, the --! lines between them as they are.
  {
    echo "set -e; cd /tmp"
    echo "sql() { $SPARK_SQL -f \"\$1\" 2>>/tmp/err.log || { tail -40 /tmp/err.log >&2; exit 1; }; }"
  } > "$fx/driver.sh"
  awk -v dir="$fx" '
    function phase() { n++; f = dir "/phase-" n ".sql"; printf "" > f; print "sql /fx/phase-" n ".sql" >> (dir "/driver.sh") }
    BEGIN { phase() }
    /^--! / { sub(/^--! /, ""); print >> (dir "/driver.sh"); split_next = 1; next }
    { if (split_next) { phase(); split_next = 0 } print >> f }
  ' "$script"
  docker run --rm --entrypoint bash --user 0 \
    -v "$wh:/wh" \
    -v "$fx:/fx:ro" \
    -v "$CACHE/delta-spark_2.12-3.2.1.jar:/opt/delta-spark.jar:ro" \
    -v "$CACHE/delta-storage-3.2.1.jar:/opt/delta-storage.jar:ro" \
    -v "$CACHE/delta-iceberg_2.12-3.2.1.jar:/opt/delta-iceberg.jar:ro" \
    apache/spark:3.5.4-java17 \
    -c "bash /fx/driver.sh" \
    > "docs/fixtures/delta/$name.out"
  for t in $(ls "$wh"); do
    rm -rf "example/delta/$t" && mkdir -p example/delta && cp -R "$wh/$t" "example/delta/$t"
  done
  rm -rf "$wh" "$fx"
done
