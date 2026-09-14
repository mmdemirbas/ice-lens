-- fast_forward on both formats, run against copies of checked-in tables — the oracle for
-- model/FastForwardPlan.kt. The two procedures share a name and not an operation.
--
-- Iceberg (UpdateSnapshotReferencesOperation.replaceBranch with fastForward = true, 1.8.1):
-- `CALL fast_forward(table, branch, to)` moves one ref. The branch's tip must be an ancestor of
-- `to`'s snapshot, or the call fails; a branch the table lacks is created at `to`'s snapshot;
-- the same snapshot on both is nothing to do. Nothing is deleted and no snapshot is written.
--
-- Paimon (FileSystemBranchManager.fastForward, release-1.3.1): `CALL sys.fast_forward(table,
-- branch)` replaces main from the branch's earliest snapshot id on — main's snapshot files at or
-- above that id, schema files at or above that snapshot's schema id, and tags at or above the id
-- are deleted, and the branch's snapshot/, schema/ and tag/ are copied over main's. Main's own
-- commits from that id on are dropped, not merged, and the files they wrote are left named by
-- nothing.
--
-- Run (2026-09-15) with Spark 3.5.5 (the tabulario/spark-iceberg image, Iceberg 1.8.1) and the
-- Paimon Spark 3.5 runtime jar, version 1.3, on copies of example/iceberg/default/sweepb and
-- branched mounted at /wh/default/<name> (their manifests record /wh/default/<name>/…), and of
-- example/paimon/db.db/br mounted as /wh/db.db/brf; each statement its own spark-sql -e, since
-- the CLI stops at the first error:
--
--   WH=$(mktemp -d); mkdir -p "$WH/wh/default" "$WH/pwh/db.db"
--   cp -R example/iceberg/default/sweepb "$WH/wh/default/sweepb"
--   cp -R example/iceberg/default/branched "$WH/wh/default/branched"
--   cp -R example/paimon/db.db/br "$WH/pwh/db.db/brf"
--   # Iceberg: spark.conf as in orph.scala (catalog lens, warehouse /wh, spark.testing true)
--   docker run --rm --entrypoint bash -v "$WH/wh:/wh" -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c "for each statement below: /opt/spark/bin/spark-sql \
--       --master local[1] --properties-file /tmp/spark.conf -e '<statement>'"
--   # Paimon: JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash -v "$WH/pwh:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     tabulario/spark-iceberg:latest -c "for each statement below: /opt/spark/bin/spark-sql \
--       --master local[1] --jars /opt/paimon-spark.jar <the confs of paimon-br.sql> -e '<statement>'"
--   rm -rf example/paimon/db.db/brf && cp -R "$WH/pwh/db.db/brf" example/paimon/db.db/brf
--   find example/paimon/db.db/brf -name '.*.crc' -delete
--
-- Observed, Iceberg. sweepb: main at 1069833806646654058 (five commits), dev at
-- 8337608489946395297 (its first):
--
--   fast_forward(sweepb, main, dev)      IllegalArgumentException: Cannot fast-forward: main is not an ancestor of dev
--   fast_forward(sweepb, dev, main)      dev  8337608489946395297  1069833806646654058
--   fast_forward(sweepb, dev, main)      dev  1069833806646654058  1069833806646654058   (nothing to do)
--   fast_forward(sweepb, feature, main)  feature  NULL  1069833806646654058              (created)
--   sweepb.refs afterwards               dev, feature and main all BRANCH at 1069833806646654058
--
-- branched: main and prod (a tag) at 8788892783725052840, audit at 1183816113347240589, forked
-- from v1's snapshot 1466525117601214788 while main committed twice more:
--
--   fast_forward(branched, main, audit)  IllegalArgumentException: Cannot fast-forward: main is not an ancestor of audit
--   fast_forward(branched, audit, main)  IllegalArgumentException: Cannot fast-forward: audit is not an ancestor of main
--   fast_forward(branched, audit, v1)    IllegalArgumentException: Cannot fast-forward: audit is not an ancestor of v1
--   fast_forward(branched, main, prod)   main  8788892783725052840  8788892783725052840   (nothing to do)
--   branched.refs afterwards             unchanged
--
-- Observed, Paimon. br: main snapshots 1..3 (k 1,2,3 / 4 / 6), tag base on 1, dev created from
-- the tag with snapshot 1 copied and snapshot 2 its own (k 5), empty with no snapshot:
--
--   sys.fast_forward(brf, branch => 'main')   IllegalArgumentException: Branch name 'main' do not use in fast-forward.
--   sys.fast_forward(brf, branch => 'empty')  RuntimeException: Cannot fast forward branch empty, because it does not have snapshot.
--   sys.fast_forward(brf, branch => 'dev')    true; SELECT * ORDER BY k → 1 a / 2 b / 3 c / 5 e
--
-- brf afterwards: snapshot/ holds 1 and 2 — 1 byte-identical to main's, 2 dev's snapshot-2 —
-- and no 3; LATEST 2; schema/schema-0 and tag/tag-base copied from the branch; branch/ still
-- holds branch-dev and branch-empty; bucket-0 still holds all four data files, so main's k = 4
-- and k = 6 files with their two manifests and four manifest lists are named by nothing, which
-- is what the orphan check reports and what FastForwardFixtureTest holds the plan's leftovers to.

-- Iceberg, on default.sweepb and default.branched
CALL lens.system.fast_forward(table => 'default.sweepb', branch => 'main', to => 'dev');
CALL lens.system.fast_forward(table => 'default.sweepb', branch => 'dev', to => 'main');
CALL lens.system.fast_forward(table => 'default.sweepb', branch => 'dev', to => 'main');
CALL lens.system.fast_forward(table => 'default.sweepb', branch => 'feature', to => 'main');
SELECT name, type, snapshot_id FROM lens.default.sweepb.refs;
CALL lens.system.fast_forward(table => 'default.branched', branch => 'main', to => 'audit');
CALL lens.system.fast_forward(table => 'default.branched', branch => 'audit', to => 'main');
CALL lens.system.fast_forward(table => 'default.branched', branch => 'audit', to => 'v1');
CALL lens.system.fast_forward(table => 'default.branched', branch => 'main', to => 'prod');
SELECT name, type, snapshot_id FROM lens.default.branched.refs;

-- Paimon, on db.brf (a copy of br)
CALL sys.fast_forward(table => 'db.brf', branch => 'main');
CALL sys.fast_forward(table => 'db.brf', branch => 'empty');
CALL sys.fast_forward(table => 'db.brf', branch => 'dev');
SELECT * FROM db.brf ORDER BY k;
