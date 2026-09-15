# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **The tasks a read takes are planned**, the way `TableScanUtil.planTasks` plans them at 1.8.1
  (`model/ScanTaskPlan.kt`): a Parquet, ORC or Avro file with well-defined `split_offsets` is one
  split per row group whatever the target size, any other file is cut into `read.split.target-size`
  slices, each split weighs its bytes plus its delete files' content bytes or `(1 + deletes) ×
  read.split.open-file-cost`, whichever is more, and the splits are bin-packed with
  `read.split.planning-lookback` bins open, the heaviest closed first. Spark's adaptive split size
  (`read.split.adaptive-size.enabled`) is planned beside it. Held to Iceberg's own task counts on
  every checked-in table under three option sets (`iceberg-scan-plans/tasks.txt`), to the packing
  where the table has one data manifest, and to Spark's partition count at parallelism 200.
  `rgs`'s thirteen row groups are fourteen splits and one task; on a table with several manifests
  the packing order is the worker pool's, so the count can move by a task between runs.
- **The `range-bitmap` file index is read**, release-1.3.0's fourth and the one that orders any
  type — a dictionary of the column's distinct values with a bit-sliced index over their codes,
  so `<`, `<=`, `>`, `>=`, `BETWEEN`, `=`, `<>` and the null tests are answered per row on a
  string, a float, a boolean, a decimal or a timestamp as well as the integers. The scan panel's
  file stage asks it where the bounds leave a term open, folds the terms' rows across the filter
  and names the count per term; the section's rule sentence names it. `frb` is the fixture —
  seven indexed columns, a dictionary cut into a hundred-odd chunks — held to `FileIndexPredicate`'s
  row counts over every file for seventy filters
- **`Metadata Cleanup`** on the Iceberg metadata panel — which partition specs and schemas
  `expire_snapshots(clean_expired_metadata => true)` drops besides the snapshots: the specs no
  retained snapshot's manifest records and the schemas none was written under, the default and
  current always kept, with what keeps each. `docs/fixtures/clean-expired-metadata.sql` records
  the runs on the 1.10.0 runtime
- **`where => …` and `remove-dangling-deletes` under `Rewrite`** on the Iceberg snapshot panel —
  with the table panel's filter set, what `rewrite_data_files(where => …)` considers, rules out
  and rewrites, the files picked the way a scan picks them; under both plans, which delete files
  the dangling pass removes by the partition's sequence floor after the rewrite, and why it
  stands down on an unpartitioned table. `Delete Reach` gains a `Partition Floor` column, the
  sequence rule beside the target rule. `docs/fixtures/rewrite-where.sql` records the runs
- **`Expire By Id`** on the Iceberg snapshot panel — what `expire_snapshots(snapshot_ids =>
  array(id))` does to this snapshot, the way `RemoveSnapshots.expireSnapshotId` decides it:
  refused with the refs that still name it, or removed whatever its age and its place under a
  branch, with the children left naming it, the snapshot log cut before it — every entry before
  the removed one goes, so the earliest `TIMESTAMP AS OF` that resolves moves up to the child —
  and the files the procedure frees. `docs/fixtures/expire-by-id.sql` records the runs on
  `branched`, `mor` and `rolled`
- **`Full Compaction`** on the Paimon snapshot panel — what `sys.compact` (the default `full`
  strategy) does to each primary-key bucket: which files are rewritten together, which are
  upgraded to the top level by a rename, which are left, and why, the way `pickFullCompaction`
  and `MergeTreeCompactTask` decide it; a `sys.compact` row in `Maintenance`.
  `docs/fixtures/paimon-compact.sql` records the procedure on seven tables
- **`Purge`** on the Paimon table panel — what `sys.purge_files` takes and keeps, in the order
  `FileStoreTable.purgeFiles` runs it: branches, tags and consumers gone, one truncating
  `OVERWRITE` over the latest snapshot's manifests, every other snapshot expired, `changelog/`
  and the orphans taken; every file listed with the step that takes it, and a `Maintenance` row.
  `docs/fixtures/paimon-purge.sql` records the runs, `brp` is `br` purged
- **`Rewrite Table Path`** on the Iceberg table panel — what `rewrite_table_path(source_prefix,
  target_prefix)` would stage and list for this table, seeded with the recorded location and the
  directory it was opened from: every version, manifest list and manifest rewritten, the live
  data files and equality deletes to copy as they are, the positional deletes rewritten, the
  statistics file the action lists and never stages, and the refusals — partition statistics, a
  path outside the prefix, a deletion vector. A `Maintenance` row; `docs/fixtures/rewrite-table-path.sql`
  records the runs
- **IDE strip: the Paimon `Missing files` row says what `remove_unexisting_files` would do about them**
- **IDE strip: `Fast-forward`, `Rollback` and `Cherry-pick` rows** — the metadata row says
  which pairs of refs `fast_forward` would move, and an unexpired snapshot's row what
  `rollback_to_snapshot` and `cherrypick_snapshot` would do with it, planned as the desktop plans them
- **`remove_unexisting_files` planned** — on a Paimon table, `Missing Files` leads each missing
  file with what `sys.remove_unexisting_files` would do: a DELETE entry in one APPEND for a data
  file the latest snapshot's batch scan opens, not scanned for a level-0 file such a scan skips,
  not reached for a manifest or a file only an older snapshot names; the commit's
  `deltaRecordCount` in the headline and a `Maintenance` row. `docs/fixtures/paimon-pru.sql`
  records the runs, `pru` / `prua` the table before and after
- **`Cherry-Pick`** on the Iceberg snapshot panel — what `cherrypick_snapshot` does with this
  snapshot, decided the way `CherryPickOperation` decides it: fast-forward main when its parent
  is the current snapshot, publish an append (or a dynamic overwrite) as a new commit carrying
  its files by reference, or refuse — a duplicate wap id, an ancestor, a snapshot already
  picked, a delete. `docs/fixtures/cherrypick.sql` records the runs
- **`Rollback`** on the Iceberg snapshot panel — whether `rollback_to_snapshot` to this
  snapshot is allowed (an ancestor of the current one) or only `set_current_snapshot` would
  do it, the times `rollback_to_timestamp` lands here for, the commits main would move past
  with the refs still holding each, and what an expiry after the rollback would remove and
  free. `docs/fixtures/rollback.sql` records the runs.
- **`Fast-Forward`** on the Iceberg metadata panel and the Paimon table panel — what
  `fast_forward` would do: on Iceberg, which branch can move to which ref under the ancestor
  rule and which pairs are refused; on Paimon, which of main's commits the call drops in favour
  of the branch's line, and the files it leaves named by nothing. A `fast_forward` row on both
  `Maintenance` summaries. Fixture `brf`, and `docs/fixtures/fast-forward.sql` for the runs.
- **A Paimon snapshot's `Manifest Merge`** — what the next commit does to the base manifest
  list, the way `ManifestFileMerger` does it on every commit: the bins that close on
  `manifest.target-file-size`, the leftover merged at `manifest.merge-min-count`, a full
  compaction past `manifest.full-compaction-threshold-size`, and how many entries a merge
  writes once an ADD and a DELETE of one file cancel, and what `sys.compact_manifest` would
  rewrite instead. On the `Maintenance` summary too. Fixtures `pmm` and `pmma`.
- **What `remove_orphan_files` would delete** — the table panel's `Unreferenced Files` plans a
  bare call over the walk: each file `REMOVED`, `too young` (Iceberg's default cutoff is three
  days, Paimon's one) or `never listed` (Iceberg hides `_` and `.` names; Paimon lists only
  `manifest/`, `index/`, `statistics/`, the bucket directories, `snapshot/` and `changelog/`),
  with its modification time and the reason. On Iceberg the files only an older metadata
  version or a `DELETED` entry names are planned too — the procedure reaches from the current
  metadata alone and deletes them while the walk counts them referenced. Fixtures `orph`/`orpha`
  and `po`/`poa`, each a table copied before the procedure ran. The `Maintenance` summary gains
  the row once the walk has run.
- **A Paimon file's bucket count checked against the table's** — every manifest entry records
  `_TOTAL_BUCKETS`, and a write to a bucket whose files record a count other than the `bucket`
  option is refused until an `INSERT OVERWRITE` rescales the table. The integrity check lists
  each live file recording a different count (`bucket`, or `bucket of <partition>`), and the
  file panel's `Total Buckets` row says so on the file. Fixtures `pbk` and `pbka`.
- **The row history's `Published` column** — once the changelog stage under it is read, each
  history row lists what that snapshot's changelog carries for the rows (`+I`, `-U, +U`, `-D`),
  so a change at an APPEND and its publication by the COMPACT after it read on adjacent rows.
- **A Paimon snapshot's `commitIdentifier` and `watermark` are said for what they are** —
  `Long.MAX_VALUE` is every batch write's identifier (`BatchWriteBuilder.COMMIT_IDENTIFIER`;
  a streaming sink writes its checkpoint id, the deduplication key), and a `Long.MIN_VALUE`
  watermark is a Flink batch write's.
- **A Paimon rollback planned** — the snapshot panel's `Rollback`: what `rollback_to` this
  snapshot removes (the snapshot files, long-lived changelogs and tags above it) and what it
  leaves on disk named by nothing — the rolled-back commits' data files, manifests and lists,
  which Paimon's rollback does not delete. Fixtures `prb` and `prba`, whose leftovers are what
  the orphan check reports afterwards.
- **`expire_tags` planned** — the table panel's `Tag Expiry`, the way `TagTimeExpire` decides
  it: a tag's recorded create time and retention (written only when created with one), a bare
  call against `older_than = now`, and what removing each tag frees — the tag file alone while
  its snapshot is retained, else the files it alone held against its neighbours. The section is
  also the panel's list of tags. Fixtures `ptt` and `ptta`; a `Maintenance` line.
- **`expire_partitions` planned** — the table panel's `Partition Expiry` on a partitioned Paimon
  table, the way `PartitionExpire.doExpire` decides it under the table's options: the latest
  snapshot's partitions folded from every manifest entry, `values-time` through the pattern and
  formatter or `update-time` off the newest file, the cap, and why each partition is kept — a
  `DATE` partition column never parses, since the extractor sees its epoch day. Fixtures `ppx`
  and `ppxa`, the table before and after a bare call; a `Maintenance` line for the procedure.
- **A row lookup as of one snapshot, on the snapshot panel** — the table panel's filter read
  against the snapshot's own live files and delete pairing (Iceberg) or replay and merge rule
  (Paimon), under `Live Rows` / `Merged Rows`; what the table's history, which walks `main`,
  cannot answer for a branch tip, a tag-only snapshot or a commit past its cap. `branched`'s
  audit-only row and `br`'s dev-only key are the oracles.
- **The Paimon row lookup's bucket read is pruned by key range** — a hit's key is asked of
  its bucket's other files only where their `_KEY_STATS` may hold it, the pruning
  `KeyValueFileStoreScan.filterByStats` runs a key predicate through, asked as one `OR` of
  the hits' keys; the lookup's headline says how many bucket files were opened and how many
  left unopened. On `pc` a key found in one file leaves the other two unopened; a filter
  reaching every key opens every file.
- **Paimon's long-lived changelogs are read** — `changelog/changelog-<id>`, the snapshots a
  decoupled changelog lifecycle keeps after expiry, drawn as `CHANGELOG ONLY` snapshots with
  their changelog list, the lists the expiry deleted named on the panel, their files referenced
  (they were orphans before), and the expiry-file plan keeping what a decoupled expiry keeps
  under the derived rule rather than an option that does not exist; a file's history names
  the long-lived changelog that still lists it. Fixtures `pcl` and `pcn` (with and without a
  changelog producer).
- **`expire_changelogs` planned** — the table panel's `Changelog Expiry`, the way
  `ExpireChangelogImpl` runs it, with the counts against the latest snapshot id and each
  `changelog.*` setting falling back to the snapshot's; a maintenance line beside it.
- **`rewrite_manifests` planned** — the snapshot panel's `Manifest Rewrite`: per content kind,
  the manifests under the output spec rewritten into their length over
  `commit.manifest.target-size-bytes`, a kind of one manifest within the target left alone,
  another spec's manifests kept, and the `manifests-created` / `kept` / `replaced` figures the
  commit would record, which `maint`'s own rewrite holds. A line on the table panel's
  maintenance summary.
- **`rewrite_position_delete_files` planned, and what it would drop read** — the snapshot
  panel's `Position Delete Rewrite`: the live positional delete files grouped and sized the way
  `SizeBasedPositionDeletesRewriter` plans them, a bare call beside `rewrite-all`, the v3 refusal
  said; behind a button, each rewritten file's positions kept or dropped as dangling against
  the snapshot's live data files, which `maint`'s own rewrite (three positions removed, one
  written) holds. A line on the table panel's maintenance summary.
- **An equality delete file read against the data files the pairing leaves for it** — behind a
  click on the delete file's panel, the rows it matches in each candidate at the newest drawn
  snapshot listing its manifest, both files placed by field id; *removes nothing* when every
  candidate answers zero, which is the equality kind's dangling and the one thing the metadata
  cannot say. `eqdel`, `eqren`, `eqpart` and `fup` are the oracles.
- **The Paimon file panel's `Stats Modes`** — `metadata.stats-mode`, `fields.<name>.stats-mode`,
  `metadata.stats-keep-first-n-columns` and `metadata.stats-mode.per.level` read off the schema
  the file names, at the level the file was written to (an upgraded file keeps level 0's), and
  whether the file's `_VALUE_STATS` are that shape; in the integrity check and on the IDE strip.
  Fixtures `psm`, `psk`, `psl`.
- **Each column's metrics mode on the file panel, with the rule that set it** —
  `write.metadata.metrics.*` read the way Iceberg reads it, the inferred limit past a hundred
  columns and the sort-column promotion included — and whether the file records that shape,
  judged under the configuration in force when it was written. In the integrity check as
  `metrics modes`, on the IDE strip's `Checks` row, and in a scan-pruning reason where a
  column's mode is why nothing could be evaluated. Fixtures `metrics` and `metricsw`.
- **`min_sequence_number` checked on every manifest** — against the lowest data sequence
  number among the manifest's live entries, on the manifest panel's tallies, in the integrity
  check and on the IDE strip's `Checks` row. It is the figure the next commit drops delete
  files by, so one recorded too high lets a delete that still applies be removed.
- **A Paimon snapshot's recorded lengths checked against the files** — `baseManifestListSize`
  and siblings, which the reader opens each manifest list at, on the snapshot panel's
  `Recorded Figures` and in the integrity check; and each index file's `_FILE_SIZE` on its
  row of `Index Files`. A tag-only snapshot's deleted changelog list has nothing to compare
  and is not counted.
- **The files the retained snapshots need that are not there, behind a click on the table
  panel** — the converse of the unreferenced-files walk. Every retained snapshot's manifest
  list, manifests and live data and delete files, plus a Paimon snapshot's index, changelog,
  statistics and schema files and the newest Iceberg metadata's statistics files, each stat'ed
  and each missing one named with its kind and the snapshots that read it. An expired
  snapshot's files and a tag-only Paimon snapshot's changelog are gone by design and not
  counted. The IDE strip prints the same as a `Missing Files` row.
- **The IDE strip carries the metadata-only checks as one `Checks` row** on the metadata,
  manifest and file rows and their Paimon twins — `all 40 figures agree`, or the figures that
  differ named with both sides.
- **`metadata.json`'s own figures folded from its contents, on the metadata panel and in the
  integrity check.** `last-column-id` and `last-partition-id` against the highest ids in use —
  the figures the next `ADD COLUMN` and `ADD PARTITION FIELD` allocate from, which no reader
  checks and which hand an id out twice when short — and the figures Iceberg refuses the table
  on: a sequence number above `last-sequence-number`, `last-updated-ms` before the log, a
  current snapshot or ref the list lacks, a `current-schema-id` no schema has, a log out of
  order, plus `next-row-id` against the furthest commit. Paimon's `highestFieldId` and a
  snapshot's `schemaId` get the same treatment.
- **A data file's partition checked against its own column bounds, on both file panels and
  in the integrity check.** A scan prunes on the partition before it looks at a bound, so a
  file registered under the wrong partition is skipped for the value its rows hold with
  nothing failing; every row's source value transforms to the partition value, so both bounds
  must — exactly for a number or a date, within the bounds for a truncated string, and for a
  bucket where the bounds are one value. A null partition holds only nulls and a non-null one
  none, which is the disagreement a file with nulls under `region=eu` gets.
- **Every recorded length a reader opens a metadata file at is checked against the file.**
  An Iceberg manifest's `manifest_length` is a seventh tally on the manifest panel and in the
  integrity check, beside the six counts; a Paimon manifest's `_FILE_SIZE` likewise; and a
  statistics file's `file-size-in-bytes` and `file-footer-size-in-bytes` are shown against the
  Puffin file's own on the metadata panel and counted by the statistics-files check. None of
  the three is stat-ed by a reader — Iceberg opens the manifest and the statistics file at the
  recorded lengths, and Paimon reads a manifest's Avro blocks up to the recorded size, so a
  short one loses entries silently.
- **A data file's size and split offsets checked against the file, on the statistics check
  and the integrity sweep.** `file_size_in_bytes` is put beside the size on disk and
  `split_offsets` beside where the Parquet footer says each row group starts, since a reader
  opens the file at the recorded length and a scan cuts it into tasks at the recorded offsets —
  neither is checked on any read path, and Iceberg silently drops an offsets list whose last
  entry is not below the file size, which the panel now says. The new `rgs` fixture is the one
  file with several row groups (thirteen); every other fixture's list was `[4]`. Two facts the
  sweep settled: a file `add_files` registered records no offsets, and neither does Iceberg's
  Avro writer.
- **What each commit published for a row — the changelog — under the row lookup on a Paimon
  table.** The history says what a batch read returns at each snapshot; the new `Changelog`
  stage reads the changelog files each snapshot names for the same filter and lists every
  record a streaming consumer received — its kind (`+I`, `-U`, `+U`, `-D`, the retractions
  coloured), the commit, the sequence number, the cells — under a sentence stating what the
  table's `changelog-producer` publishes. `lk` and `cl` are the oracle: under `lookup` the
  update of key 2 is a `-U` / `+U` pair in the COMPACT after the append, under `input` a bare
  `+I` in the append itself. Every changelog snapshot of every fixture, unfiltered, reads
  exactly its recorded `changelogRecordCount`.
- **A statistics blob's theta sketch is decoded, and the panel says whether its distinct count
  is exact or an estimate.** `compute_table_stats` records `ndv` as a property and the sketch
  it was read off beside it; the record keeps the figure and not how it was made. The compact
  sketch's preamble is read now — empty, a single item, exact with every hash, or estimating
  with a threshold θ — and the metadata panel's statistics table gains a `Sketch` column:
  `exact, 7 hashes` or `estimate, 4,096 hashes kept below θ 0.2032`, with a line under the table
  saying what that means when any column is an estimate. The whole-table check holds the
  sketch's own estimate to the recorded `ndv` as a second figure per blob. `ndv` is the fixture
  — 20,000 distinct ids, seven, one and none — and the writer's own figures are the oracle,
  reproduced to the integer.
- **A Paimon bit-sliced file index (`bsi`) is read, and it answers every comparison.** One
  Roaring bitmap per bit of the value, in two sets for the two signs, so the scan-pruning
  section answers `<`, `<=`, `>`, `>=` and `BETWEEN` exactly, per row, where a bloom filter
  answers `=` as a maybe and a bitmap dictionary `=` and `<>` alone — `n BETWEEN 4 AND 6`
  skips a file whose bounds are -5..10 and whose rows are -5, 3 and 10. The terms' rows are
  folded across the filter the way `FileIndexPredicate` folds its readers' bitmaps — an `And`
  intersects, an `Or` unites — so a file no single term rules out is still skipped when the
  terms' rows are different rows, and its reason says so with the count per term; the bitmap
  index gives rows to the same fold. A decimal is compared at the column's scale, a date as its
  epoch day, a timestamp as its microseconds (milliseconds at precision 3 and below). `fbs` is
  the fixture — an INT, a DECIMAL, a DATE and a TIMESTAMP under `bsi`, one index embedded and
  three beside their files — held to `FileIndexPredicate` over every file for twenty-eight
  filters, and to Paimon's plan.
- **A Paimon table that writes Iceberg metadata beside its own says what an Iceberg reader
  sees.** Under `metadata.iceberg.storage = table-location` every commit also writes Iceberg
  metadata under `metadata/`; the table panel reads it and puts the export's current snapshot
  and its live files against the table's own, naming the files the export leaves out by the
  rule that leaves them out. `pic` is the fixture; `pih` is the same under `hadoop-catalog`,
  whose export sits in catalog storage beside the warehouse and is found there; `pid` exports
  its deletion vectors as Iceberg v3 vectors pointing into the table's own index file, and the
  check puts each against the index manifest's range
- **An Iceberg table whose metadata is kept apart from its location says where.** A
  `write.metadata.path` layout, or the catalog-storage export a Paimon table writes, holds the
  metadata in one directory and the data under the location; the table panel and the IDE
  strip name the directory the metadata records itself under, only where it differs — and
  the Paimon table the location is, when it is one

### Fixed
- **An empty file index skips every operator `EmptyFileIndexReader` skips.** A column index the
  writer left empty was read as a skip for `=` alone; Paimon reads it as a skip for `=`, `IN`,
  every comparison and `IS NOT NULL`, and a maybe for `<>` and `IS NULL`, which it is now
- **`Expiry Files` plans the Spark procedure's cleanup, and says what the core API's would
  leave.** The section, the file history line and the `Maintenance` row picked the cleanup rule
  by the ref count — incremental with one ref, reachable with more — which is
  `RemoveSnapshots.cleanExpiredSnapshots`, the core API. `expire_snapshots` from Spark deletes
  the reachability diff whatever the ref count (`ExpireSnapshotsSparkAction.expireFiles`), and
  on `mor` the two differ: expiring the overwrite by id freed a data file the incremental rule
  leaves on disk, named by nothing. The sections plan the procedure's diff now and, with one ref,
  name the files a Java, Flink or Trino expiry would leave behind
- **A Paimon `TIMESTAMP` bound past millisecond precision decodes.** Its `BinaryRow` slot is
  the tail offset in the high 32 bits and the nanos within the millisecond in the low 32, the
  tail holding the millis — read as a variable-width field it decoded to nothing, so a
  `TIMESTAMP(6)` or `WITH LOCAL TIME ZONE` column's bounds were shown undecoded and a filter on
  one pruned no file. `ft`'s bounds now read to the microsecond and agree with DuckDB's values.
- **Two deletion vectors in one Puffin container are two delete files.** A writer puts one
  blob per data file into a container, so vectors share a `file_path`; keyed by path alone the
  second was a duplicate to the table's figures, paired with nothing by the delete pairing, and
  answered with the first's positions by the lookup and the live count — a row the second
  vector deletes read as live. A vector is keyed by its container and the data file it
  references now, everywhere. Seen on `pid`, a Paimon table's Iceberg v3 export; every Iceberg
  fixture holds one vector per container
- **A Paimon 64-bit deletion vector is read by its own size field.** Its index range's
  recorded length is the whole blob, size and CRC included, where a 32-bit vector's is the
  magic and bitmap alone; read the 32-bit way, a 64-bit vector overran by eight bytes and the
  last one in the file could not be read at all
- **A data-evolution split is stitched by field id, and the latest snapshot is read under the
  latest schema.** A column renamed since either file of a split was written read as a DuckDB
  error or as null, the stitch having selected the schema's names from the files; each file
  goes through its projection now, and which file holds a column is decided by id. A rename
  written after the last commit is what a read shows, as Paimon opens a table under the newest
  schema file and switches to a snapshot's own only on time travel. `der` is the fixture
- **A Paimon row's history is traced under the table's current names.** Every step is read
  under the newest schema, as on Iceberg, so a column renamed between two commits is one
  column throughout; read under each snapshot's own schema, a row that was only patched read
  as appearing at the patch
- **A directory carrying both formats' markers opens as the Paimon table.** Such a directory is
  a Paimon table whose Iceberg metadata is its export, and opened as Iceberg its `snapshot/`,
  `schema/` and `manifest/` read as orphans and its snapshots, levels and merge engine are
  invisible. The detector asks Paimon first now, and the export's files are referenced files
- **A catalog table's metadata versions are numbered.** A Hive, Glue or REST catalog names a
  version `00147-<uuid>.metadata.json`; the number is read off that name now, as off
  `v147.metadata.json`, so the cards say `METADATA 147` and the versions order by it
- **An Iceberg table with gzip-compressed metadata opens.** `write.metadata.compression-codec
  = gzip` names every version `v<N>.gz.metadata.json` and writes gzip bytes; the codec is
  read off the name now, the way Iceberg reads it, where every version was a read error
  before. `gzmeta` is the fixture
- **A Paimon table with a struct, array or map column opens.** Its schema JSON writes such a
  type as an object, which the schema reader took for a string — every schema of the table was
  a read error and nothing else was drawn. The type is read as a tree now, printed the way
  Paimon spells it, and the nested fields are placed by their own ids, so a field renamed or
  added inside a struct reads the way Paimon reads it; the schema steps name the change inside
  the struct rather than a type change on it. `pne` is the fixture
- **A migrated file's nested fields are placed through the name mapping's tree.** A struct,
  a list's element and a map's entries in a file `add_files` registered read as all-null
  before — the top level was placed through the mapping and every field inside it was looked
  up by an id the file does not have — so a filter on a field inside the struct found nothing
  on exactly the files a migrated table is made of. `migdeep` is the fixture

### Added
- **Both schema panels list nested fields as rows of their own.** A struct's fields under
  their path (`addr.town`), a list's element and a map's key and value, each with the id the
  format places and renames it by — where the metadata panel printed a nested type's JSON in
  one cell and the Paimon schema panel its `ROW<…>` spelling
- **A row lookup past the file cap continues a page at a time.** Sixty-four files a click, the
  headline counting what is not read yet and a control under the table reading the next page,
  the pages folded into one result — on both formats, a Paimon page keeping a data-evolution
  split together

### Fixed
- **Startup no longer walks the workspace on the main thread.** A restored warehouse comes back
  with the table list it was saved with and is drawn as `scanning…` until the first poll's sweep
  lands, which seeds every table it finds as existing rather than announcing them all as new;
  adding a root walks it on the IO dispatcher before the item appears. The periodic sweep had
  been off the main thread already, and these two were the scans the rationale for that applied
  to

### Added
- **A rename inside a struct is read.** The row lookup and the live-row count rebuild a
  file's struct by field id — `struct_pack` over the file's own column tree, a list's or a
  map's elements through `list_transform` — so `addr.town = 'Ankara'` finds the rows in a file
  written when the field was `city`, and a field added since reads as null there, where the
  file reported DuckDB's error before. The row panel's `Read As` rebuilds the struct the same
  way and says a field inside was renamed or added since
- **Schema evolution by field id, with the snapshot first written under each schema.** The
  table panel's `Schema Evolution` lists every change from each schema to the one before it —
  added, dropped, renamed, moved, type changed, nullability, default, identifier fields; on
  Paimon the keys, the options and the comment — nested fields as the leaves they are, a move
  as the fewest columns whose move explains the new order, and beside each step the first
  snapshot written under it or that none was. A Paimon schema node lists its own step, and the
  IDE strip prints it. Replaces a section that diffed the drawn metadata nodes' top-level
  fields, which aggregation could fold and a nested rename never reached
- **The table panel's `Table Properties` reads every metadata version from the model.** Its
  change history was folded from the drawn metadata nodes, which aggregation drops past the
  page size, so a table with more versions than the page lost its earliest changes; the
  versions travel on the summary now, the keys no longer wrap, and the section says when
  nothing changed
- **The data-file sweep under `Integrity` reads a table past its cap a page at a time.** Each
  click reads the next 64 live files on top of the ones read, the pages fold into one line and
  one findings table, and the button says how many files are left
- **A Paimon Avro table on its default codec is read.** DuckDB's Avro reader refuses
  `zstandard`, so every row card, the merged count, the row lookup, the live count and the
  statistics sweep said so on such a table. A sample is the file's first block, and it is read
  in this process through the Avro library now, logical types applied; the readers that run SQL
  over the whole file read a copy under deflate made once per session — block by block, no
  record decoded, the file's name kept — bounded at 2 GiB of copies, and a file no copy can be
  kept for is still refused by the codec's name
- **A row's history: the lookup run at every retained snapshot on `main`.** Under the row
  lookup's result a second click traces the matching rows through the last 20 snapshots on
  `main` and names what each commit did to them — appeared, changed, gone — against the commit
  before, comparing the live rows a read returns on the row's own columns; `mor`'s row 5 appears
  at the second append, changes at the update and is untouched by the compaction that rewrote
  its file. Both formats; on Iceberg a data file is read once per trace, not once per snapshot
- **The table panel's second click under `Integrity` opens the statistics files too.** Each
  Iceberg statistics file's Puffin footer is put against the blob records in `metadata.json`,
  each partition statistics file's size and rows against its record and the live files of its
  snapshot, and a file that cannot be opened is named with the reason — the case an orphan
  cleanup produces and nothing on the read path notices.
- **A Paimon row says what a read returns for it when the schema has moved on since the file.**
  The row panel's `Read As` projects a Paimon file's cells onto the latest schema by the field
  ids the file's own schema gives its columns — a renamed column under its new name, an added
  one as null — the row lookup reads every file the same way, so a filter on a renamed column
  finds the rows an older file holds, and the Paimon schema panel draws a `Default` column
  where `ALTER COLUMN … SET DEFAULT` set one. A Paimon default is write-time, and the section
  says so by putting nothing into the absent column. A primary key renamed between writes is
  one key across the bucket's files: the merged count, the row lookup and the row panel's
  `Merge` read each file's `_KEY_*` column by its field id, so the old file's `_KEY_k` and the
  new one's `_KEY_id` merge as Paimon merges them.
- **A file registered by `add_files` or `migrate` is read through the table's name mapping.**
  Such a file records no field ids; `schema.name-mapping.default` places its columns, on the
  row panel's `Read As`, in the row lookup, the live-row count and the statistics check.
- **A row says what a read returns for it when the table's schema has moved on since the
  file.** The row panel's `Read As` section projects the file's cells onto the current schema
  by field id — a renamed column under its new name, a dropped one listed as not read, an
  added one as its `initial-default` or null — and the metadata panel's schema table draws a
  v3 column's initial and write defaults. The IDE strip fills a `Read as` row.
- **The table panel reads the data files behind a second click under `Integrity`.** The
  metadata check opens no data file; the new button reads up to 64 of the current snapshot's
  live files — from the model, so files the graph folded away are read too — and lists each
  recorded bound or count the file's rows contradict beside the metadata findings, with the
  file that could not be read named rather than counted as agreeing. The file panel's own
  headline now counts figures rather than columns.
- **A sampled Iceberg row says whether a positional or equality delete removes it.** The row
  panel's `Delete Files` section asks the delete files a scan pairs with the row's file, behind
  a click — the panel's `Deleted` row could only answer for a v3 vector, and said *not by a
  deletion vector* of a row a v2 positional delete had removed. It now says how many delete
  files the pairing leaves for the file and that they are asked under `Delete Files`.
- **A sampled Paimon record says what the merge engine does with it.** The row panel's `Merge`
  section runs the table's row lookup for the record's key, behind a click — superseded by the
  file holding the later write, a retraction, folded, live — with the key's other records under
  it.
- **A row of a patched data-evolution file says what a read returns for it.** The same section
  reads the split at the row's position and prints the stitched row, each patched column with the
  file it came from — the file's own cells hold the values the patch replaced.
- **The IDE strip lists a file's history.** A `History` row under a data file on either format,
  drawn reading and filled off the EDT — which commit added the file, which removed it, how many
  retained snapshots still list it live.
- **Paimon sequence groups are applied.** A `partial-update` table with `fields.*.sequence-group`
  is counted and looked up rather than reported as not applied: a retraction retracts its group's
  columns and the key stays, and under `partial-update.remove-record-on-sequence-group` a `-D` at
  or above the row's value on the named field removes the key — folded per key in sequence order,
  the way `PartialUpdateMergeFunction` decides it. `sg` and `sgd` are the fixtures, and `sgm`
  for a group versioned by two fields — compared as a tuple with a null below every value, the
  generated comparator's order, and removed on a `-D` whichever of its fields the option names.
- **The Paimon row lookup reads a data-evolution split stitched.** Files sharing a first row id
  are joined on their row number, each column from the freshest file holding it, and the filter
  runs over the stitched row — so on `de` a lookup answers `(1, 11, 1)` with `b from <patch>` as
  the note, and `b = 11` finds the row whose `id` sits in a file recording `b` in 1..2. A split
  is read whole when the filter left any file of it.

### Fixed
- **The scan-pruning file stage is over data files only.** A delete file was evaluated and
  counted as a data file the scan would read — "2 of 3 data files" on a table with two — where
  a scan applies it to the data files it is paired with and never opens it on its own.
- **Scan pruning binds a filter's column by field id, so a rename no longer hides a file's
  bounds.** A file whose manifest still calls a column by its old name is pruned by a filter on
  the new one, the way the engine prunes it, on both formats; the prunable columns are the
  current schema's, each once; and a nested column is named by its path (`addr.town`,
  `tags.element`, `props.value`) with its own bounds and counts, and the row lookup reads it as
  struct access. A Paimon file written before a column existed prunes as the scan reads it —
  null in every row.
- **An equality delete written before a column rename decides the row again.** The delete
  file's columns are named as the schema named them when it was written; read by the schema's
  current name the file answered with an error and the row came back *not decided* where the
  engine deletes it. The row lookup and the row panel read the delete file projected onto the
  schema by field id now, and the panel matches the row's cells under the schema's names.
- **A filter on a column renamed or added since a file was written reads that file instead of
  failing on it.** Every DuckDB read under a filter addressed the file by its own column names;
  the row lookup on `evolved`'s `note` reported the two older files as errors, where a read
  returns their rows with `note` null. The file is read under the schema's names now, a
  missing column as its initial default or null.
- **A data file recorded as a `file:` URI outside the table resolves beside it.** `add_files`
  records `file:/wh/plain-files/…`, which was rebuilt under the table root as `<table>/file:/…`.
- **A non-Parquet data file is read by the right DuckDB table function, or refused with the
  reason.** Every reader called `read_parquet` on whatever file it was given, under a comment
  saying DuckDB detected Parquet, ORC and Avro; an Avro file failed on its magic bytes and drew
  blank row cards, and an ORC file did the same for a reason DuckDB cannot help with — it has no
  ORC reader. `read_avro` reads Avro now (without row positions, which the row's fate, the
  live-row count and the merged count say rather than guess), an ORC file and an Avro file
  under a codec DuckDB refuses (`zstandard`, Paimon's default) are refused before any query with
  one sentence, and a row card whose file could not be read prints it in place of the cells.
- **A partitioned table's columns are read from the file, never from the path.** DuckDB read
  `dt=19787/` and `amount=98765.43/` directories as Hive partitions, typing the column from the
  path text over the file's own — a Paimon `DATE` arrived as a `BIGINT`, an Iceberg `DECIMAL`
  as text, a row lookup on either found nothing, and on DuckDB 1.4.4 the DATE-over-BIGINT
  collision was an internal error that broke every later query until restart. Every read now
  passes `hive_partitioning = false`.
- **A Paimon primary-key table's scan pruning follows the scan's own rule.** Every file was
  pruned by its own value bounds, which is what an append table's scan does and what a
  primary-key table's does not: a key predicate prunes a file on its own, the whole filter is
  decided per bucket — file by file only where the bucket's files cannot overlap, otherwise the
  bucket read whole if any file may match — and `partial-update` and `aggregation` without
  deletion vectors are never pruned by value. On `pc`, `v = 'g'` opens all three live files where
  the panel said one. A level-0 file of a table whose batch reads skip level 0 is `not read`; a
  file read for its bucket's sake says so in its reason cell; the rule is stated once above the
  file table. Held to the plans Paimon itself made (`docs/fixtures/paimon-scan-plans.scala`).
- **A Paimon file index is decoded and the scan plan asks it — where Paimon's read would.** A
  `bloom-filter` index, embedded in the entry or in the `.index` file beside the data file, is
  read (the container, the filter, xxHash64 for strings and Wang's hash for numbers) and an
  equality on an indexed column the filter rules out skips the file. When it is asked follows
  release-1.3.1: an append table tests an embedded index as it plans and the `.index` file when
  the read opens it — such a file is listed by the plan and yields no row, which the row says —
  while a primary-key table's scan tests an embedded index only under deletion vectors and its
  read consults one only on a split read raw, one file alone in it. `fa` is the new fixture; on
  `fi` the two files merge-read and their indexes are never opened, which the rows say too. The
  panel's `File Index` row names the columns and index types. Held to `FileIndexPredicate` and
  the plan on both tables (`docs/fixtures/paimon-scan-plans.scala`).
- **A data file's recorded statistics are checked against its rows.** `Statistics Check` on
  both formats' file panels, behind a click: each column's recorded bounds, null count and
  (Iceberg) value and NaN count beside the same figures counted from the file, and the entry's
  row count beside `count(*)` — one-sided on the bounds, since a string bound is truncated,
  exact on the counts. These are what a scan prunes on without opening the file, so nothing on
  the read path checks them. Every Parquet file of every fixture agrees; the check names a
  bound moved past a row.
- **A Paimon bitmap file index is read, and it answers exactly.** One Roaring bitmap per
  distinct value and one for null, so the scan-pruning section rules a value out by the
  dictionary — no false positive, unlike a bloom filter — and answers `<>`, `IS NULL` and
  `IS NOT NULL` the way `BitmapFileIndex`'s reader does. v1 and v2 layouts, the v2 block
  directory read block by block. `fb` is the fixture, held to `FileIndexPredicate` over every
  file and every operator.
- **A column that is null in every row rules a file out for any comparison.** Both formats'
  own evaluators skip such a file for `=`, `<`, `<=`, `>`, `>=` and a prefix, and the file
  stage now does too; for `<>` Paimon skips and Iceberg keeps, and the verdict follows the
  format the file belongs to, with the reason saying which does what.
- **A Paimon bloom filter over a timestamp, time or date column is asked.** `FastHash`'s
  temporal half: a date over its epoch day, a time over its milliseconds of the day, a
  timestamp of either kind over its microseconds since the epoch (milliseconds at precision 3
  and below). `ft` is the fixture, and Paimon's plan is the oracle — a value the file holds is
  kept, and the same second without its microseconds is skipped though it sits inside the
  file's bounds.
- **The delete pairing applies Iceberg's partition and bounds rules.** A delete is keyed by the
  spec and partition it was written under and weighed only against data files under the same
  key — a vector or a positional delete naming one file is keyed by path instead, and an
  equality delete under an unpartitioned spec is global — and an equality delete is ruled out
  where its bounds on an equality column cannot meet the file's, null counts included
  (`canContainEqDeletesForFile`). Two new verdicts on the file panel's `Deletes Reaching This
  File`, each with its reason; the row panel's `Delete Files` asks only what is left. Two
  fixtures pin them against Iceberg's own plan: `fupp`, Flink's upsert sink on a partitioned
  table, where commit 2's equality delete for a new key is dangling by its bounds — and
  `eqpart`, equality deletes on `id` alone written under both specs of a table partitioned after
  its first commit, where the partitioned one is attached to its own partition's file only and
  the unpartitioned one to every file. Without the partition rule an equality delete in another
  partition stayed "maybe" on every partitioned merge-on-read table.
- **A Flink-written merge-on-read table joins the fixtures.** `fup` is Flink 1.20's upsert
  sink on a v2 table: each commit's equality delete sits beside its data file at one sequence
  number, and a key upserted twice in one checkpoint gets a positional delete in that same
  commit — the shape no Spark statement writes, and the one that separates "at or below" from
  "strictly below" in the pairing. Read back by Flink as `(1, a2), (2, b2), (4, d)`, which the
  live row count and the row lookup both answer.
- **The delete pairing is held to Iceberg's own plan.** `FileScanTask.deletes()` over every
  checked-in table's current snapshot (31 tables, 89 data files) is checked in as an oracle, and
  `deleteReach` agrees with it both ways: every delete Iceberg applies is reached or unsettled,
  every proved reach is one Iceberg applies, and the metadata settles every positional delete
  and vector in the corpus, so the plan's deletes are the proved ones plus the equality deletes.
- **Iceberg's two pruning stages are held to Iceberg's own plans.**
  `docs/fixtures/iceberg-scan-plans.scala` prints the data files `planFiles()` opens for 41
  filters over five checked-in tables — every transform shape, both partition specs, three
  manifest schemas, `IN`, `BETWEEN`, `NOT`, `OR`, `LIKE`, `IS NULL` — and `IcebergScanPlanTest`
  requires no file Iceberg opens to be skipped here; all 36 filtered plans agree file for file.
- **A branch cut from another branch draws in a column of its own, and no column is reused.**
  Two children of one commit were ordered by write time unless one was on `main`, so a branch
  cut from a branch and committed to first took the older branch's column and put the fork
  commit under its own name; and a branch reserved after another line had ended reused that
  line's column, which put `main`'s commits under `b2` on the same table. Lines are ranked now —
  `main`, then the other branches by the metadata version that first lists them — and every
  line keeps a column of its own. `nested` is the fixture.
- **A Paimon key with no insert record is not a row.** Every engine but `aggregation` answers
  no row for a key whose records are all retractions — ignored under `ignore-delete`, or
  retracting by group — and the merged count took them as rows; they are counted as *never
  inserted* and taken off.
- **No file of a data-evolution table is pruned by its own bounds.** A patch may replace the
  values a file's bounds describe, and Paimon 1.3.0+ consults none of them on such a table
  (`DataEvolutionFileStoreScan`); the file stage now declines with the reason, said once above
  the file table, while the manifest stage still prunes by partition. The section's headline
  counts a file nothing could be evaluated against as read — it said *would read 0 of 3* on `de`
  — and names such files on a line of their own.
- **A level-0 file a batch read skips says so.** On a `first-row` table or a primary-key table
  with deletion vectors, the file panel's `LSM Level` row and the IDE strip's `Level` row note
  that a batch read of the table skips level 0, so a row in the file is not returned until a
  compaction moves it up — the `dv` append files before their forced compaction, and `fr`'s
  rewritten file, which nothing ever moves.

### Changed
- **Every fixture sweep runs on every checked-in table.** The tests that sweep the fixtures list
  them from `example/` (`FixtureCatalog`) instead of from twenty hand-kept copies that had each
  stopped at the fixture current when they were written; the core test worker gets a 2 GB heap
  for it.
- **The scan-pruning headline carries bytes and rows** — `1.94 KiB of 7.78 KiB, 1 of 4 rows`
  beside the file count, since a scan's cost is what it reads and three files of a thousand may
  be the three large ones.
- **The maintenance sections have a file of their own.** `MaintenanceSections.kt` holds the
  nine sections the planners draw; `NodeDetails.kt` is down to 2,725 lines.
- **The inspector's per-node panels are out of `NodeDetailsContent`.** Its 2,080-line `when`
  now dispatches to one composable per node kind — `NodePanels.kt` (table, row, error, group),
  `IcebergNodePanels.kt` (metadata, snapshot, manifest, file) and `PaimonNodePanels.kt` (the five
  Paimon kinds) — and `NodeDetails.kt` keeps the header, the multi-select branches and the
  sections and helpers the panels share. No behaviour change; every inspector capture renders
  as before.
- **The tool-window layout is out of `App.kt`.** `DockState` holds where each window sits,
  which are hidden, the pane sizes and a drag in flight, with the rules as functions that
  `DockStateTest` asserts on — where a drop lands, how far a pane may grow, what a bar lists.
  `DockLayout` draws it and is rendered by `DockLayoutTest` in its three shapes, which is the
  first time this layout has been seen in a test at all. `App.kt` 1,009 → 593 lines. One
  observable change: the drop targets a drag lights up are judged against, and drawn over, the
  dock below the toolbar rather than the whole window.
- **The README describes the app that ships.** Its limitations still said local filesystem
  only, Iceberg v1 and v2, partition values not decoded and manifest summaries not read — four
  claims each false for some time. Features, format coverage, toolbar and shortcuts are current.
- **Paimon cards are the height of what they draw.** Manifest list 80→42dp, schema 80→54,
  manifest 80→64, snapshot 84→66 — measured worst plus four, the same rule as the Iceberg cards,
  which `CardHeightTest` can now assert over two Paimon fixtures rather than one. A manifest list's
  card says how many manifests it names instead of the words "Manifest List"; the count is on the
  node and in the inspector, tooltip and IDE tree too.

### Fixed
- **A deletion vector in object storage is opened at its location, not at a relative path.** The
  row lookups, the live and merged row counts and the Paimon file node turned a vector's location
  string back into a path with `Paths.get`, which reads `s3://bucket/key` as a relative path and
  reported every remote vector as unreadable. They go through `StorageLocation.pathOf` now, and a
  test holds every file in core to it.
