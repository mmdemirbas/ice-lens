-- expire_snapshots(snapshot_ids => array(id)) — what expiring one snapshot by id does, on copies
-- of three checked-in tables: the oracle for ExpiryOptions.snapshotIds in model/ExpiryPlan.kt and
-- the procedure's file cleanup in model/ExpiryFilePlan.kt, recorded rather than checked in.
-- ExpireByIdFixtureTest holds the plan on the checked-in tables to the refusals, the six-figure
-- result rows, the file names that went and the `.history` that remained.
--
-- The rules, read at Iceberg 1.8.1:
--   - RemoveSnapshots.expireSnapshotId: a listed id a *surviving* ref names — after the ref-age
--     rule — is refused with `Cannot expire <id>. Still referenced by refs: [...]` (the ref order is
--     a HashMap's; compare as a set) and nothing at all is removed; otherwise the id goes whatever
--     its age or its place under a branch's min-snapshots-to-keep, with the age rules running
--     beside it (older_than => 2000 here, so they remove nothing). The children keep a
--     parent-snapshot-id the table no longer holds.
--   - TableMetadata.Builder.updateSnapshotLog: the removed entry goes from the snapshot log, and
--     every entry before it — a log of [(t1, s1), (t3, s3)] would read s3 as current between t2
--     and t3. So `mor`'s `.history` holds two entries of six afterwards, and a TIMESTAMP AS OF
--     before the child's commit no longer resolves, while VERSION AS OF still reads the retained
--     snapshots before it.
--   - ExpireSnapshotsSparkAction.expireFiles: the procedure commits with cleanExpiredFiles(false)
--     and deletes fileDS(original, expiredIds) EXCEPT fileDS(updated) — every manifest list,
--     manifest, live content file (BaseSparkAction.ReadManifest iterates the live entries) and
--     statistics file the expired snapshots reach that no retained snapshot reaches. That is a
--     reachability diff whatever the ref count. RemoveSnapshots.cleanExpiredSnapshots — the core
--     API from Java, Flink or Trino — picks IncrementalFileCleanup at exactly one ref, which frees
--     a file only when an expired commit on the live line removed it or an expired commit off the
--     live line added it. `mor` separates the two: the overwrite's data file was added by an
--     ancestor's manifest and removed by the retained compaction's, so the incremental rule leaves
--     it on disk and the procedure freed it (1 0 0 1 1 0). `rolled` and the sweep/swept pair agree
--     under both.
--
-- Run (2026-09-15) on tabulario/spark-iceberg (Spark 3.5.5, Iceberg 1.8.1), each statement its
-- own `spark-sql -e` under the spark.conf of orph.scala plus spark.sql.session.timeZone UTC — the
-- same setup as rollback.sql — with the copies mounted at /wh/default/<same name>:
--
--   branched  array(1466525117601214788)  refused: [v1]
--             array(1183816113347240589)  refused: [audit]
--             array(8788892783725052840)  refused: [prod, main]
--             array(6979025444437793121)  0 0 0 0 1 0 — the first commit, held by nothing but its
--                                         place under main's tip: its manifest list alone, the
--                                         manifest carried by every later list; `.snapshots` lists
--                                         the other four with 1466525117601214788's parent_id still
--                                         naming it; `.history` 1466…, 3698…, 8788…, all current ancestors
--   mor       array(6495533870975959056)  1 0 0 1 1 0 — the overwrite in the middle of main:
--                                         data/00000-7-a310efbe-6c53-43c4-813b-0dcce2b29751-00001.parquet,
--                                         metadata/2d4bf52e-9996-4609-8d91-c957af962214-m0.avro,
--                                         metadata/snap-6495533870975959056-1-2d4bf52e-….avro gone;
--                                         `.snapshots` five with the replace's parent_id still 6495…;
--                                         `.history` 4129372135852686703 and 6710179397072758000 only;
--                                         count(*) 5; VERSION AS OF 4216642347117264083 reads 1 3 4 5 6 7
--   rolled    array(8241983636156380084)  1 0 0 1 1 0 — the abandoned commit on no ref:
--                                         data/00000-2-80b7a28d-e0b5-4714-a449-a065002c2a65-0-00001.parquet,
--                                         its manifest and its list gone; `.snapshots` three
--
-- Result columns: deleted_data_files_count, deleted_position_delete_files_count,
-- deleted_equality_delete_files_count, deleted_manifest_files_count, deleted_manifest_lists_count,
-- deleted_statistics_files_count.
--
-- To reproduce:
--
--   WH=$(mktemp -d); mkdir -p $WH/wh/default
--   for t in mor branched rolled; do cp -R example/iceberg/default/$t $WH/wh/default/$t; done
--   find $WH/wh -name '.*.crc' -delete
--   (the spark.conf heredoc of orph.scala into $WH/spark.conf, plus `spark.sql.session.timeZone UTC`)
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg -c 'S="/opt/spark/bin/spark-sql --master local[1] --properties-file /tmp/spark.conf"; \
--       $S -e "CALL lens.system.expire_snapshots(table => '"'"'default.mor'"'"', snapshot_ids => array(6495533870975959056), older_than => TIMESTAMP '"'"'2000-01-01 00:00:00'"'"')"; \
--       $S -e "SELECT snapshot_id, parent_id, is_current_ancestor FROM lens.default.mor.history ORDER BY made_current_at"'

CALL lens.system.expire_snapshots(table => 'default.branched', snapshot_ids => array(1466525117601214788), older_than => TIMESTAMP '2000-01-01 00:00:00');
CALL lens.system.expire_snapshots(table => 'default.branched', snapshot_ids => array(1183816113347240589), older_than => TIMESTAMP '2000-01-01 00:00:00');
CALL lens.system.expire_snapshots(table => 'default.branched', snapshot_ids => array(8788892783725052840), older_than => TIMESTAMP '2000-01-01 00:00:00');
CALL lens.system.expire_snapshots(table => 'default.branched', snapshot_ids => array(6979025444437793121), older_than => TIMESTAMP '2000-01-01 00:00:00');
SELECT snapshot_id, parent_id, operation FROM lens.default.branched.snapshots ORDER BY committed_at;
SELECT * FROM lens.default.branched.refs;
SELECT snapshot_id, parent_id, is_current_ancestor FROM lens.default.branched.history ORDER BY made_current_at;
CALL lens.system.expire_snapshots(table => 'default.mor', snapshot_ids => array(6495533870975959056), older_than => TIMESTAMP '2000-01-01 00:00:00');
SELECT snapshot_id, parent_id, operation FROM lens.default.mor.snapshots ORDER BY committed_at;
SELECT snapshot_id, parent_id, is_current_ancestor FROM lens.default.mor.history ORDER BY made_current_at;
SELECT count(*) FROM lens.default.mor;
SELECT * FROM lens.default.mor VERSION AS OF 4216642347117264083 ORDER BY id;
CALL lens.system.expire_snapshots(table => 'default.rolled', snapshot_ids => array(8241983636156380084), older_than => TIMESTAMP '2000-01-01 00:00:00');
SELECT snapshot_id, parent_id, operation FROM lens.default.rolled.snapshots ORDER BY committed_at;
