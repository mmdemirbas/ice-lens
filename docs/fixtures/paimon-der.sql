-- Regenerates example/paimon/db.db/der — `de` with a column renamed on either side of the
-- patch: a data-evolution append table whose whole file was written under one set of names,
-- whose patch file was written after `a` became `aa`, and whose schema renamed `b` to `bb`
-- after the patch. A read stitches the two files by row id and places each file's columns
-- through the schema its own `_SCHEMA_ID` names, so the rename never reaches the files — the
-- whole file still holds `a` and `b`, the patch `b` — and a stitch that selects the schema's
-- names from the files finds `aa` and `bb` in neither. This table is what that looks like.
--
-- Written by Spark 3.5.5 (the tabulario/spark-iceberg image) with the Paimon Spark 3.5 runtime
-- jar, version 1.3, the same way as paimon-de.sql.
--
-- Statements, in order — the numbers matter because the expected values in
-- PaimonDataEvolutionRenameFixtureTest are read off them:
--
--   1  CREATE   (id INT, a INT, b INT), bucket -1, row-tracking + data-evolution
--   2  INSERT   (1 1 1) (2 2 2)                       snapshot-1: one whole file, schema-0
--   3  RENAME   a → aa                                schema-1
--   4  MERGE    UPDATE SET b = 11 for id 1            snapshot-2: a patch file holding b only, schema-1
--   5  RENAME   b → bb                                schema-2
--   6  SELECT * ORDER BY id                           the oracle: 1 1 11 / 2 2 2
--   7  SELECT WHERE aa = 1, WHERE bb = 11             the filtered reads
--
-- The last statements print the rows the reader is expected to produce. What the jar printed
-- (2026-09-14): the whole read is `1 1 11 0 2` / `2 2 2 1 2` — both rows' _SEQUENCE_NUMBER is
-- the patch's 2, as the stitch takes the freshest file's — `WHERE aa = 1` printed `1 1 1` and
-- `WHERE bb = 11` nothing, because on the 1.3-SNAPSHOT that writes these fixtures a filtered
-- read of a data-evolution table does not stitch (see paimon-de.sql); 1.3.0+ reads `1 1 11`
-- for both, which is what the lookup here is held to.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-der.sql:/tmp/paimon-der.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-der.sql"
--   rm -rf example/paimon/db.db/der && cp -R "$WH/db.db/der" example/paimon/db.db/der
--   find example/paimon/db.db/der -name '.*.crc' -delete

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.der (id INT, a INT, b INT)
TBLPROPERTIES (
  'bucket' = '-1',
  'row-tracking.enabled' = 'true',
  'data-evolution.enabled' = 'true'
);

INSERT INTO db.der VALUES (1, 1, 1), (2, 2, 2);

ALTER TABLE db.der RENAME COLUMN a TO aa;

CREATE TABLE db.der_src (id INT, b INT);
INSERT INTO db.der_src VALUES (1, 11);

MERGE INTO db.der AS t
USING db.der_src AS s
ON t.id = s.id
WHEN MATCHED THEN UPDATE SET t.b = s.b;

ALTER TABLE db.der RENAME COLUMN b TO bb;

SELECT id, aa, bb, _ROW_ID, _SEQUENCE_NUMBER FROM db.der ORDER BY id;

SELECT id, aa, bb FROM db.der WHERE aa = 1 ORDER BY id;

SELECT id, aa, bb FROM db.der WHERE bb = 11 ORDER BY id;
