-- rollback_to_snapshot, set_current_snapshot and rollback_to_timestamp on Iceberg, run against
-- copies of checked-in tables — the oracle for model/IcebergRollbackPlan.kt beside the resets
-- the fixtures' own metadata logs record (sweep v6 → v7, rolled v5 → v6).
--
-- SetSnapshotOperation (1.8.1): rollback_to_snapshot requires the target to be an ancestor of
-- the current snapshot; set_current_snapshot takes any snapshot the table holds;
-- rollback_to_timestamp picks the newest current ancestor whose timestamp is strictly before
-- the time and refuses when there is none. None writes a snapshot — the commit is a
-- metadata.json with main and current-snapshot-id moved and a snapshot-log entry added — and
-- nothing is deleted: the commits main moves past wait for an expiry.
--
-- Run (2026-09-15) with Spark 3.5.5 (the tabulario/spark-iceberg image, Iceberg 1.8.1) on copies
-- of example/iceberg/default/sweep, branched and rolled mounted at /wh/default/<name>, the
-- spark.conf of orph.scala plus spark.sql.session.timeZone UTC; each statement its own
-- spark-sql -e, since the CLI stops at the first error. sweep's current line is
-- 1593994005910419243 → 2680708796143659910 → 6454228146597789500 → 5727754623681412775
-- (commits 5, 3, 2, 1; commit 4, 3500867974852930453, was rolled back at v7); branched's main
-- is 8788892783725052840 with audit at 1183816113347240589 off its line; rolled's main is
-- 9055605182434980539 with the abandoned 8241983636156380084 off its line.
--
--   rollback_to_snapshot(sweep, 3500867974852930453)          ValidationException: Cannot roll back to snapshot, not an ancestor of the current state: 3500867974852930453
--   rollback_to_timestamp(sweep, '2026-09-13 15:26:23.518')   1593994005910419243  6454228146597789500   (a millisecond after commit 2's 1789313183517)
--   rollback_to_timestamp(sweep, '2026-09-13 15:26:23.295')   IllegalArgumentException: Cannot roll back, no valid snapshot older than: 1789313183295   (commit 1's own moment; strictly before)
--   sweep.history afterwards                                  commits 3, 4, 5 is_current_ancestor false; a seventh entry for 6454228146597789500
--   rollback_to_snapshot(branched, 1183816113347240589)       ValidationException: Cannot roll back to snapshot, not an ancestor of the current state: 1183816113347240589
--   set_current_snapshot(branched, 1183816113347240589)       8788892783725052840  1183816113347240589
--   branched.refs afterwards                                  main BRANCH 1183816113347240589; v1, prod, release and audit unchanged
--   rollback_to_snapshot(branched, 1466525117601214788)       1183816113347240589  1466525117601214788   (v1's snapshot, an ancestor of audit's tip)
--   rollback_to_snapshot(rolled, 8241983636156380084)         ValidationException: Cannot roll back to snapshot, not an ancestor of the current state: 8241983636156380084
--   rollback_to_snapshot(rolled, 7121122354710552183)         9055605182434980539  7121122354710552183
--
-- The unix_millis check that settled the literal: SELECT current_timezone(),
-- unix_millis(TIMESTAMP '2026-09-13 15:26:23.518') → UTC, 1789313183518.

CALL lens.system.rollback_to_snapshot(table => 'default.sweep', snapshot_id => 3500867974852930453);
CALL lens.system.rollback_to_timestamp(table => 'default.sweep', timestamp => TIMESTAMP '2026-09-13 15:26:23.518');
CALL lens.system.rollback_to_timestamp(table => 'default.sweep', timestamp => TIMESTAMP '2026-09-13 15:26:23.295');
SELECT snapshot_id, parent_id, is_current_ancestor FROM lens.default.sweep.history;
CALL lens.system.rollback_to_snapshot(table => 'default.branched', snapshot_id => 1183816113347240589);
CALL lens.system.set_current_snapshot(table => 'default.branched', snapshot_id => 1183816113347240589);
SELECT name, type, snapshot_id FROM lens.default.branched.refs;
CALL lens.system.rollback_to_snapshot(table => 'default.branched', snapshot_id => 1466525117601214788);
CALL lens.system.rollback_to_snapshot(table => 'default.rolled', snapshot_id => 8241983636156380084);
CALL lens.system.rollback_to_snapshot(table => 'default.rolled', snapshot_id => 7121122354710552183);
