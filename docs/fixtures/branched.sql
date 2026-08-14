-- Regenerates example/iceberg/default/branched — the branch-and-tag fixture.
--
-- Lineage edges and refs exist, and until this fixture there was nothing with more than one ref
-- to point them at: every other table has `main` and nothing else, so a snapshot carrying two
-- refs, a tag, or a commit that is NOT on main were all untested shapes.
--
-- What it produces:
--
--   * `main` with three commits.
--   * `audit`, a branch forked from the second commit and then committed to, so the history
--     genuinely forks — two snapshots share a parent, which the vertical ordering has no way to
--     express and only the lineage edges show.
--   * `v1` and `release`, two tags on different commits, so a tag is distinguishable from a
--     branch and one snapshot ends up carrying more than one ref.
--
-- WAP and branch writes need `spark.wap.branch` or the `branch_` suffix; the VERSION AS OF /
-- `branch_audit` write path below is the one that works in Spark 3.5 without session config.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
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
--     -v "$WH/wh:/wh" -v "$PWD/docs/fixtures/branched.sql:/tmp/branched.sql:ro" \
--     -v "$WH/spark.conf:/tmp/spark.conf:ro" \
--     tabulario/spark-iceberg:latest -c \
--     "/opt/spark/bin/spark-sql --master 'local[1]' --properties-file /tmp/spark.conf -f /tmp/branched.sql"
--   rm -rf example/iceberg/default/branched
--   cp -R "$WH/wh/default/branched" example/iceberg/default/branched
--   find example/iceberg/default/branched -name '.*.crc' -delete

CREATE TABLE lens.default.branched (
  id   INT,
  name STRING
) USING iceberg
TBLPROPERTIES ('format-version' = '2');

-- commit 1 on main
INSERT INTO lens.default.branched VALUES (1, 'alpha'), (2, 'bravo');

-- commit 2 on main — the fork point
INSERT INTO lens.default.branched VALUES (3, 'charlie');
ALTER TABLE lens.default.branched CREATE TAG `v1`;
ALTER TABLE lens.default.branched CREATE BRANCH `audit`;

-- commit 3 on main, which the audit branch does not have
INSERT INTO lens.default.branched VALUES (4, 'delta');
ALTER TABLE lens.default.branched CREATE TAG `release`;

-- a commit on audit only, so two snapshots share commit 2 as their parent
INSERT INTO lens.default.branched.branch_audit VALUES (5, 'echo-audit-only');
