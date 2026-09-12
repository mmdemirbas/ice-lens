-- Regenerates example/iceberg/default/sorted — an Iceberg table written under three sort orders.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every other
-- Iceberg fixture is unsorted: one sort order, id 0, no fields, and every data file's
-- sort_order_id 0. A sort order is what a planner uses to skip within a file and what a reader
-- of a data file wants to know before opening it, and the file panel printed only its id with
-- nothing to resolve it against; this table is what resolves it.
--
-- Statements, in order — the numbers matter because the expected values in
-- SortedFixtureTest are read off them:
--
--   1  CREATE   format-version 2, unpartitioned                         v1: order 0, unsorted
--   2  INSERT   (3, 'gamma'), (1, 'alpha'), (2, 'beta')                 snapshot 1: one file, rows as inserted, sort_order_id 0
--   3  ALTER    WRITE ORDERED BY name                                   v3: order 1 = name ASC NULLS FIRST
--   4  INSERT   (6, 'zeta'), (4, 'delta'), (5, 'epsilon')               snapshot 2: one file, rows in name order, sort_order_id 0
--   5  ALTER    WRITE ORDERED BY id DESC NULLS LAST, name               v5: order 2 = id DESC NULLS LAST, name ASC NULLS FIRST
--   6  INSERT   (7, 'eta'), (9, 'iota'), (8, 'theta')                   snapshot 3: one file, rows in id DESC order, sort_order_id 0
--   7  CALL     rewrite_data_files(strategy => 'sort')                    snapshot 4: the three files rewritten as one, nine rows in id DESC order, sort_order_id 0
--
-- Expected afterwards: metadata carries three sort orders and default-sort-order-id 2, and
-- --master local[1] makes each INSERT one file. The rows inside files 2 and 3 are in the order
-- that was in force (delta, epsilon, zeta; 9, 8, 7), the compacted file holds all nine in
-- id DESC — and every data file, the compacted one included, still records sort_order_id 0.
-- That is not a mistake in the script: Spark's writer sorts through the write's requested
-- ordering and never passes the order to its file writer factory, so DataFiles.Builder keeps
-- SortOrder.unsorted().orderId(). The header first predicted 0, 1 and 2; the manifests settled
-- it, and the rewrite was added to ask the one writer whose purpose is the order.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/sorted.sql:/tmp/sorted.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/sorted.sql"
--   rm -rf example/iceberg/default/sorted && cp -R "$WH/wh/default/sorted" example/iceberg/default/sorted

CREATE TABLE default.sorted (id INT, name STRING)
USING iceberg
TBLPROPERTIES ('format-version' = '2');

INSERT INTO default.sorted VALUES (3, 'gamma'), (1, 'alpha'), (2, 'beta');

ALTER TABLE default.sorted WRITE ORDERED BY name;

INSERT INTO default.sorted VALUES (6, 'zeta'), (4, 'delta'), (5, 'epsilon');

ALTER TABLE default.sorted WRITE ORDERED BY id DESC NULLS LAST, name;

INSERT INTO default.sorted VALUES (7, 'eta'), (9, 'iota'), (8, 'theta');

-- A sort compaction is the one write whose whole purpose is the order. min-input-files defaults
-- to 5 and there are three files, so it is lowered; the target size is not, since nine rows fit.
CALL system.rewrite_data_files(table => 'default.sorted', strategy => 'sort', options => map('min-input-files', '2'));