- **A Paimon file listing every column in `_WRITE_COLS` is not a partial-column file.** A full
  compaction under `row-tracking.enabled` records every column plus `_ROW_ID` and
  `_SEQUENCE_NUMBER` there, and the table's figures read its rows as columns of rows other files
  hold — `rt` showed five rows read as zero, its file panel called the compacted file a patch, and
  the IDE strip did too. A file is partial only when a schema column is missing from the list.
- **A looked-up row whose delete file could not be read is `not decided`, not `live`.** The Iceberg
  lookup noted the unread delete and still reported the row live; a vector decoded past its cap
  read the same way.
- **The maintenance summary and the snapshot panel's rewrite and merge sections plan from the
  newest metadata and the current snapshot whatever the page size draws.** They looked both up on
  the drawn graph, where each is the last of its siblings — so a page size that folded them, or a
  snapshot filter, had the sections planning under an older version's options, or not drawn at
  all. `TableNode.maintenance` now carries both off the builder's full node set.
- **An unpartitioned spec and an unsorted order say so.** Each drew a table with one row of
  `N/A` in every cell, which read as a decode that failed; they are one line of text now, and the
  spec heading marks `(default)` the way the order heading already did.
- **A file draws as many row cards as it has rows.** Every data file got five row nodes whatever
  its record count, so a one-row file sat beside four empty `ROW` cards — on every table, since
  Spark writes small inserts as one file per row. The count is `min(5, record_count)` now, decided
  from the manifest before the file is opened.
