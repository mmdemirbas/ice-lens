// Regenerates example/iceberg/default/nested — a branch forked from another branch, which is
// the shape docs/fixtures/branched3.sql leaves out: `CREATE BRANCH` takes the table's current
// snapshot and the `AS OF VERSION` form needs a literal snapshot id, so the id is read back from
// the `.refs` metadata table here and interpolated. Every statement is still Spark SQL; this
// file only drives it.
//
// Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
//
// The shape, oldest first. `b2` is cut from `b1`'s first commit and commits BEFORE `b1` does
// again — on write time alone `b2`'s commit is the "first child" of c3, takes the column `b1`
// was drawn in, and `b1`'s own line moves sideways under a column labelled `b2`. Which branch
// the fork commit belongs to is decided from the metadata log instead: `b1` is listed by an
// earlier metadata version than `b2` (v4 against v6), so it is the older line and keeps its
// column through the fork.
//
//   c1  main                                  v2
//   c2  main        <- b1 forks here          v3; CREATE BRANCH b1 writes v4
//   c3  b1          <- b2 forks here          v5; CREATE BRANCH b2 AS OF VERSION c3 writes v6
//   c4  b2                                    v7
//   c5  b1                                    v8
//   c6  main                                  v9
//   c7  b2                                    v10
//
// Expected columns: main 0 (c1, c2, c6), b1 1 (c3, c5), b2 2 (c4, c7).
//
// To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
//
//   WH=$(mktemp -d)
//   cat > "$WH/spark.conf" <<'EOF'
//   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
//   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
//   spark.sql.catalog.lens.type       hadoop
//   spark.sql.catalog.lens.warehouse  /wh
//   spark.sql.defaultCatalog          lens
//   spark.sql.catalogImplementation   in-memory
//   spark.ui.enabled                  false
//   spark.eventLog.enabled            false
//   EOF
//   mkdir -p "$WH/wh"
//   docker run --rm --entrypoint bash \
//     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/nested.scala:/tmp/nested.scala:ro" \
//     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
//     tabulario/spark-iceberg:latest -c \
//     "/opt/spark/bin/spark-shell --master 'local[2]' --properties-file /tmp/spark.conf -I /tmp/nested.scala <<< 'System.exit(0)'"
//   rm -rf example/iceberg/default/nested
//   cp -R "$WH/wh/default/nested" example/iceberg/default/nested
//   find example/iceberg/default/nested -name '.*.crc' -delete

val t = "lens.default.nested"

spark.sql(s"CREATE TABLE $t (id INT, name STRING) USING iceberg TBLPROPERTIES ('format-version' = '2')")

// c1, c2 on main
spark.sql(s"INSERT INTO $t VALUES (1, 'alpha')")
spark.sql(s"INSERT INTO $t VALUES (2, 'bravo')")

// b1 forks at c2, and commits c3
spark.sql(s"ALTER TABLE $t CREATE BRANCH b1")
spark.sql(s"INSERT INTO $t.branch_b1 VALUES (3, 'charlie')")

// b2 forks from b1's c3 — the id read back, since CREATE BRANCH alone would take main's tip
val c3 = spark.sql(s"SELECT snapshot_id FROM $t.refs WHERE name = 'b1'").first().getLong(0)
spark.sql(s"ALTER TABLE $t CREATE BRANCH b2 AS OF VERSION $c3")

// c4 on b2 first, then c5 on b1, c6 on main, c7 on b2
spark.sql(s"INSERT INTO $t.branch_b2 VALUES (4, 'delta')")
spark.sql(s"INSERT INTO $t.branch_b1 VALUES (5, 'echo')")
spark.sql(s"INSERT INTO $t VALUES (6, 'foxtrot')")
spark.sql(s"INSERT INTO $t.branch_b2 VALUES (7, 'golf')")

println("-- refs")
spark.sql(s"SELECT name, type, snapshot_id FROM $t.refs ORDER BY name").show(false)
println("-- snapshots")
spark.sql(s"SELECT snapshot_id, parent_id, committed_at FROM $t.snapshots ORDER BY committed_at").show(false)
