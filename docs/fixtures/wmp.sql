-- Regenerates example/iceberg/default/wmp and example/iceberg/wmp-data — a table whose metadata
-- is kept apart from its location by `write.metadata.path`, written by a catalog that honours the
-- property. A HadoopCatalog table keeps its metadata under `<location>/metadata` whatever the
-- property says (`HadoopTableOperations` derives every path from the location), which is why
-- every other Iceberg fixture has its metadata beside its data and this layout was only ever built
-- at runtime (`RecordedPathResolutionTest`, a rearrangement of `test`). `BaseMetastoreTableOperations`
-- reads the property (`metadataFileLocation` at 1.8.1: `<write.metadata.path>/<file>`, else
-- `<location>/metadata/<file>`), and Iceberg's own `JdbcCatalog` over a SQLite file is one of its
-- subclasses that needs no service behind it — so what is left on disk is exactly what a Hive,
-- Glue or JDBC catalog leaves: the metadata versions, manifest lists and manifests at the
-- property's path, the data under the location, and no `version-hint.text` at all. (The other
-- service-less one, `InMemoryCatalog`, was tried first and cannot be used: its FileIO is
-- `InMemoryFileIO` whatever `io-impl` says, so every statement runs, the paths print as `/wh/…`,
-- and nothing reaches the disk.)
--
-- Two things follow for a reader. The table is opened from the directory holding `metadata/`
-- (`/wh/default/wmp`), not from its `location` (`/wh/wmp-data`), and its data files are rebuilt
-- beside it the way `extdata`'s and `pih`'s are — the recorded `/wh/default/wmp` and the local
-- `example/iceberg/default/wmp` share their trailing segments, so `/wh/wmp-data/data/…` lands at
-- `example/iceberg/wmp-data/data/…`. And the versions are named the metastore way,
-- `00000-<uuid>.metadata.json` from 0, which `metadataVersionFromFileName` reads the number off;
-- until this table the corpus had only `v<N>` names and `MetastoreMetadataNamingTest` renamed a
-- copy of `test` to see the other shape.
--
-- Statements, in order:
--
--   1  CREATE   (id INT, name STRING), format-version 2, LOCATION /wh/wmp-data, write.metadata.path /wh/default/wmp/metadata
--   2  INSERT   (1 alpha) (2 bravo)                 00001-….metadata.json, one data file under /wh/wmp-data/data/
--   3  INSERT   (3 charlie)                         00002-….metadata.json, a second
--   4  SELECT * ORDER BY id                         the oracle: 1 alpha / 2 bravo / 3 charlie
--   5  the files and the metadata log              every data file under /wh/wmp-data/data/, every version under /wh/default/wmp/metadata/
--
-- To regenerate (the image's Iceberg 1.8.1; `jdbc` is JdbcCatalog over a SQLite file in the
-- warehouse, the driver mounted from the Gradle cache — on the driver class path, since a jar
-- given to `--jars` alone is not one `DriverManager` finds (`No suitable driver found`) — and it
-- has to be the default catalog, since spark-sql initialises the current one first and the
-- image's is a REST catalog with no server behind it; the SQLite file is the catalog's and is
-- not copied out):
--
--   WH=$(mktemp -d)
--   SQLITE=$(find ~/.gradle/caches -name 'sqlite-jdbc-*.jar' ! -name '*sources*' | head -1)
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$SQLITE:/opt/sqlite-jdbc.jar:ro" -v "$PWD/docs/fixtures/wmp.sql:/tmp/wmp.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --driver-class-path /opt/sqlite-jdbc.jar --jars /opt/sqlite-jdbc.jar \
--           --conf spark.sql.catalog.jdbc=org.apache.iceberg.spark.SparkCatalog \
--           --conf spark.sql.catalog.jdbc.catalog-impl=org.apache.iceberg.jdbc.JdbcCatalog \
--           --conf spark.sql.catalog.jdbc.uri=jdbc:sqlite:/wh/jdbc-catalog.sqlite \
--           --conf spark.sql.catalog.jdbc.warehouse=/wh \
--           --conf spark.sql.defaultCatalog=jdbc \
--           --conf spark.ui.enabled=false \
--           -f /tmp/wmp.sql"
--   rm -rf example/iceberg/default/wmp example/iceberg/wmp-data
--   cp -R "$WH/default/wmp" example/iceberg/default/wmp && cp -R "$WH/wmp-data" example/iceberg/wmp-data
--   find example/iceberg/default/wmp example/iceberg/wmp-data -name '.*.crc' -delete

CREATE NAMESPACE IF NOT EXISTS jdbc.default;

CREATE TABLE jdbc.default.wmp (id INT, name STRING)
USING iceberg
LOCATION '/wh/wmp-data'
TBLPROPERTIES ('format-version' = '2', 'write.metadata.path' = '/wh/default/wmp/metadata');

INSERT INTO jdbc.default.wmp VALUES (1, 'alpha'), (2, 'bravo');

INSERT INTO jdbc.default.wmp VALUES (3, 'charlie');

SELECT * FROM jdbc.default.wmp ORDER BY id;

SELECT file_path FROM jdbc.default.wmp.files ORDER BY file_path;

SELECT file FROM jdbc.default.wmp.metadata_log_entries ORDER BY timestamp;