- **A Paimon partial-column file has its bounds.** Its `_VALUE_STATS` is a row over `_WRITE_COLS`
  with `_VALUE_STATS_COLS` null, and decoding it against the schema failed the arity check — one
  field read as three — so the file showed no column bounds at all. The stats fields are now
  `_VALUE_STATS_COLS`, else `_WRITE_COLS`, else the file's schema.
- **A Paimon row card leads with the row's own columns.** It listed `_KEY_k`, `_SEQUENCE_NUMBER`
  and `_VALUE_KIND` before `k` and `v` — the file's physical order, where the system columns come
  first — and `file_row_number` as a fifth cell. Keys starting with `_` are drawn last now, and the
  position travels beside the cells as it does for an Iceberg row rather than among them.
- **A row's panel lists the row's cells.** It iterated the placeholder the builder emits before
  any file is opened — `file_no` and `row_idx` — so the panel for a selected row showed no cell of
  it while the card beside it drew five. It reads the resolved row now.
- **A bound written before its column was widened, listed by a manifest rewritten after, reads as
  the value it is.** `rewrite_manifests` copies a file's bounds verbatim under the table's current
  schema, so a four-byte `int` bound sat under a `long` and was reported as `expected 8 bytes for
  long, got 4`. It is read at the width it was written now — the spec's two promotions, `int → long`
  and `float → double`, the same tolerance Iceberg's own reader has — and the panel prints
  `1 (written as int)`. A bound for a column the manifest's schema has dropped is named and typed
  by the newest table schema that had it, marked `(dropped)`, where the panel printed `field 2`
  with the bytes undecoded. `promoted` is the fixture.
- **A Paimon data file written under an older schema than the manifest listing it keeps its
  bounds.** Key and value statistics were decoded against the manifest's `_SCHEMA_ID`; a
  compaction's delta manifest, written under the new schema, records the old-schema files it
  removed, and their two-field stats rows failed the three-field arity check and came out as no
  bounds at all. They are decoded against the file's own `_SCHEMA_ID` now, and the Column Bounds
  section says so. `se` is the fixture.
- **A Paimon table whose data was written to `data-file.external-paths` opens with its files
  found.** The entry records the external location in `_EXTERNAL_PATH` and the resolver built
  the path from the table root regardless, so every such file read as missing. The recorded
  path is used when it is there, found by its tail under the local warehouse when the table was
  copied down, and reported as recorded-elsewhere-and-absent otherwise — the file panel's
  `Resolved` row says which. `ep` is the fixture.
- **A table whose data sits outside its directory opens with its files found.** A
  `write.data.path` layout records absolute data paths that share nothing with the table below
  the warehouse, and the sub-path rule rebuilt them under the table root, where nothing is —
  every file reported missing. Such a path is now re-rooted from the recorded warehouse to its
  local counterpart, found by the trailing segments the recorded and local table directories
  share, and the file panel says so as a third `Resolved` outcome. `extdata` is the engine-written
  fixture, checked in beside its table the way it sat under `/wh`.
- **A Paimon schema card no longer sits under a manifest-list card.** The schema is a sibling
  of its snapshots, so ELK lays it out in the manifest lists' column, and overlap prevention
  kept schemas apart from schemas and lists apart from lists — `pschema_0` was drawn over
  `pml_2_delta` on `dv` and `pml_3_delta` on `ao`. The two kinds are one layer for that pass now,
  and `LayoutOverlapTest` checks every column of every checked-in table across kinds.
- **A group whose parent was itself folded away is no longer drawn over the table card.** At a
  small page size on a table with many metadata versions (`mor` at 3), the snapshot group under a
  hidden metadata version reached the layout with no edge and landed in the first column, on
  top of the table root. Such a group is dropped; its members are counted under the group that
  hid the parent, so the hidden-node figures still add up.
- **A partitioned Paimon table opens with its files where they are.** A manifest entry names its
  file by name only and its partition as a serialised `BinaryRow`, which was never decoded — so on
  any partitioned table every data file resolved to a path under the table root that does not
  exist: no rows, every file missing, and "walk the table directory" listing the whole table as
  orphans. `_PARTITION` is decoded now (dates, strings inline and in the tail, integers, decimals,
  timestamps, booleans) against the manifest's own schema, the file resolves under
  `<key>=<value>/…/bucket-N/`, and the file panel, search and IDE tree show the partition beside the
  directory text Paimon wrote — which for a date is its epoch day. The new `pt` fixture is a
  Spark-written table over two dates and two regions.
- **A v1 manifest's entries are at sequence number 0, as the spec reads them, not "N/A".** Every
  file under a manifest a v1 table wrote printed `N/A` and was left out of the delete-pairing rule
  as if its number were unknown; the format defines it as 0 and an upgrade to v2 leaves those
  manifests exactly so. The panels now print the 0 and say it is the reader's default, and a v1
  manifest orders first among its siblings rather than last. The new `v1` fixture is a v1 table
  upgraded to v2 in place, with a merge-on-read delete written after the upgrade reaching a file
  written before it.

### Added

- **The IDE tool window's table row says what an expiry would remove** — `older_than = now` on
  Iceberg, a bare call on Paimon — the one maintenance line that needs no walk.
- **An Iceberg snapshot says what `SELECT count(*)` returns.** `Live Rows` on the snapshot
  panel: each data file's `record_count` less the rows the delete files a scan pairs with it
  remove — a vector's cardinality, a positional delete's positions for the file, an equality
  delete's matches on its columns, one bit set per file — drawn at once where the snapshot lists
  no delete manifest and behind a click where it does. Checked against the rows every fixture
  with deletes left, and commit by commit on `maint`.
- **The Paimon merge engines are applied, and the level-0 files a batch read skips are named.**
  `partial-update`, `aggregation` and `first-row` decide the merged row count and a looked-up
  record's fate the way `deduplicate` already did — a record folded into the key's row, a key
  removed by a `-D` under `remove-record-on-delete`, the first record kept — each on a table
  written under it and checked against Paimon's own read. A batch read of a first-row table, or
  of a primary-key table with deletion vectors, never reads level-0 files; both sections say
  which live files that leaves unread, and `fr` shows why it matters: a DELETE Spark ran as a
  file rewrite left the surviving row at level 0, and Paimon reads the table as one row.
- **A Paimon snapshot says what `SELECT count(*)` returns.** `Merged Rows` on the snapshot panel:
  the merge a read runs over each bucket's files — one record per key, less the keys whose latest
  record is a `-D` or `-U`, less the keys whose latest record a deletion vector marks — behind a
  click on a primary-key table, per bucket on a partitioned one; an append table's figure comes
  from the metadata. Checked against the rows every Paimon fixture's script left, and against the
  `mergedRecordCount` an `ANALYZE` wrote.
- **A Paimon data file's deletion vector is decoded and its rows are marked.** The file panel's
  `Deleted Rows` section — the Iceberg one, with the index file's coordinates as its first row —
  shows the positions the vector the latest index manifest names for the file marks, against the
  manifest's cardinality and the CRC; the row cards under the file draw a marked row struck and
  faded, as an Iceberg row under a Puffin vector is; the IDE strip names the index file and the
  count. Before this a Paimon row said nothing about its vector, because none was mapped to rows.
- **Row lookup.** Under the scan filter on a table panel, a click reads the files the filter
  leaves and lists the matching rows with each one's fate. On Iceberg: live, deleted by a vector,
  by a positional delete or by an equality delete, naming the file that did it — equality deletes
  are evaluated for a looked-up row, which the metadata alone never could. On Paimon: live, marked
  by the vector its index file holds, a `-D` or `-U` retraction, or superseded by a later write
  for its key in the bucket, naming the file holding it — the bucket's other files are read for
  the key whether or not the filter left them, so a filter on an old value finds the record it
  matches and says what shadows it. Paimon deletion vectors are decoded from the index file, both
  the 32-bit form and the 64-bit one that is Iceberg's blob. Checked against the rows the `mor`,
  `eqdel`, `v3`, `lk`, `dv`, `ad` and `pc` scripts left.
