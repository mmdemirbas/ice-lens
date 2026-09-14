-- Regenerates example/paimon/db.db/psm, psk and psl — the three tables whose column statistics
-- are shaped by `metadata.stats-mode` and its siblings, Paimon's twin of Iceberg's
-- `write.metadata.metrics.*` (docs/fixtures/metrics.sql). `sm` covers one `fields.<col>.stats-mode
-- = none`; these cover the rest of `StatsCollectorFactories.createStatsFactories` (release-1.3.1):
--
--   fields.<name>.stats-mode                 highest priority, per column
--   a system column, or a key column under thin mode   truncate(128), whatever the table says
--   metadata.stats-keep-first-n-columns      past that many columns (those with a field mode
--                                            counted), the rest are none — opt-in, default -1
--   metadata.stats-mode.per.level            `0:none` — a key-value file's mode by its level,
--                                            `metadata.stats-mode` (truncate(16)) where unlisted
--
-- What each mode records (`SimpleColStatsCollector`): none — nothing, and under the dense store
-- (`metadata.stats-dense-store`, default true) the column is left out of `_VALUE_STATS` and named
-- nowhere in `_VALUE_STATS_COLS`; counts — the null count and no bounds; truncate(N) — the null
-- count and bounds cut to N characters, the upper one incremented; full — everything.
--
-- psm: primary key k, `metadata.stats-mode = counts`, `name` truncate(4), `note` full, `tag`
--      none then counts from the second insert on — a file's `_SCHEMA_ID` names the options it
--      was written under, since SET TBLPROPERTIES writes a new schema; `score` on the default.
-- psk: an append table of five columns with `metadata.stats-keep-first-n-columns = 2` — `c1`
--      and `c2` on the default, `c3`..`c5` none.
-- psl: primary key k with `metadata.stats-mode.per.level = 0:none` — the insert's level-0 file
--      records nothing, the full compaction's level-5 file the default.
--
-- What `$files` printed, per file — level, null counts, min and max value stats:
--   psm data-bf276868-047a…  level 0  nulls {k=0, name=0, note=1, score=0, tag=null}  min {k=null, name=alph, note=a note well past sixteen characters long, score=null, tag=null}  max {k=null, name=braw, note=short, score=null, tag=null}
--   psm data-9682c24c-f2be…  level 0  nulls {k=0, name=0, note=0, score=0, tag=0}  min {k=null, name=char, note=note four, score=null, tag=null}  max {k=null, name=chas, note=note four, score=null, tag=null}
--   psk data-4a0ef0cb-caa5…  level 0  nulls {c1=0, c2=0, c3=null, c4=null, c5=null}  min {c1=1, c2=one, c3=null, c4=null, c5=null}  max {c1=2, c2=two, c3=null, c4=null, c5=null}
--   psl data-b520f05a-eefc…  level 5  nulls {k=null, v=null}  min {k=null, v=null}  max {k=null, v=null}
--
-- psl's one file is the insert's, upgraded to level 5 by the compaction without a rewrite
-- (a lone level-0 run needs no merge), so it records level 0's nothing at level 5: the
-- per-level mode is decided by the level a file is written to, and an upgrade does not write.
--
-- To regenerate (see docs/fixtures/parted.sql for why --entrypoint bash is required):
--
--   WH=$(mktemp -d)
--   JAR=~/code/spark-kit/lakelab/tasks/01_FlinkUpsertRead/.run/jars/paimon-spark-3.5-local.jar
--   docker run --rm --entrypoint bash \
--     -v "$WH:/wh" -v "$JAR:/opt/paimon-spark.jar:ro" \
--     -v "$PWD/docs/fixtures/paimon-psm.sql:/tmp/paimon-psm.sql:ro" \
--     tabulario/spark-iceberg \
--     -c "/opt/spark/bin/spark-sql --master 'local[1]' --jars /opt/paimon-spark.jar \
--           --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
--           --conf spark.sql.catalog.paimon.warehouse=/wh \
--           --conf spark.sql.extensions=org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
--           --conf spark.sql.defaultCatalog=paimon \
--           --conf spark.ui.enabled=false \
--           -f /tmp/paimon-psm.sql"
--   for t in psm psk psl; do rm -rf example/paimon/db.db/$t && cp -R "$WH/db.db/$t" example/paimon/db.db/$t; done

CREATE DATABASE IF NOT EXISTS db;

CREATE TABLE db.psm (k INT, name STRING, note STRING, tag STRING, score DOUBLE)
TBLPROPERTIES (
  'primary-key'           = 'k',
  'bucket'                = '1',
  'metadata.stats-mode'   = 'counts',
  'fields.name.stats-mode' = 'truncate(4)',
  'fields.note.stats-mode' = 'full',
  'fields.tag.stats-mode'  = 'none'
);

INSERT INTO db.psm VALUES
  (1, 'alphabet-soup',     'a note well past sixteen characters long', 'x',  1.5),
  (2, 'bravo',             'short',                                   'y',  0.5),
  (3, 'alphabet-soup-two', NULL,                                      NULL, 2.5);

ALTER TABLE db.psm SET TBLPROPERTIES ('fields.tag.stats-mode' = 'counts');

INSERT INTO db.psm VALUES (4, 'charlie', 'note four', 'z', 3.5);

CREATE TABLE db.psk (c1 INT, c2 STRING, c3 STRING, c4 INT, c5 STRING)
TBLPROPERTIES (
  'bucket'     = '1',
  'bucket-key' = 'c1',
  'metadata.stats-keep-first-n-columns' = '2'
);

INSERT INTO db.psk VALUES (1, 'one', 'three', 4, 'five'), (2, 'two', 'three-b', 5, 'five-b');

CREATE TABLE db.psl (k INT, v STRING)
TBLPROPERTIES (
  'primary-key' = 'k',
  'bucket'      = '1',
  'metadata.stats-mode.per.level' = '0:none'
);

INSERT INTO db.psl VALUES (1, 'alpha'), (2, 'beta');

CALL sys.compact(table => 'db.psl', compact_strategy => 'full');

SELECT * FROM db.psm ORDER BY k;
SELECT * FROM db.psk ORDER BY c1;
SELECT * FROM db.psl ORDER BY k;
SELECT file_path, level, null_value_counts, min_value_stats, max_value_stats FROM `db`.`psm$files`;
SELECT file_path, level, null_value_counts, min_value_stats, max_value_stats FROM `db`.`psk$files`;
SELECT file_path, level, null_value_counts, min_value_stats, max_value_stats FROM `db`.`psl$files`;
