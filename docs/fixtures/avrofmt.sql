-- Regenerates example/iceberg/default/avrofmt — an Iceberg table whose data files and delete
-- file are Avro, not Parquet.
--
-- Every other Iceberg fixture is Parquet, which is what let the readers claim for the life of
-- the project that `read_parquet` "auto-detects Parquet, ORC and Avro": DuckDB does no such
-- thing, an Avro file handed to it fails on its magic bytes, and every reader — sample rows,
-- the row lookup, the statistics check, the positional-delete counts — was choosing the table
-- function for the one format it had ever seen. DuckDB's own `avro` extension reads Avro
-- (`read_avro`), with two differences this fixture is the oracle for: no `file_row_number`, so
-- a sampled Avro row has no position and a positional delete against it cannot be decided
-- from the row alone; and no field ids, so a recorded statistic is matched to its column by
-- name.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image:
--
--   1  CREATE   write.format.default = avro, merge-on-read deletes, format-version 2
--   2  INSERT   (1 alpha 10.50 2024-03-05) (2 bravo 20.25 2024-03-06) (3 charlie null 2024-03-07)
--   3  INSERT   (4 delta 40.00 2024-03-08) (5 echo 50.75 2024-03-09)
--   4  DELETE   id = 2 — a positional delete file, Avro like the data (write.delete.format.default follows)
--
-- The table holds 1, 3, 4, 5 after it. `--master local[1]`, or the first INSERT writes a file
-- per row and the DELETE removes a whole file rather than writing a positional delete.
--
-- To regenerate (see docs/fixtures/mor.sql for the spark.conf and why --entrypoint bash):
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"      # spark.conf as in mor.sql, written into $WH
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/avrofmt.sql:/tmp/avrofmt.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/avrofmt.sql"
--   rm -rf example/iceberg/default/avrofmt
--   cp -R "$WH/wh/default/avrofmt" example/iceberg/default/avrofmt
--   find example/iceberg/default/avrofmt -name '.*.crc' -delete

CREATE TABLE lens.default.avrofmt (
  id     INT,
  name   STRING,
  amount DECIMAL(9,2),
  d      DATE
) USING iceberg
TBLPROPERTIES (
  'format-version'            = '2',
  'write.format.default'      = 'avro',
  'write.delete.mode'         = 'merge-on-read',
  'write.update.mode'         = 'merge-on-read',
  'write.merge.mode'          = 'merge-on-read'
);

INSERT INTO lens.default.avrofmt VALUES
  (1, 'alpha',   10.50, DATE '2024-03-05'),
  (2, 'bravo',   20.25, DATE '2024-03-06'),
  (3, 'charlie', NULL,  DATE '2024-03-07');

INSERT INTO lens.default.avrofmt VALUES
  (4, 'delta', 40.00, DATE '2024-03-08'),
  (5, 'echo',  50.75, DATE '2024-03-09');

DELETE FROM lens.default.avrofmt WHERE id = 2;

SELECT * FROM lens.default.avrofmt ORDER BY id;
SELECT content, file_format, record_count, file_path FROM lens.default.avrofmt.files ORDER BY file_path;
