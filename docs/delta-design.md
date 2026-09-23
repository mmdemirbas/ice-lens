# Delta Lake support — design

Reference document for adding Delta Lake as the third format. Written before the code; the
fixtures under `example/delta/` are the oracle, and the spec is `PROTOCOL.md` at `branch-3.2`
of delta-io/delta, read end to end for the sections cited here.

## What a Delta table is, in this app's vocabulary

| Delta | Nearest existing idea | Consequence |
|---|---|---|
| `_delta_log/<v>.json`, one action per line | a Paimon snapshot's delta manifest list | a version is **replayed**, not filtered — the Paimon shape |
| checkpoint (`<v>.checkpoint.parquet`, multi-part, UUID-named v2 with sidecars) | a Paimon base manifest list | the replay starts from the newest complete checkpoint at or below the version |
| `add` / `remove` keyed by `(path, deletionVector.uniqueId)` | Paimon's `_KIND` ADD/DELETE by file identifier | the latest reference to a logical file wins; `remove` is a tombstone kept for VACUUM |
| deletion vector `.bin`: version byte, then per vector a big-endian size, `D1 D3 39 64`, a portable 64-bit Roaring bitmap, a big-endian CRC-32 | Iceberg's Puffin `deletion-vector-v1` blob | `PuffinReader.decodeDeletionVector` reads it at `offset` for `4 + sizeInBytes + 4` bytes |
| `add.stats` JSON: `numRecords`, `minValues`, `maxValues`, `nullCount`, `tightBounds` | a manifest entry's bounds | bridged to `ColumnStats` for pruning; wide bounds are still sound for skipping |
| `metaData.schemaString` (Spark JSON schema), `delta.columnMapping.*` | Iceberg schema with field ids / name mapping | column mapping places a file's columns by physical name or by `field_id` |
| `commitInfo.operationMetrics` | a snapshot `summary` | checked against what the commit's actions count, the `SnapshotChange.tallies` rule |
| `cdc` / `_change_data/` | Paimon changelog | read as the change stream of that version |

Checked on the fixtures before any code: a v3 `DELETE` writing two vectors put both in one
`deletion_vector_<uuid>.bin` at offsets 1 and 43, each `sizeInBytes` 34 — the size field's value,
magic and bitmap, the Puffin blob's `length`; the next `DELETE` on the same file removed
`(path, old DV)` and added `(path, new DV)`.

## Model

- `model/DeltaSchema.kt` — `@Serializable` actions, one class per action, decoded with
  `ignoreUnknownKeys` (the protocol requires readers to ignore what they do not know); the Spark
  schema as a `DeltaType` tree with each field's metadata kept.
- `service/DeltaReader.kt` — the log listing (commits, checkpoints of all three namings, log
  compactions, `_last_checkpoint`, sidecars), commit files as JSON lines, and **checkpoints and
  sidecars through DuckDB** with each action column turned back into JSON (`to_json`), so one
  decoder reads both. DuckDB is already the app's Parquet reader.
- `model/DeltaUnifiedModel.kt` — `DeltaUnifiedTableModel : FormatTableModel`: the versions the log
  can reconstruct, and `stateAt(version)`: the newest complete checkpoint at or below it plus the
  commits after it, reconciled by the spec's rules (latest protocol, metadata, `txn` per appId,
  `domainMetadata` per domain, file actions by logical key). A version whose commit file is gone
  and that no checkpoint covers is reported as not reconstructable, the way an expired Iceberg
  snapshot is a state rather than an error.

## Graph

`table_root` → `dver_<v>` (a version: its commit, operation and metrics) → `dfile_<v>_<n>` (each
file action the commit wrote: add, remove, cdc) → rows. A checkpoint is a `dcp_<v>` node beside
the version's files. A file is drawn under the commit that wrote the action, never again under
later versions — Delta's log is per commit, and the live set of a version is the replay, which the
panels and the comparison show. Version nodes implement `ComparableSnapshot`, so the two-snapshot
comparison, the partition breakdown and the live counts work as they do for the other formats.

## Checks (the integrity report's Delta half)

1. `commitInfo.operationMetrics` against the commit's own actions: files added and removed, bytes,
   rows written (from `add.stats.numRecords`), deletion vectors added and removed.
2. A checkpoint against the replay of the commits up to its version, where those commits are on
   disk: the same live files, the same tombstones, protocol and metadata.
3. `_last_checkpoint` (`version`, `size`, `numOfAddFiles`, `sizeInBytes`, `parts`) against the
   checkpoint it names.
4. A deletion vector's `cardinality` against the decoded bitmap, and its CRC.
5. `add.stats` against the file's rows — the existing statistics check, through the bridge.

## Fixtures

`docs/fixtures/delta/run.sh <name>` runs `docs/fixtures/delta/<name>.sql` on
`apache/spark:3.5.4-java17` with delta-spark 3.2.1 and keeps the engine's printed output as
`<name>.out` — the rows, `DESCRIBE HISTORY`, `table_changes` — which the tests read.

| Fixture | What it holds |
|---|---|
| `dplain` | unpartitioned, a classic checkpoint at 3, a copy-on-write DELETE and UPDATE after it |
| `ddv` | deletion vectors: two in one `.bin`, a vector replaced, an UPDATE's vector and new file |
| `dpart` | partitioned by a string and a date with a null partition, stats as struct in the checkpoint |
| more per phase | column mapping (name, id), CDF, v2 checkpoint with sidecars, multi-part checkpoint, OPTIMIZE / VACUUM before-and-after, row tracking, RESTORE |

## Order of work

1. Log reader, replay, model, detection, graph, panels, checks 1–4, CLI and IDE through `GraphTree`.
2. Deletion vectors on rows, row lookup, live row count, scan pruning from stats and partition values.
3. Column mapping, change data feed, VACUUM / OPTIMIZE / checkpoint planners, UniForm where a
   runtime to write it is at hand.