- **Time travel resolved.** A typed time and the snapshot `TIMESTAMP AS OF` (Iceberg) or
  `scan.timestamp-millis` (Paimon) would land on, the way each engine resolves it — on a
  rolled-back table a time between the abandoned commit and the reset lands on the abandoned
  commit, and the panel says so.
- **A manifest's partition summaries are checked against its entries.** The bounds a scan prunes
  on, folded from every entry's decoded partition and put beside the recorded ones on the
  manifest panel's `Partition Ranges`, each row leading with a verdict; the whole-table check
  covers them too.
- **The table panel checks every recorded figure at once.** `Integrity`, behind a click: manifest
  counts, commit summaries and snapshot totals on Iceberg, manifest counts and record counts on
  Paimon — the checks each node's panel runs, over the whole table, listing the pairs that
  disagree and where. Every checked-in table agrees with itself but for the changelog count a
  tag records against a list the expiry deleted.
- **A file panel tells the file's history.** On both formats: which commit added the file, which
  removed it, and which retained snapshots still list it live — the line a missing-file error
  sends a reader looking for, and the reason a removed file is still on disk. Live agrees with the
  live-file walk on Iceberg and the replay on Paimon, on every fixture; a Paimon level upgrade
  shows as re-added, and an Iceberg commit the expiry removed is still credited from the manifest
  it wrote. Under it, whether the expiry the table panel plans would free the file, and if not,
  which kept snapshot or tag still holds it.
- **`Ctrl/Cmd + 1 / 2 / 3` shows or hides the Workspace, Structure and Inspector tool windows**, in the order the bars list them; the cheat sheet has a Tool Windows group.
- **The Paimon table panel says which files an expiry would free, and which a tag holds.** An
  `Expiry Files` section plans the `retain_min = 1, older_than = now` call's removals the way
  `ExpireSnapshotsImpl.expireUntil` runs — the data files later commits removed, the changelog,
  the manifests and lists no tag or retained snapshot names, the snapshot files — and leads with
  the removed files a tag keeps on disk. `pe`/`pea`, one table copied before its expiry, is the
  oracle: the plan names exactly the files the expiry took out.
- **The table panel opens with a maintenance summary.** One line per procedure — rewrite,
  the next commit's manifest merge and expiry on Iceberg; compaction and expiry on Paimon —
  with what running it now would do, from the same planners the detail sections use, and the
  panel that holds the reasoning named beside each.
- **The metadata panel says which files an expiry would free.** An `Expiry Files` section under
  `Expiry` plans the `older_than = now` column's removals the way `RemoveSnapshots` cleans up —
  the incremental cleanup with one ref, the reachable one with more — and lists every manifest
  list, manifest, data file and statistics file that would go, data files first, with the rule
  that frees each. Two tables copied on disk before their expiry (`sweep`/`swept`,
  `sweepb`/`sweptb`) are the oracle: the plan names exactly the files the expiry took out, and a
  sweep over every fixture holds that no planned file is one a retained snapshot still reads.
- **The snapshot panel says what the next commit would do to the manifest list.** A `Manifest
  Merge` section plans the next append and the next merge-on-read delete the way
  `ManifestMergeManager` does on every batch write — bins by spec from the oldest end, a bin of
  one kept, the bin holding the new manifest kept under `commit.manifest.min-count-to-merge`, any
  other bin of two or more merged — one row per bin with the verdict first. Three new fixtures
  are the oracle (`merged`, `mergedel`, `mergespec`), and the sweep over every Iceberg fixture
  requires the plan from each parent to land on its child's manifest count. Two things the
  fixtures settled: a partition-spec change merges the old spec's manifests at the next commit
  under the default count of a hundred, and Spark 3.5 on Iceberg 1.8.1 rewrites a file's
  existing positional delete on a second delete rather than adding beside it.
- **An Iceberg snapshot says what `rewrite_data_files` would rewrite.** `Rewrite` on the
  snapshot panel plans a bare call the way `SizeBasedDataRewriter` does — every file outside
  75%–180% of the target size or with file-scoped deletes over 30% of its rows is a candidate,
  packed per partition, rewritten at `min-input-files` or a delete past the ratio — and leads each
  group with the verdict, the files, bytes and output count. Checked against the rewrites `mor`,
  `maint` and `sorted` ran: the plan names exactly the files each took out.
- **A Paimon snapshot shows each bucket's LSM tree and what the next flush would compact.**
  `Compaction` on the snapshot panel lists every bucket's sorted runs and levels (`L0×5`,
  `L0×2, L5×1`) and the verdict `UniversalCompaction.pick()` would reach — size amplification,
  size ratio, run count, level 0 forced up on a lookup or deletion-vector table, never on a
  write-only one — with the files and level it lands in, and marks a bucket past the stop trigger,
  where the writer waits. An append table gets the `sys.compact` side: small files per partition
  against `compaction.min.file-num`. Checked against the writer: `pc`, where the fifth of seven
  inserts is the one Paimon compacted.
- **What `expire_snapshots` would remove from a Paimon table, and why.** `Expiry` on the table
  panel, beside the consumers, plans the run the way `ExpireSnapshotsImpl.expire()` decides it —
  `snapshot.num-retained.min`/`.max`, `snapshot.time-retained`, `snapshot.expire.limit`, every
  consumer's bookmark — under the table's own options and under `retain_min = 1, older_than =
  now`, and names the rule keeping each snapshot. A removed snapshot a tag names says so. Checked
  against a table Paimon expired: `px`/`pxa`, one table written twice.
- **A partition statistics file is checked against the live files it describes.** Each row
  leads with `yes` or `NO — data files: 9 recorded, 2 counted`, a live partition the file omits
  and a listed partition with no live file are both disagreements, and the section's note says how
  many rows disagree — a stale file after a cleanup or a rewrite is otherwise invisible, since a
  planner reads it instead of walking the manifests.
- **The metadata panel says what `expire_snapshots` would remove, and what keeps the rest.** An
  `Expiry` section plans the procedure the way Iceberg's `RemoveSnapshots` does — ref by ref, a
  branch's own snapshot age standing in for `older_than` on what it reaches — under the table's
  defaults and under `older_than = now`, with the keeping rule and ref beside every snapshot
  (`newer than the cutoff of main, audit; referenced by release`). Held to the two expiries the
  fixtures ran: `retained`'s and `expired`'s, planned from the metadata before each and required
  to match the metadata after.
- **Refs print their retention as ages.** `30 days (2,592,000,000 ms)` and `not set` where the
  table's defaults apply, in place of bare milliseconds that read `N/A` on every fixture. `retained`
  is the fixture — a tag and a branch created with retention, and an `expire_snapshots` that kept
  the branch's two, the tag's one and main's tip, and whose first attempt removed nothing because
  the branch's own snapshot age stood in for `older_than`.
- **An append table's deletion vector has a fixture.** `ad` is written with
  `deletion-vectors.enabled` on a `bucket = -1` table: its DELETE commits as a `COMPACT` whose only
  change is an index manifest — one vector per touched file, both files untouched — where `ao`
  rewrote the file. The index reader already handled it; now something says so.
- **A comparison can be stepped.** Each side of the two-snapshot comparison has older / newer
  controls that move that snapshot to its neighbour in commit order while the other stays pinned —
  a branch against successive points on `main` is a click per point. Each button names the commit
  it would move to and is disabled at the end of history.
- **A snapshot lists its live files by partition.** A `Partitions` section on both formats'
  snapshot panels — data files, records, bytes, delete files and delete records per partition,
  largest first, with the largest's share of the bytes stated above the table — folded from the
  same live set the totals and the comparison use, so the three cannot disagree. It is held to the
  partition statistics file Iceberg 1.10 wrote for `pstats` and to what `paimon-pt.sql` put in
  each partition of `pt`.
- **A rollback is shown as one.** A snapshot-log entry naming a snapshot the log already holds is
  `main set back to it — left behind: <ids>` in the metadata panel, leading the row and coloured,
  with an `Ancestor of Current` column that matches Iceberg's `.history`; the commit it stranded
  says `Rolled Back: main was set back to <id> at <time>, leaving this commit behind` under its
  empty refs, and the IDE tree lists the same. `rolled` is the fixture — `set_current_snapshot`
  to a tag, the tag dropped, a commit after.
- **A Paimon data-evolution patch file is drawn against the file it patches.** A `MERGE INTO`
  on a table with `data-evolution.enabled` writes the columns it set to a file of their own, with
  `_WRITE_COLS` and the same first row id as the file holding the rest; the graph draws the pair as
  a dashed `e_patch_*` edge, the patch's panel says `b only — a partial-column file: a read
  stitches it with <file> by row id`, the patched file's says `Patched By`, and the IDE tree lists
  `Columns`. `de` is the fixture, and its script's final SELECT is the oracle for the stitched rows.
  The snapshot's panel leads its record tallies with the figure a scan returns — `the snapshot's 5
  rows read as 3` — because Paimon's `totalRecordCount` sums file rows and a patch file's rows are
  rows the table already had; the table's own `Records` row and the IDE tree say the same, from
  `ContentStats.partialRecordCount`, folded by the replay beside the record count it qualifies.
- **A v3 snapshot's panel says which row ids it took.** `added-rows` is read, and `Row IDs` prints
  the range with the summary's `added-records` beside it where they differ — `6..8 (3 ids for 1
  added record — the other 2 went to existing rows in a data manifest this commit wrote)` on
  `lineage`'s update, `none — next id stays 9` on its delete. The IDE tree lists the same line.
  `RowLineageFixtureTest` holds `first-row-id + added-rows` to the `next-row-id` of the metadata
  that introduced each snapshot.
- **A Paimon primary-key row says what kind of row it is.** A KV file carries every write as a row
  with a `_VALUE_KIND` — `+I`, `-U`, `+U`, `-D` — and a `-D` is a retraction, not a value: merged
  with the levels below it removes the key. The card draws such a row faded and struck with the
  kind in its title, the panel names the code (`-D (delete) — a retraction …`), and the IDE tree
  lists it as `Row kind`; a `+U` row carries the word in its title too. The panel no longer says
  `not by a deletion vector` of a Paimon row, which no vector was looked up for. The update pair
  has an oracle: `lk`, written with `changelog-producer = lookup`.
- **The IDE tool window lists the newer facts where a table has them.** `Next row id`, a
  snapshot's `First row id`, `WAP id` and `Published from`, a file's `Row ids` and `Sort order` —
  each only on a table that carries it.
- **A write-audit-publish flow is drawn as what it is.** A snapshot staged under `spark.wap.id`
  sits on no branch, and its panel says so with the audit id instead of "kept only by a metadata
  version"; the commit `publish_changes` writes on main names the staged snapshot in a
  `Published From` row and is joined to it by a dashed `e_source_*` edge, since its parent edge
  only says where it sits on main. The audit id is searchable from either end. `wap` is the fixture.
- **A partition statistics file is read, and shown under its record.** The metadata panel listed
  the file's name, snapshot and claimed size; it now opens the Parquet and draws one row per
  partition — data records, files and bytes, delete records and files, last updated, last
  snapshot — with the size on disk beside the size the record claims. `pstats` is the fixture,
  written with Iceberg 1.10's `compute_partition_stats`.
- **v3 row lineage is read, by inheritance, at every level it lives.** `next-row-id` on the
  metadata panel; `first-row-id` on a snapshot and a manifest; a file's `Row IDs` range, saying
  whether its first id was inherited from the manifest or recorded on the entry; and a sampled
  row's `_row_id` and `_last_updated_sequence_number`, derived from the file's first id and data
  sequence number where the file did not write them. `lineage` is the fixture, the first written
  with a runtime newer than the image's (Iceberg 1.10).
- **A data file's sort order is named, and the table's default is put beside it.** The file panel
  printed `Sort Order ID: 0` with nothing to resolve it against; it now reads the order the way
  `WRITE ORDERED BY` stated it, and when the table's default is a different order, a second row
  says which order that is and whose. The metadata panel's sort-order tables gain a `Column`
  column resolved through the current schema, print the transform unquoted, and mark the default
  order on its heading. `sorted` is the fixture, and what it settled is why the second row exists:
  Spark sorts the rows inside every file it writes under `WRITE ORDERED BY` — a sort compaction's
  included — and records `sort_order_id 0` on all of them, so a file's 0 does not mean its rows
  are unordered.
- **A Paimon file whose statistics cover a subset of the schema says so.** `fields.<col>.stats-mode
  = none` shrinks `_VALUE_STATS` to the columns that have statistics, named in
  `_VALUE_STATS_COLS`; the Column Bounds section now states which columns are covered and that
  the rest have none. `sm` is the fixture, and the oracle for the subset decoding — a bound
  attributed to the wrong column reads as an answer.
- **A row-tracked Paimon file says where its row ids are, and its row cards carry them.** The
  file panel's `Row IDs` row states the range a recorded first id implies, or that a compaction's
  output carries each row's id in its `_ROW_ID` column, or that row tracking is off; a sample row
  shows its `_ROW_ID` either way, derived from the first id or read from the column. `rt` is the
  fixture — two appends and a full compaction that reordered the rows.
- **A Paimon file index is read, and the one beside the data file is no longer an orphan.** A
  file index over `file-index.in-manifest-threshold` is `<file>.index` beside the data file and
  named in the entry's `_EXTRA_FILES`; a smaller one is `_EMBEDDED_FILE_INDEX` in the entry.
  The file panel's `File Index` row says which, and the referenced-files walk names the extra
  files, where before a 599 KB index a scan consults was reported as unreferenced. `fi` is the
  fixture, with both shapes.
- **Paimon consumers are read and listed.** `consumer/consumer-<id>` is a streaming reader's
  bookmark — the next snapshot it will consume — and the reason `expire_snapshots` keeps more
  history than retention says: it will not expire that snapshot or anything after it. The table
  panel lists each consumer with its next snapshot and whether `snapshot/` still holds it, and
  the referenced-files walk no longer skips the directory. `cs` is the fixture: `retain_max = 1`
  on three commits, two snapshots left.
- **A Paimon snapshot's record counts are checked against its manifests.** "Recorded Records" on
  the Paimon snapshot panel puts `totalRecordCount`, `deltaRecordCount` and
  `changelogRecordCount` beside the same figures read from the manifests the snapshot names —
  the total against the replay's live rows, the delta as the writer sums it, the changelog by its
  entries — with a verdict per row; the three identity rows that printed the figures unchecked are
  gone. 72 recorded figures across seven tables agree, and the one that does not is a tag whose
  changelog list expiry deleted, which the panel now says.
- **A snapshot's running totals are checked against its closure.** "What this commit left" on
  the snapshot panel puts the six `total-*` figures the summary carries — data files, delete
  files, records, files size, position and equality deletes — beside the same figures folded
  from every manifest the snapshot lists, with a verdict per row. A total that disagrees has
  been carried forward wrong since some earlier commit, and nothing on a read path checks it.
  A deletion vector is charged at its blob size, the way the writer charges it. 336 recorded
  figures across fourteen checked-in tables agree.
- **Paimon branches are read and drawn.** `branch/branch-<name>/` was skipped; now each branch
  is another line of commits under the table root, in a column of its own with its name over it
  — the same drawing an Iceberg fork gets — with the branch as a chip on its cards and a `Branch`
  row on its panel, and the table panel lists every branch with its snapshot count, latest id,
  schemas, tags and read errors. A branch's snapshot ids are its own, so its node ids carry the
  branch name. The referenced-files walk follows branches too, which it had to: a branch writes
  its manifests and data beside main's and only its snapshot file under `branch/`, so the file a
  branch commit wrote was reported as an orphan. A commit to a branch, or a new tag, now reloads
  the table. `br` is the fixture — a branch created from a tag and written to, and one created
  empty.
- **An append-only Paimon table is a checked-in fixture.** `ao` has no primary key and
  `bucket = -1`: its files carry no key range, every one lands in `bucket-0` under its partition,
  and its `DELETE` is an `APPEND` commit with a negative delta that removes one file and adds it
  back without the row. The Paimon sweeps run over six tables now.
- **Scan pruning works on a Paimon table.** The filter form on the table panel now rules a
  Paimon manifest out by the partition range its manifest list records and a Paimon file by its
  own column bounds — the same two stages, the same verdicts and reasons, the same fade on the
  canvas — where before it saw no manifests and no files and every row said "would be read".
- **A Paimon data file shows its key range and column bounds.** `_MIN_KEY` / `_MAX_KEY` over the
  trimmed primary key and `_VALUE_STATS` over every column — the figures a scan prunes files with
  and the answer to "which file holds key 42" — are decoded and drawn on the file panel, with
  `_DELETE_ROW_COUNT` (the `-D` rows inside the file, not what a vector marks) and `_FILE_SOURCE`.
- **A Paimon manifest's recorded figures are checked against its entries.** The manifest list's
  entry counts, bucket and level ranges, and partition statistics — a per-column minimum and
  maximum with null counts, two more `BinaryRow`s — sit beside the same figures folded from the
  manifest's entries in a "Recorded Summary" section, the way the Iceberg manifest's do; the IDE
  tree shows the ranges. The `pt` fixture gained a commit whose manifest minimum is a partition
  none of its entries has, which is what settles that the minimum is per column.
- **"What this commit did" checks six more figures, and two of them are about manifests.** Rows in
  delete files by kind (`added-position-deletes`, `added-equality-deletes` and their `removed-`
  pairs) and the manifest list's split into written and carried (`manifests-created` /
  `manifests-kept`, which `rewrite_manifests` records); every commit's panel now states how many
  of its manifests it wrote and how many it carried forward. The new `maint` fixture is a
  merge-on-read table after `rewrite_position_delete_files` and `rewrite_manifests`, and it is
  where `deleteReach` meets its oracle from the other direction: two deletes proved dangling
  before the rewrite, none after. 120 writer-recorded figures are now checked across ten tables.
- **An expired Iceberg snapshot is drawn as expired, not as a read error.** After
  `expire_snapshots` the older metadata versions still on disk list snapshots whose manifest lists
  are gone, and every one of them was a `SNAPSHOT READ ERROR` node — on a healthy table, which is
  what every production table is. A snapshot the current metadata no longer lists and whose list is
  missing is now marked `EXPIRED` on its card and in the inspector and IDE tree, with the writer's
  summary and nothing under it; a snapshot the current metadata *does* list stays an error when its
  list is missing, because that table is broken. The new `expired` fixture is a four-commit table
  after `expire_snapshots(retain_last => 1)`.
- **Paimon tags are read and drawn.** A tag is a snapshot copy under `tag/`, and after
  `expire_snapshots` it can be the only thing keeping a snapshot's files on disk — so a tagged
  snapshot that is gone from `snapshot/` is drawn with its tag as a chip and marked `TAG ONLY`, its
  manifests and files under it, and the file walk counts what it names as referenced rather than as
  orphans. The new `tg` fixture is that table, and it settled that a tag keeps the data and not the
  changelog: the changelog list it names was deleted, and reading the tag reports that.
- **A table can say which files on disk nothing names.** "Walk the table directory" on the table
  panel lists every file under the root that no metadata version references, with its size — a
  write that failed after its files landed, or a file the format wrote and did not commit. On the
  new `cl` fixture that is the changelog file Paimon wrote for an overwrite and then refused to
  commit; on every other checked-in table it is nothing, which is the assertion that makes the
  list trustworthy. The panel says what "referenced" means here and where Iceberg's and Paimon's
  own orphan-file procedures would differ.
- **A Paimon `ANALYZE` commit's statistics are read and shown.** The snapshot named a file under
  `statistics/` and nothing opened it. Its merged row count is the figure the format records nowhere
  else — rows after the merge engine, where the snapshot's total sums file rows — and the panel leads
  with it, then lists each column's distinct count, nulls, bounds and lengths. A snapshot's recorded
  manifest-list sizes are read too. `example/paimon/db.db/cl` (from `docs/fixtures/paimon-cl.sql`) is
  the first checked-in table with a changelog manifest list, an `OVERWRITE` and an `ANALYZE` commit.
- **A Paimon deletion vector is checked against real bytes.** `example/paimon/db.db/dv` is a
  Spark-written primary-key table with `deletion-vectors.enabled`, regenerated by
  `docs/fixtures/paimon-dv.sql`; its index manifest names two data files and the one and two rows
  each has lost, and the snapshot panel now says so in one line above the table — "3 rows across
  2 data files are marked deleted by vectors — the snapshot's 1,500 rows are 1,497 live". The
  script's header records why the first two attempts produced a compaction and no vector.
- **A Paimon snapshot's index files are read and shown.** The snapshot named an index manifest and
  nothing opened it. It carries one index file per bucket — the primary-key hash index, with the
  size and row count that are the cost of a key lookup, and, on a table with deletion vectors
  enabled, the only record the format keeps of which data files have deleted rows. The snapshot's
  `nextRowId` is shown too.
- **A data file can say how many of its rows are actually deleted, and so how many are live.** One
  click over the delete files the pairing narrowed to, counting the positions that land in this
  file. It is the only way to the number: rows-minus-delete-rows is wrong whenever a delete file is
  dangling, and on the merge-on-read fixture it gives 3 where the table holds 5. The panel now says
  "1 of 6 rows deleted — 5 live in this file".
- **A data file says which delete files reach it, and why the others do not.** The same pairing
  from the position a reader is usually in, and the direction the tree cannot show at all: the
  delete files that apply hang under other manifests, and the ones drawn beside it mostly apply to
  something else. A positional delete file's panel now also states the **range of data files it
  records about itself**, which is what a scan prunes with before opening it.
- **A snapshot says which of its delete files reach which of its data files — and which reach
  nothing.** A scan pairs the two by sequence number and by the paths a delete file records about
  itself, both readable without opening anything, so the panel can now name the *dangling* delete
  files: still read during planning, deleting rows that are no longer in the table. The
  merge-on-read fixture has two, left behind by its compaction, and they were previously visible
  only as an arithmetic that does not add up.
- **`LIKE` and `NOT LIKE` prune.** A pattern is answered against the text a column's bounds pin at
  the start — `name LIKE 'b%'` rules out every manifest and every file whose recorded names are all
  below `b` or all past it — and a partition truncated to its first characters answers it too, since
  a truncation is a prefix. A pattern that pins nothing at the front says so rather than pretending
  to a verdict, and `NOT LIKE` is answered only where the bounds are the values themselves.
- **`IN` and `BETWEEN` in the filter clause**, including `NOT IN` and `NOT BETWEEN`. Both are read
  as what SQL defines them to be — a disjunction of equalities, and a pair of bounds — so the
  pruning engine gained nothing to get wrong. Written back, they show as the shape being evaluated
  rather than as the sugar they were typed in.
- **A scan filter can be written as a `WHERE` clause.** `AND`, `OR`, `NOT` and parentheses, with
  SQL's precedence, beside the row-per-condition form rather than instead of it — the rows carry
  the table's prunable columns in a menu, which is where a reader who does not know what it is
  partitioned on starts. A filter the rows cannot represent keeps the reader in the clause editor,
  because offering "use the form" for `a = 1 OR b = 2` would have to drop the `OR`. A clause that
  does not parse says what is wrong and where, and leaves the last working filter in force rather
  than clearing the verdicts on screen.
- **Scan pruning evaluates a boolean filter, not just a list of ANDed terms.** `OR`, `NOT` and
  grouping are handled: an `AND` is ruled out by one branch, an `OR` only when every branch is, and
  `NOT` is rewritten into the leaves before evaluation because negating a one-sided proof yields no
  proof at all. The filter form still builds a conjunction, so this is the engine and not yet the
  feature — a `WHERE`-clause input is what will reach it.
- `example/iceberg/default/branched3` and `docs/fixtures/branched3.sql` — three branches forked at
  three different points, with commits on other lines in between, plus a tag on the trunk's tip.
- **A statistics file is opened, and its footer shown against what the table records about it.**
  `metadata.json` carries a copy of the blob metadata so a query planner never has to open the
  Puffin file — which is exactly what lets the two drift, since a statistics file removed by an
  orphan-file cleanup leaves its record behind and nothing on the read path notices. The panel now
  puts the recorded distinct count beside the one in the file, per column, and says which of "not
  read" and "missing" it is when there is nothing to compare. The file also answers three things
  the record cannot: each blob's compressed size, its codec, and the writer that produced it.
- **A table's statistics are read and shown per column.** `statistics` and `partition-statistics`
  in `metadata.json` were untyped and rendered as one JSON blob per cell — invisible, because every
  fixture in the repository carried an empty list for both. They are records now, and the panel
  lists **one row per blob**: the column, its distinct-value count, the sketch that produced it and
  the commit it describes. A blob names field *ids*, and they are resolved against the schema its
  own snapshot used rather than the table's current one, so a column renamed after the statistics
  were computed cannot put its name against a figure never measured for it.
- `example/iceberg/default/stats` and `docs/fixtures/stats.sql` — a real Spark-written Puffin
  statistics file. The four distinct counts were predicted from the INSERT statements before the
  fixture was generated, and Spark's own theta sketches agree with all four.

- **Tables in object storage can be opened directly.** `s3://`, `gs://`, `gcs://` and `r2://` are
  read without copying the table down first, metadata and sample rows alike. This is supplied as a
  read-only `java.nio` `FileSystemProvider`, so the model layer is unchanged and every write
  operation throws `ReadOnlyFileSystemException`. The bytes come from DuckDB, which was already a
  dependency and which reads the sample rows too — measured against AWS's `aws-java-nio-spi-for-s3`,
  which adds 48 jars for one scheme, needs its own separate credentials for `read_parquet`, and
  reports a 403 as an absent file because `Files.exists()` may not throw.
