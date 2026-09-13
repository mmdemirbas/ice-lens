-- Regenerates example/iceberg/default/rolled — a table whose main branch was moved back to an
-- earlier snapshot, leaving a commit that no ref reaches and that is not an ancestor of any ref.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
--
-- What it is for: a rollback writes no snapshot. `set_current_snapshot` moves `main` and appends
-- an entry to `snapshot-log` naming a snapshot the log already holds; the abandoned commit stays
-- in `snapshots` until an expiry removes it, and the next commit forks from the restored one. So
-- the log is the only record that a rollback happened, and it is read here as such: an entry
-- whose snapshot already appeared earlier in the log is a rollback, and the entries between the
-- two are what it undid. The tag is how the script names the target without knowing its id —
-- `set_current_snapshot` takes a `ref` — and it is dropped again so the abandoned snapshot is
-- kept by nothing but the metadata, which is the case worth drawing.
--
-- Statements, in order — the numbers matter because the expected values in RolledBackFixtureTest
-- are read off them:
--
--   1  CREATE   v2, unpartitioned
--   2  INSERT   (1, 'alpha')                                   snapshot 1, main
--   3  INSERT   (2, 'bravo')                                   snapshot 2, main
--   4  CREATE TAG keep                                         at snapshot 2
--   5  INSERT   (3, 'charlie')                                 snapshot 3, main, parent 2
--   6  CALL set_current_snapshot(ref => 'keep')                no snapshot: main back at 2, snapshot-log gains a second entry for 2
--   7  DROP TAG keep                                           snapshot 3 now reachable from no ref
--   8  INSERT   (4, 'delta')                                   snapshot 4, main, parent 2 — a fork beside the abandoned 3
--   9  SELECT   the snapshots, the log, and the rows           the oracle, printed by the writer: rows 1, 2, 4
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/rolled.sql:/tmp/rolled.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/rolled.sql"
--   rm -rf example/iceberg/default/rolled && cp -R "$WH/wh/default/rolled" example/iceberg/default/rolled
--   find example/iceberg/default/rolled -name '.*' -delete

CREATE TABLE lens.default.rolled (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO lens.default.rolled VALUES (1, 'alpha');

INSERT INTO lens.default.rolled VALUES (2, 'bravo');

ALTER TABLE lens.default.rolled CREATE TAG keep;

INSERT INTO lens.default.rolled VALUES (3, 'charlie');

CALL lens.system.set_current_snapshot(table => 'default.rolled', ref => 'keep');

ALTER TABLE lens.default.rolled DROP TAG keep;

INSERT INTO lens.default.rolled VALUES (4, 'delta');

SELECT snapshot_id, parent_id, operation FROM lens.default.rolled.snapshots ORDER BY committed_at;

SELECT made_current_at, snapshot_id, is_current_ancestor FROM lens.default.rolled.history ORDER BY made_current_at;

SELECT * FROM lens.default.rolled ORDER BY id;
