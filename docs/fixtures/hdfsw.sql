-- hdfsw: an Iceberg table Spark wrote straight into HDFS, so every path its metadata records is
-- `hdfs://icelens-hdfs:8020/warehouse/spark/default/hdfsw/...` — the shape a table on a real
-- cluster has, where every other fixture records a container's `/wh`. WebHdfsTableTest reads it
-- back over WebHDFS, where no recorded path opens (nothing on this classpath reads `hdfs://`)
-- and every one resolves by the rebuild under the table root; the checked-in copy is the same
-- rule on disk.
--
-- Partitioned by region and merge-on-read, so the DELETE writes a positional delete whose
-- `file_path` column names the data file by its `hdfs://` path, which the row lookup matches as
-- recorded. `--master local[1]` so each insert writes one file per partition and the DELETE
-- leaves a row beside the one it deletes — under local[2] it drops the file instead.
--
-- To regenerate, with docs/fixtures/hdfs-lab.sh up (Spark joins the lab container's network,
-- so `icelens-hdfs` and its datanode resolve, and writes as the user that owns /warehouse):
--
--   WH=$(mktemp -d)
--   cat > "$WH/spark.conf" <<'EOF'
--   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
--   spark.sql.catalog.lens.type       hadoop
--   spark.sql.catalog.lens.warehouse  hdfs://icelens-hdfs:8020/warehouse/spark
--   spark.sql.defaultCatalog          lens
--   spark.sql.catalogImplementation   in-memory
--   spark.ui.enabled                  false
--   spark.eventLog.enabled            false
--   EOF
--   docker run --rm --entrypoint bash --network container:icelens-hdfs -e HADOOP_USER_NAME=hadoop \
--     -v "$PWD/docs/fixtures/hdfsw.sql:/tmp/hdfsw.sql:ro" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/hdfsw.sql"
--   docker exec icelens-hdfs bash -c 'rm -rf /tmp/hdfsw && hdfs dfs -get /warehouse/spark/default/hdfsw /tmp/hdfsw'
--   rm -rf example/iceberg/default/hdfsw && docker cp icelens-hdfs:/tmp/hdfsw example/iceberg/default/hdfsw
--   find example/iceberg/default/hdfsw -name '.*.crc' -delete
--
-- hdfs-lab.sh seeds the copy back at the path it was written to.

CREATE TABLE default.hdfsw (id INT, name STRING, region STRING) USING iceberg
  PARTITIONED BY (region)
  TBLPROPERTIES ('format-version' = '2', 'write.delete.mode' = 'merge-on-read');

INSERT INTO default.hdfsw VALUES (1, 'alpha', 'eu'), (2, 'bravo', 'eu'), (3, 'charlie', 'us');
INSERT INTO default.hdfsw VALUES (4, 'delta', 'eu'), (5, 'echo', 'us');
DELETE FROM default.hdfsw WHERE id = 2;

SELECT * FROM default.hdfsw ORDER BY id;
SELECT file_path, record_count FROM default.hdfsw.all_delete_files;
