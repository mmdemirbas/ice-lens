-- cherrypick_snapshot on Iceberg, run against copies of checked-in tables — the oracle for
-- model/CherryPickPlan.kt. The procedure is the publish half of write-audit-publish
-- (publish_changes is the same operation looked up by wap id), and the runs settle which of its
-- outcomes a snapshot gets from the metadata alone.
--
-- CherryPickOperation (1.8.1), in the order it decides: an unknown id is refused; an append
-- whose staged wap.id a current ancestor already staged or published is a
-- DuplicateWAPCommitException (WapUtil.validateWapPublish); at apply, a snapshot whose parent is
-- the current snapshot is fast-forwarded — main is pointed at it, no new snapshot, whatever its
-- operation — and otherwise an append is published as a new commit on main carrying its added
-- data files with source-snapshot-id naming it (and published-wap-id its wap id), refused as
-- CherrypickAncestorCommitException when it is already an ancestor or some ancestor's
-- source-snapshot-id already names it; an overwrite written by replace-partitions is published
-- with its deletes provided its parent is a current ancestor (and no commit since touched its
-- partitions, checked against the manifests at commit); anything else — a delete, a plain
-- overwrite, a replace — is a ValidationException unless it fast-forwards. The procedure
-- returns (source_snapshot_id, current_snapshot_id), so a fast-forward prints the same id twice.
--
-- Run (2026-09-15) with Spark 3.5.5 (the tabulario/spark-iceberg image, Iceberg 1.8.1) on copies
-- of example/iceberg/default/wap, rolled, branched and mor mounted at /wh/default/<name>, the
-- spark.conf of orph.scala plus spark.sql.session.timeZone UTC; each statement its own
-- spark-sql -e, since the CLI stops at the first error.
--
--   cherrypick_snapshot(wap, 4204024477256586588)        DuplicateWAPCommitException: Duplicate request to cherry pick wap id that was published already: audit-1
--                                                        (the staged snapshot; its publish, 8273853679921106935, carries published-wap-id audit-1 and is an ancestor)
--   cherrypick_snapshot(rolled, 8241983636156380084)     8241983636156380084  8545125033544548937
--   rolled.snapshots afterwards                          8545125033544548937, parent 9055605182434980539 (main's tip), append, source-snapshot-id 8241983636156380084, added-data-files 1
--   cherrypick_snapshot(rolled, 8241983636156380084)     CherrypickAncestorCommitException: Cannot cherrypick snapshot 8241983636156380084: already picked to create ancestor 8545125033544548937
--   cherrypick_snapshot(branched, 1466525117601214788)   CherrypickAncestorCommitException: Cannot cherrypick snapshot 1466525117601214788: already an ancestor   (v1's snapshot)
--   set_current_snapshot(branched, 1466525117601214788)  8788892783725052840  1466525117601214788
--   cherrypick_snapshot(branched, 3698023222460817889)   3698023222460817889  3698023222460817889   (release's snapshot, a child of the now-current one: fast-forwarded, no new snapshot)
--   SELECT count(*) FROM branched.snapshots              5   (unchanged)
--   cherrypick_snapshot(branched, 1183816113347240589)   1183816113347240589  8598227028672999892   (audit's tip, whose parent is no longer current: published)
--   branched.snapshots afterwards                        8598227028672999892, parent 3698023222460817889, append, source-snapshot-id 1183816113347240589
--   cherrypick_snapshot(mor, 4216642347117264083)        ValidationException: Cannot cherry-pick snapshot 4216642347117264083: not append, dynamic overwrite, or fast-forward   (a delete)
--   cherrypick_snapshot(mor, 4747162675114470207)        CherrypickAncestorCommitException: Cannot cherrypick snapshot 4747162675114470207: already an ancestor   (an append on the line)
--
-- What the runs settled beyond the source: a fast-forward is decided by the parent alone, so
-- after set_current_snapshot both children of the target fast-forward and the first to be
-- picked moves main and turns the second into a publish; and a published commit's files are
-- the picked snapshot's by reference — one added data file, nothing rewritten.

CALL lens.system.cherrypick_snapshot(table => 'default.wap', snapshot_id => 4204024477256586588);
CALL lens.system.cherrypick_snapshot(table => 'default.rolled', snapshot_id => 8241983636156380084);
SELECT snapshot_id, parent_id, operation, summary['source-snapshot-id'], summary['added-data-files'] FROM lens.default.rolled.snapshots ORDER BY committed_at;
CALL lens.system.cherrypick_snapshot(table => 'default.rolled', snapshot_id => 8241983636156380084);
CALL lens.system.cherrypick_snapshot(table => 'default.branched', snapshot_id => 1466525117601214788);
CALL lens.system.set_current_snapshot(table => 'default.branched', snapshot_id => 1466525117601214788);
CALL lens.system.cherrypick_snapshot(table => 'default.branched', snapshot_id => 3698023222460817889);
SELECT count(*) FROM lens.default.branched.snapshots;
CALL lens.system.cherrypick_snapshot(table => 'default.branched', snapshot_id => 1183816113347240589);
SELECT snapshot_id, parent_id, operation, summary['source-snapshot-id'] FROM lens.default.branched.snapshots ORDER BY committed_at;
CALL lens.system.cherrypick_snapshot(table => 'default.mor', snapshot_id => 4216642347117264083);
CALL lens.system.cherrypick_snapshot(table => 'default.mor', snapshot_id => 4747162675114470207);
