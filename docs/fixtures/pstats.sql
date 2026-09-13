-- Regenerates example/iceberg/default/pstats — a partitioned table with a partition statistics file.
--
-- Written by Spark 3.5.5 with the Iceberg 1.10.0 Spark runtime dropped into the
-- tabulario/spark-iceberg image (see lineage.sql for the jar swap): `compute_partition_stats`
-- does not exist in the 1.8.1 the image ships, which is why `partition-statistics` was modelled
-- from the spec with nothing to check it against until this table.
--
-- The file is Parquet, one row per partition, and it is what a planner reads instead of walking
-- every manifest to answer "how big is partition eu". The delete is merge-on-read so one partition
-- carries a positional delete and the file's delete columns are exercised alongside the data ones.
--
-- Statements, in order — the numbers matter because the expected values in
-- PartitionStatsFixtureTest are read off them, and the last statement prints the writer's own
-- per-partition figures, which the file has to agree with:
--
--   1  CREATE   (id INT, name STRING, p STRING) PARTITIONED BY (p), v2, write.delete.mode = merge-on-read
--   2  INSERT   (1,'alpha','eu'), (2,'bravo','us'), (3,'charlie','eu')   snapshot 1: eu 2 rows, us 1 row
--   3  INSERT   (4,'delta','eu'), (5,'echo','apac')                      snapshot 2: eu +1 row, apac 1 row
--   4  DELETE   WHERE id = 1                                             snapshot 3: one positional delete file in eu, 1 record
--   5  CALL     compute_partition_stats                                  metadata/partition-stats-<snapshot 3>-<uuid>.parquet, 4,191 bytes:
--                                                                       apac 1 record 1 file / eu 3 records 2 files + 1 delete record 1 delete file / us 1 record 1 file
--   6  SELECT   * FROM pstats.partitions                                 the oracle, printed by the writer
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/pstats.sql:/tmp/pstats.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" -v "$JAR:/opt/iceberg-1.10.jar:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "rm /opt/spark/jars/iceberg-spark-runtime-3.5_2.12-1.8.1.jar /opt/spark/jars/iceberg-*-bundle-1.8.1.jar; \
--      /opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/iceberg-1.10.jar --properties-file /tmp/spark.conf -f /tmp/pstats.sql"
--   rm -rf example/iceberg/default/pstats && cp -R "$WH/wh/default/pstats" example/iceberg/default/pstats
--   find example/iceberg/default/pstats -name '.*' -delete

CREATE TABLE lens.default.pstats (id INT, name STRING, p STRING)
USING iceberg
PARTITIONED BY (p)
TBLPROPERTIES ('format-version' = '2', 'write.delete.mode' = 'merge-on-read');

INSERT INTO lens.default.pstats VALUES (1, 'alpha', 'eu'), (2, 'bravo', 'us'), (3, 'charlie', 'eu');

INSERT INTO lens.default.pstats VALUES (4, 'delta', 'eu'), (5, 'echo', 'apac');

DELETE FROM lens.default.pstats WHERE id = 1;

CALL lens.system.compute_partition_stats(table => 'default.pstats');

SELECT * FROM lens.default.pstats.partitions ORDER BY partition;
