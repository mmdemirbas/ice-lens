-- Regenerates example/iceberg/default/wap — a write-audit-publish flow: a staged snapshot on no
-- branch, main moving past it, and the staged snapshot cherry-picked onto main.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
--
-- What it is for: every other fixture's snapshots are either on a branch or expired. A snapshot
-- written under `spark.wap.id` is neither — it sits in `metadata.json`'s `snapshots` with
-- `wap.id` in its summary, its parent is main's tip at the time, and no ref points at it until
-- `publish_changes` cherry-picks it: a NEW snapshot on main whose summary carries
-- `source-snapshot-id` and `published-wap-id` and whose manifest list names the staged snapshot's
-- own manifest. The lineage edge says the published commit's parent is main's tip; only the
-- summary says where its files came from, and that is the relationship this table is for.
--
-- Statements, in order — the numbers matter because the expected values in WapFixtureTest are
-- read off them:
--
--   1  CREATE   v2, write.wap.enabled = true
--   2  INSERT   (1, 'alpha')                                   snapshot 1, main
--   3  SET spark.wap.id = audit-1
--   4  INSERT   (2, 'bravo')                                   snapshot 2: staged — summary wap.id audit-1, parent 1, no ref, current stays 1
--   5  RESET spark.wap.id
--   6  INSERT   (3, 'charlie')                                 snapshot 3, main, parent 1 — a fork from where snapshot 2 forked
--   7  CALL publish_changes(wap_id => 'audit-1')               snapshot 4, main, parent 3 — published-wap-id audit-1, source-snapshot-id 2,
--                                                              the same one manifest snapshot 2 wrote; total-records 3
--   8  SELECT   the snapshots, and the rows                    the oracle, printed by the writer
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   cat > "$WH/spark.conf" <<'EOF'
--   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
--   spark.sql.catalog.lens.type       hadoop
--   spark.sql.catalog.lens.warehouse  /wh
--   spark.sql.defaultCatalog          lens
--   spark.sql.catalogImplementation   in-memory
--   spark.ui.enabled                  false
--   spark.eventLog.enabled            false
--   EOF
--   mkdir -p "$WH/wh"
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/wap.sql:/tmp/wap.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/wap.sql"
--   rm -rf example/iceberg/default/wap && cp -R "$WH/wh/default/wap" example/iceberg/default/wap
--   find example/iceberg/default/wap -name '.*' -delete

CREATE TABLE lens.default.wap (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2', 'write.wap.enabled' = 'true');

INSERT INTO lens.default.wap VALUES (1, 'alpha');

SET spark.wap.id = audit-1;

INSERT INTO lens.default.wap VALUES (2, 'bravo');

RESET spark.wap.id;

INSERT INTO lens.default.wap VALUES (3, 'charlie');

CALL lens.system.publish_changes(table => 'default.wap', wap_id => 'audit-1');

SELECT snapshot_id, parent_id, operation, summary['wap.id'], summary['published-wap-id'], summary['source-snapshot-id'] FROM lens.default.wap.snapshots ORDER BY committed_at;

SELECT * FROM lens.default.wap ORDER BY id;