- Object-store credentials, per location: an explicit key, or the ambient AWS credential chain, with
  an endpoint override for MinIO, Ceph, OBS and anything else speaking the S3 API.
- `docs/fixtures/minio-lab.sh` — a loopback-only MinIO holding the checked-in fixtures, so the
  remote read path is tested against the same table the local one is tested against.
- **An IntelliJ IDEA plugin.** Right-click an Iceberg or Paimon table directory in the Project view
  and choose "Open in Iceberg Lens": a tool window shows the table's structure as a tree and what
  each artifact records. Built on `:core` and the IDE's own components rather than the desktop
  shell's Compose cards, because IntelliJ ships its own Skiko and a plugin cannot override it.
  `./gradlew :intellij:buildPlugin` produces the installable zip.
- **Add object storage…** in the workspace panel, with a form for the location and its credentials.
  The secret is held for the session only and never written to preferences, which the form says
  where the reader will read it; the default is the credential chain already on the machine.

- **Four graph layouts, chosen from the toolbar.** Layered left-to-right stays the default and is
  unchanged; layered top-to-bottom suits a tall window, a tree follows one branch down to its files,
  and force-directed answers "what is clustered with what" rather than "what contains what". The
  choice is persisted. The layered refinements — chronological ordering, the branch column — run
  only under the default, because each is defined against that axis; the others are ELK's own
  output rather than a half-transposed version of ours.
- **Export the graph — PNG, SVG, JSON or CSV.** A menu in the toolbar, saving through the platform's
  own save dialog. SVG and PNG are the drawing, with the reader's own node drags in them; JSON is
  the graph as structure for another tool to read; CSV is one row per data or delete file, with
  path, format, record count, size and partition — the inventory that goes into a spreadsheet. The
  PNG renders through the same canvas the window draws, minus the mini-map, so it cannot drift from
  what is on screen, and it scales down rather than allocating when a graph is too large to
  rasterise.
- **A Paimon manifest now says what each of its entries did to the table.** The Iceberg panel has
  listed per-entry contributions for a while and Paimon had nothing below the per-manifest figures.
  It cannot have the same thing: an Iceberg entry is decidable on its own, while a Paimon
  `_KIND=1` means "remove what is there" and depends on every entry before it. So each row states
  the state the entry met and the effect the two produced — added, replaced, removed, or removed
  (absent), the last being a removal for a file that was never live, which is a no-op invisible in
  every figure above it. The trace comes out of the same walk as the figures, and the test asserts
  it sums to the contribution it explains.
- **Find a node on the graph — `Ctrl/Cmd+F`.** A find bar on the canvas with a match counter,
  Enter and Shift+Enter to step, and an amber halo on everything it matched. Each node kind gets a
  search vocabulary of its own rather than the one-line label the structure tree prints, so a
  manifest is findable by its path, a data file by its format or partition value, and a commit by
  the operation that made it — none of which the tree's search can reach. When aggregation has
  folded nodes away the bar says how many were not searched, because "no matches" is otherwise a
  claim about the whole table that is only true of the part of it drawn.
- **Every transitive dependency version is locked.** The version catalog pins what the build asks
  for; it said nothing about what those asks pull in, which is most of the classpath — 272 modules
  on desktop and 78 on core, versions chosen by conflict resolution afresh on every build. They are
  now written down and committed. `./gradlew resolveAndLockAll --write-locks` regenerates after a
  dependency change; the task exists because Gradle only locks configurations it actually resolves,
  so locking from an ordinary build writes a partial file.
- **The inspector's copy buttons draw focus, and the panel scrolls to keep the ring on screen.**
  The tool-window chrome and the workspace list already drew a one-dp `primary` ring where the
  keyboard was; the panel's copy buttons were left with Material's own indication, which on an icon
  tinted in a muted label colour is a grey disc indistinguishable from hover. A new capture counts
  the ring's ink at three tab depths, the last well past what the viewport holds, so it asserts
  both that the ring is drawn and that the panel brought it into view.

- **Canvas drawing is benchmarked, and it is linear in the node count.** ELK's layout had been
  measured to 4,000 nodes and the drawing never had. About 6µs a node in steady state, so roughly
  2,600 visible nodes inside a 16ms frame, with the first frame about forty times more expensive
  because it pays composition and layout for every node. The app draws far fewer than that, since
  aggregation caps siblings per parent. The test asserts the cost-per-node ratio rather than a
  duration, because a wall-clock bound measures the machine and a superlinear ratio is the
  regression worth catching.

- **A wide table's leading column is asserted to fit its panel.** The reader sees the leftmost
  columns and nothing else until they scroll, so a leading column wider than the panel means
  scrolling before reading anything, and the widths are hand-chosen at twenty-seven call sites in
  one file. The narrow sweep now measures what each table laid out and requires every first column
  to fit, across the 28 tables the six panels draw. The panel width is measured rather than
  budgeted, so the assertion is against the layout and not against the test's own arithmetic.

- **Focus is visible on the tool-window chrome.** Tab reached the bar's buttons and a pane's close
  and drew nothing on arrival, which the first two captures proved by coming back byte-identical:
  Material's default indication is for press, not for focus. `Modifier.focusRing` draws one dp of
  `primary`, the same ring the workspace list already draws around itself. The close button keeps
  its full click target and draws the ring inside it, because a Material `IconButton` expands to a
  48dp interaction target and a ring in the outer chain painted outside the 28dp header.

- **A crash now leaves something to send.** There was no uncaught-exception handler at all, so an
  exception took the window with it and left only whatever had already reached the rolling log. One
  is installed before the window is built. The report leads with the deepest cause rather than the
  wrapper's message, carries the machine, the thread and the log path, and is capped so a
  `StackOverflowError` still produces something a clipboard can hold. It does not quit — a frozen
  window with a dialog on it can be copied from — and it shows one dialog however many exceptions
  arrive, because a failing composition re-throws every frame. The dialog is Swing, since the
  runtime that crashed is often the one that would have to draw it.

- **The workspace list can be edited from the keyboard.** With the list focused, Delete or
  Backspace asks to remove the root under the cursor — the same dialog the `×` opens — and
  `Alt + Up / Down` moves it one place. A table inside a warehouse answers nothing to either, since
  it is the warehouse's and not a workspace item, and a move under a search does nothing, because
  the neighbour on screen is not the neighbour in the list. The cheat sheet has a Workspace
  section.
- **Tab-reachability of the tool-window chrome is asserted.** `KeyboardReachTest` sends Tab and
  Enter into an offscreen scene holding the tool-window bar and a pane, and reads the Tab order off
  the order the callbacks fire in.

- **A page size can be typed.** The badge's menu offered six sizes and `updateGraphPageSize`
  accepted anything from 2 to 2,000, so the restriction was the menu's alone. `Other…` opens a
  dialog with one field; the bounds are printed under it before anything is typed, the field turns
  red outside them, and `Apply` is dead until the number is one. When the size in force is not one
  of the six, `Other (37)` carries the check. `page-size-field-1.png` renders the field accepted and
  refused.

- **The metadata file's panel had never been rendered at reading width, and it was a table of
  contents printed twice.** Nine of its twenty-one identity rows were counts — `Total Snapshots`,
  `Total Refs`, `Snapshot Log Entries` — of sections drawn immediately below, and none of those
  sections said its own size. The counts are in the section titles now (`Refs (5)`,
  `Metadata Log (9)`), the identity table is twelve rows, and the folded panel fits a screen. An
  empty collection is drawn rather than skipped, so `Statistics (0)` answers a question the reader
  came with instead of leaving a gap that could equally mean this panel does not render them.
  `metadata-node-*.png` and `metadata-node-folded-*.png` are the captures; there were none before.

- **The badge's menu is drawn for somebody now.** Every choice the canvas badge offers lives in a
  `DropdownMenu`, and no capture had ever contained one — the page-size check mark, the paging
  item's two wordings and the two disabled rules were asserted by `AppStateAggregationTest` and
  looked at by nobody. The items are `GraphOptionsMenuItems` now, a composable of their own that
  the menu wraps, and `badge-menu-1.png` renders three states side by side: the ordinary one, the
  one where paging is off, and the inverse of the first, where the graph is whole and a group is
  open. Opening the real popup was tried first and does not work offscreen — the same menu drew at
  one scene height and not at another.

- **A parent whose pages were opened can be put back to one page, without touching any other
  parent.** Opening every page of a node removed the last `GroupNode` beside it, and with it the
  only thing on the canvas that knew those siblings were paged — so the only way back was "collapse
  every group", which closed the other parents too. The inverse now hangs off the parent itself,
  as a `Back to one page` action in the inspector's header row, drawn only when that parent has
  pages open. Which groups belong to a parent is decided by *generating* the candidate ids and
  intersecting, never by parsing one: a group id is `grp_<parentId>_<kind>_<page>` and a parent id
  contains underscores, so taking the parent back out of it is ambiguous.
- **A `bucket[N]` partition field now prunes on equality.** It used to report that it did not
  evaluate, and that was the right answer while it lasted: pruning a bucket means computing
  `bucket(v)`, and a hash written from a spec agrees with itself long before it agrees with the
  writer — a wrong bucket number would skip a manifest holding the rows. What changed is the
  evidence. The bucket is computed with `Hashing.murmur3_32_fixed()`, the same Guava function
  Iceberg's own `Bucket` transform calls, and the result is checked against the bucket numbers
  Spark recorded for `parted` and `respec` — the latter bucketing the same ids at both 4 and 8, so
  a modulus applied at the wrong point cannot pass. Every other operator still declines, because a
  range of bucket numbers says nothing about a range of values. A bucketed column is now offered
  in the filter's column list as one that prunes manifests.
- **The comparison works for Paimon too, through one seam and two unrelated answers.**
  `ComparableSnapshot` is what the panel reads, so it never asks which format it is drawing; each
  node type answers its own way. Getting there meant extracting Paimon's replay out of the graph
  builder into `model/PaimonReplay.kt`: it already computed the live file set on its way to the
  figures and threw it away, so both now come out of the same walk rather than a second one being
  written beside it. The figures are unchanged — that is the safety property of the extraction and
  the whole existing Paimon suite is what checks it. Paimon's node reports no parent snapshot
  rather than inferring `id - 1`, which is a convention nothing records and which a rolled-back
  table breaks.
