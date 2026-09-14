// Regenerates example/iceberg/default/orph and orpha — one Iceberg table kept twice, before and
// after `remove_orphan_files`, so the files the procedure deleted are the oracle for what this
// app plans it would delete (the sweep/swept shape). The table is built to hold every kind of
// file the procedure and a walk of the directory disagree about:
//
//   - `write.metadata.previous-versions-max = 2`, so after six metadata versions the current
//     one's `metadata-log` names v4 and v5 only, and v1..v3 sit on disk named by nothing the
//     procedure reads — `ReachableFileUtil.metadataFileLocations(table, recursive = false)` is
//     the current file and its log, at 1.8.1 — while every one of them is a metadata file this
//     app reads and the walk counts as referenced;
//   - an expiry run with `cleanExpiredFiles(false)` from the Java API (the Spark procedure
//     cannot), which drops snapshots 1..3 from the metadata and deletes none of their files:
//     three manifest lists, the first commit's manifest, and its data file — which the
//     copy-on-write DELETE before it had already turned into a DELETED entry, so no manifest
//     the current metadata lists holds it live (`ReadManifest` in BaseSparkAction reads
//     `ManifestReader.iterator()`, live entries only);
//   - three stray files: `data/stray.txt` and `metadata/stray.txt`, which the procedure lists
//     and deletes, and `data/_stray`, which `HiddenPathFilter` hides from it (a leading `_` or
//     `.`) and which this app's walk, hiding `.` only, reports.
//
// Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image. Every
// statement is Spark SQL; this file drives it because the expiry option and the copy are not.
// `spark.testing = true` is what lets the procedure take an `older_than` inside the last 24
// hours (`RemoveOrphanFilesProcedure.validateInterval` is skipped under it) — without it the
// call is refused: "Cannot remove orphan files with an interval less than 24 hours".
//
// Statements, in order:
//
//   1  CREATE   format-version 2, previous-versions-max 2          v1
//   2..4  three INSERTs, one file each                              v2..v4, snapshots 1..3
//   5  DELETE   WHERE id = 1 — copy-on-write, the whole first file  v5, snapshot 4: f1 DELETED in a
//              removed                                             filtered manifest, m1 dropped
//   6  expireSnapshots().expireOlderThan(snapshot 4's time)        v6: snapshots 1..3 gone from
//              .cleanExpiredFiles(false).commit()                  the metadata, every file kept
//   7  three stray files written
//   copy → orph
//   8  CALL remove_orphan_files(dry_run => true)                    bare: older_than = 3 days ago,
//                                                                   nothing listed (all files fresh)
//   9  CALL remove_orphan_files(older_than => now + 1 min)          the deletion; rows printed
//
// Observed (2026-09-14): step 8 listed nothing; step 9 returned ten rows, and orpha lacks
// exactly those ten of orph's twenty-one files — v1, v2 and v3.metadata.json, the three
// manifest lists of snapshots 1..3, the first commit's manifest (286df5e8-…-m0.avro), its data
// file (00000-0-633d76fe-…-00001.parquet), data/stray.txt and metadata/stray.txt. data/_stray is
// still there, and the table reads `2 bravo / 3 charlie`.
//
// To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
//
//   WH=$(mktemp -d)
//   cat > "$WH/spark.conf" <<'EOF2'
//   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
//   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
//   spark.sql.catalog.lens.type       hadoop
//   spark.sql.catalog.lens.warehouse  /wh
//   spark.sql.defaultCatalog          lens
//   spark.sql.catalogImplementation   in-memory
//   spark.testing                     true
//   spark.ui.enabled                  false
//   spark.eventLog.enabled            false
//   EOF2
//   mkdir -p "$WH/wh"
//   docker run --rm --entrypoint bash \
//     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/orph.scala:/tmp/orph.scala:ro" \
//     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
//     tabulario/spark-iceberg:latest -c \
//     "/opt/spark/bin/spark-shell --master 'local[1]' --properties-file /tmp/spark.conf -I /tmp/orph.scala <<< 'System.exit(0)'"
//   for t in orph orpha; do rm -rf example/iceberg/default/$t && cp -R "$WH/wh/default/$t" example/iceberg/default/$t; done
//   find example/iceberg/default/orph example/iceberg/default/orpha -name '.*.crc' -delete
//
// Then rerun docs/fixtures/iceberg-scan-plans.scala, whose delete-pairing half covers every table.

import org.apache.iceberg.spark.Spark3Util
import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

val t = "lens.default.orpha"
val root = Paths.get("/wh/default/orpha")

spark.sql(s"CREATE TABLE $t (id INT, name STRING) USING iceberg TBLPROPERTIES ('format-version' = '2', 'write.metadata.previous-versions-max' = '2')")
spark.sql(s"INSERT INTO $t VALUES (1, 'alpha')")
spark.sql(s"INSERT INTO $t VALUES (2, 'bravo')")
spark.sql(s"INSERT INTO $t VALUES (3, 'charlie')")
spark.sql(s"DELETE FROM $t WHERE id = 1")

val table = Spark3Util.loadIcebergTable(spark, t)
val current = table.currentSnapshot()
println(s"-- current snapshot ${current.snapshotId()} (${current.operation()}) at ${current.timestampMillis()}")
table.expireSnapshots().expireOlderThan(current.timestampMillis()).cleanExpiredFiles(false).commit()
table.refresh()
println("-- snapshots after expiry: " + table.snapshots().asScala.map(_.snapshotId()).mkString(", "))
println("-- metadata log entries after expiry:")
spark.sql(s"SELECT file, latest_snapshot_id FROM $t.metadata_log_entries").show(100, false)

Files.write(root.resolve("data").resolve("stray.txt"), "not a data file".getBytes)
Files.write(root.resolve("data").resolve("_stray"), "hidden to remove_orphan_files".getBytes)
Files.write(root.resolve("metadata").resolve("stray.txt"), "not a metadata file".getBytes)

org.apache.commons.io.FileUtils.copyDirectory(root.toFile, Paths.get("/wh/default/orph").toFile)
println("-- copied to orph")

println("-- step 8: bare dry run (older_than defaults to 3 days ago)")
spark.sql("CALL lens.system.remove_orphan_files(table => 'default.orpha', dry_run => true)").show(100, false)

val olderThan = new java.sql.Timestamp(System.currentTimeMillis() + 60000L).toString
println(s"-- step 9: older_than => TIMESTAMP '$olderThan'")
spark.sql(s"CALL lens.system.remove_orphan_files(table => 'default.orpha', older_than => TIMESTAMP '$olderThan')").show(100, false)
spark.sql(s"SELECT * FROM $t ORDER BY id").show()
