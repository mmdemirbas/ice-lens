-- expire_snapshots(clean_expired_metadata => true) — which partition specs and schemas an expiry
-- drops from metadata.json besides the snapshots: the oracle for model/MetadataCleanupPlan.kt,
-- recorded rather than checked in. MetadataCleanupFixtureTest holds the plan on the checked-in
-- tables to these runs.
--
-- The rule, read at apache-iceberg-1.10.0 (RemoveSnapshots.apply, lines 222–252): reachable
-- specs are the default spec plus the partition_spec_id of every manifest of every retained
-- snapshot (snapshot.allManifests — data and delete, whatever the entries' statuses); reachable
-- schemas are the current schema plus every retained snapshot's schema-id; the rest are removed
-- (TableMetadata.Builder.removeSpecs / removeSchemas). At apache-iceberg-1.8.1 the core API's
-- cleanExpiredMetadata(true) removes specs only (`// TODO: Support cleaning expired schema as
-- well`) and ExpireSnapshotsProcedure has no clean_expired_metadata parameter.
--
-- Run (2026-09-15) with Spark 3.5.5 and the Iceberg 1.10.0 Spark runtime — the lineage.sql
-- recipe: the tabulario/spark-iceberg image with its 1.8.1 jars removed and
-- iceberg-spark-runtime-3.5_2.12-1.10.0.jar on --jars — each statement its own `spark-sql -e`
-- under the spark.conf of orph.scala plus spark.sql.session.timeZone UTC, copies mounted at
-- /wh/default/<same name>. Result columns: deleted_data_files_count,
-- deleted_position_delete_files_count, deleted_equality_delete_files_count,
-- deleted_manifest_files_count, deleted_manifest_lists_count, deleted_statistics_files_count.
--
--   respec    specs [0, 1, 2, 3], default 3, two snapshots; all_manifests lists spec 0 and spec 3
--             manifests only — specs 1 and 2 were replaced before any write under them.
--             rewrite_data_files(rewrite-all) 4 3 3590 0 0; INSERT (99, 'zulu', 2024-05-01);
--             `manifests` of the current snapshot then spec 3 alone (the rewrite's filtered spec-0
--             manifests of DELETED entries left the list at the insert);
--             expire_snapshots(older_than => 2099, retain_last => 1, clean_expired_metadata => true)
--             4 0 0 4 3 0 — v9: partition-specs [3], one snapshot; SELECT reads the five rows.
--             On the checked-in table the plan drops 1 and 2 alone: the current snapshot still
--             carries a spec-0 manifest.
--   evolved   schemas [0..5], current 5, snapshots under 0, 4 and 5.
--             expire_snapshots(older_than => 2099, retain_last => 2, clean_expired_metadata => true)
--             0 0 0 0 1 0 — v10: schemas [4, 5]
--   promoted  schemas [0..5], current 5, snapshots under 0, 4, 5, 5, 5.
--             expire_snapshots(older_than => 2099, retain_last => 1, clean_expired_metadata => true)
--             3 0 0 4 4 0 — v12: schemas [5]
--   a second copy of evolved under clean_expired_metadata => false, retain_last => 1: schemas
--             [0..5] all kept with one snapshot retained (the file deletion then failed on a
--             manifest list of the first copy — the copy's metadata records /wh/default/evolved
--             paths, so a copy must be mounted under the table's own name).
--
-- Traps: `.snapshots` has no schema_id column (the snapshot's schema-id is in metadata.json
-- alone); and a copy under another name resolves its manifest lists by the recorded absolute
-- path, into the original's directory.
--
-- To reproduce:
--
--   WH=$(mktemp -d); mkdir -p $WH/wh/default
--   for t in respec evolved promoted; do cp -R example/iceberg/default/$t $WH/wh/default/$t; done
--   find $WH/wh -name '.*.crc' -delete
--   JAR=~/code/spark-kit/lakelab/.cache/iceberg-spark-runtime-3.5_2.12-1.10.0.jar
--   (the spark.conf heredoc of orph.scala into $WH/spark.conf, plus `spark.sql.session.timeZone UTC`)
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     -v "$JAR:/opt/iceberg-1.10.jar:ro" tabulario/spark-iceberg:latest -c \
--     "rm /opt/spark/jars/iceberg-spark-runtime-3.5_2.12-1.8.1.jar /opt/spark/jars/iceberg-*-bundle-1.8.1.jar; \
--      S='/opt/spark/bin/spark-sql --master local[1] --jars /opt/iceberg-1.10.jar --properties-file /tmp/spark.conf'; \
--      \$S -e \"CALL lens.system.expire_snapshots(table => 'default.evolved', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 2, clean_expired_metadata => true)\""

CALL lens.system.rewrite_data_files(table => 'default.respec', options => map('rewrite-all', 'true'));
INSERT INTO lens.default.respec VALUES (99, 'zulu', DATE '2024-05-01');
SELECT partition_spec_id, count(*) FROM lens.default.respec.all_manifests GROUP BY partition_spec_id ORDER BY partition_spec_id;
SELECT partition_spec_id, count(*) FROM lens.default.respec.manifests GROUP BY partition_spec_id ORDER BY partition_spec_id;
CALL lens.system.expire_snapshots(table => 'default.respec', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 1, clean_expired_metadata => true);
SELECT * FROM lens.default.respec ORDER BY id;
CALL lens.system.expire_snapshots(table => 'default.evolved', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 2, clean_expired_metadata => true);
CALL lens.system.expire_snapshots(table => 'default.promoted', older_than => TIMESTAMP '2099-01-01 00:00:00', retain_last => 1, clean_expired_metadata => true);
SELECT * FROM lens.default.promoted ORDER BY id;
