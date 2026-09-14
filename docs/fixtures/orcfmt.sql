-- Regenerates example/iceberg/default/orcfmt — an Iceberg table whose data files and delete
-- file are ORC, not Parquet.
--
-- DuckDB 1.4 has no ORC reader — no core table function and no community extension
-- (`INSTALL orc FROM community` answers 404) — so this is the table the app cannot read rows
-- from, and the fixture exists to pin what it says instead: a row card and its panel that name
-- the reason, a live-row count that reports the file uncounted, a statistics sweep that lists
-- the file as unreadable with the reason — never a DuckDB error about magic bytes, and never a
-- blank card. Everything read from the metadata alone is checked as on any other table.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image, the same
-- statements as avrofmt.sql under `write.format.default = orc`:
--
--   1  CREATE   write.format.default = orc, merge-on-read deletes, format-version 2
--   2  INSERT   (1 alpha 10.50 2024-03-05) (2 bravo 20.25 2024-03-06) (3 charlie null 2024-03-07)
--   3  INSERT   (4 delta 40.00 2024-03-08) (5 echo 50.75 2024-03-09)
--   4  DELETE   id = 2 — a positional delete file, ORC like the data
--
-- The table holds 1, 3, 4, 5 after it. `--master local[1]` for the reason avrofmt.sql gives.
--
-- To regenerate (see docs/fixtures/mor.sql for the spark.conf and why --entrypoint bash):
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"      # spark.conf as in mor.sql, written into $WH
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/orcfmt.sql:/tmp/orcfmt.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/orcfmt.sql"
--   rm -rf example/iceberg/default/orcfmt
--   cp -R "$WH/wh/default/orcfmt" example/iceberg/default/orcfmt
--   find example/iceberg/default/orcfmt -name '.*.crc' -delete

CREATE TABLE lens.default.orcfmt (
  id     INT,
  name   STRING,
  amount DECIMAL(9,2),
  d      DATE
) USING iceberg
TBLPROPERTIES (
  'format-version'            = '2',
  'write.format.default'      = 'orc',
  'write.delete.mode'         = 'merge-on-read',
  'write.update.mode'         = 'merge-on-read',
  'write.merge.mode'          = 'merge-on-read'
);

INSERT INTO lens.default.orcfmt VALUES
  (1, 'alpha',   10.50, DATE '2024-03-05'),
  (2, 'bravo',   20.25, DATE '2024-03-06'),
  (3, 'charlie', NULL,  DATE '2024-03-07');

INSERT INTO lens.default.orcfmt VALUES
  (4, 'delta', 40.00, DATE '2024-03-08'),
  (5, 'echo',  50.75, DATE '2024-03-09');

DELETE FROM lens.default.orcfmt WHERE id = 2;

SELECT * FROM lens.default.orcfmt ORDER BY id;
SELECT content, file_format, record_count, file_path FROM lens.default.orcfmt.files ORDER BY file_path;
