-- duni — UniForm: a Delta table that also writes Iceberg metadata beside its log, under
-- metadata/, converted synchronously after each commit (uniform.iceberg.sync.convert.enabled,
-- which the session sets so the spark-sql exits after the conversion rather than before it).
-- IcebergCompatV2 needs column mapping and refuses deletion vectors, so the DELETE rewrites.
--
--   v0  CREATE                         partitioned by p, name-mode column mapping
--   v1  INSERT (1, a, x), (2, b, y)    two files
--   v2  INSERT (3, c, x)
--   v3  DELETE id = 2                  the p=y file removed whole
--   v4  INSERT (4, d, y)
-- The converter commits the Iceberg metadata through the session's Hive metastore under a lock,
-- and the embedded Derby metastore has no transaction tables until TxnDbUtil makes them — see
-- run.sh's header.
--! echo 'org.apache.hadoop.hive.metastore.txn.TxnDbUtil.prepDb(new org.apache.hadoop.hive.conf.HiveConf()); System.exit(0)' | /opt/spark/bin/spark-shell --master 'local[1]' > /tmp/prepdb.log 2>&1 || { tail -30 /tmp/prepdb.log >&2; exit 1; }
SET spark.databricks.delta.uniform.iceberg.sync.convert.enabled = true;
CREATE TABLE duni (id INT, v STRING, p STRING) USING delta PARTITIONED BY (p)
TBLPROPERTIES (
  'delta.columnMapping.mode' = 'name',
  'delta.enableIcebergCompatV2' = 'true',
  'delta.universalFormat.enabledFormats' = 'iceberg'
);
INSERT INTO duni VALUES (1, 'a', 'x'), (2, 'b', 'y');
INSERT INTO duni VALUES (3, 'c', 'x');
DELETE FROM duni WHERE id = 2;
INSERT INTO duni VALUES (4, 'd', 'y');
SELECT 'rows', id, v, p FROM duni ORDER BY id;
DESCRIBE HISTORY duni;
--! ls -laR /wh/duni/metadata /wh/duni/_delta_log
--! cat /wh/duni/metadata/*.metadata.json | head -c 6000
