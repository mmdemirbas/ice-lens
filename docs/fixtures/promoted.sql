-- Regenerates example/iceberg/default/promoted — evolved's three schemas, then rewrite_manifests.
--
-- `evolved` proves that bounds are decoded against the schema the manifest carries, because each
-- of its manifests was written under the schema in force at the time. This fixture is the case
-- that proof does not reach: `rewrite_manifests` reads every live entry and writes them into one
-- manifest under the table's CURRENT schema, copying each data file's bounds verbatim — they are
-- bytes keyed by field id, and the rewrite does not re-encode them. So the schema-0 file's `id`
-- bound is four bytes in a manifest whose schema says `id` is a bigint, its `amount` bound four
-- bytes against a double, and its `name` bound is keyed by a field id the manifest's schema no
-- longer has at all. Iceberg's own reader tolerates the first two (Conversions.fromByteBuffer:
-- "type was later promoted to long" reads the four bytes as an int and widens); a decoder that
-- requires eight bytes for a long reports the writer's correct bound as a decode failure.
--
-- Statements, in order — the numbers matter because the expected values in
-- PromotedBoundsFixtureTest are read off them:
--
--   1  CREATE   id INT, name STRING, amount FLOAT                          schema 0
--   2  INSERT   (1, 'alpha', 1.5), (2, 'bravo', 2.5)                       snapshot 1: file A under schema 0 — id 1..2 (4 bytes), amount 1.5..2.5 (4 bytes), name alpha..bravo
--   3  ALTER    id → BIGINT, amount → DOUBLE, name → label, ADD note        schema 1
--   4  INSERT   (3000000000, 'charlie', 3.5, 'third'), (4000000000, 'delta', 4.5, 'fourth')   snapshot 2: file B — id 3e9..4e9 (8 bytes)
--   5  ALTER    DROP COLUMN label                                          schema 2
--   6  INSERT   (5000000000, 5.5, 'fifth')                                 snapshot 3: file C
--   7  CALL     rewrite_manifests                                          snapshot 4: one manifest under schema 2 holding A, B, C as EXISTING
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/promoted.sql:/tmp/promoted.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/promoted.sql"
--   rm -rf example/iceberg/default/promoted
--   cp -R "$WH/wh/default/promoted" example/iceberg/default/promoted
--   find example/iceberg/default/promoted -name '.*' -delete

CREATE TABLE lens.default.promoted (
  id     INT,
  name   STRING,
  amount FLOAT
) USING iceberg
TBLPROPERTIES ('format-version' = '2');

-- schema 0: id int, name string, amount float
INSERT INTO lens.default.promoted VALUES
  (1, 'alpha', CAST(1.5 AS FLOAT)),
  (2, 'bravo', CAST(2.5 AS FLOAT));

ALTER TABLE lens.default.promoted ALTER COLUMN id TYPE BIGINT;
ALTER TABLE lens.default.promoted ALTER COLUMN amount TYPE DOUBLE;
ALTER TABLE lens.default.promoted RENAME COLUMN name TO label;
ALTER TABLE lens.default.promoted ADD COLUMN note STRING;

-- schema 1: id bigint, label string, amount double, note string
INSERT INTO lens.default.promoted VALUES
  (3000000000, 'charlie', 3.5, 'third'),
  (4000000000, 'delta',   4.5, 'fourth');

ALTER TABLE lens.default.promoted DROP COLUMN label;

-- schema 2: id bigint, amount double, note string
INSERT INTO lens.default.promoted (id, amount, note) VALUES
  (5000000000, 5.5, 'fifth');

-- One manifest under schema 2, every live file carried into it with its bounds as written.
CALL lens.system.rewrite_manifests('default.promoted');