- **Any two snapshots can now be compared, not just a commit against its parent.** Selecting
  exactly two snapshots on the canvas replaces the multi-select summary with a comparison: what
  each side holds that the other does not, the net change in files, records and bytes, and the
  files themselves. "What this commit did" answers a different question and neither replaces the
  other — that one reads the manifests one commit wrote and is defined only against that commit's
  parent, so it cannot say anything about a branch tip against `main`, or about two snapshots ten
  commits apart. This is a set difference between two complete live file sets, so the two need no
  relationship at all. Both sides are folded through the same ledger the table's own `current`
  figures come from, which is what lets the test use those figures as an oracle across all eight
  fixtures. The walk is deferred, so a table of twenty commits never walks twenty closures to
  answer a question about two.
- **A positional delete file now says what it deletes from.** The panel used to point at the
  sample rows and leave the reader to read `(file_path, pos)` pairs fifty at a time; "3 delete
  files" never became "and they remove 412 rows from these two files", because that breakdown is
  inside the delete files and nothing on a read path produces it. "Read the file" gives one row
  per targeted data file with the rows deleted and the span of positions, and puts the counted
  total beside the manifest's `record_count` — the same recorded-against-counted move as
  `manifestTallies` and the deletion vector's two figures, and the same reason: a scan plans
  against that figure without opening the file. Behind a button rather than on the graph-build
  path, because a graph is built for every artifact the metadata names. The aggregation is
  DuckDB's `GROUP BY`, so a delete file with four hundred thousand positions costs the same round
  trip as one with a single position.
- **Each column of snapshots is named after its branch.** A fork already drew as a fork — its
  commits took a column of their own and the lineage edge was dashed — but nothing said *which*
  column was `main`. The ref chips sit on whichever commit a ref happens to point at, so reading
  the column meant following the dashes back to a chip, which is the work the columns were
  supposed to remove. A chip above each column now names it. The bottom-most commit names the
  column and only if a **branch** points at it: commits are drawn oldest-first downwards, so the
  bottom of a column is the tip of that line. A tag is rejected at both ends of that rule —
  partway up it marks a point in history rather than the line, and on the tip it names the column
  only by today's coincidence, so `prod` pointing where `main` does no longer prints a second
  name over a line that has one. A column no branch points into is left unnamed rather than named
  after something else.
- **The whole table can be drawn in one action.** "Show all" opened one group's run, so reaching a
  complete graph meant expanding every group of every parent one at a time — and there was no
  route at all to a parent that only appears *because* of an expansion. The badge's menu now
  offers "Draw all N nodes", which switches paging off rather than expanding anything, and the
  count is in the label because that figure is the whole of what is being agreed to. It is a
  decision about the table in front of the reader: opening another table, or choosing a page size,
  turns it back off.
- **A snapshot now says what its commit did.** The panel reports the files that commit added and
  removed, and puts every figure beside the same figure from the snapshot's own `summary` — the
  engine's account of its own work, which sits in `metadata.json` where nothing on the read path
  checks it. A compaction reads: 1 data file added, 5 removed, 6 records added, 8 removed, 5,851
  bytes removed, each agreeing with what Spark recorded. Attribution is by
  `manifest_file.added_snapshot_id`, because an entry's status belongs to the snapshot that
  created its manifest — counting statuses across a snapshot's whole closure would credit every
  commit with all of its ancestors' work.

- **A v3 deletion vector's rows are now readable.** Iceberg 1.8.1 writes deletes for a
  format-version-3 table as a Roaring bitmap inside a Puffin blob, and the panel could say where
  that blob sat and nothing about what was in it. The blob is now opened and decoded, so the
  inspector lists the row positions the vector marks — run-folded, so a compacted file reads
  `0-3999` rather than four thousand lines. Two figures the writer recorded sit beside the count
  decoded from the bytes: the blob's own CRC-32, and the manifest's `record_count`, which a scan
  plans against without ever opening the Puffin file. Nothing on a read path compares either, so
  this does. The reader is written against the Puffin and Roaring specs rather than against the
  checked-in fixture, which holds one array container with one position in it; the array/bitset
  boundary at 4,096 and the run container are pinned by containers built from the spec text.
- **A row a deletion vector removes is drawn as deleted.** The sampled rows of the data file a
  vector covers now carry the word, a strike through their values, and the same fade a pruned node
  gets. The position each row is judged against is asked of DuckDB (`file_row_number`) rather than
  taken from the order the rows arrived in — a scan may return them in any order and nothing in
  the result would say that it had — and it is kept off the row as a column, because it is
  DuckDB's answer about the file rather than something the table declares.

### Changed
- **Changing the page size no longer forgets every other table.** Each cached session records
  the paging its graph was drawn under, and a later visit whose policy differs redraws from the
  table model already in memory — one layout, no read of the metadata tree. The same check pages
  a table again on return after it was drawn whole; before, a cache hit restored the whole graph
  under a badge saying it was paged at 24. `AppStateAggregationTest` proves the redraw takes no
  read by deleting the table's directory before the second visit.
- **The recorded-first path rule is written once.** Metadata files and data files agreed on when
  a recorded path wins and differed only in the fallback, so `resolveRecordedOr` takes the
  fallback as a parameter and both callers go through it.

- **The table panel's three timestamps moved out of its identity table into a folded
  `Table Times` section, and say what they are.** Each renders local, UTC and epoch, so three
  rows were nine lines and about 600dp — roughly half of what stood between "Collapse all" and
  the list of section names it is supposed to produce, and none of the three is identity. Their
  labels were also wrong about what they hold: Iceberg records no table creation or update time,
  and what was shown as "Table Created (Inferred)" is the oldest **retained** metadata, which
  moves forward every time old metadata expires. They now read "Oldest retained metadata",
  "Newest retained metadata" and "Current metadata last-updated-ms", with that caveat on screen.
  The identity table itself still does not fold, which is deliberate — it is what the reader
  selected the node to see.

### Fixed
- **A file's sequence number is shown even when the file does not record one.** Iceberg leaves it
  out of every entry a commit adds and keeps it once on the manifest, so three of the four files in
  the merge-on-read fixture were printing `N/A` for a number the format defines exactly. The panel
  now shows the inherited value and says that it was inherited.
- **A filter holding a quoted value survives the trip through the form.** The clause is written back
  from the filter when the editor opens, and it was written back without the quotes it needed — so
  `ts > '2024-03-05 10:00:00'` came back as two values and the reader's own accepted filter read as
  a syntax error.
- **The filter clause is readable at the width the panel is actually used at.** It wrapped nowhere
  and scrolled sideways instead, so the inspector at its normal width showed about a dozen
  characters of it — enough to lose track of your own parentheses. It now wraps to four lines, with
  the line spacing its own text size asks for rather than the 24sp Material's body style was
  handing it.
- **A pruning row no longer contradicts itself.** With `OR` in the filter, a term that ruled out
  its own condition stopped meaning the artifact was ruled out — so a manifest could show
  "would be read" beside the reason a skip would have carried. The reason now explains the verdict
  it sits next to, and a skip lists every term that proved it rather than the first.
- **The main line no longer loses its column to a branch.** At a fork, the column the parent was
  drawn in went to whichever child was *written* first — so a branch that received a commit before
  the trunk's next commit took the trunk's lane, the main line stepped sideways halfway down the
  graph, and the column header above the root commit read as the branch's name rather than
  `main`. The trunk's commits are now preferred at every fork. Visible only with two branches
  forking at different points, which is why it survived: the single-fork fixture cannot produce it.
- **A table in object storage now carries its format badge.** The ICE / PMN chip was computed
  through `java.io.File`, which is null for every `s3://` path, so remote tables drew without one.
  The badge comes from the sweep rather than from the row: which of the two globs matched a table
  *is* its format, so the warehouse listing already knew, and asking the detector in the row would
  have put a network round trip per table on the thread that draws frames.
- **A refused key was reported as an empty warehouse.** `ObjectStorage.globTables` wrapped both of
  its globs in a `runCatching { }.getOrDefault(emptyList())`, which could only ever absorb a real
  failure — `glob` already answers an empty list for a prefix with nothing under it. So a key that
  had stopped working produced exactly the answer an empty warehouse produces: the tables vanished
  from the list, and the store's own message, which says what is wrong and what to do, was seen by
  nothing but a log line. A warehouse offered nothing to open that might have said otherwise.
  A listing that could not be done is now a third answer rather than the second one. The sweep
  carries the reason back, the root **keeps the tables it last had** instead of blanking, and the
  message is drawn under the root with a **Credentials…** button beside it that reopens the form
  for that location. Since the secret is held for the session only, this is the ordinary state
  after a restart, not an exceptional one — which is why the way out is a control on the root
  rather than knowing that "Add object storage…" doubles as "edit".
- **A remote warehouse was drawn as deleted from the moment it was added.** The workspace row
  asked `File(path).exists()`, which is false for every location in object storage — `s3://` is not
  a path on this machine — so a bucket whose tables listed and opened perfectly well sat in the list
  in error red with "(deleted)" beside it. Whether a remote warehouse is still there is a question
  about the store, and the row now claims nothing until a sweep has asked it.
- **A table in object storage could never be seen to change.** `ObjectStorage` caches a directory
  listing per prefix so a graph build is not a round trip per data file, and the fingerprint the
  poll compares is the set of file names under `metadata/` — so the fingerprint was answered from
  the cache and returned the same value forever, whatever was committed to the table. Nothing
  cleared those caches when a table was opened either, so an explicit reload re-decoded the
  metadata the table used to have. `ObjectStorage.invalidate(prefix)` now drops both the listings
  and the bytes under a prefix, and the fingerprint invalidates the three prefixes it is about
  before listing them. The regression test writes a second object through DuckDB and asserts both
  directions — still stale without the invalidation, current with it.
- **Remote roots are polled on their own cadence.** The workspace sweep and the open table's
  fingerprint ran every three seconds, which for a remote warehouse is two recursive globs against
  a store that bills per request — 1,200 requests an hour per root for a table nobody is committing
  to. Remote work now runs every 30 seconds (`REMOTE_POLL_INTERVAL_MS`); local roots keep the
  three-second cadence, since a local check is a `stat` against a warm page cache. A sweep that
  skips the remote roots omits them rather than reporting them empty, so a warehouse is never
  briefly emptied on screen.
- **The workspace poll no longer freezes the window every three seconds.** `refreshWarehouseTables`
  walked every warehouse directory tree on the main thread, on a three-second timer. Measured on a
  warm cache and a local disk: 19ms at 200 tables, 90ms at 1,000, and 226ms at the 10,000-directory
  cap the scan stops at — several dropped frames, repeating for as long as the window is open. The
  walk now runs on an IO dispatcher and only the state update happens on the main thread. Two
  hazards the asynchrony introduces are closed and pinned by tests: a root removed while a sweep is
  running is not resurrected, and a root added while one is running is not reported as empty.
- **Every offscreen capture was drawing its animations at time zero.**
  `ImageComposeScene.render()` defaults its `nanoTime` argument to the constant `0`, so repeated
  no-argument renders are repeated copies of the first instant — a valid frame with the animated
  part missing, which looks like nothing is wrong. It had produced a wrong conclusion recorded as a
  project convention: two byte-identical focus captures were read as proof that Material draws
  nothing for focus, when what they showed was a state-layer fade given no time to run. The focus
  captures now pass an advancing clock, and the convention has been corrected. Every other capture
  helper was put on the same clock and all 104 written PNGs were compared against their
  frozen-clock originals: 103 came back byte-identical, so nothing else in the suite had been
  photographing a state the app does not draw. The one that moved is a focus capture whose panel
  scrolls, and it moved by a pixel.
- **The menu's heading now starts where its choices start.** "Siblings drawn per parent" was
  padded like a menu item and the items are indented past a check slot, so the heading sat 24dp
  to the left of every number under it. Found by the first render of the items.

- **A label in the inspector's key column broke in the middle of a word at the width the pane
  opens at, and the hover tooltip had no capture at all.** `DetailRow`'s key was
  `Modifier.weight(0.20f)` — a fraction, for a column whose content is a vocabulary this
  application chooses rather than one the table decides. At 300dp that share is about 62dp, and
  every label holding an eight-letter word fell back to breaking at a character: `Sequenc / e
  Num.`, `Timesta / mp`. No share fixes it, because the one that fits `Statistics` at the 200dp
  minimum is 43%, which is 600dp of label at 1400dp. `DetailTable` now derives one width for all
  of its rows from its own width, clamped to 84–190dp, so the values stay aligned on one x and the
  labels always fit. The wide panel gained about 100dp of value column from the same change.
  The divider also had a gutter after it and none before, so a label filling its column sat flush
  against the rule; it has one on both sides now.
  Deriving a width means measuring, and `NodeTooltip` — the one other `DetailTable` caller — sized
  itself with `Modifier.width(IntrinsicSize.Max)`, which asks a layout how wide it wants to be
  *without* measuring it. A `BoxWithConstraints` cannot answer that and throws. Nothing in the
  suite rendered the tooltip, so this would have reached a hover in the running app; it is swept
  for every node kind now, and the tooltip states its width instead of asking its content for one
  — which also stops a tooltip's width being decided by the longest data-file path in the table.
- **The inspector's header put its buttons past the edge of the panel at the width the panel
  actually opens at.** The title and the action buttons shared a `Row`, which neither wraps nor
  clips and which measures its unweighted children — the buttons — before the weighted title. The
  pane opens at 300dp and can be dragged to 200dp; at that width the buttons took the whole line
  and the title was laid out one character per line underneath them, with the rightmost button
  painted past the panel edge where nothing could reach it. The title now takes its own line,
  capped at two, and the actions are a `FlowRow` that wraps. Found by rendering the panel at 300dp
  rather than at the 1400dp every existing capture uses — a `Row` overflowing is invisible at any
  width where it happens to fit, so `collapse-pages-narrow-1.png` renders it at the narrow one.
- **A node's deferred read was part of its identity, against a comment saying it was not.**
  `FileNode.deletionVectorLoader` was a `private val` lambda in a data class's primary
  constructor, which is a component of the generated `equals` — so two nodes for the same manifest
  entry, built by two graph builds, carried two distinct lambda objects and compared unequal.
  Nothing failed and no test asked; the only symptom was Compose re-composing a deletion-vector
  card that had not changed. It goes through a `DeferredRead` now, whose `equals` states the
  invariant instead of a comment claiming it, and `DeferredReadTest` pins both halves — two nodes
  for one entry are equal, and nodes for different entries are still not.
- **A group card was truncating the word that says what it hides.** At 200dp the count line read
  `6 more metadata versi…` — the number survived and the noun did not, which is the half a reader
  needs. It was one sentence at body size, and no width fits that sentence for every kind: at a
  six-figure count five of the ten overflowed. The noun now sits in the eyebrow and the count on
  the value line — the shape every other card here already has — and the card reads
  `METADATA VERSIONS` over `6 not drawn`. `GroupCardWidthTest` is the bound: it draws each of the
  ten kinds with width slack and requires the content to fit the 200dp the node declares. The
  height sweep could never have caught this, because an ellipsis costs a card no height at all.
- **A collapsed group was losing the line that says it is a control.** `GroupNode` declared a 58dp
  base and the plainest group card measures 60.5, so any group standing for exactly its members —
  no hidden subtree, no read errors — drew "NOT DRAWN", its count, and then nothing: the
  "Double-click to open" hint went under the card's own border, where Compose clips nothing and
  reports no error. It survived the render check because every group the capture drew happened to
  stand for a subtree, and so declared the taller height. `CardHeightTest` now sweeps every node
  of every fixture in both filter states rather than one instance per kind, which is what reached
  the shape that was broken; the capture now draws it too.
- **A deletion vector is named as one.** It declares `content = 1` exactly as a v2 positional
  delete file does, so both cards read `POS DELETE` and only the `.puffin` extension told them
  apart. The card, its tooltip and the inspector title now say `DELETE VECTOR`, from one function
  rather than the three copies of the decision that existed before.
- **A file card's row count says what the number counts.** `record_count` is rows held for a data
  file, rows removed for a positional delete or a deletion vector, and predicate tuples for an
  equality delete — where one tuple can remove thousands of rows. All three read `1 row`; they now
  read `1 row`, `deletes 1 row` and `1 equality row`.
- **A file node reserves 68dp instead of 60.** `FILE 5: DELETE VECTOR — NOT READ` is the longest
  first line a file card can draw and it wraps at 200dp, and the verdict is appended when a filter
  is on — after the layout that reserved the height. The card was losing its last line silently,
  which is the failure `CardHeightTest` exists for and which it did not see, because it measured
  no pruned card and no vector. It measures both now.

- **Pruning now answers a file count, not just a manifest count.** Iceberg prunes twice — a
  manifest by the partition summaries its list records, then a file by the bounds it records about
  its own columns — and only the first stage was modelled. The panel now reports both, and leads
  with the file line, because "would read 1 of 4 data files" is the number a reader arrives with;
  a query reports how many files it read and never which. Three consequences worth knowing:
  an **unpartitioned** table is no longer told there is nothing to do here, since its file bounds
  still prune; a **bucketed** column, which no range can rule a manifest out on, still eliminates
  files, because file bounds are the plain source values; and `IS NOT NULL` can be settled at the
  file stage, which records how many values it holds and how many are null, where a manifest
  summary records only that a null exists somewhere. A file under a ruled-out manifest reads
  `not reached` rather than `skipped` — a scan never opens it, so the verdict is not its own — and
  the canvas fades every node the query does not touch, files included.

### Changed
- **A card's height is now a bound rather than a coincidence, and the graph is shorter for it.**
  Most cards printed a file name with no line cap, so "does this fit" had only ever been asked of
  the short names eight checked-in fixtures carry. Under a catalog's own
  `00147-<uuid>.metadata.json` the metadata card measured *exactly* its declared height, with
  nothing left for the rounding another display scale does to a font metric. Every text a table's
  content can lengthen is now capped at the lines its node reserves, which makes the measurement a
  worst case — and against that, the snapshot card came down from 112dp to 88 with ref chips and
  from 84 to 68 without, roughly 25dp of empty space under every snapshot in the graph. The cards
  that had measured at exactly their declared height gained 4dp instead. The Paimon heights are
  left alone: there is one Paimon table checked in, with one snapshot, so a conditional line that
  never appears in it would be invisible to the measurement.
