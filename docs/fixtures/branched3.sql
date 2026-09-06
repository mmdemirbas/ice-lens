-- Regenerates example/iceberg/default/branched3 — three branches open at once.
--
-- Written by Spark 3.5.5 / Iceberg 1.8.1 from the tabulario/spark-iceberg image.
--
-- What it is for: `example/iceberg/default/branched` has a single fork, and the column assignment
-- in `service/SnapshotTracks.kt` stops being exercised there. Its interesting cases all need a
-- third column — a commit taking the column its parent reserved, a reservation *held* across
-- commits that belong to other lines, and a header row wide enough that two names could collide.
--
-- The shape below, oldest first. Every fork is followed by commits on other lines before the
-- forked line is committed to, which is the case a reservation exists for: the column `audit`
-- opened at c2 must still be its own when c7 arrives, four commits later.
--
--   c1  main
--   c2  main        <- audit forks here
--   c3  main        <- wip forks here
--   c4  audit
--   c5  main        <- staging forks here
--   c6  wip
--   c7  audit
--   c8  staging
--   c9  main
--
-- What it does NOT cover: a branch forked from another *branch*. `CREATE BRANCH` takes the table's
-- current snapshot, and the `AS OF VERSION` form needs a literal snapshot id that is not known
-- until the script has already run — so a branch off a branch needs a second pass and is left out
-- rather than faked.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required, and why
-- the shell must be `bash -c` rather than `bash -lc`):
--
--   WH=$(mktemp -d)
--   cat > "$WH/spark.conf" <<'EOF'
--   spark.sql.extensions              org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
--   spark.sql.catalog.lens            org.apache.iceberg.spark.SparkCatalog
--   spark.sql.catalog.lens.type       hadoop
--   spark.sql.catalog.lens.warehouse  /wh
--   spark.sql.defaultCatalog          lens
--   spark.sql.catalogImplementation   in-memory
--   spark.ui.enabled                  false
--   spark.eventLog.enabled            false
--   EOF
--   mkdir -p "$WH/wh"
--   docker run --rm --entrypoint bash \
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/branched3.sql:/tmp/branched3.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[2]' --properties-file /tmp/spark.conf -f /tmp/branched3.sql"
--   rm -rf example/iceberg/default/branched3
--   cp -R "$WH/wh/default/branched3" example/iceberg/default/branched3
--   find example/iceberg/default/branched3 -name '.*.crc' -delete

CREATE TABLE lens.default.branched3 (
  id   INT,
  name STRING
) USING iceberg
TBLPROPERTIES ('format-version' = '2');

-- c1, c2 on main
INSERT INTO lens.default.branched3 VALUES (1, 'alpha'), (2, 'bravo');
INSERT INTO lens.default.branched3 VALUES (3, 'charlie');

ALTER TABLE lens.default.branched3 CREATE BRANCH `audit`;

-- c3 on main
INSERT INTO lens.default.branched3 VALUES (4, 'delta');

ALTER TABLE lens.default.branched3 CREATE BRANCH `wip`;

-- c4 on audit, two commits after audit forked
INSERT INTO lens.default.branched3.branch_audit VALUES (5, 'echo-audit');

-- c5 on main
INSERT INTO lens.default.branched3 VALUES (6, 'foxtrot');

ALTER TABLE lens.default.branched3 CREATE BRANCH `staging`;

-- c6 on wip, three commits after wip forked
INSERT INTO lens.default.branched3.branch_wip VALUES (7, 'golf-wip');

-- c7 on audit again, so its column has been held across four other commits
INSERT INTO lens.default.branched3.branch_audit VALUES (8, 'hotel-audit');

-- c8 on staging
INSERT INTO lens.default.branched3.branch_staging VALUES (9, 'india-staging');

-- c9 on main, so main is not the last line committed to either
INSERT INTO lens.default.branched3 VALUES (10, 'juliet');

-- A tag on the main tip, so a tag and a branch name the same commit — the case that printed
-- `main  prod (tag)` over one column in `branched` and settled that a tag never names a column.
ALTER TABLE lens.default.branched3 CREATE TAG `release`;
