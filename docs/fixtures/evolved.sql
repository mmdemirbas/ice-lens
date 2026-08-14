-- Regenerates example/iceberg/default/evolved — the schema-evolution fixture.
--
-- This one exists to test the codebase's most important correctness claim, which until now was
-- asserted everywhere and verified nowhere: **bounds are decoded against the schema the manifest
-- carries in its own Avro file metadata, never against the table's current schema.** Every other
-- fixture has a single schema, so the two are the same object and a decoder reading the wrong one
-- passes all of them.
--
-- The type widenings are chosen so that reading the wrong schema cannot fail quietly:
--
--   * `id` int -> bigint. A bound written under schema 0 is FOUR bytes; under the current schema
--     it would be read as an eight-byte long. Little-endian, so the wrong read either runs off
--     the end of the buffer or produces a wildly wrong number - not a near miss.
--   * `amount` float -> double. Same shape, four bytes against eight, and a float's bit pattern
--     read as a double is a denormal near zero rather than an error.
--
-- And the name changes make the same point on the labelling side:
--
--   * `name` is renamed to `label`, then dropped. A file written before the rename has bounds
--     keyed by field id, and the manifest's own schema is the only thing that still knows the
--     column was called `name` when that file was written. The current schema does not contain
--     the column at all.
--   * `note` is added, so files written before it exist have no bound for a field the current
--     schema lists.
--
-- `--master local[1]` keeps one data file per insert, so the manifest-to-schema mapping stays
-- readable when the assertions are written.
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/evolved.sql:/tmp/evolved.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/evolved.sql"
--   rm -rf example/iceberg/default/evolved
--   cp -R "$WH/wh/default/evolved" example/iceberg/default/evolved
--   find example/iceberg/default/evolved -name '.*.crc' -delete

CREATE TABLE lens.default.evolved (
  id     INT,
  name   STRING,
  amount FLOAT
) USING iceberg
TBLPROPERTIES ('format-version' = '2');

-- schema 0: id int, name string, amount float
INSERT INTO lens.default.evolved VALUES
  (1, 'alpha', CAST(1.5 AS FLOAT)),
  (2, 'bravo', CAST(2.5 AS FLOAT));

ALTER TABLE lens.default.evolved ALTER COLUMN id TYPE BIGINT;
ALTER TABLE lens.default.evolved ALTER COLUMN amount TYPE DOUBLE;
ALTER TABLE lens.default.evolved RENAME COLUMN name TO label;
ALTER TABLE lens.default.evolved ADD COLUMN note STRING;

-- schema 1: id bigint, label string, amount double, note string
INSERT INTO lens.default.evolved VALUES
  (3000000000, 'charlie', 3.5, 'third'),
  (4000000000, 'delta',   4.5, 'fourth');

ALTER TABLE lens.default.evolved DROP COLUMN label;

-- schema 2: id bigint, amount double, note string
INSERT INTO lens.default.evolved (id, amount, note) VALUES
  (5000000000, 5.5, 'fifth');
