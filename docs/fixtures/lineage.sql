-- Regenerates example/iceberg/default/lineage — a format-version 3 table with row lineage.
--
-- Written by Spark 3.5.5 with the Iceberg 1.10.0 Spark runtime, NOT the 1.8.1 the
-- tabulario/spark-iceberg image ships: row lineage is a 1.10 feature, and the image's own
-- runtime jars have to be removed inside the container so two Iceberg versions are not on one
-- classpath. Every v3 table written by 1.10 tracks row lineage — there is no property to turn on.
--
-- What the format records, and where: `next-row-id` in metadata.json is the next id the table
-- will hand out; a snapshot's `first-row-id` is where its allocation started; a data file's
-- `first_row_id` (field 142) is the id of its first row, and the rest follow in file order
-- unless the file carries a `_row_id` column, which a file that REWRITES rows does so they keep
-- the ids they had. `_last_updated_sequence_number` travels the same way. A delete file allocates
-- nothing and records no first id.
--
-- Statements, in order — the numbers matter because the expected values in
-- RowLineageFixtureTest are read off them. The table is partitioned by `p` so that one INSERT
-- writes two files into one manifest, which is the only way the running sum in first_row_id
-- inheritance is exercised: the second file's id is the manifest's plus the first file's rows.
-- The second INSERT puts two rows in p=1 so the DELETE of one of them leaves a row and writes a
-- deletion vector; a DELETE matching every row of a file drops the file instead (see mor.sql).
--
--   1  CREATE   format-version 3, PARTITIONED BY (p)                         next-row-id 0
--   2  INSERT   (1,'alpha',1), (2,'bravo',2), (3,'charlie',1)                snapshot 1, first-row-id 0: one manifest, first_row_id 0, two files recording
--                                                                            null — p=1 (ids 1, 3) inherits 0, p=2 (id 2) inherits 2; next 3
--   3  INSERT   (4,'delta',1), (5,'echo',2), (6,'foxtrot',1)                 snapshot 2, first-row-id 3: manifest first_row_id 3, p=1 (4, 6) inherits 3,
--                                                                            p=2 (5) inherits 5; next 6
--   4  UPDATE   name = 'BRAVO' WHERE id = 2   (copy-on-write)                snapshot 3, first-row-id 6: the added-file manifest gets first_row_id 6 and its one
--                                                                            file inherits 6 — though its row carries _row_id 2 and a null
--                                                                            _last_updated_sequence_number — and the manifest rewritten to mark the old p=2
--                                                                            file DELETED gets 7 and writes the inherited ids (0, 2) into its entries; its 2
--                                                                            EXISTING rows are counted too, so next is 6 + 1 + 2 = 9: two ids burned
--   5  ALTER    write.delete.mode = merge-on-read
--   6  DELETE   WHERE id = 4                                                 snapshot 4, first-row-id 9: a deletion vector over snapshot 2's p=1 file, in a delete
--                                                                            manifest with no first_row_id; next stays 9
--   7  SELECT   every row with _row_id and _last_updated_sequence_number      the oracle, printed by the writer:
--                                                                            1 alpha 0 1 / 2 BRAVO 2 3 / 3 charlie 1 1 / 5 echo 5 2 / 6 foxtrot 4 2
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/.cache/iceberg-spark-runtime-3.5_2.12-1.10.0.jar
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/lineage.sql:/tmp/lineage.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" -v "$JAR:/opt/iceberg-1.10.jar:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "rm /opt/spark/jars/iceberg-spark-runtime-3.5_2.12-1.8.1.jar /opt/spark/jars/iceberg-*-bundle-1.8.1.jar; \
--      /opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/iceberg-1.10.jar --properties-file /tmp/spark.conf -f /tmp/lineage.sql"
--   rm -rf example/iceberg/default/lineage && cp -R "$WH/wh/default/lineage" example/iceberg/default/lineage
--   find example/iceberg/default/lineage -name '.*' -delete

CREATE TABLE lens.default.lineage (id INT, name STRING, p INT)
USING iceberg
PARTITIONED BY (p)
TBLPROPERTIES ('format-version' = '3');

INSERT INTO lens.default.lineage VALUES (1, 'alpha', 1), (2, 'bravo', 2), (3, 'charlie', 1);

INSERT INTO lens.default.lineage VALUES (4, 'delta', 1), (5, 'echo', 2), (6, 'foxtrot', 1);

UPDATE lens.default.lineage SET name = 'BRAVO' WHERE id = 2;

ALTER TABLE lens.default.lineage SET TBLPROPERTIES ('write.delete.mode' = 'merge-on-read');

DELETE FROM lens.default.lineage WHERE id = 4;

SELECT id, name, p, _row_id, _last_updated_sequence_number FROM lens.default.lineage ORDER BY id;