- **A verdict column now marks only the exception.** Every leading cell in the pruning and tally
  tables was bold, because each row supplied a colour and `WideTable` bolded any cell that had
  one — including the neutral colour that exists to say "ordinary". The ordinary rows now pass no
  colour at all and read as body text, which leaves `SKIPPED` as the one bold, green cell in its
  column.
- **The toolbar is its own file.** 266 lines came out of `App.kt`'s thousand-line composable into
  `Toolbar.kt`, with the snapshot-filter menu — half of it by line count — split off again into
  its own composable. It is stateless: it reads values and reports intent, and every write to
  `java.util.prefs` stays with the caller that owns the value. The question the extraction had to
  answer was which of `App`'s mutable state the toolbar actually touched, which could not be read
  off the old code; it was eight things, and the signature now says so.
- **The two canvas modes say what they do.** The toolbar's tooltips read "Pan mode — drag the
  empty canvas to move the view" and "Select mode — drag the empty canvas to select what it covers
  (hold Shift to add or remove)". They named the modes before, which tells a reader nothing about
  the one thing the modes disagree about: a node is dragged and the wheel pans in either. Tooltip
  text now wraps at 280dp instead of running off the window.
- **Five text sizes instead of eight.** Every size in the desktop shell now comes from
  `TypeScale`, at a ratio near 1.2 — 10 / 12 / 14 / 17 / 21. What was there before ran from 8sp
  to 16sp in steps averaging 1.09x, picked one call site at a time, which is under the difference
  at which a size reads as deliberate: the screen had eight sizes and one apparent level. Card
  text is slightly larger throughout as a result, and the inspector's section titles and node
  header are now distinct from body text rather than a little heavier than it.

### Added
- **The app has an icon.** A lens set down on a stack of records: the bars are the metadata rows
  the app reads, and under the ring they turn accent-coloured. Two shapes, which is about what
  survives being drawn at 32 pixels. `tools/icon/GenerateIcon.java` draws it and writes every size
  the three installers want, including the `.icns` and `.ico` containers — both written directly
  rather than shelled out to `iconutil`, which exists only on macOS and would have made the
  Windows icon regenerable on a Mac and nowhere else. Deliberately not an iceberg and not Apache
  Iceberg's mark: the app reads Paimon too, and the name is already an open trademark question.
- **The workspace answers to the keyboard**, with one rule the other two lists do not need:
  moving the cursor does not open a table. On the canvas and in the tree the selection *is* the
  cursor, because selecting costs nothing; here, opening a table reads its whole metadata tree off
  disk, so holding Down through a warehouse of forty tables would load forty of them. The cursor
  moves, and Enter opens what it is on. Arrows expand and collapse a warehouse as they do
  everywhere else, and the cursor is drawn only while the list holds the keyboard, so it cannot be
  mistaken for a second selection.
- **The structure tree answers to the arrow keys too**, with the keymap every file browser uses:
  up and down move a line whatever its depth, right opens a closed line and otherwise steps into
  it, left closes an open one and otherwise steps out to its parent. The selection is the cursor,
  so there is no second highlight to keep in step and the existing scroll-into-view already brings
  a keyboard move on screen. The rules are stated against the flattened list rather than against
  the graph — the first child of an open line **is** the line below it — so the keyboard cannot
  disagree with what is drawn. Clicking a row hands the list the keyboard as well, and the list
  shows a border while it holds focus.
- **The graph answers to the arrow keys.** Left and right move to the parent or the child drawn
  across from you; up and down walk the column. Nothing selected and the first arrow lands on the
  root. The step is decided from where the nodes are actually drawn rather than from the order a
  comparator would put them in, which is what makes a fork behave: a branch has a column of its
  own, so walking down the main line does not step sideways into a commit that merely sits at a
  similar height. Lineage and deletion-vector edges are not steps — they are annotations over the
  tree, which is why they are drawn dashed, and following them would make one keystroke mean two
  different things by "parent". Bare arrows only, so Cmd+Left still means "back" and Alt+Arrow
  still moves by word in the field beside the canvas.
- **Inspector sections fold.** Every titled section in the inspector — 39 of them across the node
  types — now carries a caret and folds away, and the panel header has one button that collapses
  or expands the lot. Folded, a table's panel is a list of what the table holds: eight lines
  instead of ten thousand device-independent pixels of scrolling to find out that "Table
  Properties" is down there. The fold state is keyed on the stable half of the title, so a
  section whose heading carries a live count ("Manifest Entries (12)") does not silently re-open
  when the count moves, and it is held for the panel rather than per node, because a reader who
  folds "Raw metadata.json" away means it for the table.
- **A card that outgrows its node now fails a test.** `CardHeightTest` draws all thirteen card
  shapes with 400dp of room to spare and requires each to have fitted inside what its node
  declares. This was the missing half of a change to a text size: a card drawn at its own height
  does not report that it wanted more — the `Column` simply does not place its last child, every
  `Text` clips itself to what it was measured at, and nothing is painted outside the border for a
  pixel probe to find. Measuring the same card with slack is what makes the overflow a number.
- **Four more numbers explain themselves.** A manifest's inspector now says how its counted
  figures were reached — every entry takes a place in the entry count because that figure
  measures what a scan reads, and what it adds beyond that depends on two rules — and lists the
  entries that added nothing, with the reason and what each would have added. It is the same
  `manifestLedger` the table's totals are folded from, called with deduplication scoped to the
  one manifest and saying so. A column statistic's decoded bound now sits directly beside the
  bytes it was decoded from, with the field id, and names the schema it was read against: the two
  used to be at opposite ends of an eleven-column table, so the value was on screen and its
  evidence four columns past the panel edge. A snapshot's summary is marked as the one set of
  figures the tool reports rather than computes. And a snapshot's manifest-list overview carries
  a "Summary" column saying whether each manifest's recorded counts match its own entries.
- **A fork is drawn as a fork.** A branch now gets its own column inside the snapshot layer,
  assigned the way `git log --graph` does it: a commit takes the column its parent kept for it,
  the first child continues in the parent's, and every later child opens a column and holds it
  empty until the drawing reaches it — so the two branches read as parallel rather than as one
  list with an edge reaching back over it. A table with no branches is untouched: every commit
  lands in column 0 and the pass returns before moving a node. Lineage edges, and the v3
  deletion-vector edges beside them, are now dashed. They are the only edges whose two ends can
  sit side by side, and drawn solid they were indistinguishable from the parent-child edges
  crossing the same gap.
- **Enter a filter, see which manifests a scan would skip.** The table inspector takes a
  conjunction of conditions — a column, an operator, a literal — and reports, per manifest,
  whether a query carrying that filter would open it, with the term and the numbers that decided
  it: "d_day — 2024-03-06 is above 2024-03-05 … 2024-03-05". The skipped manifests fade on the
  canvas, and each manifest's own inspector shows every term's outcome against the bounds it was
  measured with. Iceberg computes this during scan planning and then discards it — a query
  reports how many files it read, never which it skipped or which predicate did the skipping —
  so it is not available from any engine surface. Each manifest is evaluated against **its own**
  partition spec, the same rule the bounds follow. The literal is bridged to the partition value
  only through order-preserving transforms; `bucket[N]` reports that it did not evaluate rather
  than a verdict that might be wrong, because pruning equality on a bucket means reproducing
  Iceberg's murmur3 and there is no oracle here for it. `SKIPPED` is a proof, "would be read" is
  only the absence of one, and a manifest nothing could be evaluated against is counted apart
  from both.
- **The canvas states what it is not drawing, and how much of a page it draws is a setting.** A
  badge in the bottom-left corner reads "Drawing 431 of 6,180 nodes" and, under it, why the rest
  are missing — "5,749 inside 37 collapsed groups", "335 removed by the snapshot filter". The two
  reasons are counted separately because they are undone separately, and each missing node is
  attributed to one of them, so the figures sum to the whole table rather than double-counting.
  Clicking the badge sets the page size, which was previously a constant only a recompile could
  change; changing it clears the expansion (a group id names a page *at a size*) and drops every
  cached graph but the one on screen (a graph drawn at the old size disagrees with the badge over
  it). The group inspector gains "Show all N manifests" beside "Show the next page", because
  5,000 manifests is 208 double-clicks at 24 to a page.

- **The graph draws a page of siblings and says what it is not drawing.** Under one parent, the
  first 24 siblings of a kind are drawn; the rest become one node that states how many there
  are and opens on a double-click, revealing a page at a time. This replaces both halves of the
  old behaviour: everything else was drawn without limit, which no production table survives,
  and data files were capped at ten per manifest, which made a manifest holding 5,000 files
  look exactly like one holding ten. The pass is a pure function over a finished graph, so both
  formats get it from one implementation. Three properties it holds, each pinned by a test that
  fails without it: a manifest shared by several snapshots survives when only one of them
  collapses it (removal is by reachability, not by subtree); every node is either drawn or
  claimed by exactly one group, so the counts add up to what actually went; and a read error is
  never folded into a group, with any error that leaves under a collapsed manifest counted on
  the group and shown in red on its card. Sample rows are now read after aggregation, for the
  data files that survived, rather than for every file in the table.
- **Summary figures explain themselves.** `TableSummary.current` and `.history` are no longer
  stored numbers; they are folded from a per-manifest ledger (`StatsDerivation` /
  `ManifestContribution`) and exposed as getters over it, so a figure cannot disagree with its
  explanation. Each contribution records what one manifest added, how many of its entries named
  a file another manifest had already counted, and — for a manifest a later snapshot re-lists —
  which snapshot counted it first. The table inspector prints the ledger directly under each set
  of figures. Paimon contributions can be negative, because it applies a snapshot's delta
  manifest list over its base and a fold has no other way to express a removal.
