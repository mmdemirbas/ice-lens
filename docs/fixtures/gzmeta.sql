-- Regenerates example/iceberg/default/gzmeta — the minimal table under
-- `write.metadata.compression-codec = gzip`, so every metadata.json is gzip-compressed and named
-- for it.
--
-- Iceberg names a compressed metadata file `v<N>.gz.metadata.json` (HadoopTableOperations:
-- `"v" + version + TableMetadataParser.getFileExtension(codec)`, the codec's `.gz` *before*
-- `.metadata.json`; a metastore table gets `<N>-<uuid>.gz.metadata.json`) and reads the codec
-- back off the name (`Codec.fromFileName`, which also accepts the older `.metadata.json.gz`).
-- `version-hint.text` still holds the bare version number. The manifest lists and manifests are
-- Avro and untouched by the option.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image:
--
--   1  CREATE   (id INT, name STRING), v2, write.metadata.compression-codec = gzip      v1.gz.metadata.json
--   2  INSERT   (1 alpha) (2 bravo)                                                      v2.gz.metadata.json
--   3  INSERT   (3 charlie)                                                              v3.gz.metadata.json
--   4  SELECT * ORDER BY id                                                              the oracle: three rows
--
-- To regenerate (see docs/fixtures/mor.sql for the spark.conf and why --entrypoint bash):
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh"      # spark.conf as in mor.sql, written into $WH
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/gzmeta.sql:/tmp/gzmeta.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/gzmeta.sql"
--   rm -rf example/iceberg/default/gzmeta
--   cp -R "$WH/wh/default/gzmeta" example/iceberg/default/gzmeta
--   find example/iceberg/default/gzmeta -name '.*.crc' -delete

CREATE TABLE lens.default.gzmeta (id INT, name STRING)
USING iceberg TBLPROPERTIES ('format-version' = '2', 'write.metadata.compression-codec' = 'gzip');

INSERT INTO lens.default.gzmeta VALUES (1, 'alpha'), (2, 'bravo');

INSERT INTO lens.default.gzmeta VALUES (3, 'charlie');

SELECT * FROM lens.default.gzmeta ORDER BY id;
SELECT snapshot_id, manifest_list FROM lens.default.gzmeta.snapshots ORDER BY committed_at;
