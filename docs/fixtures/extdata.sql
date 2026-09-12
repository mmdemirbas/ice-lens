-- Regenerates example/iceberg/default/extdata and example/iceberg/extdata-files — an Iceberg
-- table whose data files live outside its own directory, under write.data.path.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every other
-- Iceberg fixture keeps its data under <table>/data/, which is the default and not a rule:
-- write.data.path puts it anywhere, and a table registered over files written elsewhere has the
-- same shape. The manifests then record absolute paths that share nothing with the table's own
-- directory, and a reader that rebuilds a data file's sub-path under the table root — which is
-- what opens a table copied down from a bucket — has no sub-path to rebuild. What this tool did
-- with such a table had never been looked at: it rebuilt the whole recorded path under the table
-- root (<table>/wh/extdata-files/…), where nothing is, and reported every data file missing.
--
-- Statements, in order — the numbers matter because the expected values in
-- ExternalDataPathFixtureTest are read off them:
--
--   1  CREATE   format-version 2, unpartitioned, write.data.path = /wh/extdata-files   v1
--   2  INSERT   id = 1, 2                                          snapshot 1, v2, one file
--   3  INSERT   id = 3                                             snapshot 2, v3, one file
--
-- Expected afterwards: /wh/default/extdata holds metadata/ only — no data/ directory at all;
-- /wh/extdata-files holds the two data files directly (Iceberg's location provider puts a
-- partition path under write.data.path, and an unpartitioned table has none); every manifest
-- entry's file_path starts with /wh/extdata-files/. --master local[1] so an INSERT is one file.
--
-- Both directories are checked in side by side, the way they sat under /wh, so the local
-- counterpart of the recorded warehouse is example/iceberg and the rebuild rule has something
-- to find.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/extdata.sql:/tmp/extdata.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/extdata.sql"
--   rm -rf example/iceberg/default/extdata example/iceberg/extdata-files
--   cp -R "$WH/wh/default/extdata" example/iceberg/default/extdata
--   cp -R "$WH/wh/extdata-files" example/iceberg/extdata-files

CREATE TABLE default.extdata (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2', 'write.data.path' = '/wh/extdata-files');

INSERT INTO default.extdata VALUES (1, 'alpha'), (2, 'beta');

INSERT INTO default.extdata VALUES (3, 'gamma');