- **Delete files, a compaction, and format-version 3 are covered by real tables.** Three new
  fixtures, each checked against Iceberg's own metadata tables rather than against this code:
  `example/iceberg/default/mor` (merge-on-read v2 — three positional delete files and a
  `rewrite_data_files` compaction that leaves two of them dangling),
  `example/iceberg/default/eqdel` (both delete kinds in one table; the equality delete is
  written with Iceberg's own `EqualityDeleteWriter`, since Spark has no SQL that produces one),
  and `example/iceberg/default/v3` (format-version 3, whose merge-on-read deletes are Puffin
  deletion vectors rather than parquet delete files). Before these, `posDeleteFileCount`,
  `eqDeleteFileCount`, `deleteRecordCount` and the `DELETES` manifest branch were decided by
  code no real table had ever run through.
- **A recorded path is used when the file is there.** Manifest lists and manifests resolved by
  discarding the recorded directory and matching the file name against the local `metadata/`
  dir — the behaviour that makes a table copied down from object storage openable, but which
  also meant a `write.metadata.path` layout resolved to a file that was not there. The recorded
  path now wins when it is absolute and exists; everything else falls back exactly as before.
  The strategy used is shown in the snapshot and manifest inspectors.
- **Manifest partition ranges.** `manifest_file.partitions` is read and decoded — the
  per-partition-field bounds a scan intersects with a query predicate to decide whether to open
  a manifest at all. Shown in the manifest inspector above the entries, using the same
  transform-result resolution as the per-file tuples, so a `year` range reads `1969..2025`
  rather than the stored ordinals. The pairing with the spec is positional, so a length
  disagreement decodes to nothing rather than mislabelling every field.
- **A changed partition spec and a branched history are covered by real tables.**
  `example/iceberg/default/respec` has two specs (a dropped field, `bucket(4)`→`bucket(8)`,
  `days`→`months`), closing the partition half of the schema-resolution rule.
  `example/iceberg/default/branched` has a fork, five refs including two tags, a snapshot
  carrying two refs, and ten metadata versions — the first fixture that can tell a numeric
  ordering of `vN.metadata.json` from a lexicographic one.
- **A delete file says what it deletes from.** The inspector answers per kind: a v3 deletion
  vector names its data file and byte range (`referenced_data_file`, `content_offset`,
  `content_size_in_bytes` are now modelled), a v2 positional delete keeps its targets in its own
  `file_path` column, and an equality delete has no recorded target at all — stated where a
  reader would look for the link rather than left blank.
- **Snapshot lineage and refs.** `parent-snapshot-id` is drawn as an edge, and the branches and
  tags pointing at a snapshot appear on its card and in its inspector. Lineage edges carry
  `affectsLayout = false`: a parent is another snapshot, so letting them constrain a layered
  layout puts every commit in its own layer — measured at 2.10x the graph width on a six-commit
  table, growing with history length. Refs are read from the latest metadata version, since
  `main` moves with every commit. The inspector distinguishes a first commit from a parent that
  has been expired away. Snapshots are ordered by lineage rather than timestamp, so each branch
  stays contiguous; on an unbranched history the two orderings are identical.
- **The schema-resolution rule is tested, not just asserted.** `example/iceberg/default/evolved`
  carries three manifest schemas in one table (`int`→`long`, `float`→`double`, a column renamed
  then dropped, another added), so a manifest's own schema genuinely differs from the table's
  current one. Every previous fixture had a single schema, which made the two the same object
  and let a decoder reading either pass.
- **The inspector renders off-screen in the test suite.** `InspectorRenderTest` draws the panel
  into an image with no window and no screen-capture permission, writing PNGs to
  `desktop/build/reports/inspector/`. It is both a regression check no data-level test can make
  — a composable that throws while measuring fails here and nowhere else — and the way the
  drawing can be looked at on a machine where screen capture is unavailable.
- **Partition values are decoded and readable.** `data_file.partition` is a nested Avro record
  whose schema comes from the table's own partition spec, so avro4k's static decode could not
  reach it and the inspector's Partition column had shown a hardcoded `"N/A"`. The file
  inspector now lists each partition field with its transform, source column, result type,
  value and stored bytes, and the manifest-entries table carries the tuple in the same
  `name=value` form Iceberg writes into the data file's path.
- Partition values are decoded against the spec the *manifest* carries in its own Avro file
  metadata, not the table's current spec — a repartitioned table describes each file by the
  spec in force when it was written, and the current one mis-decodes silently.
- Transform result types are resolved rather than assumed: `day` yields a `date` while `year`,
  `month` and `hour` yield `int` ordinals counted from the epoch. All four occupy the same four
  little-endian bytes, so the wrong choice produces a plausible value instead of an error. Both
  readings are shown — a `year` partition for 2024 stores `54` and renders as `2024`.
- `example/iceberg/default/parted`, a partitioned fixture written by Spark 3.5.5 / Iceberg
  1.8.1: eight partition fields across all the transform shapes, two snapshots, a pre-epoch row
  and a negative decimal. The tests assert the decoded tuple equals the directory Iceberg chose
  for each file, which is an oracle this codebase did not produce. Regenerate with
  `docs/fixtures/parted.sql`.
- **Column statistics are decoded and readable.** A manifest stores per-column stats as five
  parallel maps keyed by field id — `column_sizes`, `value_counts`, `null_value_counts`,
  `nan_value_counts`, `lower_bounds`, `upper_bounds` — which the inspector previously showed as
  raw `1:40, 2:56` strings and hex. They are now pivoted into one row per column with bounds
  decoded to values, alongside the raw bytes so a decode can always be checked.
- `AvroReader` keeps each file's Avro key-value metadata. Iceberg records the schema a manifest
  was written against under its `schema` key; that — not the table's current schema — is the only
  correct source for decoding its bounds, and using the current one mis-decodes silently across a
  type change. `UnifiedManifest`, `ManifestNode` and `FileNode` carry it.
- `ColumnStats` / `columnStatsFor()` in core, with an all-null flag so an absent bound on a
  populated column is explained rather than blank.

### Changed
- **Split into `:core` and `:desktop` Gradle modules.** `core` is the headless engine — readers,
  decoders, model, analysis, ELK layout — and may not depend on a UI toolkit; a Gradle check
  fails the build if a Compose or AndroidX artifact reaches its compile classpath. `desktop` is
  Compose over it. A server, CLI or IDE plugin attaches as a sibling of `desktop`.
- `GraphModel.initialPositions` → `layoutPositions: Map<String, Point>`, immutable. The
  Compose-observable drag map moved to `ui/NodePositions`, so a `GraphModel` is now cacheable,
  comparable, serialisable and safe to build off the main thread.
- `SnapshotFilter` moved to core (pure graph work); `ToolWindowTypes` moved to desktop (holds an
  `ImageVector`).

- **The manifest inspector shows each recorded count against the entries it summarises.** The six
  counts in `manifest_file` — added, existing and deleted files and rows — are what a scan reads
  to plan without opening the manifest, and nothing on the read path checks them. They now appear
  beside the same figures folded from the manifest's own entries, with a column saying whether
  the two agree; a count the writer omitted, which v1 permits for all six, reads as "nothing to
  check" rather than as agreement. The section is drawn even when no entries were read, because a
  manifest list claiming three added files over a manifest that yielded nothing is the case most
  worth seeing.
- **A deletion vector is drawn pointing at the file it deletes from.** `referenced_data_file` is
  the only delete-to-data link Iceberg records — a positional delete file names its targets one
  per row, and an equality delete names none — so the graph now draws that one and keeps
  explaining the other two in the inspector. The edge is withheld from ELK, like commit lineage:
  both ends are data files in the same layer, and letting it constrain layering would push the
  referenced file a whole layer to the right of a node it sits beside.

### Fixed
- **The build's one flaky test measured the machine's load rather than the graph builder.**
  `PerformanceTest` compared the *sum* of three unwarmed builds at two table sizes against the
  size ratio, so it failed at a ratio of 25.25 during a build that was rendering Compose scenes
  alongside it and passed three times in a row when run on its own — a red build there meant
  nothing. It now takes the fastest of seven warmed trials at each size, which a busy machine can
  only make slower, never faster. Measured at 5.35 alone, 5.90 and 7.03 under a full concurrent
  build, against a threshold of 22.4.
- **Every card on the graph was drawn over its neighbour on a display scaled past 100%.** The
  canvas positioned each node with `Modifier.offset { IntOffset(...) }`, which is specified in
  device pixels, and sized the card inside it with `Modifier.size(...dp)`. At 100% the two spaces
  agree and nothing is wrong; at 200% every card doubled and none of them moved. The graph model
  is dp, the surface is pixels, and `zoom * density` is now the single conversion between them —
  applied to node placement, edge drawing, drag deltas, the marquee rectangle, culling, the pan
  clamp, zoom-to-fit, scroll-into-view, the tooltip and the mini-map, because all of them shared
  the assumption. Two things came out of the same pass: scroll-into-view scaled the *difference*
  between the node and the viewport centre instead of the node's own coordinate, so it only
  centred correctly at a zoom of exactly 1; and the mini-map's viewport rectangle, which is
  larger than the map whenever the whole table fits on screen, was drawn straight out of the map
  and across the graph, because Compose clips nothing by default.
- **A data file recorded outside the table directory is now read where the table says it is.**
  Manifest lists and manifests have preferred the recorded path since the `write.metadata.path`
  fix; data files were still always rebuilt under the local table root, so a `write.data.path`
  table — or one registered against data written elsewhere — reported every file missing while
  they sat on disk exactly where the manifest said. The fallback is unchanged and still opens a
  table copied down from object storage. The file inspector now shows the path being read and
  which of the two rules produced it.
- **`TableMetadata.lastSequenceNumber` was `Int` where the spec says `long`.** Not a misread but
  a refusal: one out-of-range field and the whole `metadata.json` fails to parse, taking the
  table with it. Every other sequence number in the model was already `Long`.
- **The first page under a snapshot was the wrong page.** Aggregation chose which siblings to
  draw in the order the builder emitted the edges. That is the right order under the snapshot
  that wrote a manifest and the wrong one under every later snapshot that carries it forward,
  since the manifest is emitted once and each later snapshot inherits a position it did not
  choose. `SiblingOrder` now holds one definition of the order per kind, read by layout to decide
  which sibling sits above which and by aggregation to decide which are drawn at all.
- **Sample rows were exempt from the page size.** They are attached after the pass that bounds
  every other kind, so the pass now runs again over them. Five rows per file sits below the
  default page size of 24, so nothing changes there — but the page size is now a setting, and 8
  is one of its choices.
- **Node cards were painting half their lines under their own border.** A card is sized to the
  height its node declares and Compose clips nothing, so the surplus was drawn and never seen.
  Material3's body style carries `lineHeight = 24.sp` and a `Text` that overrides only
  `fontSize` inherits it, so a 9 sp label occupied 24 dp and five lines wanted 136 dp of card.
  The table card's snapshot count and current metadata version, the metadata card's snapshot
  count and current snapshot, and the file card's row count were invisible on every table in
  the app. Card bodies now lay text out by the font's own metrics; no node size changed.
- **"1 rows" on a single-row data file**, and a record count printed without a thousands
  separator beside one that had one.
- **Every row of the manifest-entries table was eight lines tall.** A row is as tall as its
  tallest cell, and at a uniform 180dp the partition tuple and the bounds maps each wrapped to
  the line cap — so two entries did not fit on a screen. Columns are sized to their content and
  the cap is four lines.
- **The inspector's wide tables hid their most important columns.** Partition and Column
  Statistics both led with `Field ID` at the same 180dp every other column got, which in a
  700dp panel left the decoded value and both bounds off the right edge — with no cut, shadow
  or scrollbar to say the table continued. Columns are now sized individually and ordered so
  the answer precedes the identifiers, and a horizontal scrollbar appears when the content
  overflows. Found by rendering the panel and looking at it.
- **The table summary's Oldest / Latest rows were unreadable.** Two three-line timestamps were
  joined into one cell, so the second block's first line ran onto the first block's last, the
  arrow between them landed mid-paragraph, and the second epoch was pushed past `maxLines` and
  never drawn. Split into one row each.
- **Build output was tracked in git.** `.gitignore` carried a root-anchored `/build/`, which
  stopped covering anything when the tree split into `:core` and `:desktop`; 839 build
  artifacts had been committed and every build dirtied the working tree. Now `**/build/`.
- **A manifest whose records carry no `partition` field failed to decode entirely.** Avro
  1.12's `GenericRecord.get(String)` throws for an unknown field rather than returning null, so
  reaching for a field the writer never wrote failed every record in the file — the manifest
  read as empty with each entry reported as a decode error.
- **A missing `version-hint.text` was reported as a table read error.** The file is written
  only by HadoopCatalog / HadoopTables; tables managed by Hive, Glue, REST or Nessie catalogs
  never have one, so the majority of real Iceberg tables opened with a red TABLE READ ERROR
  node attached. Absence is now normal; a file that exists but cannot be read is still
  reported. `versionHint` / `TableSummary.versionHintText` are nullable.
- **Per-manifest caps hid entries silently.** The graph draws at most
  `MAX_FILES_PER_MANIFEST` child nodes per manifest, and the inspector's entry table was built
  from those same children — so a manifest holding 5,000 entries was indistinguishable from
  one holding 10. Manifest nodes now carry every entry: the graph still draws the first ten,
  the card says "10 of 5,231 shown", and the inspector lists all of them.
- **The `showRows` branch re-derived data-file paths a second way**, from a `"/<tableName>/"`
  string marker, while `UnifiedManifest` had already resolved them and the row loader read
  from that resolved path. When the two disagreed, rows were silently dropped for files that
  exist on disk.
- **Table summary counts described the traversal, not the table.** `manifestEntryCount`,
  `dataFileCount`, `posDeleteFileCount`, `eqDeleteFileCount` and `totalRecordCount` were
  incremented per visit while walking metadata → snapshots → manifests → files. Because
  Iceberg shares structure on purpose — one manifest is referenced by every snapshot that
  carries its files forward, and every snapshot is re-listed in every later `metadata.json` —
  these figures multiplied with commit history. A two-commit fixture reported 4 manifest
  entries where 2 exist; the error grows with the number of retained metadata versions.
- `status=DELETED` manifest entries were counted as live data files.
- Delete files' `record_count` (a count of delete records) was added to the table's row count.
- Paimon current-state figures counted every ADD entry ever written instead of applying the
  snapshot's delta manifest list over its base, so a compacted table reported every file it
  had ever held.
- Table node card labelled `manifestEntryCount` as "Data Files".

### Added
- `TableSummary.current` / `TableSummary.history` (`ContentStats`) — the table as it is now
  (manifest closure of `current-snapshot-id`, live entries only; `recordCount` is the figure
  `SELECT count(*)` should agree with before deletes are applied) alongside everything still
  reachable from any retained snapshot (deduplicated by manifest and data-file path — the
  "what can I not expire yet" view). The inspector renders both under their own headings.
- File and record byte totals: `dataSizeBytes` / `deleteSizeBytes` per group, so table size on
  disk is visible without leaving the app.
- `ContentStats.deletedEntryCount` — entries recording a removal (Iceberg `status=DELETED`,
  Paimon `_KIND=1`), counted toward scan cost but contributing no files, records or bytes.
- `formatCount()` / `formatBytes()` / `formatBytesExact()` in `FormatUtils` — thousands
  separators and binary byte units for the figures a data engineer compares against engine
  output. Folds in the private copies that lived in `NodeComponents`, whose byte formatter
  divided by 1024 and labelled the result KB/MB/GB.
- Named spec constants `ManifestContent`, `ManifestEntryStatus`, `DataFileContent`,
  `PaimonEntryKind` replacing bare 0/1/2 literals at the sites touched by this change.
- `TableSummaryAccuracyTest` — pins all three miscounts against fixtures laid out the way the
  formats actually write them.
- Snapshot filter now works for Paimon tables (previously Iceberg-only); `asSnapshotFilterOption()` extension unifies both formats
- Sample-row inspector for Paimon nodes (`RecursiveDataTableSection` invoked from every Paimon inspector branch; `collectDescendantRows` descends through `PaimonDataFileNode`)
- 5-entry LRU session cache (was unbounded — could OOM after enough table switches)
- `formatTimestampShort()` — single-line timestamp for fixed-width table cells
- `HoverTooltip` shared composable; tool-window bar icons now show their title on hover
- Multi-select inspector renders a per-node summary table (type + key field per node type)
- Snapshot-filter dropdown is scrollable (`heightIn(max=420dp).verticalScroll`) for tables with many snapshots
- Stack-trace inspector panel: bounded scroll (`heightIn(max=320dp)`) + Copy button
- Toolbar tooltips include keyboard shortcut hints
- Hover cursor on draggable workspace items
- Apache Paimon table format support (data model, reader, unified model, graph types, graph builder)
- `PaimonSchema.kt` — `@Serializable` data classes for Paimon snapshots, schemas, manifest lists, and manifest entries
- `PaimonReader.kt` — reads Paimon snapshot/schema JSON and manifest list/manifest Avro files
- `PaimonUnifiedModel.kt` — aggregated Paimon table model linking snapshots, schemas, manifests, and data files
- `PaimonGraphBuilder.kt` — builds graph nodes and edges from a Paimon unified table model
- Paimon graph node types: `PaimonSnapshotNode`, `PaimonSchemaNode`, `PaimonManifestListNode`, `PaimonManifestNode`, `PaimonDataFileNode`
- `AvroReader.kt` — shared Avro file reader extracted from `IcebergReader`, reused by both Iceberg and Paimon readers
- Paimon node rendering (cards, tooltips, colors, inspector panels) in `NodeComponents.kt` and `NodeDetails.kt`
- Paimon snapshot color per `commitKind` (APPEND=blue, COMPACT=purple, OVERWRITE=amber, ANALYZE=teal)
- Paimon manifest list color per kind (base=gray, delta=blue, changelog=amber)
- Paimon data file color per operation (ADD=green, DELETE=red)
- `TableFormat.PAIMON` detection in `TableFormatDetector` (presence of `snapshot/` + `schema/` directories)
- `GraphLayoutService.layoutPaimonGraph()` — Paimon-specific layout entry point
- Paimon node post-processing in `GraphLayoutService` (ordering, alignment, overlap prevention)
- Format badge ("ICE"/"PMN") next to table names in workspace sidebar
- Paimon test fixtures (`src/test/resources/paimon-fixtures/`)
- `PaimonSchemaTest` — Paimon JSON deserialization tests (9 tests)
- `PaimonGraphBuilderTest` — Paimon graph construction tests (11 tests)
- Paimon detection tests in `TableFormatDetectorTest` (4 new tests)
- `IcebergGraphBuilder` — extracted Iceberg-specific graph construction from `GraphLayoutService` into a dedicated builder
- `TableFormatDetector` — directory-based table format detection (Iceberg detection, extensible for Paimon)
- `GraphLayoutService.layoutNodes()` — public API accepting pre-built nodes/edges for format-agnostic layout
- `AboutDialog` extracted from `App.kt` into `ui/AboutDialog.kt`
- Keyboard shortcuts: Ctrl/Cmd + =/- (zoom), Ctrl/Cmd + 0 (reset zoom), Ctrl/Cmd + Shift + F (fit graph), Ctrl/Cmd + L (re-layout)
- Copy-to-clipboard buttons on file paths, UUIDs, and locations in the inspector panel
- `normalizeFilePath` now handles cloud URIs (`s3://`, `hdfs://`, `gs://`, `abfs://`) and UNC paths
- Schema evolution view in inspector panel (diff between schema versions)
- Table properties inspector with change tracking across metadata versions
- In-app cheat sheet (About dialog > Cheat Sheet tab)
- Empty state with "Add to Workspace" button on the main canvas
- Error bar with Reload button and auto-dismiss (8 seconds)
- Stale data indicator when viewing cached table after filesystem deletion
- Undo for node dragging (Ctrl/Cmd+Z, up to 20 levels)
- Snapshot filter hint text in dropdown
- Tool window drag indicator (grip icon + move cursor on title bar)
- Dark mode support for node card colors (fill, border, text)
- Gradle version catalog (libs.versions.toml)
- ProGuard enabled for release builds
- Unit tests (80+ tests) covering parsing, layout, filtering, workspace, formatting, security, performance
- Performance benchmarks for layout, filtering, and graph builder operations
- CI workflow running tests on all platforms

### Changed
- `loadRequestId` is now `AtomicLong` (was `@Volatile var Long` with non-atomic increment); cache-hit branch also bumps it so any in-flight load/reapply bails out
- `reapplyCurrentLayout` re-reads `graphModel` after the staleness check (was using a captured reference that could go stale)
- `loadTable` and `reapplyCurrentLayout` route format dispatch through `loadTableModel()` (single dispatch point)
- `sessionCache` field type is now `MutableMap<String, TableSession>` backed by a synchronized LRU
- `WorkspaceItem` serialization percent-encodes `%`, `;`, `|` in path values for safe round-trip
- `ManifestListEntry.sequenceNumber` and `minSequenceNumber` are now `Long?` (Iceberg spec is int64); manifest comparator sentinels widened to `Long.MAX_VALUE`
- `normalizeFilePath` reconstructs `file://host/path` URIs as UNC `//host/path` instead of dropping the host
- `GraphLayoutService.parsePosition` rejects values outside `Int` range instead of silently truncating
- `GraphLayoutService` post-processing partitions nodes by class once per pass (was ~20 `filterIsInstance` scans of the full node list)
- `Path` and snapshot→manifest fan-in/out counts are reused / pre-cached in `GraphCanvas` (eliminates per-edge per-frame allocation and O(E) scans)
- `jsonToAnnotatedString` is wrapped in `remember(rawJson, colors)` so the highlighter no longer runs on every recomposition
- Window position/size persistence is debounced (500ms via `javax.swing.Timer`) instead of one Preferences write per `componentMoved` event
- Paimon `PaimonManifestListNode` uses a dedicated `simpleId` counter (was reusing the snapshot's `simpleId`)
- Paimon manifest nodes deduplicate by `fileName` via `manifestPathToId` (mirrors Iceberg)
- Paimon `TableSummary.dataFileCount` counts ADD entries; `posDeleteFileCount` and `eqDeleteFileCount` stay 0 (Paimon has no positional/equality delete files like Iceberg)
- Paimon `resolveDataFilePath` flags resolved paths that escape the table directory (mirrors Iceberg's path-traversal check)
- `computeTableFingerprint` detects Paimon (`snapshot/` + `schema/`) — was always returning `"missing"` for Paimon, breaking cache invalidation
- `GraphLayoutService` is now layout-only — graph construction delegated to `IcebergGraphBuilder`
- `UnifiedSnapshot.manifestLists` renamed to `manifests` — field now correctly describes its contents
- `UnifiedManifest.manifests` renamed to `dataFiles` — field now correctly describes its contents
- `ParquetReader` renamed to `SampleRowReader` — reflects that DuckDB supports Parquet, ORC, and Avro formats
- `WorkspaceUtils.scanForTables()` now uses `TableFormatDetector` instead of inline checks
- Node positions separated from data model (thread-safe initialPositions + Compose-observable positions)
- DuckDB connection fully synchronized for thread safety
- Session cache uses ConcurrentHashMap for safe concurrent access
- Row data loading deferred until display (lazy dataLoader on RowNode)
- Edge deduplication uses HashSet instead of linear scan
- Shared utilities extracted: Theme.kt, CommonComponents.kt, FormatUtils.kt, WorkspaceUtils.kt, SnapshotFilter.kt, IcebergPaths.kt
- Tooltip delays unified to 500ms across all components
- Sorting comparators moved to `IcebergGraphBuilder`

### Fixed
- Stale graph displayed when switching to a deleted table with `forceReloadFromFs` and no cached session (prior fix branch was conditional on `forceReloadFromFs`)
- `Reveal` button hidden for cloud paths (`s3://`, `hdfs://`, `gs://`, `abfs://`, …) where it would silently fail
- About-dialog tab indicators now have a background colour on the active tab (was distinguishable only by font weight, easy to miss)
- `PropertiesEvolutionSection` no longer double-counts keys present in both compared metadata versions
- `DataFile` and `KeyValuePairBytes` now use structural `ByteArray` comparison in `equals`/`hashCode` — fixes broken deduplication in sets/maps
- Manifest node IDs use stable incrementing counter instead of `hashCode()`-based IDs — eliminates potential hash collision bugs
- `SampleRowReader` (formerly `ParquetReader`) uses parameterized queries and validates file existence/extension — fixes SQL injection risk
- `UnifiedManifest` validates resolved data file paths stay within the table directory tree — prevents path traversal attacks
- `IcebergReader` rejects non-`file:` URI schemes (`http:`, `ftp:`, `jar:`, etc.) — prevents remote file access
- Deprecated `$buildDir` replaced with `layout.buildDirectory` in `build.gradle.kts`
- Gradle deprecation warning for `Task.project` at execution time resolved
- Field name typos: `sorderOrderId` -> `sortOrderId`, `cominSequenceNumber` -> `minSequenceNumber`
- Duplicate snapshot-to-metadata edges in graph construction
- Unnecessary `!!` on non-null receiver in App.kt
- Check-then-act race condition on session cache lookup
- O(n^2) position-delete ordering replaced with indexed lookup
- Missing cancellation checks in long-running layout operations
- Compose state written from wrong thread (Dispatchers.Default)

### Removed
- Unused `KeyValuePairInt` and `KeyValuePairString` data classes
- Dead `ParentAlignment` enum and unused `_strategy` parameter
- Duplicated utility functions across files

## [1.0.2] - 2025-02-13

### Added
- Dark mode support and color scheme integration
- Snapshot selection and filtering functionality
- Identifier fields in graph node representation

## [1.0.1] - 2025-02-12

### Added
- Initial release with graph visualization for Iceberg tables
- Metadata, snapshot, manifest, and data file inspection
- Sample row display via DuckDB
- Workspace management with multiple tables/warehouses
- Cross-platform installers (DMG, MSI, DEB)

[Unreleased]: https://github.com/mmdemirbas/ice-lens/compare/v1.0.2...HEAD
[1.0.2]: https://github.com/mmdemirbas/ice-lens/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/mmdemirbas/ice-lens/releases/tag/v1.0.1
