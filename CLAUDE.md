# CLAUDE.md

## Project overview

**Iceberg Lens** is a read-only desktop application for inspecting Apache Iceberg and Apache Paimon table structure from local filesystems. It renders an interactive graph visualization (table → metadata → snapshots → manifests → data files → sample rows) alongside a detailed inspector panel.

## Tech stack

- **Kotlin 2.3.10** + **JetBrains Compose Desktop 1.10.1** + **Material3**
- **Apache Avro 1.12.1** / **Avro4k 2.10.0** — manifest list & manifest file deserialization
- **DuckDB JDBC 1.4.4.0** — Parquet and Avro sample row queries (no ORC reader exists for it)
- **Eclipse ELK 0.11.0** — layered graph layout engine
- **Kotlinx Serialization 1.10.0** — JSON metadata parsing
- **Gradle 9.0.0** with Kotlin DSL; requires **Java 17+**

## Architecture

Two Gradle modules. The split is load-bearing, not cosmetic: `core` is the engine every future
shell sits over (server, CLI, IDE plugin), and it may not depend on a UI toolkit. A Gradle check
(`:core:noComposeOnCoreClasspath`) fails the build if a Compose or AndroidX artifact reaches
core's compile classpath, because a convention that is only written down erodes.

```
core/     — headless engine. Readers, decoders, model, analysis, ELK layout. No UI.
desktop/  — shell #1. Compose Desktop over core, in-process: direct java.nio and DuckDB JDBC,
            no server, no network. Adding `server/` and `cli/` means siblings here.
intellij/ — shell #2. An IDE tool window over core, drawn with the IDE's own Swing components.
```

**Shell #2 does not reuse shell #1's cards, and that is forced rather than chosen.** Compose
Desktop 1.10.1 binds to Skiko **0.9.37.4**; IntelliJ 2026.2 ships Skiko **0.144.5** with its own
native library, and a plugin cannot override a platform class. Bundling Compose produced
`UnsatisfiedLinkError: org.jetbrains.skia.paragraph.ParagraphStyleKt._nSetFontRastrSettings` on the
first text layout — the Kotlin bindings of one version calling into the native library of another.
The IDE also ships no Material3 at all; Jewel replaces it. So `intellij/` depends on **`:core` only**
and draws with `Tree` and `JBTable`, which is the better tool window anyway: it matches the editor
beside it, follows the IDE theme and font size for free, and a docked panel is tall and narrow —
the shape a node-and-edge drawing reads worst in and a tree reads best in. What *is* shared is
everything worth sharing: the readers, the model, the analysis, and `GraphNode.displayLabel()`.

Where the boundary sits, and why:

- **Layout positions are core; drags are shell.** `GraphModel.layoutPositions` is an immutable
  `Map<String, Point>` — `Point` exists so core needs no toolkit geometry type. The user's drags
  live in `ui/NodePositions`, which is Compose-observable and main-thread-only. That keeps a
  `GraphModel` cacheable, comparable, serialisable and safe to build off-thread.
- **`SnapshotFilter` is core**, not UI: it manipulates the graph, and the desktop shell is only
  one of its callers.
- **`ToolWindowTypes` is desktop**, because it holds an `ImageVector`.

```
core/src/main/kotlin/
├── export/
│   └── GraphExport.kt         # The graph as SVG, JSON and CSV — pure text, no toolkit
├── model/
│   ├── IcebergSchema.kt       # @Serializable Iceberg data classes (metadata, snapshot, manifest, data file)
│   ├── IcebergPaths.kt        # Shared path utilities (normalizeFilePath, metadataVersionFromFileName)
│   ├── UnifiedModel.kt        # Aggregated data layer — reads & links all Iceberg artifacts into a tree
│   ├── PaimonSchema.kt        # @Serializable Paimon data classes (snapshot, schema, manifest list, manifest entry)
│   ├── PaimonUnifiedModel.kt  # Aggregated Paimon data layer — reads & links snapshots, schemas, manifests
│   ├── GraphTypes.kt          # Point, GraphModel (nodeById, layoutPositions, groups), GraphNode (sealed incl. Paimon + GroupNode), AggregationKind, GraphEdge, TableSummary/ContentStats
│   ├── IcebergTypes.kt        # Iceberg type model + parser (field-id → type, from a manifest's own schema)
│   ├── SingleValueDecoder.kt  # Appendix D: bytes + type → DecodedValue (bounds, partition values)
│   ├── PuffinSchema.kt        # Puffin footer JSON + DeletionVector (positions, and the two figures it is checked against)
│   ├── PartitionStatistics.kt # A partition statistics file's rows, read through DuckDB, and the record they are checked against
│   ├── PartitionDecoder.kt    # partition-spec parsing, transform result types, DecodedPartition
│   ├── BucketTransform.kt     # Iceberg's bucket[N], via the same Guava murmur3 the writer uses
│   ├── SnapshotDiff.kt        # Two snapshots' live file sets, and the set difference between them
│   ├── SchemaEvolution.kt     # Each schema against the one before it, by field id — added, dropped, renamed, moved, promoted — with the first snapshot written under it, both formats
│   ├── ManifestMergePlan.kt   # What the next commit does to the manifest list — ManifestMergeManager's bins and verdicts
│   ├── MaintenanceInput.kt    # The newest metadata and the current snapshot's node, carried on the table node for the planners — never read off the drawn graph
│   ├── FileHistory.kt         # One file across the retained snapshots — added by, removed by, still listed live by — on either format
│   ├── Integrity.kt           # Every recorded figure against the same figure counted, over the whole table at once — the panels' checks, run everywhere
│   ├── StatsCheck.kt          # A data file's recorded column bounds and counts against the same figures counted from its rows — a file read, behind its own click
│   ├── FileStatsSweep.kt      # The same over the current snapshot's live files from the model, capped — the table panel's second click under Integrity
│   ├── TimeTravel.kt          # Which snapshot a read as of a time lands on — Iceberg's last log entry at or before, Paimon's latest snapshot at or before
│   ├── RowLookup.kt           # What finding a row takes, off the current snapshot: the live data files, the delete files, and their pairing — and the fates a hit can have, both formats
│   ├── RowHistory.kt          # The retained snapshots on main with what looking a row up in each takes, and what each commit did to the matching rows — appeared, changed, gone
│   ├── ReadProjection.kt      # A sampled row as a read returns it: the file's cells placed onto the current schema by field id, a renamed column under its new name, a dropped one not at all, an added one as its initial default
│   ├── NameMapping.kt         # `schema.name-mapping.default`, and the one rule placing a file's columns — its own field ids first, the mapping for a column recording none
│   ├── PaimonRowLookup.kt     # What reading a Paimon snapshot takes: the live files by bucket, the index manifest's vectors, the merge rule — for the row lookup and the merged count
│   ├── PaimonMergeRule.kt     # What a read does with a key's records under each merge engine, and which level-0 files it never reads
│   ├── ScanFilterSql.kt       # A ScanFilter as DuckDB's WHERE clause, every literal bound and cast to its column's type
│   ├── ExpiryFilePlan.kt      # Which files an expiry frees — RemoveSnapshots' incremental and reachable cleanups
│   ├── PaimonExpiryFilePlan.kt # Which files a Paimon expiry frees — ExpireSnapshotsImpl's four passes, and what a tag holds
│   ├── PaimonReplay.kt        # Paimon's delta-over-base replay: per-manifest figures, the file set, and a per-entry trace — one walk
│   ├── SnapshotFilter.kt      # Snapshot filter options and graph filtering (pure graph work — core, not UI)
│   ├── ManifestTally.kt       # manifest_file's six counts against the same figures folded from its entries
│   ├── PartitionSummaryTally.kt # manifest_file's partition summaries — the bounds a scan prunes on — against the entries' decoded partitions
│   ├── ManifestLedger.kt      # Per-entry: what it added to a manifest's figures, or which rule dropped it
│   ├── ScanPruning.kt         # Predicate → which manifests a scan would skip, and which term did it
│   ├── GraphNavigation.kt     # Arrow keys → the next node, decided from where the nodes are drawn
│   ├── GraphSearch.kt         # What each node kind can be found by, and the matches in drawn order
│   ├── PuffinSchema.kt        # @Serializable Puffin footer + the decoded DeletionVector
│   └── WorkspaceTypes.kt      # WorkspaceItem sealed class (Warehouse / SingleTable), serialization
├── service/
│   ├── AvroReader.kt          # Shared Avro file reader (reified readAvro<T>), used by both Iceberg and Paimon
│   ├── PuffinReader.kt        # Puffin footer + deletion-vector blob → the row positions it marks
│   ├── IcebergReader.kt       # Iceberg JSON/Avro reading (delegates Avro to AvroReader)
│   ├── PaimonReader.kt        # Paimon JSON snapshot/schema + Avro manifest list/manifest reading
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet and Avro, the table function chosen by extension; ORC refused with the reason — max 50)
│   ├── AvroRows.kt            # An Avro data file's first rows read in this process — the row cards' way through a codec DuckDB refuses
│   ├── AvroTranscode.kt       # A copy of such a file under deflate, once per session, bounded — the SQL readers' way through the same
│   ├── RowLookup.kt           # The rows a filter matches, read through DuckDB, and each one's fate under the delete files paired with its file
│   ├── PaimonRowLookup.kt     # The same on Paimon: a record's fate under its file's vector, its own `_VALUE_KIND`, and the bucket's later writes for its key
│   ├── RowHistoryTrace.kt     # Either lookup run at every retained snapshot on main, one file read per trace on Iceberg
│   ├── PaimonMergedCount.kt   # What `SELECT count(*)` returns as of a Paimon snapshot: the merge over each bucket's files, less retractions and vector-marked keys
│   ├── LiveRowCount.kt        # The same on Iceberg: each data file's record_count less the rows the delete files the scan pairs with it remove
│   ├── FileProjection.kt      # A data file as a `FROM` under the schema's names — every column placed by field id or the mapping, a missing one its initial default or NULL — so a lookup, a count or a check names columns the way the schema does
│   ├── StatsCheckReader.kt    # One DuckDB statement per file — count, min, max, nulls per recorded column, NaNs kept out — for StatsCheck; columns found by field id where the Parquet file carries one
│   ├── PaimonDeletionVectorReader.kt # A Paimon index file's vector at (offset, length) → the row positions it marks; v1 32-bit, v2 as Iceberg's blob
│   ├── PaimonSequenceGroups.kt # partial-update's remove-record-on-sequence-group, folded over a key's records in sequence order
│   ├── StorageLocation.kt     # A location string → the Path that opens it. The one place a scheme is resolved
│   ├── DuckDb.kt              # The shared DuckDB connection, and the object-store credentials configured on it
│   ├── ObjectStorage.kt       # Listing and reading object storage through DuckDB, with the caches that make it viable
│   ├── ObjectFileSystem.kt    # A read-only java.nio FileSystem over ObjectStorage (s3/gs/gcs/r2)
│   ├── PuffinReader.kt        # Puffin footer + `deletion-vector-v1` blob → the row positions a v3 vector marks
│   ├── IcebergGraphBuilder.kt # Iceberg-specific graph construction: UnifiedTableModel → nodes + edges
│   ├── PaimonGraphBuilder.kt  # Paimon-specific graph construction: PaimonUnifiedTableModel → nodes + edges
│   ├── GraphAggregation.kt    # Format-agnostic: long sibling runs → one expandable GroupNode
│   ├── SiblingOrder.kt        # One order per kind — read by layout AND by aggregation
│   ├── SnapshotTracks.kt      # Which column each snapshot draws in, and which branch names it
│   ├── GraphLayoutAlgorithm.kt # The four shapes on offer, and which one gets the refinements
│   ├── GraphLayoutService.kt  # Format-agnostic ELK layout + post-processing (ordering, alignment, overlap prevention)
│   └── TableFormatDetector.kt # Directory-based table format detection (Iceberg / Paimon / Unknown)

desktop/src/main/kotlin/
├── Main.kt                    # Entry point, window state persistence (multi-monitor aware)
└── ui/
    ├── AppState.kt            # Business logic: workspace mgmt, table loading, caching, snapshot filter (testable, no UI)
    ├── App.kt                 # Thin UI layer — the window, keyboard shortcuts, dialogs, LaunchedEffects (delegates to AppState)
    ├── DockState.kt           # Where the tool windows sit, which are hidden, pane sizes, a drag in flight — persisted, testable
    ├── DockLayout.kt          # The dock drawn from a DockState: bars, stacked side panes, the bottom strip, drop targets
    ├── Toolbar.kt             # The row above the canvas, and the snapshot-filter menu. Stateless
    ├── NodePositions.kt       # Drag state layered over core's immutable layout positions (Compose-observable)
    ├── ToolWindowTypes.kt     # ToolWindowAnchor enum, ToolWindowConfig (holds an ImageVector → shell, not core)
    ├── AboutDialog.kt         # About dialog with version info, diagnostics, cheat sheet
    ├── Theme.kt               # Color schemes, dark surface detection, selection highlight
    ├── CommonComponents.kt    # Reusable widgets: draggable dividers, toolbar group/icon button
    ├── FormatUtils.kt         # Timestamp/count/byte formatting, long set serialization
    ├── WorkspaceUtils.kt      # Table detection, recursive scanning, native file chooser, workspace dedup
    ├── GraphCanvas.kt         # Interactive graph: zoom/pan, node selection/drag, marquee, mini-map, viewport culling
    ├── NodeComponents.kt      # Node card composables (Iceberg + Paimon node types) + tooltip + copy buttons
    ├── NodeDetails.kt         # Inspector panel — header, multi-select, the shared sections and helpers the panels reach for
    ├── MaintenanceSections.kt # The planners' sections — rewrite, manifest merge, expiry and its files, compaction, the table's summary line per procedure
    ├── FileHistorySection.kt  # A file's life on both file panels: which commit removed it, and what still keeps it on disk
    ├── IntegritySection.kt    # The whole-table check behind a click on the table panel, and its findings
    ├── PaimonMergedCountSection.kt # The rows a read of a Paimon snapshot returns, behind a click on a primary-key table
    ├── LiveRowsSection.kt     # The rows a read of an Iceberg snapshot returns, behind a click where a delete manifest is listed
    ├── StatsCheckSection.kt   # A data file's recorded statistics beside the counted ones, behind a click on either format's file panel
    ├── RowDeletesSection.kt   # Whether a read returns a sampled Iceberg row: the delete files paired with its file, asked for it behind a click
    ├── PaimonRowMergeSection.kt # The same on Paimon: the merge engine over the record's key, the row lookup run for it behind a click
    ├── ReadAsSection.kt       # An Iceberg row as a read returns it, where the schema has moved on since the file — drawn only when it differs
    ├── TimeTravelSection.kt   # A typed time and the snapshot it resolves to, on the metadata panel and the Paimon table panel
    ├── RowLookupSection.kt    # The scan filter one step further: the matching rows read from the files it leaves, each with its fate — both formats
    ├── NodePanels.kt          # Table, row, error and group panels
    ├── IcebergNodePanels.kt   # Metadata, snapshot, manifest and file panels
    ├── PaimonNodePanels.kt    # Paimon snapshot, schema, manifest list, manifest and data file panels
    ├── RemoteLocations.kt      # A location in object storage and how to reach it — persisted, minus the secret
    ├── RemoteLocationDialog.kt # The form for a location no file chooser can browse to
    ├── Sidebar.kt             # Workspace panel — add/remove roots, search, drag-to-reorder, format badges (ICE/PMN)
    ├── NavigationTree.kt      # Structure tree view — flatten graph, search, expand/collapse
    ├── ScanPruningSection.kt  # The filter form and its per-manifest verdicts, in the table inspector
    ├── GraphStatusBadge.kt    # Canvas overlay: how much of the table is drawn, and the page size
    └── ToolWindow.kt          # Draggable tool window bars and panes
```

intellij/src/main/kotlin/plugin/
├── IceLensToolWindowFactory.kt # Attaches the panel to the tool window
├── IceLensPanel.kt             # Tree + details, with the read on a Task.Backgroundable
├── GraphTree.kt                # GraphModel → tree rows, and what each node lists
├── IceLensService.kt           # Which table is open, per project
└── OpenInIceLensAction.kt      # "Open in Iceberg Lens" on a directory in the Project view

## Build & run

```bash
./gradlew run          # Run the application
./gradlew build        # Build
./gradlew :desktop:packageDmg   # macOS installer
./gradlew :desktop:packageMsi   # Windows installer
./gradlew :desktop:packageDeb   # Linux installer

./gradlew resolveAndLockAll --write-locks   # after ANY dependency change

./gradlew :intellij:buildPlugin   # the installable zip, in intellij/build/distributions/
./gradlew :intellij:runIde        # a sandbox IDE with the plugin loaded
# Both download an IDE on first use. To build against one already installed:
#   -PintellijLocalPath="$HOME/Applications/IntelliJ IDEA.app"
```

## Key conventions

- State is persisted via `java.util.prefs.Preferences` under `com.github.mmdemirbas.icelens`
- All data access is read-only — no table modifications
- Node colors are hardcoded per node type in `NodeComponents.kt` (`getGraphNodeColor` / `getGraphNodeBorderColor`)
- Dark mode detection uses `perceivedBrightness()` (0.2126R + 0.7152G + 0.0722B < 0.5)
- Graph layout flow: `FormatTableModel` → `GraphLayoutService.layoutGraph()` dispatches to the
  format-specific builder → `GraphBuildResult` → `GraphAggregation.apply()` → sample rows attached
  for the surviving data files → `GraphAggregation.apply()` again, which only rows can be affected
  by → `layoutNodes()` → `GraphModel` → `GraphCanvas`. **The order is
  load-bearing**: the builder emits a node for every artifact the metadata describes, aggregation
  decides which are drawn, and only then are rows read — building them first costs a filesystem
  stat and up to five nodes per data file in the table. A file gets `RowNode.countFor(recordCount)`
  nodes — its recorded row count, capped at five — because the count is metadata and a one-row file
  drew four empty cards beside its one
- `GraphModel.nodeById` provides a lazy `Map<String, GraphNode>` — use it instead of `nodes.find`/`nodes.associateBy`
- Manifest lists and manifests resolve via `resolveRecordedOrRelative()`: the recorded path when
  it is absolute and the file exists, else `resolveForceRelative()` (file name against the local
  metadata dir). The fallback is what opens a table copied down from object storage; the recorded
  path is what opens a `write.metadata.path` layout. **Manifests resolve against their manifest
  list's directory**, not the table's metadata dir. **Data files follow the same recorded-first
  rule** with a different fallback: the recorded table prefix is stripped and the sub-path rebuilt
  under the local table root, so `data/name=alpha/…` survives. The traversal check applies to that
  rebuilt branch only — a recorded path landing outside the table root is the table's own
  statement, and `UnifiedDataFile.pathResolution` / `FileNode.pathResolution` carry which rule ran
  so the inspector can say it. **A data file recorded outside the table has a third rule.** A
  `write.data.path` file (`/wh/extdata-files/x.parquet` for a table at `/wh/default/extdata`) has
  no sub-path under the table to rebuild, and the sub-path rule put it at `<table>/wh/…`, where
  nothing is. `rebuildBesideTable` re-roots it instead: the recorded table directory and the local
  one agree on their trailing segments (`default/extdata`), what is above those is the warehouse
  in each world, and the recorded path is moved from the one to the other —
  `PathResolution.REBUILT_BESIDE_TABLE`. It stands down (null, and the older rule runs) when no
  trailing segment is shared, the recorded path is not under the recorded warehouse, or the result
  escapes the local one. `extdata` is the engine-written oracle: the table holds `metadata/` and
  no `data/`, and its two files sit under `example/iceberg/extdata-files/` beside the table, the
  way they sat under `/wh`
- **A data file's `sort_order_id` is the writer's claim, and the table's default is put beside
  it because the two are not one fact.** `SortOrder.describe(nameOf)` renders an order the way
  `WRITE ORDERED BY` states it (`id DESC NULLS LAST, name ASC NULLS FIRST`; order 0 is
  `unsorted`), with source ids resolved through the schema the caller supplies — the manifest's
  own for a file, the current one for the metadata panel. `FileNode.sortOrder` and
  `.defaultSortOrder` are both looked up in the **newest** metadata's `sort-orders`, which is
  right because orders only accumulate. The `sorted` fixture settled what the panel has to say:
  three commits under three orders, then a `rewrite_data_files(strategy => 'sort')`: the rows
  inside each file **in the order that was in force**, the compacted file's nine in the default
  order, and `sort_order_id 0` on every one of them — Spark's writer sorts through the write's requested
  ordering and builds `SparkFileWriterFactory` without a `dataSortOrder`, so `DataFiles.Builder`
  keeps `SortOrder.unsorted().orderId()`. So a file's 0 is not a statement that its rows are
  unordered, only that the metadata does not claim an order; the `Table Default Order` row is
  drawn when the two ids differ and says whose it is. The script's header first predicted 0, 1
  and 2 — the manifests corrected it, which is the reason the fixture is an oracle
- Workspace serialization uses `W|path` / `T|path` items joined by `;`. The path component
  percent-encodes `%`, `;`, and `|` so paths containing those characters round-trip safely.
- `normalizeFilePath` handles `file:` URIs (including `file://host/path` authority,
  reconstructed UNC-style as `//host/path`), Windows backslashes, UNC paths, and passes
  through cloud URIs (`s3://`, `hdfs://`, `gs://`, `abfs://`, …) as-is
- `loadRequestId` is an `AtomicLong`; the cache-hit branch in `loadTable` also bumps it so
  any in-flight load/reapply coroutine fails its staleness check and bails out
- **A computed figure is folded from its explanation, never stored beside it.**
  `TableSummary.current` / `.history` are getters over `currentDerivation.total` /
  `historyDerivation.total`; `StatsDerivation.total` folds a `List<ManifestContribution>`.
  Adding a new aggregate follows the same shape — an explanation computed separately is a second
  implementation of the number, and two implementations drift. One contribution per *manifest*,
  not per entry, so memory tracks manifests while drill-down stays available by re-running the
  accumulator scoped to one. Contributions may be negative (Paimon's delta manifest list removes
  files its base still lists), so never clamp a delta at zero
- **Summary figures are always deduplicated.** Both formats share structure on purpose (one
  manifest is referenced by every snapshot that carries its files forward; every Iceberg
  snapshot is re-listed in every later metadata.json), so a per-visit counter multiplies with
  commit history. `TableSummary` carries two `ContentStats`: `current` (manifest closure of
  `current-snapshot-id`, live entries only) and `history` (everything reachable from any
  retained snapshot, deduplicated by manifest and data-file path). `manifestEntryCount` is
  status-blind in both — it measures scan cost — while file/record/byte totals cover live
  entries only. Delete-file `record_count` goes to `deleteRecordCount`, never `recordCount`.
- **The same fold at three depths, never three implementations.** `manifestLedger` decides what
  one entry contributes and which rule dropped it; `ContentStatsAccumulator.contributionOf` folds
  it into one `ManifestContribution` per manifest; `StatsDerivation.total` folds those into the
  table's figures. The manifest inspector's drill-down calls the same function with a *fresh*
  `seenFileKeys` set, which scopes deduplication to that manifest — and says so on screen, because
  the table's totals also drop a file another manifest counted first and that cannot be seen from
  inside one. `ManifestLedgerTest` replays `mor`'s whole traversal through the ledger and requires
  every contribution to come out identical; a drill-down computed separately would pass its own
  unit tests and drift from the number it explains
- **What a commit did is read from the manifests it wrote, not from its closure.**
  `model/SnapshotChange.kt` answers "what changed here" at a snapshot, and the rule that makes it
  correct is `manifest_file.added_snapshot_id`: an entry's `status` is relative to the snapshot
  that created the *manifest* holding it, and a manifest is carried forward unchanged into every
  later snapshot that still needs its files — so a manifest written by commit 3 still says `ADDED`
  when commit 9 lists it. Counting statuses across the closure credits every commit with all of
  its ancestors' work. The figures are folded from `files`, never stored beside it, and
  `SnapshotChange.tallies` puts each one against the snapshot `summary` the engine wrote, which
  is the same idea as `manifestTallies` a level up — `SnapshotChangeTest` checks 120 such pairs
  across ten checked-in tables and is the suite's strongest oracle, because nothing here
  produced any of the summaries. Fourteen figures: files, rows and bytes in and out, **rows in
  delete files by kind** (`added-position-deletes` and `added-equality-deletes` are positions and
  key tuples, not files — `rewrite_position_delete_files` on `maint` records three positions out
  and one in, the two dangling deletes and the live one), and **the manifest list's own split**
  (`manifests-created` / `manifests-kept`, which only `rewrite_manifests` records and which check
  the `added_snapshot_id` attribution rule itself rather than the entries read under it — kept
  as `SnapshotChange.manifestTallies`, drawn only where recorded, since the split is stated in
  prose on every commit). Two things `maint` settled: **the rewritten delete file keeps the
  sequence number of the delete it replaces**, recorded explicitly on the entry — a delete
  applies at or below its number, and taking the rewrite's would apply it to rows committed in
  between — and **a manifest holding only `DELETED` entries is dropped from the very next
  commit's list**, so a manifest rewrite finds four to replace where seven were written. Two things the fixtures settled that a reading of the spec did
  not: **`added-dvs` is a breakdown of `added-delete-files`, not a v3 replacement for it** (one
  vector records both as 1, so summing them double-counts), and **`added-files-size` counts a
  deletion vector's `content_size_in_bytes`, not its Puffin file's size** — one container holds a
  blob per data file it covers, so charging the container once per vector counts the same bytes
  repeatedly
- **What a commit left is checked against its closure, which is the other half of the summary.**
  `model/SnapshotTotals.kt` puts the six `total-*` figures — data files, delete files, records,
  files size, position and equality deletes — beside the same figures folded from `liveFilesOf`,
  the walk the table's `current` figures and the two-snapshot comparison already run, so it is a
  third reading of one walk and not a second implementation. The writer keeps a total by
  arithmetic, previous plus added minus removed, so a total that disagrees has been wrong since
  some earlier commit; nothing on a read path checks it. `total-files-size` charges a deletion
  vector at its `content_size_in_bytes` — `EntryContribution.chargedSizeBytes`, beside the file
  size that is on disk — which the `v3` fixture settles: charging the Puffin container instead
  disagrees with the writer by exactly the container-minus-blob difference. `SnapshotTotalsTest`
  checks 336 such pairs across fourteen tables, the same oracle shape as `SnapshotChangeTest`
- **Two snapshots are compared as sets, never as a replay of the commits between them.**
  `model/SnapshotDiff.kt` answers "what is different between these two", which is not the question
  `SnapshotChange` answers and cannot be built out of it: `SnapshotChange` is defined only against
  a commit's *parent*, so a branch tip against `main` has no path to fold along, and a snapshot on
  a path that aggregation folded into a group is not in the drawn graph to fold. `liveFilesOf`
  runs the **same `manifestLedger`** the table's `current` figures are folded from, with one shared
  `seenFileKeys` across the closure — which is why `SnapshotDiffTest` can use `TableSummary.current`
  as an oracle on all eight fixtures, two ways of counting one set. The walk is a `DeferredRead` on
  `SnapshotNode`, so a table of twenty commits never walks twenty closures to answer a question
  about two. The panel is reached by selecting exactly two snapshots — the multi-select branch of
  `NodeDetailsContent`, not a picker — and orders them oldest-first by **sequence number**, because
  a timestamp is a clock and two commits from a fast writer can share one. `MAX_DIFF_ROWS` caps the
  file list at 500 and says so on screen when it bites; files on both sides are counted and not
  listed, because on any real table they are almost all of it. **Each side can be stepped while
  the other stays pinned**: `GraphModel.stepComparableSnapshot` (core, `SnapshotDiff.kt`) answers
  the neighbour in the same commit order the panel sorts the pair by, skipping expired snapshots,
  and the panel's two rows of older / newer buttons call `onSelectNodes` with the pair moved —
  stepping *is* selecting, so the panel holds no state and the canvas highlights what it shows. A
  button at the end of history is disabled, not dropped, so the row keeps its shape; each names the
  commit it would move to. `SnapshotSteppingTest` pins the order and the ends
- **A partition statistics file is shown against the live files of the snapshot it names.**
  `checkPartitionStatistics` in `model/PartitionStatistics.kt` puts each row of the file beside
  the `PartitionShare` folded from `liveFilesOf` for that snapshot — the `manifestTallies` rule one
  level up, and for the same reason: a planner reads the file instead of walking the manifests,
  which is exactly what lets the two drift, and nothing on the read path checks it again. A
  partition on one side only is a disagreement of its own kind (the file omits a live partition;
  the file lists one that holds no live file), and a figure that differs is named with both
  values. The panel leads the per-partition table with the verdict, reaches the snapshot's
  deferred walk through `snap_<id>` in the drawn graph, and says "not checked" when that snapshot
  is not drawn rather than walking it itself. `PartitionStatsCheckTest` holds `pstats` to full
  agreement and plants three kinds of drift to see each named
- **A snapshot's partition breakdown is folded from the same live set the comparison uses.**
  `List<LiveFile>.partitionBreakdown()` in `model/SnapshotDiff.kt` groups a snapshot's live files
  by their decoded partition — `LiveFile.partition`, carried out of `liveFilesOf` by zipping the
  ledger's contributions with the entries they came from, and out of the Paimon replay, whose live
  map now holds the entries rather than their file metadata — into one `PartitionShare` per
  partition, largest data first. It is the question Iceberg's `.partitions` metadata table answers
  and the `Partitions` section on both snapshot panels draws it; `PartitionBreakdownTest` holds it
  to the per-partition rows Iceberg 1.10's `compute_partition_stats` wrote for `pstats` and to the
  row counts `paimon-pt.sql` put into each partition. A tuple that did not decode is grouped under
  `UNDECODED_PARTITION` and coloured, never dropped: a partition that silently loses its files is
  the one wrong this section must not be. The section costs no read of its own — `TotalsSection`
  already ran the walk — and an unpartitioned table is one line rather than a one-row table
- **The comparison is format-agnostic; the two ways of answering it are not.** `ComparableSnapshot`
  in `GraphTypes.kt` is the seam — six questions the panel asks and neither node type's own
  vocabulary — so `SnapshotComparison` never asks which format it is drawing. Underneath, Iceberg
  *filters* (`manifestLedger`: a `DELETED` entry contributes nothing, a repeated path is a
  duplicate, so entries fold in any order) and Paimon *replays* (`replayPaimonSnapshot`: the delta
  manifest list applies over the base, so a `_KIND=1` entry removes a file the base still lists and
  an entry's meaning depends on the entries before it). That is why the shared per-entry ledger
  cannot cover Paimon and why a Paimon contribution may be negative. **The replay emits both the
  per-manifest figures and the file set it ends on, from one walk**, because two walks would be two
  implementations of one rule — `PaimonSnapshotDiffTest` asserts the two halves describe the same
  walk rather than trusting the source. Paimon's node reports **no parent**: `id - 1` is a
  convention nothing states and a rolled-back table breaks it, so the panel says the format records
  none rather than inferring lineage
- **Paimon's per-entry drill-down is a replay trace, and it is a different shape from Iceberg's
  ledger on purpose.** `manifestLedger` gives a *verdict per entry* — counted, records a removal,
  already counted — because an Iceberg entry can be decided on its own, which is also what lets the
  drill-down be re-run scoped to one manifest. A Paimon entry has no such reading: `_KIND=1` means
  "remove what is there", so what it did depends on what the entries before it left. So
  `PaimonEntryTrace` records **the state the entry met** and the effect the two produced together —
  `ADDED`, `REPLACED`, `REMOVED`, `REMOVED_ABSENT` — and it is emitted by `replayPaimonSnapshot`
  itself, under a `traceFor` naming one manifest. One manifest, because recording every entry of
  every manifest would hold the whole table's entry list in memory for a panel that shows one; and
  it still costs the **whole replay**, because the manifest's own entries are not enough to decide
  them. `PaimonManifestNode.replayTrace` is therefore a `DeferredRead` — deferred not to avoid a
  file open but to avoid a replay per manifest at build time. **The oracle is that the trace sums
  to the contribution it explains**, on files, records and bytes at once, which is the same
  "two readings of one walk" rule `PaimonSnapshotDiffTest` holds the figures and the file set to.
  **That oracle cannot see a wrong reading of `DELETE`** — both readings come from the one walk and
  move together, and a mutation making `DELETE` behave as `ADD` passed every sum-oracle in both
  classes. What catches it is `dv`: its compactions are the first real removals any Paimon fixture
  reaches, and the tests pin the replay against the snapshot's own `deltaRecordCount` and
  `totalRecordCount`, figures the writer recorded. An upgrade compaction there is `DELETE` at level
  0 then `ADD` of the same file at level 5, so the trace shows `[REMOVED, ADDED]` and the
  contribution is zero — a set comparison of the two snapshots sees nothing, correctly.
  The colour marks `REPLACED` and `REMOVED_ABSENT` only: adding and removing are both ordinary in a
  compaction, while a rewrite whose record delta is a *difference* and a removal that **found
  nothing to remove** are the two rows invisible in every figure above them
- **Which delete files a scan pairs with which data files is answered from the metadata, and the
  interesting answer is "none".** `model/DeleteAssignment.kt` applies the four rules
  `DeleteFileIndex.forDataFile` applies (1.8.1), none of which needs a file opened. **Sequence:** a positional delete or a v3 vector reaches a
  data file at or below its own number, an equality delete only one strictly below — the difference
  is what makes equality deletes safe to write, since they must not touch rows a later commit adds.
  **Target:** a vector names its file in `referenced_data_file`, and a v2 positional delete records
  **bounds on its own `file_path` column** (reserved field 2147483546) — the same thing Iceberg's
  planner prunes with, and the reason this needs no scatter-gather. Bounds that *meet* name one file
  and settle it; bounds that span only rule paths out. An equality delete has no target and every
  candidate stays unsettled, which is why `reaches` and `mayReach` are separate lists: "the metadata
  ruled everything out" and "the metadata could rule nothing out" would otherwise both read as an
  empty list, and only the first means the file is **dangling**. **Partition:** a delete is filed
  under the spec id and partition tuple it was written under and weighed only against data files
  under the same key (`PartitionScope`, spec id included — the same tuple under another spec is
  another key) — except an equality delete under an *unpartitioned* spec, which is global and
  weighed against every file, and except a vector or a positional delete that names one file,
  which is keyed by path and never asked. A partition this could not decode is not compared,
  since a wrong exclusion here hides a delete and an unsettled pair is only the absence of a
  proof. **Bounds:** an equality delete is ruled out when, on any of its equality columns, its
  bounds and the data file's cannot meet — `canContainEqDeletesForFile`: a delete holding no
  null against a file all null on the column and the reverse, a required column never null, and
  otherwise the two ranges compared as values through `compareValues`; a missing figure on either
  side leaves the column undecided (`equalityDeleteMayTouch`). It is the one rule that can rule
  an equality delete out short of sequence, and `fupp` is where it does: Flink's upsert sink
  writes an equality delete for every key it inserts, so commit 2's delete for a new key names
  nothing any earlier file holds and is dangling by its own bounds. `mor` was already documented
  as having two dangling deletes and nothing asserted it, because nothing could compute it;
  `DeleteAssignmentTest` now does, from the delete files' own bounds against the live paths. **The same pairing is asked from both
  ends and they are one implementation**: `deleteReach(snapshot)` walks a closure and answers per
  delete file, `deleteCandidatesFor(dataFile, drawn)` needs no walk at all — both operands' sequence
  numbers and targets are facts about the files — and both fold `reachVerdict`. The per-file
  direction is therefore scoped to *what the graph draws* rather than to a snapshot, which is the
  scope `evaluateScan` already answers in and gives up only liveness; the panel says so. `mor` is
  where the two rules separate: of the two delete files that miss the compacted file, one is ruled
  out by its target and the other by sequence, having been written before that file existed.
  **And the pairing is held to Iceberg's own**: `iceberg-scan-plans.scala` prints
  `FileScanTask.deletes()` for every data file of every checked-in table's current snapshot —
  `DeleteFileIndex`'s pairing — into `core/src/test/resources/iceberg-scan-plans/deletes.txt`,
  and `IcebergDeletePairingPlanTest` asserts both directions over 31 tables and 89 files: a
  delete Iceberg applies is reached or unsettled here, a reach proved here is one Iceberg
  applies, and — since no positional delete or vector in the corpus is left unsettled — the
  plan's deletes are exactly the proved ones plus the equality deletes left unsettled. `test` is
  the one table left out, its manifest list recorded at the path it was written to. A target rule
  weakened to "unsettled" is caught on `eqdel`, and the sequence rule's boundary both ways on
  `fup` — the Flink upsert sink is the one writer that puts a delete in the same commit as the
  data file it can apply to: commit 1 holds a data file, a positional delete for its position 0
  and an equality delete for its keys at one sequence number, and Iceberg attaches the first and
  not the second. The bounds rule is caught on `fupp` and the partition rule on `eqpart`, which
  exists because `fupp` cannot separate the two: its partition column is an equality column, so
  the bounds on it already differ across partitions. `eqpart`'s deletes are on `id` alone over
  files that all hold ids 1..2 — the one under the partitioned spec in `p=y` is attached to the
  `p=y` file alone, and the one under the unpartitioned spec the table started with is attached
  to all three, the spec-0 file included; dropping the partition rule, the global rule or the
  bounds rule each fails the plan test on one of the two
- **The live row count exists only by reading the delete files, and the pairing is what makes that
  cheap.** `record_count` counts rows *before* deletes, and subtracting the delete files' own
  `record_count` is wrong the moment one is dangling — on `mor` that subtraction gives 3 where the
  table holds 5, which `MergeOnReadFixtureTest` pins precisely so nobody ships it. So
  `SampleRowReader.queryDeletedRowCount` counts the positions that land in *one* data file, over the
  candidates `deleteCandidatesFor` already narrowed to, behind a click for the same reason
  `queryPositionalDeleteTargets` is. **`count(DISTINCT pos)` over one `UNION ALL`, not a sum of
  per-file counts**: two delete files may mark the same position, and a sum can exceed the row count
  it is subtracted from — a plausible wrong number of exactly the kind a reader cannot catch. Every
  value is bound; the SQL text is generated only because `read_parquet` takes one file per call. A
  deletion vector is not queried at all — it was decoded exactly — and the two figures are reported
  side by side rather than added, because the union of an in-process bitmap and a DuckDB aggregate
  is not something either of them can compute. On `mor` the answer is *1 of 6 rows deleted, 5 live*,
  which is the figure the table actually has and the first time this app could say it
- **A row is found by reading the files the filter leaves, and its fate is decided by the delete
  files paired with its file — the one question about a merge-on-read table the metadata cannot
  settle.** `model/RowLookup.kt` reads what it takes off the current snapshot (`TableNode.rowLookup`,
  a `DeferredRead` on Iceberg): every live data file with the path DuckDB opens it at, every
  delete file with what deciding needs — a vector's blob offset and length, an equality delete's
  `equality_ids` resolved to the current schema's names — and `deleteReach`'s pairing, sequence
  rule included. `model/ScanFilterSql.kt` renders the same `ScanFilter` the pruning rules read
  against bounds as DuckDB's `WHERE`, every literal bound as text and **cast to the column's
  type** (`duckDbTypeOf`) — DuckDB compares a typed column with a text parameter only through a
  cast, and the parser keeps literals as text for exactly the reason a cast is needed here. Then
  `service/RowLookup.kt` opens each file the drawn graph's pruning did not rule out (`MAX_FILES`
  64, `MAX_HITS_PER_FILE` 20, a file not drawn is read rather than guessed at) with
  `file_row_number = true`, and puts every hit to its file's delete files in the order a scan
  finds them decisive: a vector by the position's bit, a positional delete by `(file_path, pos)`
  **with the path as the manifest recorded it** (the container's `/wh/…`, not the local one — a
  lookup by the local path finds nothing and looks like a live row), an equality delete by the
  row's own values in its columns, which is the one delete kind the metadata cannot resolve and
  the bytes can. `RowLookupFixtureTest` is three delete kinds on three tables and one oracle:
  the rows each script's final table holds, by id, are found live exactly and no others —
  `mor` 1,3,4,5,6 (2 compacted away without a trace, 7 by position, 5 as `echo-updated`),
  `eqdel` 1,4,5,7 (2 and 6 by equality across both files, 3 by position), `v3` 1,3,4,5 (2 by a
  vector; 4 twice, the old row marked and `delta-updated` live). An Avro hit has no
  position and its fate is `not decided`, said rather than guessed — as is a hit whose delete file
  could not be read or whose vector was decoded past `MAX_POSITIONS`, since "no delete proved it
  gone" is not "live" when one was never applied
- **DuckDB reads Parquet and Avro and not ORC, and every reader chooses its table function by
  the file's extension.** `SampleRowReader.readerCall` is the one place that choice is made —
  `read_parquet` or `read_avro`, `hive_partitioning = false` on both — and every DuckDB read in
  `service/` goes through it. Before it, every reader called `read_parquet` under a comment
  saying DuckDB "auto-detects Parquet, ORC and Avro": it does not, an Avro file fails on its
  magic bytes, and no ORC table function exists for 1.4.4 (`INSTALL orc FROM community` is a
  404) — a claim that stood because every fixture was Parquet. Three things follow, each with a
  fixture. **Only Parquet answers `file_row_number`**, so an Avro row has no position
  (`hasRowPositions`): a sampled row's `Position` is absent, a hit's fate against a positional
  delete is `not decided`, the live-row count reports the file uncounted, a vector's positions
  cannot be put to a Paimon record, and a data-evolution split cannot be stitched — said, never
  guessed from the result's order. `avrofmt` settled one more: **Iceberg's Avro writer records
  no column metrics**, so its positional delete has no `file_path` bounds and Iceberg's own plan
  attaches it to every file of the partition (`deletes.txt`), which is what the pairing calls
  `mayReach`; the statistics check finds the row count and nothing else to compare. **An ORC
  file is refused at `resolveForQuery` with `ORC_UNREADABLE`**, before any query, so the row
  cards (`RowNode.readError`, a red line where the cells would be — the cards drew blank
  before, with the DuckDB error in the log), the row panel, the IDE strip, the live-row count,
  the row lookup and the statistics sweep all say the same sentence; `orcfmt` holds that. **And
  DuckDB's Avro reader decompresses `null`, `deflate` and `snappy` and refuses `zstandard` and
  `bzip2`** ("File header contains an unknown codec", a line that does not name the codec) —
  measured on files written with each; Iceberg's default is deflate and Paimon's `file.compression`
  default is zstd, so a Paimon Avro table on its defaults is one DuckDB cannot open as written.
  **Two ways through, one per cost.** The row cards' sample is the file's first block, so
  `querySampleRows` reads such a file in this process through the Avro library that reads the
  manifests (`service/AvroRows.kt`, `zstd-jni` already on the classpath), with the file's
  logical types applied so a `date` is a `LocalDate` and a `decimal` a `BigDecimal` — the shape
  DuckDB gives a Parquet row's, near enough for a card — and a null cell spelled `"null"` as
  DuckDB's arrive, so the two readers give one shape of row (`paz` against `pav`). The readers
  that run SQL over the whole file — the lookup, the merged count, the live count, the
  statistics sweep — cannot take rows read here without becoming a second query engine, so
  `resolveForQuery` hands them **a copy under `deflate`** (`service/AvroTranscode.kt`): made
  block by block with `appendAllFrom(reader, recompress = true)`, no record decoded, the
  header's metadata carried over, once per session, in a temp directory removed at exit. The
  copy **keeps the file's name** in a directory of its own, because the Paimon readers tell a
  `UNION ALL`'s files apart by the `filename` column's last segment. The cache is bounded
  (`MAX_CACHE_BYTES`, 2 GiB, least recently used copies evicted) and a file no copy can be kept
  for is refused by the codec's name (`avroCodecUnreadable`), since DuckDB's own line does not
  name it. `pav` is written under `deflate` and `paz` on the default, and the count, the sweep
  and the lookup answer the same on both. `resolveDataFile` is the resolve without the codec
  step, for the in-process read and for `fileColumnsOf`
- **A Paimon row is found the same way, and what decides it is the merge a read runs, applied
  to one record.** `model/PaimonRowLookup.kt` reads what it takes off the latest snapshot on
  `main` (`TableNode.paimonRowLookup`): the replay's live files with their partition and bucket,
  the index manifest's `_DELETIONS_VECTORS_RANGES` with the index file each sits in, the
  schema, the trimmed primary keys and `merge-engine`. `service/PaimonRowLookup.kt` reads the
  files the filter leaves through the same DuckDB statement and decides a hit in the order the
  format does: the vector its index file holds for the file, by position, first — the file's own
  statement; then its `_VALUE_KIND`, since a `-D` or `-U` is the marker a delete or an update
  wrote and not a row; then whether a later write for its key exists in the bucket, which under
  `deduplicate` shadows it. **That last one needs the bucket's other files whether or not the
  filter left them** — `lk`'s `v = 'b'` matches the old record of a key whose new value is `B`,
  and the new record is in a file the filter never opens — so the bucket is asked once per bucket
  the hits fall in (`UNION ALL` over its live files, the hit keys bound and cast, `max` and
  `arg_max` per key), never once per hit. A merge engine that combines versions rather than
  picking one is reported and not applied: a key with one record is that record, a key with
  several is `not decided`. An append table has neither keys nor sequence, so a hit is live
  unless its vector marks it (`ad`). `service/PaimonDeletionVectorReader.kt` decodes the vector
  from the layout `DeletionVector.read` reads at 1.3.1 and the `dv` index's own bytes confirm:
  a version byte opens the file; at each range's offset a big-endian size, a magic, the bitmap,
  a big-endian CRC-32 over magic and bitmap — and the range's recorded length is the size, so it
  excludes the size and CRC fields where a Puffin manifest's `content_size_in_bytes` includes
  them. Magic `1581511376` is a 32-bit portable Roaring bitmap, which is a Puffin vector's inner
  bitmap without the bucket wrapper, so `PuffinReader.readRoaring32` decodes it; magic
  `1681511377` read little-endian is the bytes `D1 D3 39 64`, Iceberg's own blob copied over, and
  the whole range goes through `PuffinReader.decodeDeletionVector` — `v3`'s Puffin blob is its
  oracle. `PaimonRowLookupFixtureTest` holds `lk`, `dv`, `ad` and `pc` to their scripts, and
  both formats land in one `RowLookupResult` with one `RowFate`, which is why the section is one
  composable: `RETRACTION` and `SUPERSEDED` are the two fates Iceberg has no need of
- **A schema's changes are read by field id against the schema before it, and the first
  snapshot written under each is named beside them.** `model/SchemaEvolution.kt` walks a
  table's schemas in id order and answers each step as a list of `SchemaChange` — added,
  dropped, renamed, moved, type changed, nullability, default, identifier fields; on Paimon the
  keys, the options and the comment too — decided by **id**, which is what makes `evolved`'s
  `name → label` a rename and `promoted`'s `label` a drop rather than a drop and an add twice
  over. Nested fields are compared as the leaves they are (`deep`: `addr.city → addr.town`,
  said of the leaf and not of the struct), and a **move** is the fewest columns whose move
  turns one sibling order into the other — everything outside a longest common subsequence of
  the ids both schemas keep (`movedIds`) — so a drop or an add beside a column is not a move
  of it, and `a b c → c a b` is one move of `c` and not three neighbours changing. Each step
  names the first snapshot written under it (`schema-id` on the snapshot, the lowest sequence
  number), which is the reader's question — which data was written under which shape — and
  is null for a schema DDL wrote between two inserts. `TableNode.schemaEvolution` carries the
  steps from the **model** on both formats (the section it replaces read the *drawn* metadata
  nodes, which aggregation folds, and diffed top-level fields with an algorithm its test
  duplicated rather than called); the table panel draws one table of every step's changes with
  a drop in the error colour and a type change that is not a promotion the spec allows, a
  Paimon schema node draws its own step, and the IDE strip prints it as a row.
  `SchemaEvolutionFixtureTest` holds `evolved`, `deep`, `defaults`, `pse` and `pkr` to their
  scripts' DDL step by step, and every Iceberg fixture to a first step of columns and a change
  on every later one, since a schema id is assigned only when the schema differs
- **The same lookup run at every retained snapshot on `main` is a row's history, and the fate at
  the current snapshot cannot stand in for it.** A row deleted three commits ago and one deleted
  by the last commit look the same there; `model/RowHistory.kt` names the commit. The table node
  carries `rowHistory`, a `DeferredRead<RowHistoryInputs>` both builders fill from the model —
  Iceberg's current-ancestor chain (`currentAncestorIds`, newest first) with `rowLookupInputOf`
  run for each, Paimon's `snapshot/` by descending id with `paimonReadInputOf` off each one's
  replay — capped at `MAX_HISTORY_SNAPSHOTS` (20) with `onMain` beside it, since a trace that
  stops at the cap has to say the horizon is the cap and not the table's first commit.
  `RowLookupInput` and `PaimonReadInput` implement one `LookupInput` marker, so
  `service/RowHistoryTrace.kt` dispatches on the type and the trace has no format in it. Each
  step compares **the live rows a read returns, on the row's own columns**, with the step
  before: `appeared`, `changed`, `gone`, `unchanged`, and null for the oldest traced, which has
  nothing older to stand against. The row's own columns, because a Paimon record's
  `_SEQUENCE_NUMBER` moves when the same value is written again, which is not a change a read
  shows. On Iceberg every snapshot is read under the newest schema — the lookup input's rule —
  so the rows compare column for column, and a data file's matching rows are the same at every
  snapshot listing it: `RowLookup.lookup` takes a `reads` map and the trace passes one, so a
  file is opened once per trace rather than once per snapshot. Paimon reads a file under its
  snapshot's own schema, which an `ADD COLUMN` changes between two, so it reads per snapshot.
  `RowHistoryFixtureTest` holds `mor`'s rows to the script commit by commit — 7 appears at the
  second append and is gone at the last delete; 5 changes at the update and not at the
  compaction that rewrote its file, where both versions are found, the old one deleted by
  position; 2 is gone at the first delete and the compaction leaves nothing to find — `eqdel`'s
  2 to gone at the equality delete with nothing to appear at, being in the first insert, and
  `lk`'s key 2 to changed at the append that superseded its value and unchanged at every
  `COMPACT`. The `History` stage sits under the lookup's result behind a second click, and its
  change column marks the exception: `unchanged` is printed on the rest so a column of them reads
  as a history rather than a table with holes
- **The one question asked from the directory rather than from the metadata is "what is here that
  nothing names".** `model/UnreferencedFiles.kt` walks the table root and subtracts every path the
  model resolved — manifest lists, manifests, data and delete files, Puffin vectors and statistics,
  metadata versions and the ones `metadata-log` names, the version hint; Paimon's snapshot and
  schema files, its three manifest lists, index manifests and index files, statistics, changelog
  files. **The test that carries the weight is that twelve engine-written tables report nothing**:
  a file kind missed by the referenced set is a false orphan on a checked-in table, which is where
  it should fail first. `cl` is the oracle for the other direction — the changelog file Paimon
  wrote for an overwrite and declined to commit. "Referenced" means named by *any* metadata version
  on disk, which is the literal reading and never produces a false orphan; Iceberg's
  `remove_orphan_files` reaches from the current metadata only and can delete more; Paimon's
  follows tags, as this does, and branches, which this leaves out of the walk with `consumer/`.
  Both are said on the panel, under the answer. Hidden files are skipped — Hadoop's `.crc`
  sidecars and `.DS_Store` are the filesystem's.
  It is a `DeferredRead` on `TableNode` behind a click, because it is the one thing on the table
  panel that scales with the data rather than the metadata, and on a remote table it is a subtree
  listing. A `Path` is an `Iterable<Path>` of its own segments, so the referenced set is built with
  `add`, never `+=`, which would append the segments and compile
- **A data file's own statistics are the last recorded figures, and they are checked behind a
  click.** `model/StatsCheck.kt` puts each column's recorded lower and upper bound, null count
  and (Iceberg) value and NaN count beside the same figures `service/StatsCheckReader.kt` counts
  from the file's rows in one DuckDB statement, and the entry's row count beside `count(*)`.
  They are what a scan prunes on without opening the file, so nothing on the read path checks
  them, and a bound a writer got wrong loses rows with nothing failing — which is why the
  table-level `Integrity` check leaves them out (a file read per entry) and the file panel
  offers them behind a button on both formats. The comparison is **one-sided on the bounds and
  exact on the counts**: Iceberg truncates string metrics to sixteen characters and increments
  the upper one, so a bound may be wider than the values and disagrees only when a row lies
  outside it. NaNs are kept out of a float's bounds by both writers, so they are counted apart
  with `FILTER (WHERE isnan(...))`. A positional delete's reserved `file_path` and `pos` columns are in no table
  schema, so their bounds are decoded by the types the spec fixes and checked too. Columns are
  found by **field id** through `parquet_schema`, so a column renamed since the file was
  written is still its column. `StatsCheckFixtureTest` sweeps every Parquet file of every
  fixture — 100-odd Iceberg files and 129 Paimon ones, every type the corpus carries, nothing
  left "not checked" but a column a rewritten manifest records for a file that never had it —
  and moves one bound past a row to see the disagreement named, since a check that never
  disagrees may not be looking. **The same check runs over the table behind a second click
  under `Integrity`.** `model/FileStatsSweep.kt` takes the current snapshot's live files from
  the **model** (`TableNode.fileStats`, filled by both builders like `integrity`; a deletion
  vector left out, its Puffin container having no rows), reads up to `MAX_FILE_STATS_CHECKS`
  (64) of them through `StatsCheckReader`, and turns each disagreement into an
  `IntegrityFinding` under `FILE_STATISTICS`, one per figure — `StatsProblem` is the structured
  form the per-column `reason` sentence is joined from, so the table lists `id lower bound:
  5 / a smaller 4` on the file rather than a sentence — listed in the same table as the metadata
  findings and folded into the section's title. A file that could not be read is named with the
  reason, never counted as agreeing. **A table past the cap is read a page at a time**:
  `sweepFileStats` takes `from`, the next click reads the next `MAX_FILE_STATS_CHECKS` from
  the files read so far and `FileStatsSweep.plus` folds the pages into one, so the whole table
  is reachable and no click opens more than a page — the stage keeps the pages read on screen
  while the next is opened, which is why it holds an accumulator under a `LaunchedEffect`
  rather than a `produceState` that clears on each request, and `IntegritySection.pageSize` is
  a parameter so `integrity-files-paged` can show the button on `parted`'s four files.
  `FileStatsSweepTest` holds every fixture's targets to exactly the live set (a page size of
  one shows the graph draws fewer than the sweep reads) and every target to no finding through
  DuckDB, and folds injected reads to see a throwing read become one unreadable file, a moved
  bound one finding, the cap stated, and two pages fold to the one sweep
- **A recorded figure is shown against the same figure counted.** `manifestTallies` in
  `model/ManifestTally.kt` puts each of `manifest_file`'s six counts beside what the manifest's
  own entries add up to. A scan trusts those counts without opening the manifest and nothing on
  the read path checks them, so the inspector does. It is also the suite's only assertion that
  compares what this code decoded against what Iceberg recorded about the same bytes — a status
  misread or an entry dropped shows up as a disagreement on a checked-in table.
  **The partition summaries get the same treatment**, and they are the figures that matter more:
  a scan skips or opens a manifest on `partitions[i].lower_bound` / `upper_bound` /
  `contains_null` before it reads any count. `partitionSummaryTallies` in
  `model/PartitionSummaryTally.kt` folds every entry's decoded partition — every entry whatever
  its status, because `ManifestWriter.addEntry` runs `stats.update` after the status switch
  (1.8.1) — and compares bounds as values through `compareValues`, never as text. A field with
  an undecoded entry or two incomparable values is *not counted*, which is not a disagreement;
  `contains_nan` and a null `lower_bound` are compared only where recorded. The manifest panel's
  `Partition Ranges` leads each row with the verdict and puts the counted bound beside the
  recorded one; `PartitionSummaryTallyTest` holds every engine-written manifest to agreement
  across every transform the corpus carries, moves one bound by hand to see one figure
  disagree, and holds `lineage`'s DELETED-only manifests to recording their removed files'
  partitions as bounds — the status-blind fold seen, not read
- **What a positional delete file removes is counted by DuckDB, behind a button.**
  `SampleRowReader.queryPositionalDeleteTargets` runs `GROUP BY file_path` over the delete file's
  own rows, so what crosses back is one row per targeted data file whether the file holds one
  position or four hundred thousand — grouping in this process would pull the whole file into
  memory to produce a handful of counts. Nothing in the metadata answers this: `record_count` is
  how many positions the file holds and `referenced_data_file` exists only when the writer made
  one file per target, so the breakdown is the file's contents. It sits behind an action for the
  same reason `deletionVectorLoader` is a lambda — a graph is built for every artifact the
  metadata names, and reading each delete file at build time is a file open per delete on a table
  where most are never looked at. The counted total is put beside the manifest's `record_count`,
  and `PositionalDeleteTallyTest` is where that comparison is asserted on `mor`'s real
  Spark-written deletes. **The result state is one a click produces**, so
  `PositionalDeleteTargets` takes `startRequested` (the `sectionCollapse` rule) *and* an
  `onSettled` callback: the read is genuinely async, an `ImageComposeScene` only advances its
  dispatcher when rendered, and sixty frames in a tight loop finish long before a DuckDB query
  does — the first capture attempt was a PNG of the loading line. `renderUntil` polls with a
  deadline and fails rather than capturing a spinner
- **A sampled row is the file's, and what a read returns for it is projected onto the current
  schema by field id — the rule every Iceberg reader applies and the card does not.** The card
  prints the file's columns under the file's names, which on a table that evolved after the file
  was written is not what a query returns: `evolved`'s first file holds `name`, a column renamed
  to `label` and then dropped, and lacks `note`; `defaults`' first file predates `region` and
  `score`. `model/ReadProjection.kt` (`projectRow`) places each cell by the file's own field ids —
  a Parquet footer's `field_id` per top-level column, walked past a struct's children by the
  children counts `parquet_schema` lists; an Avro header's `field-id` prop, read with
  `getObjectProp` because it is a JSON number — against the newest metadata's current schema: a
  renamed column reads under its new name, a dropped one is listed as *not read* with the value
  the file holds, an added one reads as its **`initial-default`** (v3; `TableSchemaField` /
  `NestedField` carry `initial-default` and `write-default` now, and the metadata panel's schema
  table draws both where any field records one) or as null, and a column recording no field id
  is *unmatched* — a read resolves it through a name mapping this does not apply. `RowNode.readAs`
  is a `DeferredRead`, since the footer is a read; `UnifiedDataFile.fieldIds` is its lazy source.
  `ReadAsSection` on the row panel is drawn only when the projection differs from the file, with
  the count in its title; the IDE strip fills a `Read as` row the way it fills `History`. The
  oracle is Iceberg 1.10's own read of `defaults` — `1 alpha eu 0 / 2 bravo eu 0 / 3 charlie us 7`,
  printed by the script — and `ReadProjectionFixtureTest` holds the first two rows to it; the
  same script shows `updateColumnDefault` moving `write-default` to `us` and leaving
  `initial-default` at `eu`, which is the difference between the two figures
- **Every DuckDB read of a data file under a filter goes through the file projected onto the
  schema, and a file that records no field ids is placed through the name mapping.**
  `SELECT * FROM read_parquet(?) WHERE …` addresses a file by its own column names, and a
  filter names the schema's: on `evolved`, `note = 'fifth'` came back as a DuckDB *error* on the
  two files that predate `note`, where a read returns their rows with `note` null — and
  `note IS NULL` is true of every row in them, which no error can say. `service/FileProjection.kt`
  builds the `FROM` a lookup (`RowLookup.readMatches`), a live-row count's equality join
  (`LiveRowCount`, both the data file and the delete file) and the statistics check
  (`StatsCheckReader`, its placement) run over: each schema field aliased from the file column
  `placeFileColumns` places for it — the file's own field id first, the table's
  `schema.name-mapping.default` (`model/NameMapping.kt`) for a column recording none, which is
  every file `add_files` or `migrate` registered — a field the file lacks as its
  `initial-default` bound and cast to the column's type, or `NULL`. `migrated` is the fixture:
  a plain-Spark Parquet file brought in with `add_files`, then `RENAME COLUMN name TO label`, and
  the rename **appends** `label` to the mapping's names for field 2 (`["name", "label"]`), so a
  filter on `label` reads the file's `name`. Three more things it settled. **`add_files` records
  the source's `file:/wh/plain-files/…` URI**, and compared raw against the manifest's `/wh/…`
  prefix it shared nothing and was rebuilt as `<table>/file:/wh/…`; the resolver normalises
  both sides now, and the file lands under `example/iceberg/plain-files/` beside the table, the
  `extdata` rule. **`add_files` appends a manifest, and a `manifest_file` carries no byte
  total**, so the commit's summary has `added-records` and no `added-files-size`, and
  `total-files-size` starts at 0 and stays short by the registered bytes on every later commit
  (`SnapshotSummary.MetricsUpdate.addedManifest` at 1.8.1 adds counts only) — the integrity
  check's `SNAPSHOT_TOTALS` finds it (`0` against `916`, then `943` against `1859`), and
  `IntegrityFixtureTest` / `SnapshotTotalsTest` hold `migrated` to exactly that rather than to
  agreement. And the manifest `add_files` wrote still names field 2 `name`, so the *statistics*
  check matched the file by name even before the mapping; it is a read that needs it.
  **An equality delete file is read the same way**, because it is a Parquet file whose columns
  are named as the schema named them when it was written and a scan matches it by field id
  (`equality_ids`): `eqren` is `eqdel` with `name` renamed to `label` *after* the equality
  delete on it, and read by name the delete file answered `label` with an error, so id 2 and
  6 came back *not decided* where Spark deletes them. `RowLookup.equalityMatches` projects the
  delete file now, `decide` carries the schema and mapping for it, and the row panel puts the
  row's cells under the schema's names — `RowNode.cellsForRead`, the projection's cells with an
  absent column null, else the card's — since the delete's columns are the schema's and the
  card's are the file's; `GraphModel.newestIcebergMetadata()` is where both panels get the
  metadata to read under. `eqren`'s row 8 is the contrast: `label` bravo, written after the
  delete at a higher sequence number, which Iceberg's own plan does not attach the delete to
  (`deletes.txt`) — Spark reads `1 4 5 7 8`, and `RowLookupFixtureTest`, `RowFateFixtureTest`
  and `LiveRowCountFixtureTest` hold the lookup, the panel's decision and the count to it
- **A Paimon row is projected the same way, and its columns are placed by the schema the file's
  own `_SCHEMA_ID` names — never by an id inside the file.** Paimon evolves a read from the
  file's schema id (`SchemaEvolutionUtil`), so `paimonFileColumns` in `model/PaimonPruningBridge.kt`
  gives each physical column the id *that* schema gives its name and a system column (`_KEY_*`,
  `_SEQUENCE_NUMBER`, `_VALUE_KIND`) none; the ids a Paimon Parquet file records happen to be the
  schema's, and a Paimon **Avro** file records none at all — the first version placed by the
  file's ids and `pav`'s lookup found nothing. `paimonSchemaAsIceberg` puts the latest schema in
  the shape `projectRow` and `FileProjection` read, with a field's `defaultValue` carried as a
  **write default and no initial default**: a Paimon default (`ALTER COLUMN … SET DEFAULT`,
  `DataField.defaultValue` in the schema JSON, `PaimonField.defaultValue`) is filled in when a
  write omits the column and is not applied on read — the first run of `paimon-pse.sql` set
  `fields.w.default-value = 7` as a table option and Paimon 1.3's read still returned null for
  every row it could have applied to. So `PaimonUnifiedDataFile.fileColumns` and
  `PaimonGraphBuilder`'s `RowNode.readAs` project onto the latest schema on `main` (a row node
  is built once per file, whichever snapshots list it; none on a data-evolution table, whose
  read is a stitch), `PaimonRowLookup.readMatches` reads the file through `FileProjection.of`
  with the system columns passed through under their own names (`passThrough`) — the merge is
  decided on them, and five of the lookup's fixture tests fail without it — and the Paimon
  schema panel draws a `Default` column where any field records one. `pse` is the fixture, an
  append table because a primary-key table's compaction rewrites the old file under the new
  schema and leaves nothing to read across the evolution (`se`): `1 a null / 2 b null / 3 c 30 /
  4 d 7`, the first file's `v` read as `label` and its missing `w` as null, the 7 the DDL
  default put into a row that omitted `w`; `PaimonReadProjectionFixtureTest` holds the
  projection, the placement on both `pse` and `pav`, and the lookup on `label`, `w IS NULL` and
  `w = 7` to it. **A key-value file's system columns are placed by id too, and a bucket read is
  a `UNION ALL` of projections.** Paimon 1.3.1 lets a *primary key* be renamed (`SchemaManager`
  guards partition keys only, and `primaryKeys` follow), so a bucket holds `_KEY_k` in one
  file and `_KEY_id` in the next for one key — which the first version, passing `_`-columns
  through under their own names, asked every file for `_KEY_id` and hit `Binder Error:
  Referenced column "_KEY_id" not found` on the old one. `PaimonSystemColumns` carries
  `SpecialFields`' ids — a key field at `KEY_FIELD_ID_START (1073741823) + field id`,
  `_SEQUENCE_NUMBER` at `Integer.MAX_VALUE - 1`, `_VALUE_KIND` at `- 2` — `paimonFileColumns`
  derives them from the file's own schema, `paimonSchemaAsIceberg(schema, systemColumns =
  true)` leads a primary-key schema with them (`PaimonReadInput.readSchema`), and
  `PaimonRowLookup.BucketSources` is the bucket's files each projected onto it, which
  `latestPerKeySql`, `sequenceGroupRecords` and `readMatches` all read through and bind
  through (`FileProjection.of` gained `filename` for the `UNION ALL`). The row panel's `Merge`
  asks the key under the schema's name from `RowNode.cellsForRead`, since the card's is
  `_KEY_k`. `pkr` is the fixture — Paimon reads `1 A / 2 b / 3 c` across the rename — and
  `PaimonRowLookupFixtureTest` holds key 1's old record to superseded by the file written after
  the rename. The one Paimon read still addressed by name is a data-evolution split's stitch
  (`readSplit`), which joins by `file_row_number` and selects the schema's names
- **A sampled row's position is asked for, not inferred.** DuckDB is given
  `read_parquet(?, file_row_number = true)`, and `UnifiedRow.position` carries the answer as
  something separate from the row's cells — it is DuckDB's statement about the file, not a column
  the table declares. That position is the coordinate an Iceberg positional delete and a v3
  deletion vector both address, so it is what `RowNode.isDeletedByVector` is decided against;
  taking the row's ordinal in the result set instead would be a guess about scan order that
  nothing in the result could contradict. The set of deleted positions reaches the row from
  another node — the vector is a file node under some manifest, found by its
  `referenced_data_file` — through an index built on first use, one vector opened per drawn data
  file rather than every `.puffin` in the table. A deleted row card carries the word, the strike
  and the fade: green at full strength is the colour of a live row, which would be colour arguing
  against the label printed on it. **A positional or equality delete is asked behind a click on
  the row's panel**, because either is a file read: `RowDeletesSection` takes the row's parent
  file, the delete files `deleteCandidatesFor` leaves for it — the same pairing the file's own
  panel lists — and `RowLookup.fateOf`, the decision the lookup makes for a hit with the row's
  position and cells already in hand. The v2 merge-on-read shape Spark writes is the common one,
  and until this the panel's `Deleted` row said *not by a deletion vector* of a row a positional
  delete had removed — it now says how many delete files the pairing leaves for the file and that
  they are asked under `Delete Files`, from the same `rowDeleteCandidates` the section draws from,
  so the two lines cannot disagree. `RowFateFixtureTest` holds `mor`'s id 7 to deleted under the compacted file
  and live under the copy the compaction removed — the delete written after it does not reach
  that file — and `eqdel`'s 2 and 3 to their two kinds. `PaimonRowMergeSection` is the Paimon
  twin on the same panel: a record is a version of a row and its fate depends on the key's
  other records in other files, so it runs the table's row lookup for the record's own key,
  leads with the record's line and lists the key's other records under it — `lk`'s superseded
  record is the capture. On a data-evolution table the same section is `Read As`: an append
  table merges nothing, but a whole file's cells on a patched column are the values a patch
  replaced, so `PaimonRowLookup.stitchedRowAt` reads the split at the row's position and the
  panel prints the row a read returns with each patched column's file named
- **A deletion vector is decoded, and decoded lazily.** `service/PuffinReader.kt` reads the
  Puffin container and the `deletion-vector-v1` blob inside it — a 4-byte big-endian length, the
  magic `D1 D3 39 64`, a 64-bit "portable" Roaring bitmap, and a 4-byte big-endian **CRC-32**
  (not CRC-32C; the fixture's own bytes settled that before any code shipped). It is written
  against the two published specs rather than against the fixture, because the fixture holds
  exactly one shape — one array container, one position — and a decoder written from that agrees
  with itself and fails on the first table with more than 4,096 deleted rows in a 64k block.
  `FileNode.deletionVectorLoader` is a `DeferredRead`, not a value: the graph is built for every
  artifact the metadata names and drawn for a page of them, so reading every vector at build time
  would open a file per delete on a table where most are never looked at. **A deferred read is
  kept out of the node's identity, and `DeferredRead` in `GraphTypes.kt` is what makes that
  true** — a bare `private val` lambda does not, because a private primary-constructor parameter
  is still a component of a data class's generated `equals`, so two nodes for the same entry
  built by two graph builds compared unequal while a comment claimed the opposite. Any future
  deferred read on a node goes through the same type; `DeferredReadTest` pins it.
  **Two recorded figures sit beside what was decoded**,
  for the same reason `manifestTallies` exists: the blob's own CRC-32, and the *manifest's*
  `record_count`, which the spec requires to equal the cardinality and which a scan plans against
  without opening the Puffin file at all. Both are passed in by the builder, not read here.
- **A statistics file is read as a record, and its blobs are read as rows.** `statistics` and
  `partition-statistics` in `metadata.json` were `List<JsonElement>` and rendered as one JSON blob
  per cell, which was invisible because every fixture carried `[]` — `docs/fixtures/stats.sql`
  exists so they are exercised full. The shape that matters is that the list is *files*, each
  holding *blobs*, and the question a reader has is about neither: it is "how many distinct values
  does this column have", which `model/TableStatistics.kt` answers as one row per blob. **A blob's
  `fields` are field ids resolved against the schema its own snapshot used**, never the table's
  current one — the same rule that decodes a manifest against the partition spec it records, and
  for the same reason: a statistics file describes one commit, and a column renamed after it would
  otherwise put a name against a figure never measured for it. A field no schema names is printed
  as its id rather than guessed at. The `ndv` a `apache-datasketches-theta-v1` blob declares is a
  *property string*, and it is the figure a planner uses without opening the sketch — reading the
  sketch itself would need the datasketches library and answers nothing more precise
- **And the record is shown against the file it describes**, the same rule as `manifestTallies` one
  level up. `metadata.json` carries a *copy* of the blob metadata so a planner never has to open
  the `.stats` file, which is precisely what lets the two drift: a statistics file removed by an
  orphan-file cleanup leaves its record behind and nothing on the read path notices.
  `MetadataNode.statisticsFooters` is a `DeferredRead` — opened on the first render of that panel,
  not at build time, where it would be a file open per metadata version of the table — and the
  path resolves recorded-first like a manifest list's, which is what opens it for a table written
  at some container's `/wh` or copied down from a bucket. `StatisticsBlobRow.fileRead` is carried
  rather than inferred from the file-side fields being null, because **"the file was not read" and
  "the file was read and holds no such blob" would otherwise look identical**, and the second is
  the one that means the table is pointing a planner at statistics that are gone. The file also
  answers three things the record cannot: each blob's compressed size, its codec, and `created-by`
- **A partition statistics file is the same shape one level over, and its rows are Parquet.**
  `partition-statistics` in `metadata.json` names a file per snapshot and nothing more; the
  figures — one row per partition, `data_record_count`, `data_file_count`, the size, the delete
  counts, `last_updated_snapshot_id` — are inside it, which is what a planner reads instead of
  walking every manifest. `model/PartitionStatistics.kt` reads them through the same DuckDB
  connection a data file's rows go through (`SampleRowReader.queryPartitionStats`, with the
  `partition` struct rendered `p=eu` the way a path carries it), under `MAX_PARTITION_STATS_ROWS`
  rather than the sample cap, since a table has as many rows here as partitions.
  `MetadataNode.partitionStatistics` is a `DeferredRead` for the reason `statisticsFooters` is,
  and the panel puts the size on disk beside the size the record claims. `pstats` is the fixture,
  written with the 1.10 runtime because `compute_partition_stats` does not exist in 1.8.1, and it
  has **two oracles**: the writer's own `pstats.partitions` output, printed by the script, and this
  app's live-file walk of the current snapshot grouped by partition — two ways of counting one set.
  `total_record_count` is optional in the spec and 1.10 writes it null, which the panel says
  rather than printing a figure
- **Pruning has two stages and they prune on different things.** `evaluateScan` in
  `model/ScanPruning.kt` returns a `ScanPlan` carrying both: a manifest is ruled out by the
  partition summaries its list records, a **file** by the `lower_bounds`/`upper_bounds` it records
  about its own columns. Only a partitioned column reaches the first; every column reaches the
  second, which is why `prunableColumns` offers the union and why `id > 1000` on an unpartitioned
  table is now a question this answers. The file stage needs no transform bridge — a column
  statistic's bounds *are* the source values — and it can settle one term the manifest stage never
  can: `IS NOT NULL` is proved empty when `null_value_count == value_count`. **The two are composed
  in one function on purpose**: a scan that ruled a manifest out never opens the entries inside it,
  so a file under it is `NOT_REACHED` and not `SKIPPED`, whatever its own bounds say. Reporting it
  as skipped would credit the wrong term and double-count it against the file stage. **Both
  stages are held to Iceberg's own plans**: `docs/fixtures/iceberg-scan-plans.scala` runs
  `table.newScan().filter(expr).planFiles()` on 1.8.1 over `parted`, `respec`, `evolved`, `pstats`
  and `mor` — `ManifestEvaluator` over the partition summaries and `InclusiveMetricsEvaluator`
  over the file bounds, the two `evaluateScan` reproduces — and prints the data files each of 41
  plans opens; `IcebergScanPlanTest` requires every file a plan opens to be *would be read* here,
  which is the direction that loses rows, and pins that all 48 filtered plans agree file for file.
  Every other pruning test reads its expectation off the script that wrote the rows, which is an
  oracle for the rows and not for the planner: a wrong skip of a file holding no matching row
  passes it. An `Or` proved by one branch — the unsound reading — is caught by `id IN (1, 2)` on
  `parted` skipping the two files Iceberg opens
- **A filter's column binds by field id through the current schema, the way a scan binds it,
  and a nested leaf is named by its path.** `ColumnBinder` in `model/ScanPruning.kt`:
  `BySchema` resolves a column to the current schema's field id (`IcebergSchemaModel.idOfPath`
  — `label`, or `addr.town`) and a manifest's partition source and a file's statistics are then
  matched by that id; `ByName` is what a graph with no schema drawn (the synthetic tests) falls
  back to. Matching by name — the only rule until this — stopped pruning at the first rename:
  `eqren`'s two older manifests still call field 2 `name`, so `label = 'alpha'` evaluated
  nothing on them and the app read three files where Iceberg's plan opens one, and
  `prunableColumns` listed `name` and `label` as two columns of one table. Now the columns are
  the current schema's leaves by path, each once (`prunableColumnsOf`), and a column the schema
  lacks is *not evaluated* with the reason on screen rather than matched against whatever an
  old manifest called it. Nested columns arrived with the same change, and `deep` is their
  fixture — a struct, a list and a map, `addr.city` renamed to `addr.town` and `addr.country`
  added after the first file: Iceberg records bounds and counts per **leaf** id, so
  `fieldsById` now descends into a list's element and a map's key and value (`tags.element`,
  `props.key`, `props.value`, ids 8–10), `pathsById` / `pathOf` name every field by its dotted
  path, `ColumnStats.columnName` is that path, and `TableMetadata.fieldsEverDefined()` is the
  one definition of "every field any schema defined, newest winning, by path" that the builder
  and the file-statistics sweep both take (they had drifted: the sweep's oldest definition won).
  Two things `deep` recorded that a reading would not predict: **an empty list or map counts as
  one null element** in `value_counts` / `null_value_counts` (`['a','b'], ['c'], []` is 4 and 1),
  and a file written before a column existed records nothing for it, which Iceberg reads as
  "cannot prune" (`addr.country = 'TR'` opens both files) — where **Paimon reads the same file
  as null in every row**: `AppendOnlyFileStoreScan.filterByStats` keeps a filter on a field the
  file predates (`keepNewFieldFilter = true`) and `SimpleStatsEvolution` evolves the stats with
  `nullCount = rowCount` for it, so `w = 7` skips `pse`'s first file and `w IS NULL` keeps it;
  `paimonEvolvedColumnStats` adds those all-null statistics for every column the file's own
  schema lacks — its schema, not its `stats-mode`, so `sm` stays unevaluated. Paimon's
  partition summaries bind by id too (`PaimonPartitionValue.fieldId`, carried out of
  `decodePaimonRow`). Both oracles were rerun: `eqren` and `deep` on the Iceberg side (48 cases),
  `pse` and `pkr` on the Paimon side, every case agreeing file for file. The row lookup reads a
  nested leaf as struct access (`quoteSqlColumnPath`: `"addr"."zip"`, the type through
  `idOfPath`), so a filter on a leaf every file holds answers; what it still cannot do is
  rename *inside* a struct — the projection renames top-level columns only, so `addr.town` on
  the file written when it was `city` reports DuckDB's error rather than the row, said per file
- **A filter is a boolean expression, and `NOT` is removed before anything is evaluated.**
  `model/ScanFilter.kt` holds `Term`/`And`/`Or`/`Not`; `evaluateScan`, `evaluatePruning` and
  `evaluateFilePruning` each take one, and the list form every existing caller passes is wrapped
  as a conjunction so behaviour is unchanged. The rules follow from pruning being a **one-sided
  proof** — bounds can show an artifact *cannot* hold a matching row and can never show it does.
  So an `And` is proved by one branch, an `Or` only by every branch, and **`Not` is rewritten
  away**: negating a one-sided proof yields no proof, so a `Not` left in the tree would silently
  stop the pruning underneath it. `pushNegation` does De Morgan through the connectives and
  `PredicateOp.negated` at each leaf, which is what Iceberg's own `Expressions.not` does and is
  sound with nulls for the same reason SQL's is. **The predicates are evaluated once and the
  verdict folded over the tree afterwards**, not during: folding as it evaluates would skip the
  branches an `And` short-circuits, and the panel could then not say why the other half was never
  looked at. A `Not` that somehow reaches the fold answers "might match" rather than being guessed
  at — the absence of a proof is the only safe answer, and a wrong skip is the one pruning bug
  that loses rows
- **There are two ways to write the filter, and which one is shown is decided by the filter.**
  The rows carry the prunable columns in a menu, which is where a reader who does not know what
  the table is partitioned on has to start; the clause editor (`parseScanFilter`) is the only
  input that can say `OR`, `NOT` or a group. `ScanFilter.asConjunction()` returns null for
  anything the rows cannot represent, and the panel then **keeps the reader in the editor** —
  offering "use the form" for `a = 1 OR b = 2` would have to drop the `OR`, and a control that
  silently discards half of what was typed is worse than no control. The clause's text is local
  state seeded when the editor opens, never re-derived from the filter per keystroke: rendering it
  each frame would rewrite `a=1` to `a = 1` under the cursor. It is pushed out only when it
  parses, so a half-typed clause leaves the last good verdicts on screen instead of clearing them.
  A parse failure carries the **offset**, and the panel shows a word-snapped window around it —
  the first version cut at a fixed offset and produced `…D name =`, the tail of `AND` reading as a
  word of its own. **Literals stay text in the parser**: `2024-03-05` is a date to one column and a
  string to another, and only the artifact being evaluated knows which, so `parseLiteral` stays the
  one place a literal becomes a value — and **what a literal needs quoting back to is the
  tokenizer's rule, kept beside the tokenizer** (`quoteScanLiteral`), because a renderer deciding
  that separately drifts from what the parser accepts and the drift arrives as the reader's own
  filter reading as an error: `ts > '2024-03-05 10:00:00'` rendered as `ts > 2024-03-05 10:00:00`,
  which is two literals and parses as neither. **The field wraps rather than scrolling sideways**, which is
  a decision about the width the panel is actually used at: it opens at 300dp and drags to 200dp,
  where one line showed `name IN ('alph` of a filter four times that long — so a reader could not
  see whether their own parentheses balanced, and the error message's word-window was carrying that
  alone. It is capped at four lines, because a filter is not a document and the verdicts it explains
  have to stay on screen. Wrapping is also what exposed Material3's `lineHeight = 24.sp` surviving
  in a `textStyle` that overrode only `fontSize` — the same trap `CardColumn` exists for, invisible
  on one line and double-spaced on three
- **`IN` and `BETWEEN` are parser sugar, and a leaf for either would have been a second
  implementation of a rule already written.** `IN (a, b)` is `= a OR = b` and `BETWEEN lo AND hi` is
  `>= lo AND <= hi` — SQL's own definitions — so `ScanFilter` gained no node and the evaluator no
  case: a manifest is ruled out by an `IN` exactly when every value is ruled out, which is what
  `Or` already says. The negated forms go the same way, `x NOT IN (…)` parsing to a `Not` around the
  disjunction so `pushNegation` works out De Morgan rather than the parser writing it a second time.
  Three things follow. The `AND` inside a `BETWEEN` is consumed by it and never by the connective
  parser — read as a connective it drops the upper bound, which prunes *less* and looks correct.
  `render()` writes both back as the shape being evaluated (`a = 1 OR a = 2`), because there is no
  node to render as `IN`. And the parser **splices a branch of the same connective into its
  parent**: `BETWEEN` desugars to an `And`, so without that `a = 1 AND d BETWEEN 2 AND 3` is a
  nested `And`, `asConjunction` answers null, and the panel keeps the reader in the editor for a
  filter the rows can show perfectly well
- **`LIKE` is the one pattern operator with a leaf, and it proves only what a prefix can.** Bounds
  are a range of strings, so the only thing a pattern can be disproved against is the text it pins
  at the *start*. `likePattern` in `model/ScanPruning.kt` takes the text before the first wildcard;
  `%pha` pins nothing and reports that it did not evaluate. The comparison runs over **the shorter
  of the prefix and the bound**, which is what keeps it sound where the bound is itself a prefix —
  Iceberg truncates string metrics at 16 characters and a `truncate[W]` partition value is a prefix
  by construction, so comparing `alph` against a three-character `alp` would skip a manifest holding
  `alpha`, which is the one pruning bug that loses rows. **`NOT LIKE` runs the proof the other way**
  and needs *every* value to match, so it is answered only where the bounds are the values
  themselves — an identity field, or a file's own column statistics — and only for `text%`, since
  every value starting `abc` is not every value matching `abc_`. A wildcard-free pattern is
  equality: the clause parser reads it as `=`, which is settled by any bound that excludes the
  value, while the row form can still produce a bare `LIKE` and the whole pattern is its prefix
- **`bucket[N]` prunes on equality, and only because the hash is the writer's own.**
  `BucketTransform` calls `Hashing.murmur3_32_fixed()` — the same Guava function Iceberg's
  `Bucket` transform calls — so nothing here re-derives a hash, and the only thing left to get
  wrong is how a value becomes bytes. That part is checked against the writer, not the spec text:
  `BucketTransformTest` reproduces the `id_bucket` Spark recorded for each data file of `parted`
  and `respec` from the file's own bounds, at **two different bucket counts**, so a modulus applied
  at the wrong point cannot pass both. `c = v` implies `bucket(c) = bucket(v)`, so a literal whose
  bucket falls outside a manifest's recorded bucket range is a proof — **and nothing else crosses**:
  a range of bucket numbers says nothing about a range of values, so every other operator still
  reports it did not evaluate. `BucketPruningTest` checks the direction that matters, that no value
  the table actually holds is pruned away; a wrong sign still skips *something*, so "a manifest was
  skipped" proves nothing on its own
- **A manifest is evaluated against its own partition spec, never the table's current one.**
  `evaluatePruning(graph, predicates)` in `model/ScanPruning.kt` reads each
  `ManifestNode.partitionSummaries`, decoded against the spec that manifest records — the same
  rule the bounds themselves follow. The bridge from a source literal to a partition value
  exists only for order-preserving transforms (`identity`, `year`, `month`, `day`, `hour`,
  `truncate[W]`). `bucket[N]` reports that it did not evaluate rather than a verdict that might
  be wrong: pruning equality on a bucket means reproducing Iceberg's 32-bit murmur3 over its own
  serialisation, and a hash re-implemented from a spec agrees with itself long before it agrees
  with the writer. **`SKIPPED` is a proof; "would be read" is only the absence of one** — so a
  manifest nothing could be evaluated against is counted and coloured separately, never folded
  in with the ones that were checked and kept
- **The reason a row gives has to explain the verdict beside it, not the best any single term
  managed.** Once a filter could hold an `OR`, "some term ruled this out" stopped meaning "this is
  ruled out" — one proved branch of `a = 1 OR b = 2` proves nothing — so `summarise` takes the
  verdict as a parameter rather than reading a proof off the outcomes. Without it a row printed
  `would be read` beside the reason a skip would have had, contradicting itself in two adjacent
  cells with nothing failing. When the verdict *is* a skip, **every** proving term is listed rather
  than the first: under a conjunction there is usually one, under a disjunction there is one per
  branch and all of them were needed, and listing them is true of both. `ManifestPruneResult.skippedBy`
  is deliberately not what the panel reads — it answers "did any term rule its own condition out",
  which can be true while `isSkipped` is false
- **A verdict column marks the exception, not every row.** `WideTable` takes `leadCellColors`,
  one entry per row for the leading cell, and bolds that cell **only where a colour was supplied**
  — so a verdict table passes `null` for the ordinary outcome and it stays at body colour and body
  weight. `Theme.kt` carries the two that are not ordinary (`verdictSkippedColor` /
  `verdictUnevaluatedColor`, lightened on a dark surface, since #0A7048 sits below it); there is
  deliberately no third for "would be read". The word stays — colour is never the only signal —
  but a column of "would be read" with one "SKIPPED" in it has to be findable without reading
  every row, and a column bolded on every row has spent its emphasis before the exception
  arrives. The reason cell names the partition **field**, not the whole condition: the condition is
  already in the form above and the reason names the literal, so printing it again put the same
  date in one cell three times
- **Partition transforms do not share a result type.** `day` produces a `date`; `year`, `month`
  and `hour` produce `int` ordinals counted from the epoch (a `year` partition for 2024 stores
  `54`). All four are four little-endian bytes, so the wrong choice yields a plausible value,
  never an error. `partitionResultType` in `PartitionDecoder.kt` is the single place this is
  decided, and `PartitionValue` keeps both the stored value and Iceberg's own rendering
- **The partition spec comes from the manifest, not from `metadata.json`** — same rule as the
  schema for bounds. A repartitioned table describes each file by the spec in force when it was
  written, and using the current spec mis-decodes silently
- **The manifest's schema is right for a file written under it, and a rewritten manifest lists
  files that were not.** `rewrite_manifests` writes every live entry into a manifest under the
  table's *current* schema and copies each file's bounds verbatim — bytes keyed by field id — so a
  file written before `int → long` sits under a schema that says `long` with four-byte bounds, and
  a file written before a `DROP COLUMN` carries a bound for a field id that schema no longer has.
  Two rules follow, and `promoted` (`evolved` plus a rewrite) is the oracle for both.
  `decodeSingleValue` reads four bytes under a `long` as an `int` and under a `double` as a
  `float` and widens, which is what Iceberg's own `Conversions.fromByteBuffer` does ("type was
  later promoted"); `DecodedValue.writtenAs` says which width it came from and the panel prints
  `1 (written as int)`. Only those two, because they are the spec's promotions and the value is the
  same number either way — two bytes is still a failure, and a four-byte timestamp is not read as
  anything. And a field the manifest's schema lacks is named and typed by **the newest table
  schema that had it** (`FileNode.tableFieldsById`, every field any `metadata.json` schema
  defined, newest definition winning — `label`, not `name`, for a column renamed and then
  dropped), with `ColumnStats.dropped` set so the panel says `label (dropped)`. That type is safe
  to decode with because a type only ever widens, so the newest definition is the widest. The
  fallback answers *only* where the manifest's schema does not, so `evolved` decodes exactly as
  before. `promoted` ends with a `rewrite_data_files` for the contrast: a **data** rewrite
  re-encodes — its one file has eight-byte bounds and none for the dropped column — while the
  DELETED entries beside it in the same manifest still carry the old file's four bytes
- **A v3 row id is read by inheritance at three levels, and the running sum is the part that
  needs a fixture.** A snapshot's `first-row-id` is `next-row-id` as it stood; a manifest's
  `first_row_id` is assigned in manifest-list order from that; a data file's is the manifest's
  **plus the record counts of the files before it in the manifest that also recorded none**, so
  it is decided in `UnifiedModel` where the entries are in order — `UnifiedDataFile.firstRowId` /
  `.firstRowIdInherited`, carried to `FileNode` like the sequence number is — and a row's
  `_row_id` is the file's plus its position unless the file wrote the column, which a rewrite does
  so its rows keep their ids; a null `_last_updated_sequence_number` is the file's data sequence
  number. `IcebergGraphBuilder.sampleRowFactory` fills both where absent, and a DuckDB null arrives
  as the string `"null"`, so "absent" has two spellings there. Three things `lineage` settled that
  the spec text states but a reader would not predict: an entry **carried into a rewritten
  manifest has its inherited id written in** (0 and 2 on the update's DELETED and EXISTING
  entries); a manifest with only existing rows still advances the allocation by them, so an
  `UPDATE` of one row moved `next-row-id` by three and **ids 7 and 8 are burned**; and the added
  file of that update inherits 6 though its one row carries `_row_id` 2. A deletion vector's
  manifest and entry record none. And a `rewrite_data_files` at the end **carries every row's id
  and last-updated number into the files it writes**, columns copied from the rows — the promise
  of row lineage, and the reason the writer's own per-row printout is identical before and after
  it — while its files still inherit 9 and 12 and `next-row-id` moves to 14. `lineage` is
  partitioned so one INSERT writes two files into one manifest, which is the only shape that
  exercises the sum. **The burn is recorded, as `added-rows`.** The spec's worked example calls it
  the sum of added rows, but `SnapshotProducer` writes `writer.nextRowId() - base.nextRowId()` —
  the id space the commit took, existing rows included — so it is 3 on the update whose
  `added-records` is 1, `first-row-id + added-rows` is the `next-row-id` of the metadata that
  introduced the snapshot on every commit of `lineage`, and `Snapshot.describeRowIds()` prints the
  range with the summary's figure beside it where the two differ. It is one function in core
  because both shells list it and two spellings of "the other 2 went to existing rows" drift
- **A ref's retention settings replace the table's and the procedure's for the snapshots that
  ref reaches.** `retained`'s first run gave the branch `WITH SNAPSHOT RETENTION 2 SNAPSHOTS 7
  DAYS`, and `expire_snapshots(older_than => 2099)` removed **nothing**: a branch's own
  `max-snapshot-age-ms` stands in for `older_than` on every snapshot the branch reaches, none was
  seven days old, and every snapshot of that table — main's two included — was an ancestor of the
  branch tip. The checked-in fixture sets no age, so the branch falls back to `older_than`, keeps
  its `min-snapshots-to-keep` two, and snapshots 1 and 3 expire: two manifest lists deleted, no
  manifest and no data file, since later snapshots carry them forward. The refs table prints the
  three settings as ages (`formatRetentionMs`: `30 days (2,592,000,000 ms)`) with `not set` where
  the table's defaults apply, instead of the bare milliseconds it printed as `N/A` on every
  fixture before this one
- **What an expiry would remove is decided the way `RemoveSnapshots` decides it, and checked
  against two expiries Iceberg ran.** `model/ExpiryPlan.kt` reads the rules off that class rather
  than the docs, because the docs call `older_than` the cutoff and the code makes it only the
  *default* one: a ref survives if it is `main` or younger than its `max-ref-age-ms`; a surviving
  ref keeps its snapshot; a surviving branch keeps ancestors from the tip while fewer than its
  `min-snapshots-to-keep` are kept **or** the ancestor is newer than *its* cutoff — its own
  `max-snapshot-age-ms` if set, else `older_than` — stopping at the first that is neither; a
  snapshot on no surviving ref is kept only while newer than the default cutoff. Each keep is a
  `Keep(rule, ref)`, so the panel can say `newer than the cutoff of main, audit; referenced by
  release` rather than one string per ref. `ExpiryPlanTest` plans from the metadata *before*
  `retained`'s and `expired`'s expiries and requires the retained set to equal what the metadata
  *after* lists, and reproduces `retained`'s first run — a 7-day branch age, nothing removed — by
  adding the age back. The metadata panel's `Expiry` section draws two columns, the table's
  defaults and `older_than = now`, because the reader's question is "what protects this snapshot"
  and only the age rule moves between them; ages are measured from `LocalExpiryClock`, which the
  render tests pin to the table's last write so a capture does not change with the calendar
- **Which files an expiry frees is a second plan over the first, and the strategy is chosen by
  the ref count.** `model/ExpiryFilePlan.kt` takes the snapshot ids `ExpiryPlan` would drop and
  reads the manifest lists and manifest entries of the table *as it stands* — which is why it is
  planned before the expiry and cannot be checked on `expired` or `retained`, whose removed
  manifests are gone. `RemoveSnapshots.cleanExpiredSnapshots` picks `IncrementalFileCleanup`
  when exactly one ref is left and `ReachableFileCleanup` otherwise, and they free different
  things: both delete every expired snapshot's manifest list and every manifest no retained
  snapshot lists, but **incremental** frees a data file when an expired commit on the live line
  recorded it `DELETED` (the entry's own snapshot gone too) or when an expired commit *off* the
  live line — rolled back, or on no ref — recorded it `ADDED`, while **reachable** frees a file
  only when it is live in a manifest that goes and live in none that stays, so a `DELETED` entry
  frees nothing while another ref can read the file. A cherry-picked commit, or one picked from
  the live line, is left entirely alone. The oracle is `docs/fixtures/cleanup.sql`, which copies
  each table on disk *before* running `expire_snapshots` on the original in place — same file
  names in both — so `ExpiryFilePlanFixtureTest` requires the plan from `sweep` to name exactly
  the files missing from `swept` (four lists, three manifests, a file removed on the live line and
  a file added by the rolled-back commit) and the plan from `sweepb` to name what `sweptb` lost
  (three lists and the delete's rewritten manifest, no data file — the branch still reads it).
  The sweep every fixture is held to is the one wrong the planner must not do: no planned data or
  delete file is live in a retained snapshot, which is where the ancestor rule earns its keep on
  the two tables with a `rewrite_manifests`. The metadata panel's `Expiry Files` section plans
  the `older_than = now` column's removals from `TableNode.expiryFiles`, a `DeferredRead` the
  builder fills from the **model** — never from the drawn nodes, because aggregation folds the
  snapshots and manifests past the page size out of the graph, and those are exactly the older
  lists an expiry removes; the first version read the graph and was complete only on tables
  small enough to draw whole. Data files first and in the error colour, `MAX_EXPIRY_FILE_ROWS`
  (200) listed
- **"Is this table consistent" is one click, and it runs the panels' own checks.**
  `model/Integrity.kt` runs `manifestTallies` and `partitionSummaryTallies` on every distinct manifest, each commit's
  `snapshotChangeOf(...).tallies` and `snapshotTotals` on its closure (Iceberg), and
  `paimonManifestTallies` on every distinct manifest with `paimonRecordTallies` on every
  snapshot's replay across main, the branches and the tag-only snapshots (Paimon), and lists the
  pairs that disagree with where they are — nothing of its own, so it cannot say anything a
  node's panel would not. `checked` counts only pairs with both sides; a figure a writer did not
  record is not a comparison. The two checks that walk a closure per snapshot stop after
  `MAX_CLOSURE_CHECKS` (50), newest first, and the report says how far they got; the statistics
  files and the data files are file reads, behind a second click under the report
  (`FileStatsSweep.kt` and `StatisticsFilesCheck.kt`, below). It rides `TableNode.integrity` behind a
  click, the `UnreferencedFilesSection` shape. `IntegrityFixtureTest` holds every engine-written
  table to no findings but the two the format wrote — `tg` and `pea` each keep a tag on a
  snapshot whose changelog list the expiry deleted, so the tag's `changelogRecordCount` stands
  against nothing — and proves the report reaches the checks the other way, by changing two
  summary figures in a copy's newest `metadata.json` and requiring exactly those two findings.
  **The second click opens the statistics files too** (`model/StatisticsFilesCheck.kt`,
  `TableNode.statisticsFiles`, Iceberg only): each `statistics` file's Puffin footer against
  the blob records `metadata.json` keeps of it — an `ndv` the two disagree on, or a blob the
  record names and the file lacks (`STATISTICS_FILES`) — and each `partition-statistics` file's
  size on disk against `file-size-in-bytes` and its rows against the live files of the snapshot
  it names, through the metadata panel's own `checkPartitionStatistics`
  (`PARTITION_STATISTICS`; `PartitionStatsVerdict.differences` is structured now, the strings
  the panel prints derived from it). A file that cannot be opened is listed with the reason
  under the stage, which is the finding that matters: the record is a copy kept so a planner
  never opens the file, and an orphan cleanup that deleted the file leaves nothing on the read
  path to notice. The readers are the builder's (`readStatisticsFooters`,
  `readPartitionStatisticsFiles`), the check takes their results, and `StatisticsFilesCheckTest`
  holds `stats` and `pstats` to agreement, sweeps every fixture, and plants a blob dropped from
  a footer, a partition figure moved, a partition dropped and a file gone
- **A file's history is a third reading of the walks the suite already trusts, and it is asked
  of one file at a time.** `model/FileHistory.kt` answers the two questions a missing-file error
  raises — which commit removed it, and what still lists it live, which is what keeps it on disk
  — for either format, and each format answers its own way. Iceberg *filters*: a retained
  snapshot lists the file live when any manifest in its list holds a non-`DELETED` entry for the
  path, and the commit whose manifest (`added_snapshot_id`) holds the `ADDED` or `DELETED` entry
  is the one that did it, which is `snapshotChangeOf`'s rule. Paimon *replays*: the file is live
  when the last entry for it across the base manifests then the delta manifests, a manifest
  once, is an `ADD` — `replayPaimonSnapshot`'s order applied to one file — and the delta's
  entries are its events; a level upgrade writes `DELETE` then `ADD` of one name in one delta,
  which is `FileEvent.REWRITTEN` and leaves the file live (`dv`). Neither runs `liveFilesOf` or
  the replay per snapshot: those walk a closure to answer about one file, so this reads each
  distinct manifest once for the one path — and it is a `DeferredRead` on both file nodes
  (`history`), with the key the ledger already uses (`UnifiedDataFile.ledgerFileKey`,
  `paimonDataFileKey`), none on a changelog entry. `FileHistoryFixtureTest` holds "live" to
  `liveFilesOf` and the events to `snapshotChangeOf` on every retained snapshot of every Iceberg
  fixture, and "live" to the replay on every Paimon fixture and branch with an event required at
  every transition between **adjacent** commits — adjacent, because the earliest retained
  snapshot's base carries what expired commits added, and a tag-only snapshot stands apart from
  the next retained one with the expired commits between (`cs`, `pea` each caught a stricter
  version). **On Iceberg an expired commit is still credited**: the manifest it wrote outlives
  it in every later snapshot's list, and its `added_snapshot_id` names the commit — so a file the
  expiry's survivors carry is `added by snapshot X (append), since expired`, with the operation
  and time from the summary an older metadata version keeps (`expired` is the fixture), marked
  `expired` in the table, never live, and outside the "listed by k of N retained" count. Paimon
  records no writer on an entry, so a file older than the earliest retained snapshot is
  `carried in`. A snapshot the table no longer retains can otherwise be neither credited nor
  blamed, and the panel says so under the table. **Under the line the panel says whether the expiry the table
  panel plans would free the file** — `older_than = now` on Iceberg, `retain_min = 1` with
  `older_than = now` on Paimon — from the same `planExpiryFiles` the `Expiry Files` sections
  draw, and when not, which listed-live snapshot is kept and by what rule, or which tag holds
  it. Only for a file that is not live now, since an expiry keeps the current snapshot, and not
  on a Paimon branch, whose ids are its own (`FileHistory.branch`). `sweep` and `pe` hold the
  history to the expiries that ran: a file the expiry deleted was not live now, and a file live
  now was not deleted
- **The table panel sums the maintenance procedures to a line each, and computes none of them.**
  `MaintenanceSection` in `ui/MaintenanceSections.kt` asks the four planners at the table's current
  snapshot — `planRewrite`, `planManifestMerge`, `planExpiry` with `planExpiryFiles` on Iceberg;
  `planCompaction` / `paimonAppendVerdict` and the two `PaimonExpiryOptions` calls on Paimon —
  and prints a verdict, a detail and the panel that holds the reasoning, coloured only where a
  procedure would act and in the error colour where a writer would block. It is on the table
  panel because that is where a reader starts and the verdicts otherwise sit three panels deep;
  it calls the same functions the detail sections call, so it cannot drift from them, and it
  costs the current snapshot's deferred walk once. `formatCounted` in `ui/FormatUtils.kt` agrees
  a count with its noun, because `1 candidates in 1 groups` was the first render. **What it
  plans from rides the table node** — `TableNode.maintenance`, a `MaintenanceInput` the builder
  fills off its full node set with the newest metadata and the current snapshot's node — and the
  snapshot panel's `Rewrite` and `Manifest Merge` read the newest metadata's options from the
  same place. All three first looked them up on the drawn graph, and both are the last of their
  siblings in drawn order, so a page size or a snapshot filter that folded them left the sections
  planning under an older version's options with nothing failing, or not drawn at all.
  `MaintenanceInputTest` is the page size of one that did it
- **What `rewrite_data_files` would rewrite is planned the way `SizeBasedDataRewriter` plans it,
  and checked against the three rewrites the fixtures ran.** `model/RewritePlan.kt` reads the
  rules at Iceberg 1.8.1: one task per live data file carrying the delete files the scan pairs with
  it (the same `deleteReach`, `mayReach` included, since the scan pairs an equality delete by
  sequence alone); grouped by partition, a file under a spec that is not current going into the
  empty partition with every other such file; a **candidate** when outside 75%–180% of
  `write.target-file-size-bytes`, or with `delete-file-threshold` deletes, or with *file-scoped*
  deletes — a vector, or a positional delete whose path bounds meet, which is exactly
  `DeleteTargets.namesOneFile` and exactly Iceberg's `ContentFileUtil.referencedDataFile` — marking
  `delete-ratio-threshold` (30%) of its rows; bin-packed in scan order with one open bin; a group
  **rewritten** with `min-input-files` (5) or more, more than the target in bytes, or any file past
  a delete threshold. So every small file is a candidate and it still takes five of them, which is
  what a reader asking "why did rewrite_data_files do nothing" needs to hear. `mor`, `maint` and
  `sorted` each ran it with `min-input-files = 2` and left a `replace` snapshot;
  `RewritePlanFixtureTest` plans from the snapshot before under that option and requires the
  rewritten set to equal what the replace took out — `sorted` counts because the sort strategy
  shares the planner. What the defaults would have done is read, not run, and said so: `maint` and
  `sorted` left alone, `mor` still rewritten by both rules. `LiveFile.specId` exists for the
  spec rule; the snapshot panel's `Rewrite` section takes the target size and the current spec
  off the latest metadata node
- **What the next commit does to the manifest list is planned the way `ManifestMergeManager`
  does it, and it runs on every batch write.** `model/ManifestMergePlan.kt`, read at 1.8.1: the
  manifests a commit is about to list — the one it wrote, then the ones it kept in list order,
  minus any with neither added nor existing files — are grouped by partition spec and packed
  **from the oldest end** into `commit.manifest.target-size-bytes` (8 MB) bins; a bin of one is
  kept, a bin holding the *first* manifest (the commit's own, else the newest) is kept under
  `commit.manifest.min-count-to-merge` (100), and **any other bin of two or more is merged whatever
  the count**. That last clause is the one a reading of the docs misses, and `mergespec` is its
  oracle: an append after `ADD PARTITION FIELD` merged the two spec-0 manifests under the default
  hundred, because the new manifest is in the spec-1 group and theirs holds no first. `merged`
  (`min-count-to-merge = 2`) shows the ordinary path — every append after the first folds the
  list into one manifest with the earlier `ADDED` turned `EXISTING` and an earlier snapshot's
  `DELETED` dropped — and its last commit runs with merging off. `mergedel` is the delete side,
  and settled something else on the way: Spark 3.5 on 1.8.1 deletes at **file granularity**
  (`SparkWriteConf` defaults it since #11478), so a second `DELETE` on a file that already has a
  positional delete rewrites it — the new delete file holds both positions and the old one is
  `DELETED` in the same commit, which is what a merged delete manifest of `1 ADDED + 1 DELETED`
  means where the script's header had predicted `1 ADDED + 1 EXISTING`. `ManifestMergeFixtureTest`
  sweeps every append and delete in every Iceberg fixture, reading whether the commit wrote a
  manifest of each content off its own summary, and requires the plan from the parent to land on
  the child's count; the snapshot panel's `Manifest Merge` section plans the next append and the
  next merge-on-read delete from `SnapshotNode.manifestList` under the table's current options
- **Paimon's expiry is planned the same way, from `ExpireSnapshotsImpl.expire()`, and checked
  against the one oracle a planner can have.** `model/PaimonExpiryPlan.kt` applies the six things
  that method reads: the newest `snapshot.num-retained.min` stay; everything below
  `latest - num-retained.max + 1` goes **whatever its age**; a consumer's `nextSnapshot` and all
  after it stay; at most `snapshot.expire.limit` go in one run; and between those bounds the walk
  stops at the **first** snapshot younger than the cutoff — age is consulted only there, which is
  why a bare call on a fresh table removes nothing and `retain_max` on the same table removes
  seconds-old commits. A removed snapshot a tag names lives on as the tag. The oracle is
  `docs/fixtures/paimon-px.sql`: one table written twice with identical statements, `px` left
  unexpired and `pxa` expired, so `PaimonExpiryFixtureTest` plans from `px` and requires the
  retained set to equal `pxa/snapshot` — and `px` carries the age rule's own oracle, an
  `expire_snapshots(retain_min = 1)` that removed nothing. The trap in that script: `pxa/bucket-0`
  still holds all six data files, because an expiry removes a file only once a later snapshot has
  stopped listing it, and a write-only table never stops. The inputs travel on
  `TableSummary.paimonExpiry` (`PaimonExpiryInput`: snapshot times, consumer bookmarks, tags, the
  latest schema's options) so the table panel plans without the model; its `Expiry` section is the
  Iceberg shape's twin — a bare call under the table's options beside `retain_min = 1, older_than
  = now`, which is as far as a call goes without `retain_max`, so what the second column still
  keeps is what no call can remove. `InspectorUnderTest` pins the clock to the last Paimon commit
  plus a second for the same reason it pins Iceberg's to the last metadata write
- **Which files a Paimon expiry frees is planned from `ExpireSnapshotsImpl.expireUntil`, and a
  tag is the reason a bucket stays full.** `model/PaimonExpiryFilePlan.kt` walks the removed
  range `[begin, end)` the way release-1.3.1 does, in four passes: data files from the **delta
  lists of `(begin, end]`** — `end`, the first retained snapshot, included, because a file its
  delta records as `DELETE` was live only in the one before, which expires; an `ADD` of the same
  file after the `DELETE` unmarks it, which is what keeps a compaction's upgraded file — unless
  the **nearest earlier tag** still holds the file in its own merged manifests; changelog files
  added by `[begin, end)`, which no tag reaches; manifest lists, manifests, index manifests, index
  files and statistics of `[begin, end)` that neither the tags in the range nor snapshot `end`
  name — a tag's changelog list is not in that set, which is the `tg` finding again; then the
  snapshot files. The oracle is `docs/fixtures/paimon-pe.sql`, the `sweep`/`swept` shape on
  Paimon: `pe` is the table before, `pea` the same table expired in place, and
  `PaimonExpiryFilePlanFixtureTest` requires the plan from `pe` to name exactly the files missing
  from `pea` — two data files (the compaction's five removals minus the three the tag on 3
  holds), five changelog files, five manifests, fifteen lists, six snapshot files. The
  `retain_max => 2` call was refused until `retain_min => 1` came down with it, which the script
  header records. `PaimonExpiryProtected` carries the files a tag kept, because "I expired
  everything and the bucket is still full" is the question; the table panel's `Expiry Files`
  section leads with that line, and `TableNode.paimonExpiryFiles` carries the model-built input
  for the reason `expiryFiles` does
- **A Paimon bucket is drawn as the LSM tree its writer restores, and the next flush's compaction
  is planned the way `UniversalCompaction.pick()` plans it.** `model/PaimonCompaction.kt`:
  `paimonBucketLsms` groups a snapshot's live files by partition and bucket into sorted runs —
  every level-0 file its own run, newest first by `maxSequenceNumber` with `Levels`' tie-breaks,
  each higher level one run — and `planCompaction` applies the four branches read at 1.3.1: under
  `num-sorted-run.compaction-trigger` nothing; at it, size amplification (the runs newer than the
  oldest against 200% of it, into the top level) else size ratio (the newest runs within 1% of
  each other, into the level below the first left out, never level 0); above it, the run count
  takes the newest regardless. A lookup, deletion-vector, `first-row` or `force-lookup` table
  wraps that in `ForceUpLevel0Compaction`, which is why `dv` and `lk` compact after every append;
  `write-only` turns it off. **The oracle is the writer itself**: `pc` is seven one-row inserts on
  every default, and Paimon compacted after the fifth — five near-identical level-0 files,
  400% against 200% — so `PaimonCompactionFixtureTest` holds the plan at each snapshot to whether
  the next commit is a `COMPACT`, and sweeps that over every primary-key fixture (the forced-up
  ones compact every time, `px` never, the rest never reach the trigger). An append table has no
  tree and no writer-driven compaction — Spark writes never run `AppendCompactCoordinator` — so
  its side is what `sys.compact` would pack: the files under 7/10 of `target-file-size` per
  partition, a task at `compaction.min.file-num` of them, which `rt`'s explicit compaction at
  `min.file-num = 2` pins. The trees ride the same deferred replay as the live files, on
  `PaimonSnapshotNode.bucketLsms`, with the schema's options and key-ness beside them; the
  snapshot panel's `Compaction` section leads each bucket with the verdict and marks a bucket
  past `num-sorted-run.stop-trigger` in the error colour, because that is the one where the
  writer blocks
- **A read as of a time is resolved the way the engine resolves it, and the rolled-back table
  is where that differs from "the newest snapshot before T".** `model/TimeTravel.kt`: Iceberg's
  `SnapshotUtil.nullableSnapshotIdAsOfTime` (1.8.1) takes the **last `snapshot-log` entry at or
  before** the time, and a log entry is a moment `main` was pointed at a snapshot — so on
  `rolled` a time between the abandoned commit and the reset lands on the abandoned commit
  (`currentAncestor = false`, `leftBehindAt` the reset), and the reset's own moment lands on its
  target through the reset's entry (`viaReset`). An expiry drops the removed snapshots' log
  entries, so the earliest resolvable time moves forward (`expired`: the log's ids are exactly
  the retained ones) and Iceberg raises `Cannot find a snapshot older than …` before it. Paimon's
  `SnapshotManager.earlierOrEqualTimeMills` (1.3.1) binary-searches ids by `timeMillis` for the
  latest at or before, nothing when the earliest is later — read here as the latest id at or
  before over `PaimonExpiryInput.snapshotTimes`, the same answer while commit times rise with
  ids. `TimeTravelSection` sits beside the Iceberg log and on the Paimon table panel; its field
  is seeded with the expiry clock **exactly** (`formatAppTimestampExact`, milliseconds kept),
  because two commits a hundred milliseconds apart are one second on screen and the first
  capture resolved both seeds to the same wrong entry; `parseAppTimestamp` is the inverse and
  `FormatUtilsTest` round-trips it. `TimeTravelTest` pins all of the above
- **A rollback is read from the snapshot log, because it is written nowhere else.**
  `set_current_snapshot`, `rollback_to_snapshot` and `rollback_to_timestamp` write no snapshot:
  they move `main` and append a `snapshot-log` entry naming a snapshot the log already holds, and
  the commit they moved past stays in `snapshots` until an expiry removes it, on no ref and not an
  ancestor of the current snapshot. `model/SnapshotHistory.kt` reads the log in time order —
  an id's first entry is its commit, a later entry is a `RESET`, and the entries between the two
  are what it `leftBehind` — and `currentAncestorIds()` walks the parent chain from
  `current-snapshot-id`, which is the flag Iceberg's own `.history` table prints as
  `is_current_ancestor` and the oracle `RolledBackFixtureTest` holds it to (`true, true, false,
  true, true` down `rolled`'s log). The builder carries `SnapshotNode.leftBehindAt` from the
  newest metadata, so the abandoned commit's panel and IDE row say which reset stranded it; the
  metadata panel's log leads with the event and colours the reset row, since a column of "made
  current" with one "set back" in it has to be findable without reading every row. The abandoned
  commit gets a column of its own from `snapshotTracks` for free: it is a second child of the
  reset's target, and the trunk rule keeps `main` where it was
- **A null sequence number on an entry means "the manifest's", never "unknown".** Iceberg inherits
  it: an entry written by the commit that wrote its manifest stores nothing, because every entry
  that commit adds shares one number, and only an entry *carried forward* records one of its own.
  `effectiveSequenceNumber` in `IcebergSchema.kt` is the single reading of that rule, and
  `FileNode.sequenceNumber` / `.sequenceInherited` carry it to the panel — which said `N/A` for three
  of `mor`'s four files until it existed. The manifest's number reaches the node from the *builder*
  rather than being looked up from a parent, because one file can hang under several manifests and
  the panel would have to pick one. It is not only a display fact: which delete files a scan applies
  to a data file is decided by comparing these two numbers. **And a manifest with no number is a
  v1 manifest, whose entries are at 0** — the spec's own reading ("sequence numbers for all files
  must default to 0"; "use 0 when reading v1 manifest lists"; a v1 snapshot's is 0 too), so
  `effectiveSequenceNumber` returns a `Long`, never null, and `FileNode.sequenceDefaulted` says
  when the 0 is the reader's rather than the writer's. `v1` is the fixture: upgrading rewrites
  nothing, so its v1 manifests sit under v2 metadata, and a merge-on-read delete written after the
  upgrade reaches a v1-written file *by the sequence rule* (2 ≥ 0) rather than by the rule being
  skipped for a missing number — which gave the right answer for the wrong reason and no answer at
  all for an equality delete. The same default orders the siblings: a v1 manifest draws first, not
  last
- Spec constants live in `IcebergSchema.kt` (`ManifestContent`, `ManifestEntryStatus`,
  `DataFileContent`) and `PaimonSchema.kt` (`PaimonEntryKind`). Prefer them over 0/1/2 literals
- **An expired snapshot is a state, not a read error, and the rule needs both of its halves.**
  `expire_snapshots` deletes the dropped snapshots' manifest lists and rewrites `metadata.json`
  without them, but the older versions stay on disk (`write.metadata.previous-versions-max`, a
  hundred by default) and still list them — so every production table opens with snapshots whose
  manifest list is gone, and this drew a `SNAPSHOT READ ERROR` for each. `UnifiedSnapshot.expired`
  is true when the manifest list is missing **and** the *current* metadata — the highest version,
  by the same order `metadatas` is sorted in — no longer lists the snapshot. Both conditions,
  because a snapshot the current metadata lists with no manifest list behind it is a broken table,
  and `ExpiredSnapshotsFixtureTest` deletes the surviving list from a copy to keep that an error.
  An expired snapshot carries no manifests, so the builder gives it no `change`, no diff and no
  delete-reach: its summary is all the writer left of it, and the card and panel say so. The
  precedent is the version hint below — absence that the format defines is not a failure to read
- `versionHint` is nullable — `version-hint.text` exists only for HadoopCatalog/HadoopTables
  tables, so absence is normal and must not be reported as a read error
- **Nothing leaves the graph silently.** `GraphAggregation` draws the first
  `AggregationPolicy.pageSize` (24) siblings of a kind under a parent and folds the rest into one
  `GroupNode` that states the count and expands. `ManifestNode`/`PaimonManifestNode` still carry
  *every* entry in `entries`, and the inspector lists all of them. Expansion state is a
  `Set<String>` of group ids in `AppState.expandedGroupIds`, passed to `layoutGraph`; an id is
  `grp_<parentId>_<kind>_<pageIndex>`, so it survives the graph rebuild that expanding causes.
  Three rules the pass must keep, each with a test that fails without it: removal is by
  **reachability from the original graph's roots**, never by subtree, because one manifest is a
  child of every snapshot carrying it forward; every departed node is attributed to exactly one
  group, so `sum(group.hiddenNodeCount)` equals what actually went; and an `ErrorNode` is never
  grouped, with errors inside a collapsed subtree counted in `hiddenErrorCount` and shown in red
  on the card. A fourth follows from the first: **a group whose parent is not kept is not
  emitted** — it would reach ELK with no edge and land in the first column over the table root
  (`mor` at page size 3) — and its members are attributed to whichever group hid the parent.
  Never add a cap that isn't visible in the UI
- **Expanding is per group; the inverse is per parent.** Opening every page of a node leaves no
  `GroupNode` beside it, so the affordance that expanded is gone and the reader has nothing left to
  click — which is why `Back to one page` hangs off the *parent* in the inspector's header row
  rather than off a group, and why it is one control for all thirteen node branches rather than
  thirteen. `GraphAggregation.expandedGroupIdsUnder(parentId, expanded)` decides which ids belong
  to a parent by **generating** candidates for every `AggregationKind` and page and intersecting
  with the expanded set, never by parsing one: an id is `grp_<parentId>_<kind>_<page>` and a parent
  id contains underscores, so `man_3` and `man_3_manifest` cannot be told apart by splitting
- **A label column is a width; a value column is a share.** `DetailRow`'s key holds a vocabulary
  this repository chooses — `Sequence Number`, `Statistics`, `Added Snapshot` — so a label that
  does not fit is a defect every time, the same rule that makes `GroupCardWidthTest` legitimate
  where a general width sweep would not be. Its value holds a path or a bound the *table* decides,
  where ellipsis is the design. So `DetailTable` derives one width for every row from its own
  width, clamped to 84–190dp, and `DetailRow` takes `Modifier.width(it)` for the key and
  `weight(1f)` for the value. One number per table is what keeps the values on one x; a fraction
  cannot work at all, because the share that fits `Statistics` at the 200dp minimum is 600dp of
  label at 1400dp
- **`NodeTooltip` states its width and is rendered for every node kind.** It is `DetailTable`'s
  other caller, and it sized itself with `IntrinsicSize.Max` — which asks a layout for a width
  without measuring it, something a `BoxWithConstraints` cannot answer and throws on. Nothing had
  ever rendered the tooltip, so that crash would have shipped to the first hover. Keep it at a
  stated width: an intrinsic one is also decided by the longest unwrapped value in it, so a
  tooltip's shape changed with whichever path the table happened to hold
- **The inspector header is a `FlowRow`, and the render that proves it is the narrow one.** The
  title and the actions shared a `Row` until a third action arrived. A `Row` neither wraps nor
  clips, and it measures unweighted children before weighted ones — so at the 300dp the pane opens
  at (200dp minimum, `App.kt`), the buttons took the whole line, the title laid out one character
  per line under them, and the rightmost button painted past the panel edge unreachable. Every
  existing capture renders at 1400dp and looked correct throughout; `collapse-pages-narrow-1.png`
  renders at 300dp, which is the width the defect exists at. Any new header action goes in that
  `FlowRow`, and the title stays `maxLines`-capped
- **Paging can be switched off entirely, and that is a decision about one table.**
  `AppState.drawEverything` swaps the policy for `AggregationPolicy.NONE` — the same "draw every
  node" the layout tests have always used — because expanding group by group could never reach a
  parent that only appears *because* of an expansion. It is **not persisted**, unlike
  `graphPageSize`: a page size describes this reader's screen and holds for every table, while
  "draw all of it" was consented to against a node count the reader had in front of them, and
  re-applying that to the next table opened applies a consent never given for it. Opening another
  table and choosing a page size both turn it back off, and the menu item carries the count it is
  about to draw, because that figure is the whole of what is being agreed to
- **A menu's items are a composable; the menu is not capturable.** `GraphOptionsMenuItems` in
  `ui/GraphStatusBadge.kt` holds everything the badge offers — which page size carries the check,
  what the paging item says, which of the two are dead — and `DropdownMenu` holds only the popup
  around them. The split is what puts them in front of somebody: seeding `menuOpen` from a
  parameter was tried first and a `DropdownMenu` in an `ImageComposeScene` drew its items at one
  scene height and nothing at all at another, which is a capture worse than none. Rendering the
  items directly is the same idea as `sectionCollapse` and `startRequested` — a state a click
  produces is a state a render never reaches — applied to the content rather than the state. The
  menu's heading is laid out against `MENU_ITEM_PADDING + MENU_CHECK_SIZE + MENU_CHECK_GAP` so it
  starts where the choices start rather than where their check marks do
- **The page size is a setting, and two things travel with a change of it.** `AppState.graphPageSize`
  is persisted and feeds `AggregationPolicy`; `GraphStatusBadge` on the canvas both states the
  figures and offers the choices — six listed sizes and `Other…`, which opens a dialog for a typed
  one. `parsePageSize` is the one place a typed size is read, and it accepts the thousands
  separator the bounds line prints. The dialog's field is `PageSizeField`, a composable of its own
  for the same reason the menu's items are: a dialog is a window and a capture cannot open one. Changing it clears `expandedGroupIds` — a group id names a page
  *at a size*, so the same id means a different set of siblings at a different one. Every cached
  session stays: `TableSession.policy` records what the graph was drawn under, and a cache hit
  whose policy differs from the one in force is **redrawn from its retained table model** rather
  than restored, which is a layout and not a read. The same check is what pages a table again on
  return after it was drawn whole. That redraw merges no drags — node ids such as `table_root`
  and `snap_<id>` repeat across tables, and the drags on screen belong to the previous one
- **Which siblings get drawn and which sibling sits above which are one decision.**
  `SiblingOrder` is the single definition, read by `GraphLayoutService` for placement and by
  `GraphAggregation` for membership. The builder's emission order is not it: a manifest several
  snapshots carry forward is emitted once, under whichever snapshot first wrote it, so every
  later snapshot would page through another snapshot's order
- **Aggregation runs twice, and the second pass is for rows.** Rows are attached after the first
  pass so they are only read for the data files that survived it; they then go through the same
  pass rather than being the one kind exempt from the page size. The second pass can only touch
  rows — every other kind is already at or below the page size, and a `GroupNode` has no
  `aggregationKind()`, so it never becomes a member of anything
- **A table in object storage is opened by supplying the missing `FileSystemProvider`, not by
  threading a storage object through the model.** `java.nio.Path` already *is* this app's storage
  abstraction — the model resolves manifests against their list's directory, walks `metadata/`, asks
  whether a data file is there, all in `Files` and all correct for any filesystem. So `s3://`,
  `gs://`, `gcs://` and `r2://` get a provider (`ObjectFileSystem.kt`, registered through
  `META-INF/services`) and the model layer is untouched. The alternative — a storage interface —
  would have been a parameter on `UnifiedTableModel`, every `UnifiedSnapshot`, `UnifiedManifest`
  and `UnifiedDataFile`, and on the `DeferredRead` lambdas that read lazily from inside graph
  nodes, to say something only the location knows. **Write is a `ReadOnlyFileSystemException`, so
  "all data access is read-only" is a type here and not only a rule in this file.** `toFile()`
  throws for the same reason: returning a plausible `java.io.File` is exactly how a remote path
  silently becomes a read of a local path that is not there
- **Every `read_parquet` passes `hive_partitioning = false`, and the sweep that found why is the
  statistics check.** Both formats lay files out under `name=value` directories and both write
  the partition columns into the file, so the path is a layout convention and the file is the
  truth. DuckDB reads such paths as Hive partitions by default — a column per segment, typed from
  the path text, overriding a file column of the same name — which is what had `parted`'s
  `amount` (a `DECIMAL(9,2)` in the file, `amount=98765.43` in the path) reading as text and
  `pt`'s `dt` (a `DATE` in the file, `dt=19787` under `partition.legacy-name`) as a `BIGINT`: a
  row lookup on either compared the wrong type and found nothing, and on DuckDB 1.4.4 the
  DATE-over-BIGINT collision was an `INTERNAL Error: Vector::Reference used on vector of
  different type` that **invalidated the connection for every query after it**. The first
  version of the statistics check coerced the two readings back; the sweep passing without the
  coercions once the flag was set is what says the flag is the whole fix. `parted` and `pt` pin
  it from both lookups
- **The bytes come from DuckDB, and that was measured against the alternative.** AWS's
  `aws-java-nio-spi-for-s3` was run against a MinIO container before this was written. Three things
  decided it: DuckDB is **already a dependency** where the SPI adds 48 jars and 34 MB to `core` for
  one scheme; DuckDB reads the **sample rows** too, so there is one credential configuration rather
  than two for `read_parquet`; and **the SPI's errors do not survive the boundary** —
  `Files.exists()` is specified to answer `false` rather than throw, so a 403 on a bucket came back
  *identical to a missing table*, the likeliest failure reported as the one thing it is not.
  `ObjectStorage.describe` tells a 403, a 404 and a refused connection apart and says what to do
  about each, because one generic message is how a reader re-clicks forever. The SPI also could not
  be configured per location: its `newFileSystem(URI, Map)` builds a CRT client that ignores the
  configuration's own region
- **Two caches stand between the graph build and the network, and without them this is unusable.**
  A graph is built for every artifact the metadata names and `Files.isRegularFile` runs once per
  data file — a thousand-file table would be a thousand round trips before anything is drawn. So
  `ObjectStorage` caches a **directory listing** per prefix and the **bytes** of the objects it
  reads. `list` globs the whole subtree and derives one level from it rather than globbing one
  level, because object storage has no directory entries and a one-level glob silently omits every
  subdirectory; that costs a subtree listing, so it belongs on `metadata/`, never on a warehouse
  root. A warehouse is scanned by `globTables` instead — two globs for the two path shapes the
  formats are detected by, rather than a request per level per table
- **The same caches make "has this changed" a question about memory, so change detection has to
  invalidate first.** `AppState.remoteTableFingerprint` calls `ObjectStorage.invalidate` on the
  table's `metadata/`, `snapshot/` and `schema/` prefixes before listing them, because a
  fingerprint served from a cached listing is frozen at whatever the first read produced — a
  remote table would never reload, however many commits it received, and an explicit reload would
  re-decode the metadata the table used to have. `invalidate` sweeps **both** maps under the
  prefix, since a stale listing and stale bytes fail differently: the first hides a new snapshot,
  the second re-reads the old one. `RemoteTableTest` writes a second object through DuckDB and
  asserts *both* directions — still stale before, current after — because only the pair says the
  invalidation is what did it
- **Locality decides the poll cadence, and that is a cost decision, not a tuning one.** A local
  check is a `stat` against a warm page cache; a remote one is a LIST against a store that bills
  per request. On `FILESYSTEM_POLL_INTERVAL_MS` (3s) that is 1,200 requests an hour per remote
  root for a table nobody is committing to. So remote work runs on `REMOTE_POLL_INTERVAL_MS`
  (30s): the open table's fingerprint, and the workspace sweep, which for a remote warehouse is
  two recursive globs over the whole thing rather than a directory walk. The mechanism is
  `scanWorkspace(items, includeRemote = false)`, which **omits** those roots rather than reporting
  them empty — the fold already reads a missing key as "not covered" and leaves the root alone,
  so the slower cadence needed no new state. The default stays `true`, because an explicit
  refresh must refresh the roots the reader pressed it for
- **A listing that could not be done is a third answer, and spelling it as the second is how a
  refusal disappears.** `globTables` used to wrap both globs in
  `runCatching { }.getOrDefault(emptyList())`, which could only absorb real failures — `glob`
  already answers an empty list for a prefix holding nothing — so a refused key produced exactly
  what an empty warehouse produces. It throws now, and `scanWorkspace` catches it into
  `WorkspaceScan.unreachable` rather than into either of the other two maps, so the existing
  "a root the sweep did not cover is left alone" rule keeps the tables the reader had. The message
  is the store's own, drawn under the root with the control that fixes it: **the secret is
  session-only by design, so a saved location with a typed key arrives unusable on every restart**,
  which makes this the ordinary state rather than an exceptional one. The same trap sits one level
  down and is why `remoteTableStillThere` exists — `TableFormatDetector` asks `Files.isDirectory`,
  which is specified to answer `false` rather than throw, so a throwing glob has to run first or a
  refused key and a dropped table are the same answer
- **Credentials are a `CREATE SECRET` statement, which makes them the app's one SQL trust
  boundary.** `CREATE SECRET` takes no bind parameters, so every value is inlined; quotes are
  doubled, and the one field that cannot be escaped at all — the secret's *name*, which is an
  identifier — is rejected rather than escaped unless it matches `[A-Za-z0-9_]{1,64}`.
  `ObjectStoreCredentials` overrides `toString` to redact, because a data class prints every field
  and the obvious way to write "DuckDB rejected the credentials named X" would otherwise carry the
  key into a log line. `useCredentialChain` exists because on a developer's machine or an instance
  with a role the key is already in the environment, and asking for a paste is asking a reader to
  copy a secret into one more place
- **The IDE plugin shares the engine and nothing above it.** `intellij/` depends on `:core`, never
  on `:desktop` — see the architecture note for the Skiko clash that decides it. The one piece of
  *drawing* knowledge that is shared is `GraphNode.displayLabel()`, which lives in core because
  what an artifact is **called** is a fact about the artifact: two shells with their own vocabulary
  for one set of things drift the first time a node type is added. `GraphTree.details` lists a
  fact a table *may* carry — a row id, a WAP id, a sort order — only on the tables that carry it,
  so a v2 table's strip is the strip it was; `GraphTreeTest` pins both directions. The table's
  row carries one maintenance line, `Expiry (older_than = now)`, because an expiry is planned
  from the metadata alone (`planExpiry` off `TableNode.maintenance`'s newest metadata; a bare
  call on Paimon) — the rest of the maintenance summary walks a closure and stays in the desktop
  shell, and `details(node, nowMs)` takes the clock so the test's answer is the panel's. It is
  deliberately not what `GraphSearch.searchableText` answers — a label is one line chosen to fit a row, so a manifest
  reads by its add count and cannot be found by its path, which is right for a label and wrong for
  a search. `GraphTree.details` is *not* shared: the desktop inspector is a panel per node kind
  with tallies and drill-downs, and the tool window is a docked strip answering "what am I looking
  at", so a shorter list is the design rather than a subset. **A row that costs a read is not
  in `details`.** A file's history walks every retained snapshot's entries, so `GraphTree.details`
  stays eager and `GraphTree.deferredDetails` holds the `History` row; `IceLensPanel.showDetails`
  puts the eager rows up, draws that row as reading, and fills it from a `Task.Backgroundable`,
  keyed by a selection generation so a scan landing for the previous click is dropped rather
  than written under the wrong node
- **The tree follows structural edges only.** An `affectsLayout = false` edge is an annotation —
  snapshot lineage, or a deletion vector pointing at the data file it covers — and both run between
  nodes at one depth, so following one would make every commit a child of the commit before it and
  hang the whole history under itself once per metadata version. `GraphTreeTest` checks the *depth*
  for exactly that: containment is at most six levels, and a lineage-following walk grows with the
  commit count instead. A node reached from several parents is expanded under each, which is not a
  bug — one manifest genuinely is a child of every snapshot carrying its files forward
- **The secret is the one thing about a remote location that is not persisted.** Everything else —
  URL, key id, region, endpoint, TLS, URL style — goes into `java.util.prefs`, which is a plist in
  the user's Library on macOS and an XML file under `~/.java` on Linux: plain text, world-readable,
  backed up and synced. A cloud key written there is a key handed to every process the reader runs.
  So `RemoteLocation` **has no field for a secret at all** — there is nothing to accidentally
  serialise — and the typed one lives in `AppState.remoteSecrets` for the session. The form says so
  under the field rather than leaving it to be discovered at the next launch, and the default is the
  ambient credential chain, which on a machine with the AWS CLI configured needs no paste at all.
  A key is scoped to its **bucket**, never to the store: a workspace holding two buckets in two
  accounts must not send one account's key to the other's endpoint
- **A remote root is added by a second control, and the reading happens off the main thread.** The
  native chooser cannot browse a bucket — there is no directory to point at until credentials
  exist — so `Add object storage…` is its own affordance rather than a mode of `Add to Workspace`,
  at secondary weight because a local warehouse is still the common case.
  `AppState.addRemoteWorkspaceRoot` is `suspend`: "is this a table" and "what is under it" are one
  filesystem call locally and network round trips here, so they run on `Dispatchers.IO` and only
  the *deciding* returns to the main thread, which is the one place `workspaceItems` may be
  written. **It probes first and lets that probe throw** — every other question it asks
  (`isDirectory`, `isTableLocation`) is written to answer `false` when it cannot tell, so a refused
  key would otherwise arrive as an empty warehouse with nothing to diagnose. A failed add also
  forgets the credentials it was given, or a mistyped key stays configured and shadows the correct
  one for the same bucket. **`canonicalWorkspacePath` must not send a remote URL through
  `File.canonicalPath`**: that resolves it against the working directory and stores
  `<cwd>/s3:/warehouse/db` in place of the location
- **A remote table's fingerprint is its file *names*.** An object listing through DuckDB carries no
  size and no modification time, so the local signature cannot be computed — but a commit to either
  format *adds a file* (`v<N>.metadata.json`, `snapshot-<N>`), so the set of names changes on
  exactly the events the poll is watching for
- **Every read opens through `StorageLocation.pathOf`, and nothing in core names `java.io.File`.**
  A `File` can only ever be a file on this machine's disk, so every reader that built one — the
  Avro reader, both JSON readers, the Puffin reader, the format detector — was a place a table in
  object storage could not reach. `Paths.get(URI)` dispatches to whichever `FileSystemProvider`
  claims the scheme, so the same call opens a local path today and a remote one as soon as a
  provider is installed, and no reader has to know which it got. The trap the type exists to close
  is that **`Path.of("s3://bucket/key")` does not fail**: it yields a *relative* path whose first
  segment is `s3:`, which then reports "not found" against the working directory — a wrong answer
  wearing a plausible message. `pathOf` throws `UnsupportedLocationException` naming the scheme
  instead. Avro needed one more piece: `DataFileReader` seeks, and Avro ships adapters for exactly
  a `java.io.File` and a `ByteArray`, so `AvroReader.ChannelInput` adapts its `SeekableInput` to
  the `SeekableByteChannel` that `Files.newByteChannel` returns for *any* filesystem. **And a
  node's `localPath` is that string** — `Path.toString()`, which prints a remote path as its
  URL — so a reader turning it back into a path with `Paths.get` walks into the same trap one
  step later; five vector readers had, and `ObjectFileSystemTest` now holds every file in core
  to `pathOf`
- `formatCount` / `formatBytes` / `formatBytesExact` live in `ui/FormatUtils.kt` — do not add
  private copies to a UI file. Byte units are binary and labelled as such (KiB, not KB)
- **A `GraphNode`'s declared width/height is what ELK reserves, and Compose clips nothing.** A
  card that draws more than its node declares loses the overflow under its own border with
  nothing failing — `SnapshotNode` declares 112dp instead of 84dp when it carries ref chips, and
  `GroupNode` sizes itself from the lines it will draw. Any card gaining content needs its node
  size revisited in the same change. **Card bodies go through `CardColumn`**, which provides
  `lineHeight = TextUnit.Unspecified`: Material3's body style carries `lineHeight = 24.sp` and a
  `Text` overriding only `fontSize` inherits it, so a 9sp label occupies 24dp and a five-line card
  wants 136dp whatever its font sizes say. That is what had the table card's snapshot count and
  current-version lines invisible on every table in the app for the whole life of the project. A
  raw `Column` in a card reintroduces it. **`CardHeightTest` is the assertion for all of this**:
  it draws each card inside `LocalCardHeightSlack` — 400dp beyond what the node declares — so
  `CardColumn` is measured unconstrained, and `LocalCardContentProbe` reports the height the
  content actually came to. Measuring the card at its own size cannot work, because that is the
  clamped number rather than a measurement of the clamp. The scene must be tall enough to hold
  every card at its slack height: a `Column` out of room measures what is left with a maximum of
  zero, and a probe reporting 0dp reads as a card that fits. It sweeps **every node of every
  fixture in both filter states**, not one instance per kind — a card's line count varies with what
  the artifact carries, so one sample measures whichever instance a fixture listed first — and a
  second pass draws them all again with the long names a real table has. `fileNameFromPath` throws
  the directories away, so the *last segment* is what has to be long; the first version of that
  constant measured a card as getting **shorter** under stress. Every `Text` a table's content can
  lengthen is now `maxLines`-capped, which is what makes the stressed measurement a bound rather
  than the tallest thing eight fixtures happen to contain, and the heights were tightened against
  it. Kind coverage is checked against `GraphNode::class.sealedSubclasses`, so a node type added
  without a card fails here instead of needing a hand-maintained count, and the card a node gets is
  chosen by `GraphNodeCard` — one composable shared with the canvas, because a `when` written twice
  would let the sweep measure a card the app does not draw
- **Width is the same failure on the other axis, and only the group card can be swept for it.**
  A line too wide is ellipsised or wrapped inside the measurement, and ellipsis costs a card no
  height at all, so `CardHeightTest` cannot see it — `6 more metadata versi…` shipped and passed
  every check. `LocalCardWidthSlack` is the width twin of the height slack and is used by exactly
  one test, `GroupCardWidthTest`, because a general width sweep would be wrong: every other card
  prints something the **table** decides — a path, a file name, a branch — and a long one
  ellipsising is the design. A `GroupNode` prints a count and a noun from `AggregationKind`, a
  closed vocabulary this repository chooses, and a string we choose that does not fit the box we
  chose for it is a defect every time. The two slacks are never used together: with width slack a
  wrapping line stops wrapping, so a height sweep run with both measures a card the app never
  draws. The card's shape follows from the fix — **the noun is the eyebrow and the count is the
  value line**, the same shape as every other card here, because one sentence at 200dp truncated
  the half that says *what* was not drawn
- **Text sizes come from `TypeScale` in `ui/Typography.kt`, and nothing else names a number of
  `sp`.** Five steps at a ratio near 1.2 (10/12/14/17/21), replacing eight sizes from 8sp to 16sp
  chosen a call site at a time — consecutive steps 1.09x apart read as one flat size with noise on
  it rather than as a hierarchy. A step and a node's declared height are one decision: raising
  `TypeScale.small` by 6sp overflows the table and metadata cards, which is what `CardHeightTest`
  is there to catch
- **The graph model is in dp and the canvas surface is in device pixels; `zoom * density` is the
  only conversion.** ELK laid the graph out against card sizes the nodes declare in dp, and the
  cards are drawn with `Modifier.size(...dp)` — but the pointer, `BoxWithConstraints`'
  constraints, `graphicsLayer`'s translation and every `DrawScope` are pixels. Anything crossing
  goes through that one factor: node placement (`p.x.dp.roundToPx()`, because `Modifier.offset`'s
  lambda returns pixels), edge drawing (the whole `DrawScope` is scaled once), drag deltas,
  the marquee, culling, `clampOffset`, zoom-to-fit, scroll-into-view, the tooltip and the
  mini-map. Positioning in one space and sizing in the other draws every card over its neighbour
  at 200% and nothing at all at 100%, which is why `InspectorRenderTest` renders the same graph
  into a scene of twice the pixels at twice the density and asserts the drawing lands at exactly
  twice the coordinate. A new coordinate on this canvas needs its space named
- **A branch gets its own column, and a table without one is untouched.** ELK lays a layer out
  at a single x, which is right everywhere except the snapshot layer, where two commits being
  concurrent is what the reader came to see. `snapshotTracks` assigns a column the way
  `git log --graph` does — a commit takes the column its parent reserved for it, the first child
  continues in the parent's, every later child opens one and *holds* it until the walk arrives —
  and `spreadSnapshotBranches` runs last in `layoutNodes`, moving x only, pushing the layers to
  the right of the snapshots over by what the branch took. **The trunk is preferred at every
  fork, and that is not cosmetic**: `lineageChildren` ordered siblings by when they were written,
  so a branch that commits before the trunk's next commit was the "first child" and took the
  column its parent was drawn in — leaving the main line to change column halfway down and the
  root commit sitting under a feature branch's name. `branched3` is where that shows: `main`
  landed in column 3 and column 0 was labelled `staging`. `lineRanks` ranks every line — the
  `main` tip and its ancestors first, then each other branch's in the order the branches were
  made, a commit taking the lowest rank of any branch reaching it — and the sibling order puts
  the older line first, which is the only thing that separates two children of one commit when
  both are the tip of their own line. `main` is not a borrowed git convention here — Iceberg's
  spec requires the branch and ties `current-snapshot-id` to it. **Between two other branches
  the format records no such thing, and what it does keep is the metadata log**: a ref carries
  no time, but `CREATE BRANCH` writes a metadata version, so the lowest retained version whose
  `refs` lists the name (`SnapshotRefLabel.createdInVersion`, read off every version by the
  builder) says which branch is the older line — `nested` is the fixture, `b2` cut from `b1`'s
  first commit and committing before `b1` does again, where write order alone put `b1`'s fork
  commit under `b2`. Two branches the log cannot tell apart fall to name order. **And a column
  is never reused**: the depth-first walk would make that safe for overlap, but a column is
  named by the branch at its bottom, so it has to be one line — on `nested`, `b2`'s reservation
  was made after `main`'s last commit had freed column 0, and `main`'s three commits drew under
  `b2`. A graph with no branch drawn falls back to exactly the order it always had. It returns
  before touching a node when the highest column is 0, so a linear history draws exactly where
  it drew before. It also stands
  down entirely if the snapshots are not all at one x, since the shift is defined relative to
  that. `lineageChildren` is shared with `snapshotLineageOrder` because the two have to agree on
  which child is first: one walks it next, the other gives it the parent's column
- **A column is named by the branch at its bottom, and a tag never names one.** `snapshotColumns`
  in `service/SnapshotTracks.kt` groups the drawn snapshots by rounded x and takes the refs of the
  bottom-most commit in each — commits are drawn oldest-first downwards, so the bottom of a column
  is the tip of that line, and a branch ref on the tip is what a line of commits *is*. It filters
  to `isBranch`, which rejects a tag at both ends of the rule: partway up a column a tag marks a
  point in history rather than the line, and *on* the tip it names the column only by today's
  coincidence — `prod` pointing where `main` does printed `main  prod (tag)` over the column in
  the first render, a second name for a line that has one. Positions arrive through a `positionOf`
  lambda rather than being read from `layoutPositions`, the same rule as `model.stepFrom`: a
  reader who has dragged a snapshot labels the drawing they made. One column returns nothing, the
  same standing-down as `spreadSnapshotBranches`. The chip is drawn inside `GraphCanvas`'
  transformed `Box` so it pans and zooms with what it labels, and **its colours come from the
  theme, not from `RefBranchChip`** — those are fixed light-mode values chosen against a card, and
  this chip sits on `colors.surfaceVariant`, the canvas field itself, where a 20%-black pill under
  dark-grey text is a smudge on a dark canvas
- **Arrow-key navigation is defined against where the nodes are drawn**, not against a
  comparator. `model/GraphNavigation.kt` takes a `positionOf` lambda rather than reading
  `layoutPositions`, so the canvas passes its own `NodePositions` and a reader who has dragged a
  node navigates the drawing they made. Left and right follow the edges to the parent or child
  whose vertical centre is nearest; up and down move to the nearest node whose horizontal span
  **overlaps** yours, which is the column as the eye reads it and is what keeps a walk down the
  main line out of a branch column drawn at a similar height. Only structural edges are followed
  — an `isSibling` edge joins two nodes at one depth, and an `affectsLayout = false` edge is an
  annotation, so neither answers "what contains this"
- **Search is a second vocabulary per node kind, not the label the tree prints.** `GraphSearch` in
  `model/GraphSearch.kt` defines `searchableText` — every field a reader might plausibly have in
  hand for each kind: a path, a format, a content word (`data` / `position deletes`), a partition
  tuple, a commit `operation`, a branch name, an error message. The tree's `getNodeLabel` is one
  line chosen to fit a row, so a manifest reads `Manifest (12 adds)` and cannot be found by its
  path at all — right for a tree, where the label is what the reader is looking at; wrong for a
  search, where they are looking for something they cannot see. It is core because it is knowledge
  about the artifacts, not about a screen. **A `GroupNode` contributes nothing**, asserted as the
  property rather than by searching for the words it prints: those words match nothing in any
  fixture, so the obvious test passes before the rule exists. Matches come back **in drawn order**
  — column, then down the column, through a `positionOf` lambda so a reader who dragged a node
  steps through the drawing they made. **`GraphSearchResult.notDrawn` is why it is a type and not a
  `List<String>`**: aggregation folded those nodes out of the graph, so "no matches" is a claim
  about the whole table that is only true of the part drawn, and the bar says so on a second line
- **Four export formats, because they answer four questions.** SVG is the picture at any size and
  stays editable, PNG is the picture where only a raster will do, JSON is the graph as structure,
  and CSV is **the file inventory** — one row per data or delete file, which is the row-shaped thing
  in this domain and the one that goes into a spreadsheet. A CSV of every node would be a table
  whose columns are empty for most rows. Three of the four are `export/GraphExport.kt` in core,
  pure text and no toolkit; **PNG needs a renderer and so is the shell's**, and it renders through
  `GraphCanvas` rather than laying the cards out again, because a second drawing of the same graph
  drifts the first time a card changes. Two consequences of going through the canvas: `chromeVisible
  = false` drops the mini-map, which means nothing in a picture that has no viewport, and the scene
  must be sized from **where the cards are** plus `CANVAS_INITIAL_OFFSET` — `GraphModel.width` is
  neither the rightmost card's right edge nor where the canvas starts drawing, and sizing to it lost
  the whole file column off the first export taken, in a perfectly valid PNG. `MAX_PNG_SIDE` caps a
  side at 8,000px and scales down to fit, because a scene is one bitmap and a few thousand nodes at
  density 2 asks for tens of gigabytes. Colours come through a `colorOf` lambda from
  `getGraphNodeColor` — core has no palette — and the SVG always uses the **light** one, since an
  exported picture is read on a white page where the dark cards were never going to work
- **The find bar is drawn on the canvas, and stepping is just selecting.** `GraphSearchBar` is a
  `searchOverlay` slot on `GraphCanvas`, top-centre, for the same reason `GraphStatusBadge` sits
  bottom-left: a question about what this drawing contains belongs on the drawing. It is *not* in
  the toolbar, which is a fixed-height `Row` of icon groups — a 260dp field there would push the
  rightmost group past the edge at a narrow window, unreachable, with nothing looking wrong.
  `Ctrl/Cmd+F` opens it and a magnifier in the toolbar is its visible reveal, because a bar that
  exists only once you know the chord is a feature only its author has. **Stepping sets the
  selection and stops there**: the canvas already scrolls a single selected node into view, and on
  this surface the selection is the cursor, so a match reached by Enter lands in the inspector
  exactly as one reached by clicking would. The halo is **amber, and a sibling box drawn outside
  the card** — the accent already means selection here and the two are shown at once, and at the
  card's exact size the selection border paints over the halo, so the node the reader steps to is
  precisely the one that would lose it
- **`navKey` decides which keystrokes count; each surface decides what they mean.** `ui/KeyNav.kt`
  holds the one parser — bare arrows plus Enter and Space, modifiers deliberately left alone so
  Cmd+Left still means "back" and Alt+Arrow still moves by word in a field. The canvas maps the
  result to a `GraphDirection`; the tree and the workspace each have their own keymap over their
  own rows. Three copies of the modifier test is how they drift. The workspace has a second parser
  for *editing* the list, `workspaceEditKey`: Delete / Backspace bare asks to remove the root under
  the cursor (through the same dialog as the `×`), and `Alt + Up / Down` moves it — a chord,
  because the bare arrows are the cursor, and Alt is the one modifier a list may take since there
  is no text field inside it. A nested table row answers nothing to either, and a move under a
  search answers nothing, because the neighbour the reader sees is not the neighbour the list has
- **The dock is a state class and a composable, and `App()` holds neither's rules.** `DockState`
  in `ui/DockState.kt` owns where each tool window sits, which are hidden, the pane widths and
  splits, and a drag in flight — the anchors and sizes persist as they change, the hidden set and
  the drag do not — and every rule is a function on it: `visibleAt`, `buttonsAt`, `toggleAll`,
  `resizeLeftPane` (the centre keeps 260dp; the right pane's width is reserved only while it
  shows), `dropAnchorAt` (sides before bottom, so a corner is a side). `DockLayout` draws it:
  bars, the stacked side panes, the bottom pane or — when a bottom window is hidden — a 32dp
  strip, since that bar is the only way back to it. It measures its own `bounds`, so what a drop
  is judged against and where the drop targets are drawn are one rectangle. `App()` is never
  rendered in a test (it owns the preferences node and the coroutines), so `DockLayoutTest` is
  where the layout is seen at all, and its assertion is the row's arithmetic: the centre gets the
  width minus bars, panes and dividers, which a `Row` that neither wraps nor clips would get
  wrong silently. `Ctrl/Cmd + 1..9` toggles the tool window in that position of the bars
  (`DockState.windowAt`, the configured order — Workspace, Structure, Inspector), and a number
  past the last window falls through; the chord is in `App.kt`'s key handler beside the zoom
  chords, not in `navKey`, which deliberately leaves modifiers alone
- **Tab-reachability of the chrome is asserted, not assumed.** `KeyboardReachTest` drives
  `ImageComposeScene.sendKeyEvent` — the skiko `KeyEvent(key, type)` constructor, not the AWT
  wrapper, which the scene casts and throws on — through the tool-window bar and a pane's close
  button and reads the Tab order off the order the callbacks fire in. The inspector's copy buttons
  are the same `IconButton`, inferred rather than driven because their action is the system
  clipboard — what is asserted about them instead is that the ring they draw is *on screen*
- **Focus is drawn by `Modifier.focusRing`, one dp of `primary`, on every control in the window.**
  Material does draw a focus indication of its own, but it is a state layer in the *content*
  colour: on a `TextButton` that is the accent and reads well, and on an icon button tinted with a
  muted label colour it is a grey disc no different from hover. One vocabulary per window is worth
  more than either, so the ring goes on the icon-only controls — the tool-window bar, a pane's
  close, the inspector's copy buttons — and the Material text buttons keep their own. The workspace
  list keeps a private copy of the ring because its focus state also decides whether the row cursor
  is drawn, so it has to be hoisted there anyway. The **told-focus** form of `focusRing` exists for
  `IconButton`, which expands to the 48dp minimum interaction size *after* the modifier it was
  handed: a ring in that chain is measured at 48dp and paints outside the 28dp header or across the
  rows above and below, so the button keeps its click target and the ring is drawn on a box inside
  it from the button's own `interactionSource`.
- **`ImageComposeScene.render()` draws every animation at time zero, so a capture of anything
  animated has to pass a clock.** Its `nanoTime` parameter *defaults to the constant `0`* — a
  hundred no-argument renders are a hundred copies of the first instant, and nothing about the
  result looks wrong: the frame is valid, the layout is settled, and only the animated part is
  missing. What it cost here was a wrong conclusion recorded as a convention. `focus-bar-1.png` and
  `focus-pane-close-1.png` came back **byte-identical** and were read as proof that Material's
  default indication is for press and not for focus; what they actually showed was a state-layer
  fade that had not been given one millisecond to run. `FrameClock` in `InspectorRenderTest` is the
  fix — `frame()` advances 16ms and `settle()` runs the sixty frames a fade or a bring-into-view
  scroll needs — and `renderFocused` uses it. The scene also holds **several** focusables on
  purpose: an earlier version tabbed past the last one and wrapped to the first, producing two
  identical captures that read exactly like "focus draws nothing" a second time.
- **A ring below the fold is a ring nobody sees, and that is a third claim.** Tab arriving
  (`KeyboardReachTest`) and a ring being drawn (the chrome capture) leave open whether a panel
  several screens tall keeps the focused control in the viewport. It does — `Modifier.focusable`
  asks its scroll parent to bring it into view and `verticalScroll` honours it — and
  `a copy button draws focus, and the panel scrolls to keep it in view` says so by counting the
  ring's own ink at three tab depths, the last one well past what the viewport holds. It measures a
  column of the panel's own `DetailRow`s rather than the assembled panel, because the measure is a
  colour and `primary` is also the colour of the panel's header actions and links, whose count
  moves with the scroll being measured. The count is by **distance** from `primary`, not equality:
  a one-dp stroke is blended along both its edges, and an exact match came to zero on a capture
  that plainly showed the ring
- **The workspace poll reads the filesystem off the main thread, and folds the result in on it.**
  `scanWorkspace(items)` in `ui/WorkspaceUtils.kt` is a pure directory walk returning a
  `WorkspaceScan` keyed by path; `AppState.applyWorkspaceScan` writes the state. The split is not
  tidiness — the walk costs **90ms at a thousand tables and 226ms at the 10,000-directory cap**
  `scanForTables` stops at (warm cache, local disk), and `App.kt` runs it every
  `FILESYSTEM_POLL_INTERVAL_MS` (3s), so on the main thread that is several dropped frames every
  three seconds for as long as the window is open. Two rules make the asynchrony safe, each with a
  test: the roots are read on the main thread and **passed in** rather than read from the
  dispatcher, and the result is **folded over the list as it is when it lands**, not over the one
  the sweep started from — otherwise a root removed mid-sweep comes back. A path *missing* from the
  scan is a root the sweep never saw, which is not the same as a warehouse with no tables, so it is
  left untouched rather than emptied. `refreshWarehouseTables()` is the two composed, kept for
  callers where a frame is not at stake. The two remaining main-thread scans are deliberate:
  `loadPersistedState` at startup and `addWorkspaceRoot` are one-off and user-initiated, and moving
  them needs a "not yet scanned" state the workspace list does not have
- **Moving a cursor must cost what the reader expects it to cost.** The canvas and the structure
  tree make the *selection* the cursor, because selecting is free there. The workspace does not:
  opening a table reads its whole metadata tree, so `workspaceKeyAction` moves a separate
  `focusedPath` and only `Enter` produces `Open`. Holding Down through a forty-table warehouse
  must not load forty tables
- **The tree's arrow keys are a different keymap from the canvas's, deliberately.** A canvas is a
  picture and its rules are geometric; a tree is a list, and up on a list means the line above
  whatever its depth. `treeKeyAction` in `ui/NavigationTree.kt` states the file-browser keymap
  against the **flattened** rows, which is what keeps it short and what stops the keyboard
  disagreeing with the drawing: the first child of an open line is the next line, and the parent
  is the nearest line above with a smaller depth. The list is one focus target rather than one per
  row, because the selection is already the cursor
- **`GraphEdge.affectsLayout = false` records a relationship without letting it shape the
  graph**, and it is also what the canvas draws dashed. Snapshot lineage runs between nodes in
  the same layer; feeding it to ELK stretches the graph by the length of the commit history
  (measured: 2.10x width on six commits). Such edges are withheld from ELK and from layout
  post-processing, and still drawn, because the canvas routes from node positions rather than
  ELK sections. They are the one kind whose two ends can sit side by side, so drawn solid they
  are indistinguishable from the parent-child edges crossing the same gap — which is why a
  deletion vector's edge and a fork's only read as annotations once they are dashed
- **A published write-audit-publish commit is drawn with two dashed edges, because it has two
  origins.** A snapshot written under `spark.wap.id` is *staged*: in `snapshots` with `wap.id` in
  its summary, parent main's tip at the time, no ref — so it draws in a column of its own with no
  name over it, and its `Refs` row says why. `publish_changes` then writes a *new* snapshot on
  main whose parent is main's current tip and whose summary carries `source-snapshot-id` and
  `published-wap-id`; its files are the staged snapshot's, under a manifest of its own. The
  parent edge says where the commit sits on main and `e_source_*` says where its files came
  from — the relationship a reader of a WAP table came to see, and the one the lineage alone
  cannot say. `Snapshot.wapId` / `.publishedWapId` / `.sourceSnapshotId` read the summary keys
  (`IcebergSchema.kt`), the audit id is searchable from both ends, and `wap` is the fixture.
  `lineRanks` is what keeps the staged commit out of `main`'s column: it and main's next
  commit are both children of one snapshot, and the staged one was written first
- **Four layouts, and only one of them gets the refinements.** `GraphLayoutAlgorithm` offers
  layered left-to-right (the default, and the right shape for a containment hierarchy drawn as
  depth), layered top-to-bottom for a tall window, `mrtree` for following one branch down to its
  files, and `force` for "what is clustered with what". **`refinesLayers` is true for exactly one
  entry**, and that is the load-bearing part: every post-processing pass is defined against the
  left-to-right shape — ordering runs *down a column*, alignment centres a parent *vertically*,
  `spreadSnapshotBranches` claims a *column*. Under a downward layout each is about the wrong axis;
  under a tree or a force layout there are no layers for them to be about. The other three are
  ELK's own output, which is an honest drawing where half-transposed passes would be a worse one.
  Transposing them properly is a later change and the flag is where it attaches. Each algorithm is
  **a separate ELK artifact registered through a metadata service and resolved by id at layout
  time**, so a missing provider compiles cleanly and fails in front of the reader —
  `GraphLayoutAlgorithmTest` lays a real table out under every entry for exactly that reason, and
  checks the direction took by asserting which axis a *layer* collapses onto
- **Overlap prevention runs per column, and a column is not always one kind.** A Paimon schema is
  a sibling of its snapshots, so ELK lays it out in the manifest lists' column; `preventOverlaps`
  takes the two kinds as one layer, or a schema card sits under a list's (`dv`, `ao`).
  `LayoutOverlapTest` checks both readings — per kind, which is where a group node goes wrong,
  and per drawn column across kinds on every checked-in table, which is what a reader sees
- **Layout post-processing runs ordering, then alignment, then ordering again.** Alignment moves
  a parent to its children's centre, which overrides the order the first pass set — so the
  vertical order of snapshots was decided by ELK's manifest placement until the second pass
  existed. Ordering is a constraint and alignment a preference; the constraint goes last. Any new
  post-processing step needs placing against that rule
- **`manifest_file.partitions` pairs with the partition spec positionally** — the summaries
  carry no field ids. A spec of a different length decodes to nothing rather than mislabelling
  fields, because a bound attributed to the wrong partition field reads as an answer
- **A count belongs to the section it counts, and an empty section is still drawn.**
  `CountedSection` in `ui/NodeDetails.kt` puts the size in the title — `Refs (5)`, `Statistics (0)`
  — which is what let the metadata panel's identity table lose nine rows that each restated a size
  the section right below them never printed. `sectionKey` already strips a ` (count)` suffix, so
  fold state survives the number moving. The empty case is drawn rather than skipped for the same
  reason the group cards state what is not drawn: a metadata file's panel is a list of what the
  format defines, "no statistics files" is an answer a reader comes for, and a section that is
  simply absent cannot be told from one this panel does not know how to render
- **A titled inspector section is a `Section`, and it owns the gap above itself.** `Section` in
  `ui/CommonComponents.kt` draws the caret, folds the body, and emits `content()` straight into
  the caller's layout rather than into a `Column` of its own — a wrapper would change what
  `fillMaxWidth` and `weight` inside a section resolve against, and a lambda written at the call
  site keeps the enclosing `ColumnScope` as its implicit receiver anyway. Its header sits in
  `DisableSelection` because the panel is a `SelectionContainer` and a drag across a title would
  otherwise start a text selection instead of reaching the toggle. **Do not write a `Spacer`
  before a `Section`**: separation is decided inside it, 16dp expanded and 8dp folded, because a
  folded panel that keeps an expanded panel's rhythm reads as headings floating a screen apart
  rather than as a list of what the node holds. Fold state is keyed on `sectionKey(title)` — the
  title minus its ` (count)` and ` — verdict` suffixes — so a section does not re-open when its
  count moves, and it is held by `NodeDetailsContent` for the panel rather than per node
- **An uncaught exception leaves a report, and the report is the whole artifact.** After a crash
  the window is gone or frozen, so what the reader pastes into an issue is all anyone will ever
  have — which is why `crashReport` in `ui/CrashReport.kt` is asserted rather than eyeballed. It
  leads with the **deepest** cause, because an exception that crosses a layer arrives wrapped and
  the wrapper's message says least; the stack comes from `printStackTrace` rather than a hand-rolled
  walk of `cause`, since that is what emits `Caused by:` and `Suppressed:` in the form every JVM
  reader knows. It is capped at `CRASH_REPORT_STACK_LINES` and says where it cut, because a
  `StackOverflowError` is both the likeliest source of a huge stack and the crash whose report most
  needs to survive a clipboard. The handler **does not exit** — a frozen window with a dialog on it
  can be copied from and a quit one cannot — and shows **one** dialog ever, because a failing
  composition re-throws every frame. The dialog is Swing: asking a broken Compose runtime to draw
  the report about its own failure is asking the wrong runtime. That the AWT event thread reaches
  the default handler at all is asserted in `CrashReportTest`, not assumed, since the JDK has not
  always routed it there
- **`WideTable` column order is load-bearing, and so are its widths.** The inspector panel is
  far narrower than the table, and the reader sees the leftmost columns and nothing else until
  they scroll — so the answer goes first and identifiers follow it. Pass `columnWidths`: one
  width for every column spends the panel on the narrow ones and truncates the wide ones. The
  scrollbar appears only when the content overflows, and it is the only signal that more columns
  exist — do not remove it. **The leading column is asserted to fit**: `LocalWideTableProbe`
  reports what each table laid out, and the narrow sweep requires every first column to fit the
  panel it was given, across the 28 tables the six panels draw. The panel's width is *measured*
  rather than budgeted, because the inspector's padding is decided in several places and a
  hard-coded allowance asserts against the test's own arithmetic. The scrollbar's own invariant is
  deliberately not asserted — `WideTable` shows it on `horizontalState.maxValue > 0`, which is
  Compose's `horizontalScroll` contract rather than anything this repository decides

## Known quirks

- Shared UI utilities (dark surface detection, selection highlight color) live in `ui/Theme.kt`
- Shared path utilities (`normalizeFilePath`, `metadataVersionFromFileName`) live in `model/IcebergPaths.kt`
- Node card text colors (`NodeCardTextPrimary`, `NodeCardTextSecondary`) are hardcoded light-mode colors, not theme-aware
- `GraphNode.x`/`y` are plain `var Double` used during layout only; UI reads from `GraphModel.positions` (Compose-observable `mutableStateMapOf`)
- `GraphModel.initialPositions` (immutable Map) is thread-safe for background layout; `positions` must only be written on the main thread
- `SampleRowReader` uses a `synchronized` lock for DuckDB connection safety across threads
- `sessionCache` is a 5-entry LRU (`Collections.synchronizedMap` over a `LinkedHashMap`
  with `removeEldestEntry`) — bounds memory across many table switches
- Dependencies are managed via Gradle version catalog (`gradle/libs.versions.toml`), and **every
  transitive version is locked** — `gradle.lockfile` per project, committed. The catalog pins what
  the build *asks* for; the lock pins what those asks drag in, which is most of the classpath:
  **272 modules on desktop and 78 on core**, versions decided by conflict resolution rather than by
  anything written down. Change a dependency and the build fails until the lock is regenerated with
  `./gradlew resolveAndLockAll --write-locks`; that task exists because Gradle only records a lock
  for a configuration it actually resolves, so locking from `build` writes a partial file and the
  next build fails on the first configuration it missed. Both halves of the enforcement were
  exercised rather than assumed: a *changed* version is pulled back by the `{strictly}` constraint
  the lock injects, and a *new* module fails with `Resolved '<module>' which is not part of the
  dependency lock state`
- ProGuard is enabled for release builds with keep rules in `proguard-rules.pro`
- Tests use JUnit 5 via `kotlin-test-junit5`; run with `./gradlew test`. **A `@Tag("bench")` class
  is excluded from `test` and run by `./gradlew :core:bench`** on its own JVM with a 4 GB heap.
  `ElkScalingBench` prints timings for up to 64k ELK nodes and catches `Throwable` per
  configuration, which on the worker's default 512 MB heap meant provoking `OutOfMemoryError` inside
  a JVM shared with every other class — an OOM lands on whichever thread allocates next, and when
  that was Gradle's the worker died with 21 classes never run. It did so twice in a row on code
  that had just passed, with one, three and eight configurations reporting OOM across three runs
  of the same bytes; on 4 GB it reports none, so the "which placement strategy overflows" figures
  it had been printing were figures about the heap it ran in

## Node ID conventions

### Iceberg (`IcebergGraphBuilder`)

- `table_root` — the single table root node
- `meta_<filename>` — metadata nodes (e.g., `meta_v1.metadata.json`)
- `snap_<snapshotId>` — snapshot nodes
- `man_<n>` — manifest nodes (incrementing counter, stable per manifest path)
- `file_<manId>_<simpleId>_<index>` — file nodes
- `row_<fId>_<index>` — row nodes
- `err_<seq>_<hash>_<hash>` — error nodes

Edge IDs: `e_table_*`, `e_snap_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`, plus three that
record a relationship without shaping the layout (`affectsLayout = false`): `e_lineage_*` between
snapshots, `e_source_*` from a staged write-audit-publish snapshot to the commit that published
it, and `e_dv_*` from a v3 deletion vector to the data file its `referenced_data_file` names. All
run between nodes of one layer, which is exactly why ELK must not see them.

### Paimon (`PaimonGraphBuilder`)

- `table_root` — the single table root node (shared with Iceberg)
- `pschema_<id>` — Paimon schema nodes
- `psnap_<id>` — Paimon snapshot nodes on main; `psnap_<branch>_<id>` on a branch, because ids are per branch
- `pml_<snapshotId>_<kind>` — manifest list nodes (kind = base/delta/changelog); `pml_<branch>_<snapshotId>_<kind>` on a branch
- `pman_<n>` — manifest nodes (incrementing counter)
- `pdf_<manId>_<simpleId>_<index>` — data file nodes
- `row_<fId>_<index>` — reuses existing RowNode

Edge IDs: `e_table_*`, `e_schema_*` (sibling), `e_ml_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`.

## Known issues and tech debt

1. **`App.kt` is ~600 lines** — business logic lives in `AppState.kt`, the toolbar in
   `Toolbar.kt` and the tool-window layout in `DockState.kt` / `DockLayout.kt`; what is left is
   the window, its key handling, the canvas block, the dialogs and the `LaunchedEffect`s.
2. **`@Suppress("DEPRECATION")` on avro4k** — `decodeFromGenericData` API may change

## Testing

```bash
./gradlew test                                # All tests
./gradlew :core:test --tests "*.PerformanceTest"   # Specific test class
./gradlew :core:test --tests "*.IcebergPathsTest"  # Specific test class
```

~1,231 tests across 164 files (962 in :core, 260 in :desktop, 9 in :intellij) covering full pipelines for both formats (Avro fixtures
written at runtime via `avro4k`), error recovery, layout post-processing, AppState
lifecycle, snapshot filter behaviour for both formats, and `SampleRowReader` with real
Parquet files. Paimon end-to-end fixtures live in `core/src/test/resources/paimon-fixtures/`.

**The UI is verified by rendering it, not by screenshotting a window.** `InspectorRenderTest`
draws `NodeDetailsContent` and every graph card into an off-screen Compose scene and writes PNGs
to `desktop/build/reports/inspector/` — no window, no Screen Recording permission, which matters
because screen capture on this machine returns bare wallpaper for every application. Open those
files after any inspector *or card* change. A card losing a line to its declared height is
covered by `CardHeightTest` rather than by looking at `graph-cards-1.png` — the composition
succeeds and the PNG is valid either way, and a pixel probe looking for ink *below* the card
cannot see it, since the `Column` is measured against the fixed height and each `Text` clips
itself to what it was measured at. That probe was written and run against a deliberately reverted
fix; it reported nothing. What the PNGs are still the only check for is everything a number
cannot state: whether the lines that fit are the right lines, in the right order, at weights a
reader can rank. The scene is rendered twice before encoding: a control whose
visibility depends on state that layout writes (the `WideTable` scrollbar) is absent from the
first frame, so a single-frame capture shows a panel the running app never draws.

**A state a click produces is a state a render never reaches unless it is passed in.**
`NodeDetailsContent` takes `sectionCollapse` as a defaulted parameter so `table-node-folded-*.png`
can capture the panel with every section folded. That capture is what the folding is judged by —
whether the carets line up, whether the titles read as a list of what the node holds, and whether
the whole thing fits in a screen. It is also what showed that the identity table at the top of
every panel is not inside a `Section` and so does not fold, which no assertion was going to say —
and then what settled that the fix was not to fold it. The identity is what the reader selected
the node to see; what was wrong was that three of the table's eleven identity rows were
timestamps rendering local, UTC and epoch, nine lines and about 600dp of the ~1,300dp above the
section list, and none of them identity. They are a folded `Table Times` section now. **A
timestamp is not automatically a candidate**: a snapshot's `Timestamp` and a Paimon data file's
`Creation Time` are recorded, singular, and part of what identifies the artifact.

**A capture where every row says the same thing checks nothing.** `scan-pruning-table-*.png`
renders the filter over `parted` with a literal one day past the narrow manifest's range, so one
manifest is skipped and one is read — a verdict column can only be judged scannable against
another verdict. The same rule put the pruned manifest card next to the ordinary one in
`graph-cards-1.png`. And placement is checked by rendering the whole panel, not the section: the
scan-pruning form was first written after the history figures, which reads well as a list of
section names and put the panel's only control 6,000dp down the render.

`graph-canvas-partial-1.png`, `graph-canvas-whole-1.png` and `graph-canvas-branched-1.png` render
`GraphCanvas` itself, which is
where a control's *placement* on the surface can be checked rather than the control alone — and
where the `e_dv_*` edges are visible, which needs both of their ends drawn. They are rendered at
`Density(1f)` for framing: a scene is a fixed number of device pixels, so a higher density fits
less of the graph into the same file. Whether the drawing survives a scaled display is asserted
instead — `the canvas draws the same graph at every display scale` renders the same graph into a
scene of twice the pixels at twice the density and requires the ink to land at exactly twice the
coordinate, which is the check that would have caught the px/dp mismatch.
`LayoutOverlapTest` covers the half of that which is the layout's own: no two nodes of one layer
occupy the same rectangle, at five page sizes across five fixtures.

**Drawing the canvas is linear in the node count, and that is what is asserted.**
`CanvasRenderPerformanceTest` puts N file-node cards in the viewport — verified in the viewport,
since `GraphCanvas` culls and a benchmark whose nodes are off-screen measures drawing nothing — and
reports the first frame separately from the steady one, because the first pays composition and
layout for every node and later ones pay draw alone. Measured on this machine: about **6µs a node**
in steady state above 500 nodes, so roughly **2,600 nodes inside a 16ms frame**, while the first
frame costs about **0.23ms a node** (4,000 nodes ≈ 1s, the same order as ELK's layout at that size).
The assertion is the **ratio** — cost per node at 4,000 against 1,000 — not a duration: a wall-clock
bound says as much about how busy the machine is as about the code, and has to be set so loose it
catches nothing, while a superlinear ratio is exactly the regression that would matter. The 100-node
row costs 3x more per node than the 4,000-node row because the scene's fixed furniture (mini-map,
badge, background) is amortised across fewer cards; that is the confounder, and it is visible in the
table rather than hidden in the average.

**A sweep lists the fixtures from disk, never from a list it keeps.** `FixtureCatalog` (core's
tests) and `CardHeightTest`'s twin read `example/iceberg/default/*` and `example/paimon/db.db/*`,
because twenty tests carried their own copy of the fixture names and each had stopped at the
fixture that existed when it was written — the suite's strongest oracles were not running on the
six newest tables, and nothing failed. A test that means a *subset* keeps its own list and says
why (`PaimonCompactionFixtureTest` leaves `se` out, whose `COMPACT` is a `sys.compact` call;
`UnreferencedFilesTest` leaves `cl` and `tg` out, the two written with an orphan on purpose). The
sweeps decode every table, so `:core:test` runs on a 2 GB heap: the worker's 512 MB default died
the first time they did.

**The runtime-written Avro fixtures are not an oracle.** They are written with
`Avro.schema<T>()` — the schema derived from the very class under test — so writer and reader
schema are the same object, and a field typed against the wrong spec width passes all of them.
The checked-in `example/` tables are the only real oracles, and their expected values are taken
from Iceberg's own metadata tables (`.files`, `.manifests`, `.snapshots`), never from what this
code produces. Each has a regeneration script in `docs/fixtures/` whose header carries the
container invocation and the traps in it:

| Fixture | Test | Covers |
|---|---|---|
| `default/test` | `RealTableFixtureTest` | the minimal case, unpartitioned v2 |
| `default/parted` | `PartitionDecodingTest` | eight partition fields, all transform shapes |
| `default/mor` | `MergeOnReadFixtureTest` | positional deletes, a compaction, dangling deletes |
| `default/eqdel` | `EqualityDeleteFixtureTest` | both delete kinds in one table |
| `default/eqren` | `RowLookupFixtureTest`, `RowFateFixtureTest`, `IcebergScanPlanTest` | `eqdel` with the equality delete on `name`, then `RENAME COLUMN name TO label` and a row inserted after — the delete file holds `name`, the table calls it `label`, and Spark's read (`1 4 5 7 8`) is printed by the script |
| `default/v3` | `FormatV3FixtureTest` | format-version 3 with deletion vectors |
| `default/migrated` | `MigratedFixtureTest` | a plain-Spark Parquet file registered by `add_files` — no field ids, a `file:` URI outside the table, the name mapping the procedure set and a rename extended, and a `total-files-size` the procedure left short |
| `default/defaults` | `ReadProjectionFixtureTest` | format-version 3 column defaults, written by Iceberg 1.10's `UpdateSchema` from spark-shell — `region` with `initial-default eu` and `write-default us` after an `updateColumnDefault`, `score` with `0`; a file written before both, and the writer's read of it printed in the script |
| `default/lineage` | `RowLineageFixtureTest` | format-version 3 row lineage, written by Iceberg 1.10 — `next-row-id`, a snapshot's and a manifest's `first-row-id`, files inheriting theirs in entry order, a rewritten file carrying `_row_id`, a deletion vector allocating nothing, and a compaction that keeps every id |
| `default/evolved` | `SchemaEvolutionFixtureTest` | three manifest schemas — `int`→`long`, `float`→`double`, a rename and a drop |
| `default/promoted` | `PromotedBoundsFixtureTest` | `evolved` then `rewrite_manifests` — one manifest under the current schema carrying four-byte bounds under `long`/`double` and a bound for a dropped field — then `rewrite_data_files`, which re-encodes |
| `default/respec` | `PartitionSpecEvolutionTest` | two partition specs — dropped, rebucketed, `days`→`months` |
| `default/branched` | `BranchedFixtureTest` | a fork, five refs, ten metadata versions |
| `default/stats` | `TableStatisticsTest` | a Puffin statistics file — four theta sketches, one per column |
| `default/pstats` | `PartitionStatsFixtureTest` | a partition statistics file, written by Iceberg 1.10 — three partitions, one with a positional delete, checked against the writer's `.partitions` and this app's own live-file walk |
| `default/branched3` | `SnapshotTracksTest` | three branches forked at three points, plus a tag on the trunk's tip |
| `default/deep` | `DeepFixtureTest`, `IcebergScanPlanTest` | a struct, a list and a map, `addr.city` renamed to `addr.town` and `addr.country` added inside the struct after the first file — bounds and counts per leaf id, an empty list counted as one null element, and Iceberg's plan for filters on the leaves by path |
| `default/nested` | `SnapshotTracksTest` | a branch cut from another branch's first commit and committing before it does again, written by `docs/fixtures/nested.scala` — the id `AS OF VERSION` needs read back from `.refs`; the metadata log is what says `b1` is the older line |
| `default/wap` | `WapFixtureTest` | write-audit-publish — a staged snapshot on no ref, main moving past it, `publish_changes` cherry-picking it with `source-snapshot-id` and `published-wap-id` |
| `default/rolled` | `RolledBackFixtureTest` | main set back to an earlier snapshot by `set_current_snapshot` — a second `snapshot-log` entry for the target, the abandoned commit retained on no ref, the next commit forking from the target |
| `default/retained` | `RetainedFixtureTest` | refs with retention — a tag `RETAIN 90 DAYS`, a branch `RETAIN 30 DAYS WITH SNAPSHOT RETENTION 2 SNAPSHOTS` — and an `expire_snapshots` that kept what each ref's own settings say |
| `default/extdata` | `ExternalDataPathFixtureTest` | `write.data.path` outside the table — no `data/` under it, two files beside it under `example/iceberg/extdata-files/` |
| `default/sorted` | `SortedFixtureTest` | three sort orders, a commit under each, then a sort compaction — rows sorted inside every file, `sort_order_id 0` on every file |
| `default/expired` | `ExpiredSnapshotsFixtureTest` | snapshots dropped by `expire_snapshots` — the older metadata versions still list them, and they are drawn as expired, not as read errors |
| `default/maint` | `MaintenanceFixtureTest` | `rewrite_position_delete_files` dropping two dangling deletes, then `rewrite_manifests` — the commit whose summary counts manifests |
| `default/merged`, `mergedel`, `mergespec` | `ManifestMergeFixtureTest` | `commit.manifest.min-count-to-merge = 2` — every append merging the list into one manifest, a copy-on-write delete's filtered manifest alone in its bin, merging switched off; the delete side, where a second delete rewrote the first delete file; and a spec change merging the old spec's manifests under the default count |
| `default/fup` | `FlinkUpsertFixtureTest`, `IcebergDeletePairingPlanTest` | Flink 1.20's upsert sink on a v2 table — each commit's equality delete and data file at one sequence number, and a positional delete for a key upserted twice in one checkpoint; `(1, a2), (2, b2), (4, d)` read back |
| `default/fupp` | `FlinkUpsertPartitionedFixtureTest` | `fup` partitioned by `p`, with `p` in the key — commit 2's equality delete in `p=y` never weighed against the `p=x` files, and its delete in `p=x` for a new key dangling by its own bounds; `(1, x, a2), (2, y, b2), (4, x, d)` read back |
| `default/eqpart` | `EqualityPartitionFixtureTest` | two equality deletes on `id` alone written with `EqualityDeleteWriter` over three files that all hold ids 1..2 — one under the partitioned spec in `p=y`, keyed to that partition; one under the unpartitioned spec the table started with, global; `(1, x), (1, x)` read back |
| `default/sweep`, `swept`, `sweepb`, `sweptb` | `ExpiryFilePlanFixtureTest` | two tables copied on disk before `expire_snapshots` ran on the original — `swept` with one ref (incremental cleanup: a removed file and a rolled-back commit's file freed), `sweptb` with a branch (reachable: a manifest freed, no file) |
| `default/avrofmt` | `DataFileFormatFixtureTest` | `write.format.default = avro` — two Avro data files and an Avro positional delete, read through `read_avro` without positions; the writer records no column metrics, so the delete has no `file_path` bounds and Iceberg's plan attaches it to both files |
| `default/orcfmt` | `DataFileFormatFixtureTest` | the same table in ORC, which DuckDB cannot read — the fixture for what every reader says instead |
| `paimon/db.db/test` | `RealTableFixtureTest`, `PaimonIndexManifestTest` | a real Flink/Paimon table, and its index manifest |
| `paimon/db.db/dv` | `PaimonIndexManifestTest` | a Spark-written primary-key table with a deletion vector, and the compaction trap that nearly produced none |
| `paimon/db.db/pt` | `PaimonPartitionFixtureTest`, `PaimonManifestTallyTest`, `PaimonFileBoundsFixtureTest`, `PaimonScanPruningTest` | a partitioned table — `_PARTITION` decoded against the directory layout, both string encodings and a date, and one manifest whose recorded partition minimum is a partition none of its entries has |
| `paimon/db.db/ao` | `PaimonAppendOnlyFixtureTest` | an append-only table, no primary key, `bucket = -1` — no key range, everything in `bucket-0`, and a DELETE that rewrites a file as an `APPEND` with a negative delta |
| `paimon/db.db/br` | `PaimonBranchFixtureTest` | two branches — one created from a tag and committed to, one created empty; main and `dev` both hold a `snapshot-2`, and `bucket-0` holds a file only the branch names |
| `paimon/db.db/cs` | `PaimonConsumerFixtureTest` | a consumer standing on snapshot 2, and an `expire_snapshots(retain_max = 1)` that left snapshots 2 and 3 because of it |
| `paimon/db.db/fi` | `PaimonFileIndexFixtureTest`, `PaimonFileIndexPruningTest` | a bloom-filter file index both ways — a 599 KB `.index` beside the first data file, 117 bytes embedded in the second entry — on a primary-key table, whose merge read consults neither |
| `paimon/db.db/fa` | `PaimonFileIndexTest`, `PaimonFileIndexPruningTest` | the append twin — bloom filters on both columns, a 1,290-byte `.index` then an embedded one, and a 43-byte value for xxHash64's stripe; the table whose scan and read actually ask the index |
| `paimon/db.db/fb` | `PaimonFileIndexTest`, `PaimonFileIndexPruningTest` | a bitmap file index on two columns, one also under a bloom filter — one file with red, green and a null (embedded), one all red and one all null (`.index` files); the dictionary held to `FileIndexPredicate` over every file for `=`, `<>`, `IS NULL` and `IS NOT NULL` |
| `paimon/db.db/ft` | `PaimonFileIndexTest`, `PaimonFileIndexPruningTest` | an append table with a bloom filter on a `TIMESTAMP(6)`, a `TIMESTAMP(6) WITH LOCAL TIME ZONE` and a `DATE` column, embedded — the temporal hashes, held to the plan on three values a column and on two misses inside the bounds that only a microsecond hash answers |
| `paimon/db.db/ep` | `PaimonExternalPathFixtureTest` | `data-file.external-paths` — no bucket under the table, both files at `example/paimon/ep-files/bucket-0/` beside it, `_EXTERNAL_PATH` recorded |
| `paimon/db.db/rt` | `PaimonRowTrackingFixtureTest` | `row-tracking.enabled` — two appends recording first ids 0 and 3, then a full compaction whose output records none and carries `_ROW_ID` per row |
| `paimon/db.db/sm` | `PaimonStatsModeFixtureTest` | `fields.v.stats-mode = none` — `_VALUE_STATS` over two of three columns, named in `_VALUE_STATS_COLS`; the oracle for the subset decoding |
| `paimon/db.db/se` | `PaimonSchemaEvolutionFixtureTest` | `ADD COLUMN` between two writes, then a compaction — a schema-1 manifest listing a schema-0 file, whose stats decode only against its own schema |
| `paimon/db.db/pkr` | `PaimonRowLookupFixtureTest`, `PaimonMergedCountFixtureTest` | a primary key renamed between writes — `_KEY_k` in the first file, `_KEY_id` in the two after, key 1 written again after the rename; Paimon's read `1 A / 2 b / 3 c` is printed by the script |
| `paimon/db.db/pse` | `PaimonReadProjectionFixtureTest` | an append table evolved after its first file — `ADD COLUMN w`, `RENAME COLUMN v TO label`, `ALTER COLUMN w SET DEFAULT 7` — with Paimon's own read printed in the script: the old file's `v` as `label`, its `w` as null, and the default a later write stored for a row that omitted `w` |
| `paimon/db.db/de` | `PaimonDataEvolutionFixtureTest`, `PaimonRowLookupFixtureTest`, `PaimonScanPruningTest` | `data-evolution.enabled` — a `MERGE INTO` writing a one-column patch file with `_WRITE_COLS` and the first row id of the file it patches, and a whole file for the row it inserted; the stitched read `(1, 11, 1)` the lookup is held to, and the file bounds pruning must not consult |
| `paimon/db.db/lk` | `PaimonRowKindTest` | `changelog-producer = lookup` — the `-U` / `+U` pair a re-inserted key produces, carried by the COMPACT snapshot the lookup ran in, and a `-D` with the value it removed |
| `paimon/db.db/ad` | `PaimonAppendDeletionVectorFixtureTest` | an append table with `deletion-vectors.enabled` — a DELETE that commits as a COMPACT adding only an index manifest, one vector per touched file, both files untouched |
| `paimon/db.db/px`, `pxa` | `PaimonExpiryFixtureTest` | one table written twice — six commits, a tag on 2, a consumer at 4; `px` as it is, `pxa` after `expire_snapshots(retain_max = 2, retain_min = 1)` — the survivors the plan for `px` is checked against |
| `paimon/db.db/pe`, `pea` | `PaimonExpiryFilePlanFixtureTest` | one table copied on disk before `expire_snapshots(retain_max => 2, retain_min => 1)` ran on the original — a changelog, a compaction, a tag on 3; the files the expiry freed, and the three the tag held |
| `paimon/db.db/pc` | `PaimonCompactionFixtureTest` | a primary-key table on every default, seven one-row inserts — the fifth flush is the one the writer compacted, by size amplification into level 5, and the COMPACT after it is the oracle |
| `paimon/db.db/cl` | `PaimonChangelogFixtureTest` | a changelog manifest list on every append, an `OVERWRITE`, and an `ANALYZE` commit with column statistics |
| `paimon/db.db/tg` | `PaimonTagFixtureTest` | a tag on a snapshot `expire_snapshots` has removed — a data file only the tag reaches, and the changelog the tag did not keep |
| `paimon/db.db/pu`, `ag`, `fr` | `PaimonMergeEngineFixtureTest` | one primary-key table per merge engine other than the default — `partial-update` folding two writes and removing a key on `-D` until its re-insert, `aggregation` summing, and `first-row`, whose DELETE Spark ran as a file rewrite to level 0 that a batch read of a first-row table never reads: Paimon's own reads printed one row where the statements describe two |
| `paimon/db.db/sgm` | `PaimonMergeEngineFixtureTest` | `partial-update` with a sequence group of two fields, `fields.g1,g2.sequence-group = a`, and `remove-record-on-sequence-group = g2` — an insert with a null in the tuple ordered below the row's, and Paimon's read at every snapshot |
| `paimon/db.db/sg`, `sgd` | `PaimonMergeEngineFixtureTest` | `partial-update` with two sequence groups — `sg` inserts only, a lower group value not overriding a higher; `sgd` with `remove-record-on-sequence-group = ga`, a DELETE writing a `-D` that removes the key and an insert bringing it back, Paimon's read at every snapshot |
| `paimon/db.db/pav`, `paz` | `DataFileFormatFixtureTest` | `file.format = avro` — `pav` under `file.compression = deflate`, merged, looked up and checked through `read_avro`; `paz` on the default zstd, which DuckDB's Avro reader refuses — its row cards read in process, its SQL readers through a copy under deflate, to the same answers |

**Remote reading is checked against the same fixture, read twice.** `docs/fixtures/minio-lab.sh up`
starts a loopback-only MinIO and uploads `example/iceberg/default/mor` to `s3://warehouse/db/mor`;
`RemoteTableTest` then opens that table *and* the one on disk and requires the two models and the
two graphs to agree. That comparison is the whole point — a decoder fed truncated or misordered
bytes produces a model that is internally consistent and wrong, and every assertion written against
the remote side alone would pass. The tests skip rather than fail when the container is absent, so
a checkout without Docker stays green.

Two things about generating these are worth not rediscovering. **Spark writes one row per data
file** for small inserts under `local[2]`, and a `DELETE` matching every row of a file removes
the file outright instead of writing a positional delete — so a delete-file fixture must pin
`--master local[1]` or it silently covers nothing. And **Spark writes no equality deletes at
all**; `eqdel`'s is written by driving Iceberg's own `EqualityDeleteWriter` from `spark-shell`
(`docs/fixtures/eqdel-equality-deletes.scala`), which needs `MessageType` imported from
`org.apache.iceberg.shaded.…` because the runtime jar relocates Parquet and Spark's own copy is
also on the classpath. **Flink's upsert sink writes both kinds without help**, and
`docs/fixtures/flink-upsert.sql` runs it in one container — `flink:1.20-scala_2.12-java17` with
the released `iceberg-flink-runtime-1.20` 1.10.0 and Hadoop 3.3.6 jars from lakelab's cache
mounted into `lib/`, a local cluster started in the same shell, `sql-client.sh -f` with
`table.dml-sync` so each statement's job finishes before the next — which is what makes `fup`
the engine-written equality delete, and the one table with a delete beside its own data.

A third: **the image's Iceberg is 1.8.1, and a newer runtime can be dropped in.** `lineage` is
written by the 1.10.0 Spark 3.5 runtime jar (`~/code/spark-kit/lakelab/.cache/`), mounted with
`--jars` after the image's own `iceberg-spark-runtime-3.5_2.12-1.8.1.jar` and the three
`iceberg-*-bundle-1.8.1.jar` are `rm`ed inside the container — two Iceberg versions on one
classpath is not a configuration, it is whichever class loads first. That is what unblocks every
v3 feature 1.8.1 does not write: row lineage is in; `compute_partition_stats` and variant remain.

## Supported table formats

### Iceberg
- Detection: `metadata/` dir with `*.metadata.json` files
- Reader: `IcebergReader` (JSON metadata + Avro manifests via `AvroReader`)
- Model: `UnifiedTableModel` → `IcebergGraphBuilder` → `GraphLayoutService`

### Paimon
- Detection: `snapshot/` + `schema/` directories
- Reader: `PaimonReader` (JSON snapshots/schemas + Avro manifest lists/manifests via `AvroReader`)
- Model: `PaimonUnifiedTableModel` → `PaimonGraphBuilder` → `GraphLayoutService`
- Key differences from Iceberg: manifest lists split into base (accumulated) and delta (new changes); LSM tree levels on data files; commitKind (APPEND/COMPACT/OVERWRITE/ANALYZE)
- Manifest entries carry `_KIND` (0=ADD, 1=DELETE log entry). Paimon does NOT have
  positional/equality delete files like Iceberg, so `posDeleteFileCount` and
  `eqDeleteFileCount` stay 0 by definition; removals surface as `deletedEntryCount`.
- A snapshot's **delta manifest list is applied over its base** — a `_KIND=1` entry removes a
  file the base still lists — so `current` stats replay add/remove rather than summing ADD
  entries. The changelog manifest list is excluded from `current`: it carries the change
  stream, not the table's contents.
- Paimon has no data/delete manifest split (every manifest carries both kinds of entry), so
  all manifests count as `dataManifestCount`.
- **A primary-key table's data file holds every write as a row, and `_VALUE_KIND` says which
  kind.** The Parquet file's columns are `_KEY_<col>` per key field, `_SEQUENCE_NUMBER`,
  `_VALUE_KIND` and then the value fields, so a `DELETE` is a `-D` row at level 0 with the key's
  last value still in it, removed only when a compaction merges it with the levels below. The codes
  are `PaimonRowKind` in `model/PaimonSchema.kt` — 0 `+I`, 1 `-U`, 2 `+U`, 3 `-D`, all four from
  the files' own bytes: `+I` and `-D` on `dv` and `cl`, the update pair on `lk`, whose `lookup`
  producer computes the change at commit time and writes it in the **COMPACT** snapshot that
  follows each APPEND, not in the APPEND itself — and a `RowNode.isRetraction` row is drawn as the
  Iceberg-vector-deleted row is: faded, struck, the kind in the title. The card lists keys
  beginning with `_` **last**, because the file's physical order puts the three system columns
  first and a card of four lines would otherwise show none of the row's own. A Paimon row goes
  through the same `unifiedRowOf` as an Iceberg row, so its position is `UnifiedRow.position` and
  not a `file_row_number` cell; and it is resolved against its file's vector the way an Iceberg
  row is, with `vectorsResolved = false` only where a vector exists and could not be read — the
  panel then prints the position and no `Deleted` row, rather than "not by a deletion vector" of
  a row no vector was checked for
- **A Paimon data file carries the vector its latest index manifest names for it, and the rows
  are marked from it.** `vectorRangesOf` in `model/PaimonRowLookup.kt` folds every snapshot's
  `_DELETIONS_VECTORS_RANGES` by file name, main's snapshots then each branch's, a later range
  replacing an earlier — a second delete on a file writes a new vector for it — into one
  `PaimonVectorRange` per file: the index file, offset, length and cardinality, from the manifest
  alone. `PaimonDataFileNode.vectorRange` is that, and `.deletionVector` is it decoded through
  `PaimonDeletionVectorReader` on first use, one `DeferredRead` shared between the node and its
  row factory so the index file is opened once for both the panel's `Deleted Rows` section and
  the row cards' strike. The section is `DeletionVectorBody`, the same composable the Iceberg
  file panel draws, with the index file's coordinates as its first row; the IDE strip prints the
  manifest's cardinality and the index file's name without decoding, since it is drawn on the
  EDT. `PaimonVectorRowsTest` holds `dv` and `ad` to their scripts — k 2 and 1001, id 2 and 6
  within the five rows drawn — and every row of `pc`, which has no index manifest, to resolved
  and live
- **A partitioned table's file path comes from the entry's `_PARTITION`, and that is a
  `BinaryRow` this decodes.** A manifest entry names its file by `_FILE_NAME` only; the file lives
  under `<key>=<value>/…/bucket-N/`, so until `model/PaimonBinaryRow.kt` existed every data file of
  a partitioned table resolved to `<table>/<file>`, read as missing, and the whole table read as
  orphans — on the table shape that is the ordinary one. The row is little-endian behind a 4-byte
  big-endian arity: a null-bit region whose first eight bits are the row kind, one 8-byte slot per
  field, then a variable-length tail; a string of seven bytes or fewer sits *inline* in its slot
  with the length in the last byte's low seven bits and that byte's top bit set, a longer one sits
  in the tail behind `(offset shl 32) or length`. The `pt` fixture carries both (`eu`,
  `north-america`) and a date, and **the directory layout is the oracle**: the partition decoded
  from each entry has to be the directory its file is in. It is decoded against the schema the
  manifest's `_SCHEMA_ID` names, the same rule as an Iceberg manifest and its spec. Two things
  about the path: under the default `partition.legacy-name = true` a `DATE` is written as its
  epoch day (`dt=19787`), so `PaimonPartitionValue` carries the decoded value *and* the path text;
  and the resolver returns the partition path whether or not the file is there, because a missing
  file should be reported where it was supposed to be. An entry whose partition cannot be decoded
  — a type this does not read, the wrong arity — resolves the old way and says so in the panel
- **A Paimon manifest list's figures are shown against the entries too, and its partition minimum
  is per column.** `paimonManifestTallies` in `model/PaimonManifestTally.kt` is `manifestTallies`
  for this format: the entry counts, the `_MIN_BUCKET`/`_MAX_BUCKET` and `_MIN_LEVEL`/`_MAX_LEVEL`
  ranges, and `_PARTITION_STATS` — a `SimpleStats` record of a per-field minimum `BinaryRow`, a
  per-field maximum and null counts, decoded by the same `decodePaimonPartition`. **Per field**,
  not the lowest entry: `pt`'s third commit holds `(2024-03-07, eu)` and `(2024-03-05,
  north-america)` in one manifest and Paimon recorded `(2024-03-05, eu)` as its minimum, a
  partition no entry has; a fold over entries as rows would disagree with a correct manifest list
  on exactly that manifest, and the fixture was regenerated to hold it because the first two
  commits could not tell the two readings apart. The ranges cover every entry, `_KIND` 1
  included — folding additions only puts `dv`'s lowest level at 5 where the list says 0 — which
  is a fact the fixtures settled and not one a document did. Both sides are text, because a range
  of dates is not a `Long`, and the panel draws them the way the Iceberg manifest's section does
- **A Paimon data file's own bounds are read, and they are three more `BinaryRow`s.** `_MIN_KEY`
  / `_MAX_KEY` are rows over the *trimmed* primary key — the primary keys that are not partition
  keys, in primary-key order — and `_VALUE_STATS` is a `SimpleStats` over every field of the schema
  in schema order (or the columns `_VALUE_STATS_COLS` names). `decodePaimonRow` reads any of them,
  and unlike the partition decoder it leaves a field of a type it does not read *undecoded* rather
  than failing the row, because a statistics row over a table with one `ARRAY` column still says
  something about every other column. The file panel shows the key range, the per-column bounds,
  `_DELETE_ROW_COUNT` and `_FILE_SOURCE`; the IDE tree the key range. `PaimonFileBoundsFixtureTest`
  reads every bound off the scripts that wrote the rows, and two of its facts are the kind a
  document does not settle: **a string bound is lexicographic over the whole value** (`v1..v999`
  over a thousand rows, `v1001..v2` over three), and **`_DELETE_ROW_COUNT` counts the `-D` rows
  inside a file**, not the rows a deletion vector marks — 3 on `dv`'s level-0 delete file, 0 on
  the two files its vector covers. **Those rows are decoded against the schema the file's own
  `_SCHEMA_ID` names, never the manifest's** — the same rule as an Iceberg manifest's own spec,
  and the two differ as soon as a manifest written under a new schema lists a file written under
  the old, which a compaction's delta manifest does because it records the files it removed. Read
  against the manifest's schema, a two-field stats row is three fields short of the arity check
  and decodes to nothing — silently, since `decodePaimonRow` answers null for a wrong arity. `se`
  is the fixture: `ADD COLUMN` between two writes, then `sys.compact`, and the removed schema-0
  file's `k 1..2, v a..b` are what the entry has to say
- **Scan pruning answers for Paimon through a bridge, not a second evaluator.**
  `model/PaimonPruningBridge.kt` puts what a Paimon manifest and file record into the vocabulary
  the pruning rules are written in: a manifest's per-column partition range becomes one identity
  `PartitionSummary` per key, a file's column bounds become `ColumnStats` with the file's row
  count as the value count (Paimon records no per-column value count, and every row holds one
  value per column, so the two are one figure), and `paimonTypeAsIceberg` decides which Iceberg
  type each Paimon type's literal is read in — the one place a literal can be read wrong, and a
  type it cannot compare is left out rather than mapped to something plausible. `evaluateScan` and
  `prunableColumns` then take both node kinds and `ScanPruningSection` lists rows by id and label,
  so the pruned fade on the canvas and the verdict tables need nothing format-specific.
  `PaimonScanPruningTest` reads its expectations off the `pt` script — `dt = 2024-03-07` skips two
  manifests, leaves five files unreached and one skipped by its own bound — and holds the direction
  that matters over every key the script wrote: no file holding a matching row is ever pruned
- **A primary-key table's file stage is not per file, and the oracle is Paimon's own plan.**
  `KeyValueFileStoreScan` at release-1.3.1 splits the filter: the top-level conjuncts naming
  trimmed primary keys only (`pickTransformFieldMapping(splitAnd(predicate))`) prune a file on its
  own against `_KEY_STATS` — a file whose key range excludes the key holds no record of it — while
  the whole filter is applied **per bucket**, over the files the key stage left
  (`filterWholeBucketByStats`): file by file where the bucket's files cannot overlap (all at one
  level above 0), and otherwise the bucket is read whole if any file may match and skipped whole
  if none may, because a key's latest record can sit in a file whose bounds do not match —
  `PrimaryKeyFileStoreTable.nonPartitionFilterConsumer` carries the case, file 1 inserting
  `value = 1` and file 2 updating it to `2`, where pruning `value = 1` per file returns a row the
  table does not have. `partial-update` and `aggregation` without deletion vectors are never
  pruned by value at all; a table whose batch reads skip level 0 (`first-row`, deletion vectors)
  never opens a level-0 file (`FileFate.NOT_READ`) and prunes the rest each on its own
  (`DataTableBatchScan`: `withLevelFilter(level > 0).enableValueFilter()`).
  `evaluatePaimonPrimaryKeyFiles` in the bridge runs that over the latest snapshot's **live**
  files, read off the table node's deferred `PaimonReadInput` — the graph also draws the entries
  a compaction removed and the removal records, and those get their own bounds' verdict with a
  note, since a scan never plans over them and their bounds must not decide a live file's bucket.
  A file its own bounds rule out but the bucket keeps is `WOULD_BE_READ` with
  `FilePruneResult.note` saying so, and the panel leads its reason cell with the note, or the row
  would contradict itself; `ScanPlan.primaryKeyRule` is the rule in a sentence above the file
  table. `_KEY_STATS` is decoded to `keyBounds` beside the value bounds for this, because under a
  `stats-mode = none` the key still has a bound there (`sm`). The oracle is
  `docs/fixtures/paimon-scan-plans.scala`, which runs `newReadBuilder().withFilter(…).newScan().plan()`
  over copies of `pc`, `lk`, `pu`, `ag`, `dv`, `sm` and `pt` and prints the files each plan opens:
  `PaimonPrimaryKeyScanPruningTest` holds twenty-three filters to those sets — `pc`'s `v = 'g'`
  opens all three live files though only a level-0 one can hold it, `v = 'z'` none, `k = 7 AND v
  = 'a'` none because the key stage leaves one file and it no longer overlaps; `lk`'s files at
  levels 3, 4 and 5 count as overlapping; `pu` opens every file on `a = 'zzz'` and two of four on
  `k = 1`; `dv` prunes `v = 'v2'` to one file with its level-0 file off the plan. Flipping the
  overlap rule to per-file pruning failed two of them
- **A Paimon snapshot's three record counts are checked against the manifests it names.**
  `paimonRecordTallies` in `model/PaimonReplay.kt` puts `totalRecordCount` beside the rows the
  replay ends holding, `deltaRecordCount` beside the delta list's entries summed the way the
  writer sums them — `ADD` rows minus `DELETE` rows by the entry, whether or not a removed file
  was there to remove, which is why the raw sum and not the replay's contribution is the counted
  side — and `changelogRecordCount` beside the changelog list's rows. `PaimonSnapshotNode`
  carries them as a `DeferredRead` threaded through the *same* deferred replay `liveFiles` uses,
  so a panel that compared and then tallied walked once. `PaimonRecordTalliesTest` checks 72
  recorded figures across seven tables and every branch; the corpus's one disagreement is the
  format's own — `tg`'s tag records a changelog count of 3 against a changelog list expiry
  deleted — and it is asserted as a NO beside its read error rather than filtered away
- **A snapshot's `indexManifest` is read, and it is the only place two things are recorded.**
  The field was parsed into `PaimonSnapshot` and dropped — the same shape of gap Iceberg's
  `statistics` had, and invisible for the same reason: nothing rendered it, so nothing noticed it
  was never opened. It lists one index file per partition and bucket, and `_INDEX_TYPE` decides what
  the row means. `HASH` is the primary-key index a bucket looks a key up in, so its size and row
  count *are* the cost of that lookup. `DELETION_VECTORS` is Paimon's answer to the problem Iceberg
  solves with a Puffin vector, and `_DELETIONS_VECTORS_RANGES` is the only place the format records
  which data file has deleted rows and how many — a container with a blob per file, the same shape
  as Puffin, which is why a vector's length is not the index file's size. The field names come from
  the fixture's own Avro header rather than from prose, and they are not descriptive: the range
  record's `f0`, `f1`, `f2` are the data file, the offset and the length. **Both branches have an
  oracle**: the Flink-written `test` table carries a `HASH` index, and `dv` — written by Spark from
  `docs/fixtures/paimon-dv.sql` — carries a `DELETION_VECTORS` index whose cardinalities were
  written into the script before it ran. Getting a vector out of Paimon at all took three container
  runs, and the mechanism is worth not rediscovering: a `DELETE` on a primary-key table writes `-D`
  rows to level 0 and forces a lookup compaction, and **a vector is written only when the L0 row
  shadows a key in a higher-level file the compaction leaves alone**. Four rows and two rows made
  three runs of one size, universal compaction's size-ratio rule merged all of them, and the result
  was a new file and no vector — a correct table that exercised nothing. A thousand rows and five
  hundred against three is what makes the compaction stop at L0. The snapshot's `totalRecordCount`
  still counts the marked rows, which is why the panel prints the live figure beside it. **An
  append table needs none of that**: with no merge engine, `ad`'s DELETE of one row in each of two
  files commits as a `COMPACT` whose delta list is empty and whose only change is the index
  manifest — a vector of cardinality 1 per file, both files still listed as written,
  `deltaRecordCount` 0 — where `ao`, without vectors, rewrote the file
- **An `ANALYZE` commit names a statistics file, and it is the only place the merged row count
  exists.** `PaimonSnapshot.statistics` was parsed and dropped like `indexManifest` before it;
  `model/PaimonSchema.kt` now reads the JSON under `statistics/` as `PaimonStatistics`, eagerly, one
  small file per such snapshot. `mergedRecordCount` is the row count *after* the merge engine — what
  a scan returns — where `totalRecordCount` sums file rows and so counts an updated key once per
  version and a `-D` row as a row; the panel leads with it and puts the file-row total beside it.
  `snapshotId` names the snapshot the figures were computed at, which is the one **before** the
  `ANALYZE` commit that carries them (`cl`'s statistics say 4 on snapshot 5). Column stats come only
  with `FOR ALL COLUMNS`, `min`/`max` are strings whatever the type, and a string column gets none.
  Three more findings from `cl`, each pinned in `PaimonChangelogFixtureTest`: a snapshot records the
  **byte size of each manifest list** (`baseManifestListSize` and siblings, checked against the
  files); the changelog list is excluded from `current` but **counted in `history`**, because the
  changelog files are reachable and retained until their snapshot expires, which is `history`'s
  stated definition; and **an overwrite writes a changelog file and does not commit it** — Paimon
  logs "Overwrite mode currently does not commit any changelog" and leaves the file in the bucket
  unlisted, so the table has four `changelog-` files on disk and three in any manifest
- **A tag is a snapshot file under `tag/`, read as one, and it is what keeps files on disk after
  the snapshot is gone.** `PaimonUnifiedTableModel.tags` reads `tag/tag-<name>` through the same
  reader and manifest cache as `snapshot/`; `tagOnlySnapshots` is the tagged snapshots `snapshot/`
  no longer holds, and the builder draws them — `PaimonSnapshotNode.retainedByTagOnly`, with the
  tag as a chip and the eyebrow saying `TAG ONLY`, because their files are the table's even though
  no id under `snapshot/` reaches them. History walks them too. `tg` is the fixture: `retain_max
  = 1` left snapshot 4, and snapshot 1's data file is on disk because `tag-first` names it — which
  is why the referenced set follows tags, or that file is a false orphan in the direction that
  gets a file deleted. **A tag retains data, not changelog**: expiry deleted the changelog manifest
  list the tag still names, and the tag reads with exactly that one read error, drawn under it.
- **A Paimon file written outside the table records where, and that is the one path with
  something to resolve.** The format records no path for a file in its own layout —
  `<table>/<partition>/bucket-N/<file>` is the rule — so `PaimonPathResolution.LAYOUT` says
  nothing and the panel draws no row for it. `data-file.external-paths` is the exception: the
  entry's `_EXTERNAL_PATH` is `file:/wh/ep-files/bucket-0/<file>` and the table directory holds no
  bucket at all (`ep`). The recorded path wins when the file is there (`EXTERNAL_RECORDED`);
  otherwise `rerootExternalPath` looks for the recorded path's tails under the local warehouse —
  the table's grandparent when its parent is a `.db` directory, since a Paimon snapshot records no
  table location to re-root against the way an Iceberg manifest's own path does — longest tail
  first and existence-gated, because it is a search (`EXTERNAL_REROOTED`); and a file at neither
  place is reported at the layout path it is not at (`EXTERNAL_MISSING`). A path the table recorded
  outside itself skips the traversal check, the same rule as Iceberg's `RECORDED`
- **A row-tracked file keeps its ids in one of two places, and a compaction moves them.** Under
  `row-tracking.enabled` a file a commit wrote records `_FIRST_ROW_ID` and its rows take
  consecutive ids in file order, and the snapshot records `nextRowId`; a compaction's output
  records **no** first id and carries every row's id in a `_ROW_ID` column of the file, beside a
  `_SEQUENCE_NUMBER` column holding the writing commit's sequence — the `rt` fixture's compaction
  also reordered the rows, which is why the id has to travel with the row. The Paimon builder
  derives `_ROW_ID` for the first shape (`first + file_row_number`) and reads it as a cell for the
  second, so a row card carries the id either way, and the file panel's `Row IDs` row says which
  shape it is — or that row tracking is off, which is what an `APPEND` file with no first id means.
  `nextRowId` is not checked as a tally: once a compaction's inputs leave the base, nothing listed
  accounts for the ids that were handed out, so the invariant `PaimonRowTrackingFixtureTest`
  sweeps is one-sided — no file claims an id at or past the next one, and the next never goes back
- **A data-evolution file holds only the columns a `MERGE INTO` set, and its statistics are over
  those.** With `data-evolution.enabled` on a row-tracked append table, `UPDATE SET t.b = s.b`
  writes the new `b` values to a file of their own with `_WRITE_COLS = [b]` and the **same
  `_FIRST_ROW_ID`** as the file holding the rows' other columns; a read stitches files sharing a
  first row id (`DataEvolutionSplitGenerator.split`), the higher `_MAX_SEQUENCE_NUMBER` winning a
  column. Two things follow that the `de` fixture pins. The patch file's `_VALUE_STATS` is a
  one-field row with `_VALUE_STATS_COLS` **null** — the writer's null means "every column the file
  writes", not "every column of the schema" — so the stats fields are `_VALUE_STATS_COLS`, else
  `_WRITE_COLS`, else the file's schema, in that order, and decoding against the schema had the
  file at no bounds. And the pairing is drawn: `PaimonGraphBuilder.addPatchEdges` emits
  `e_patch_<patch>_to_<whole>` between `ADD` entries of one partition, bucket and first row id,
  withheld from ELK like `e_dv_*` since both ends sit in one layer, and each file's panel names the
  other end — `Columns: b only — … stitched with <file>` and `Patched By: <file> (b)`. The
  snapshot's `totalRecordCount` still sums file rows, so `de` records 5 for a table of 3 rows —
  `LiveFile.partial` carries the flag out of the replay and the snapshot panel's record tallies
  lead with `the snapshot's 5 rows read as 3`, the same shape as the vector note; the table's
  figures carry it too, as `ContentStats.partialRecordCount`, folded by the replay beside the
  `recordCount` it qualifies rather than computed a second time, so `readRecordCount` is a getter —
  and `nextRowId` moves only by the not-matched row the merge inserted. **`_WRITE_COLS` being set
  is not what makes a file partial.** `rt`'s full compaction under `row-tracking.enabled` lists
  every column plus `_ROW_ID` and `_SEQUENCE_NUMBER` there, and the reading "non-null means
  partial" had the table's five rows as columns of rows other files hold — `readRecordCount` 0,
  the file panel calling it a patch, the IDE strip too, on a table with no data evolution at all.
  `PaimonDataFileMeta.isPartialUnder(schema)` is the one reading now — partial when a schema
  column is missing from the list, decided against the schema the file's own `_SCHEMA_ID` names
  where the entry is built (`PaimonUnifiedDataFile.partial`) and carried to the node — and every
  site that asked `writeCols != null` asks it instead. `PaimonRowTrackingFixtureTest` pins `rt`
  at five rows read
- **A data-evolution split is read stitched, and no file of such a table is pruned by its own
  bounds.** `PaimonReadInput.splits` groups the read files the way `DataEvolutionSplitGenerator.split`
  does — same partition, bucket and `_FIRST_ROW_ID`, freshest first by `_MAX_SEQUENCE_NUMBER`, a file
  recording no first id alone — and `PaimonRowLookup.readSplit` reads a split of two or more as one
  statement, the files joined on `file_row_number` (every file of a split holds the same rows in
  the same order; `DataEvolutionSplitRead` checks the row counts agree) with each column taken from
  the first file holding it and the filter run over the stitched row. So `id = 1` on `de` answers
  `(1, 11, 1)` at the file holding the row with `b from <patch>` as its note, and `b = 11` finds
  it though the file holding `id` records `b` in 1..2 — which is why **a split is read whole when
  the filter left any file of it**, the ruled-out file being the one with the row's other columns.
  The pruning side follows from the same fact: a file's bounds under data evolution describe
  values a patch may have replaced, so `evaluateScan` withholds the file stage on such a table
  (`paimonFileBoundsWithheld`, the reason on `ScanPlan.fileBoundsWithheld` and on every file's
  outcome) and the panel says so once above the file table, while the manifest stage — partition
  ranges, which no patch changes — still runs. Paimon 1.3.0 and later do the same
  (`DataEvolutionFileStoreScan.filterByStats` answers true, #6443, 2025-10-21); the 1.3-SNAPSHOT
  that wrote `de` did not, and a filtered read of the checked-in bytes on it returned `(1, 1, 1)`
  for `b = 1` and nothing for `b = 11` where `SELECT *` prints `(1, 11, 1)` — a wrong answer the
  panel now reads correctly. And a headline that counted only proved reads said *would read 0 of
  3* on that table: a file nothing could be evaluated against is opened all the same, so the
  section's headline counts `UNEVALUATED` with `WOULD_BE_READ` and names them on a line of their
  own, the way it already named the manifests
- **What a read of a Paimon snapshot returns is counted, because nothing records it.**
  `totalRecordCount` sums file rows — an updated key twice, a `-D` marker as a row — and an
  `ANALYZE` writes `mergedRecordCount` once, for the snapshot it ran on. `service/PaimonMergedCount.kt`
  answers for any snapshot from its `readInput` (`PaimonReadInput`, threaded through the node's
  deferred replay): on a primary-key table under `deduplicate`, per bucket, the merge a read runs
  as one DuckDB statement — `latestPerKeySql`, the same `UNION ALL` over the bucket's files the
  row lookup asks a key's state with, `arg_max` by `_SEQUENCE_NUMBER` — counting the keys and the
  keys whose latest record is a `-D` or `-U`; then, for a file with a vector, the latest records'
  positions in it streamed back and tested against the vector's bit set, which
  `PaimonDeletionVectorReader.readPositions` decodes whole so the count is exact past
  `MAX_POSITIONS`. A merge engine that combines versions is reported and not applied; an append
  table's count is the metadata's — file rows less vector cardinalities less patch-file rows — and
  needs no click. `PaimonMergedCountFixtureTest` holds **every Paimon fixture's latest snapshot** to
  the rows its script left (nineteen tables, `br`'s branch included) and `cl`'s analyzed snapshot to
  the `mergedRecordCount` Paimon wrote, which is the one engine-written figure of its kind. The
  snapshot panel's `Merged Rows` section sits under the recorded counts, behind a click on a
  primary-key table, capped at `MAX_BUCKETS` and said so
- **The merge engines are one rule each, and a batch read of two kinds of table never reads
  level 0.** `model/PaimonMergeRule.kt` reads the four merge functions at release-1.3.1 into
  `PaimonMergeRule` — what is kept (the latest record, the first, or all folded into one), which
  `_VALUE_KIND`s as a key's latest record remove it (`-D` and `-U` under `deduplicate`; `-D`
  under `partial-update` only with `remove-record-on-delete`, and under `aggregation` only with
  its own; none under `first-row`), whether retractions are ignored (`ignore-delete` and its
  per-engine spellings) or make the read fail (`first-row`, and `partial-update` with neither
  option and no sequence group). `latestPerKeySql` carries the rule's
  removing kinds as a window, so the count and the lookup see a key's last removing record and
  the records after it — a partial-update key removed by a `-D` and re-inserted is one row
  again. **A key with no insert record is not a row**, under every engine but `aggregation`:
  `deduplicate` and `first-row` return the record they kept and `partial-update` answers
  `DELETE` while `meetInsert` is false, so a key whose only records are retractions — ignored
  under `ignore-delete`, or retracting by group — is counted in `BucketCount.insertless` and
  taken off the merged figure (`PaimonMergeRule.keyNeedsInsert`). **Sequence groups are
  applied, and the one removal they allow is folded in this process.** `fields.<seq>.sequence-group`
  makes a retraction retract its group's columns and never the key — `sg`, three rows — unless
  `partial-update.remove-record-on-sequence-group` names the group's sequence field, when a
  `-D` at or above the row's value on it removes the key (`retractWithSequenceGroup`). That is a
  scan with state, not an aggregate: `add` clears `currentDeleteRow` on every record and a
  removal resets the row so the groups start over, so `service/PaimonSequenceGroups.fold` walks
  the key's records in sequence order and the count and the lookup ask it only for the keys
  holding a `-D` (`sequenceGroupRecords`, one CTE over the bucket), taking its last removal and
  the inserts after it in the shape the SQL gives every other engine. `sgd` is the oracle —
  Spark's DELETE takes the upsert path under that option and writes a `-D` carrying the row's
  current `ga`, at or above itself — and `PaimonMergeEngineFixtureTest` holds every snapshot's
  count to what Paimon printed (2, 2, 3, 2, 3, 3) and the removed key's three records at
  snapshot 5 to superseded, retraction, live. **A group versioned by several fields compares as
  a tuple** — field by field in the key's order, a null below every value, two nulls equal, which
  is the generated comparator's order (`ComparatorCodeGenerator`, `nullIsLast = false`) — and the
  option removes on a `-D` whichever of the group's fields it names, since
  `retractWithSequenceGroup` walks the group's fields and removes at the first named one. `sgm`
  is that oracle (`fields.g1,g2.sequence-group`, `g2` named): its counts by snapshot are 3, 3, 3,
  2, 1, 1, 2, and snapshot 4 is the one that carries the null order — a key's second insert holds
  `(1, NULL)` against the row's `(1, 1)`, and only with a null *below* 1 does the `-D` carrying
  `(1, 1)` land at or above it; read the other way the count is 3, which the suite saw when the
  comparison was flipped. **And `DataTableBatchScan` filters `level > 0` for a first-row table or a primary-key
  table with deletion vectors** (`batchScanSkipLevel0`): the writer's forced compaction is
  supposed to have moved every level-0 record up, so a batch read trusts that and never opens
  level 0. `fr` is where trusting it fails — Spark refuses an upsert delete on a first-row table
  and rewrites the file instead, with `writeOnly()`, to level 0, and Paimon's own read of the
  result returned one row where the files hold two; its first snapshot, one append at level 0,
  read as **no rows at all**. `PaimonReadInput.readFiles` applies the same filter, the count
  reports the skipped files and rows, and a looked-up record in one of them is `not read`.
  `PaimonMergeEngineFixtureTest` holds `pu`, `ag` and `fr` to what Paimon printed, snapshot by
  snapshot on `fr`. The file itself says so too: `PaimonDataFileNode.unreadByBatchRead` is set by
  the builder from the snapshot's own schema options, and the `LSM Level` row on the panel and the
  `Level` row in the IDE strip carry the note on a level-0 file of such a table — which on `dv` is
  every append's file *before* the forced compaction moves it up, so a reader looking at the first
  snapshot sees why a batch read of it returned nothing
- **And the Iceberg twin is per data file, over the delete files the scan pairs with it.**
  `total-records` and every `record_count` count rows as written, and a merge-on-read delete
  touches neither; subtracting the delete files' own `record_count` is wrong the moment one is
  dangling. `service/LiveRowCount.kt` takes the snapshot's `RowLookupInput`
  (`SnapshotNode.readInput`, threaded through the same deferred walk and pairing as `liveFiles`
  and `deleteReach`, built by `rowLookupInputOf`) and counts each data file by what reaches it:
  nothing → `record_count`, unopened; a vector alone → less its cardinality, decoded and unopened,
  since a vector's positions are distinct by construction; a positional or an equality delete →
  the file opened once, DuckDB streaming back the positions the positional deletes name for it
  (`file_path` as recorded) or an equality delete's rows match on its columns (`IS NOT DISTINCT
  FROM`, nulls matching nulls as Iceberg compares them), into one bit set with the vector's whole
  positions (`PuffinReader.readDeletionVectorPositions`) — a row two deletes remove is one row
  gone. The pairing carries the sequence rule, so a delete written before the file was is never
  applied to it. `LiveRowCountFixtureTest` holds every fixture with deletes to its script and
  walks `maint` commit by commit — `4, 8, 7, 6, 6, 5, 5, 7, 6, 6`, the two rewrites changing the
  files and not the answer — and requires a table with no delete files to open nothing. The
  snapshot panel's `Live Rows` sits under the totals, drawn at once where the manifest list names
  no delete manifest and behind a click where it does, capped at `MAX_FILES` opened
- **A file index lives in one of two places, and the one beside the data file is the table's.**
  Where a Paimon file index goes is its size against `file-index.in-manifest-threshold` (500
  bytes): larger is `<file>.index` beside the data file, named in the entry's `_EXTRA_FILES`;
  smaller travels in the entry as `_EMBEDDED_FILE_INDEX`. The `fi` fixture has both — a bloom
  filter over a million items is 599 KB and a file, over a hundred is 117 bytes and embedded —
  and the referenced-files walk names the extra files beside their data file, or a scan's index
  is reported as an orphan. The panel's `File Index` row says which, and "none" for a table with
  no `file-index.*` property — and what the index holds, read off its head
- **The file index is decoded, and the scan plan asks it — where Paimon's own read would.**
  `service/PaimonFileIndexReader.kt` reads the container `FileIndexFormat` writes at 1.3.1 (a
  magic, a version, a head naming each column's index types with body offsets measured from the
  container's start, then the bytes) from the `.index` file or the embedded bytes, into
  `PaimonFileIndex` (`model/PaimonFileIndex.kt`), decoded on first use as
  `PaimonDataFileNode.fileIndex`. Only `bloom-filter` is read: a 4-byte hash-function count and
  `BloomFilter64`'s bit set, probed Kirsch–Mitzenmacher-style over one 64-bit hash — and **the
  hash is `FastHash`'s, two functions by type**: xxHash64 with seed 0 over a string's bytes
  (`xxHash64`, written from the published algorithm and held to its vectors) and Thomas Wang's
  64-bit integer hash over anything numeric widened to a long, a date as its epoch day, a time
  as its milliseconds of the day, a float as its bits, and a timestamp of either kind as its
  microseconds since the epoch — its milliseconds at precision 3 and below, which no writer in
  the corpus reaches, so that half is the source's word only. `ft` holds the temporal hashes to
  the plan: three values a column may be held, and `ts = 2024-03-07 00:00:00` inside a file
  whose bound is `…00:00:00.000001` is skipped, which a hash over milliseconds would not see. A
  zone-less literal against a `WITH LOCAL TIME ZONE` column is read at UTC, the convention
  `compareValues` already applies to the bounds. `paimonFastHash` answers null for `BOOLEAN`,
  `DECIMAL` and the nested types, which the writer refuses to index. `applyPaimonFileIndex` (`model/PaimonFileIndexPruning.kt`)
  then marks every `=` the filter rules out as proved — `IN` is a disjunction of them by then —
  and re-folds the verdict; an index the writer left empty is a skip for any equality, as
  `EmptyFileIndexReader` reads it. **When it is asked is the finding, and it is not "always".**
  An append table's scan tests the embedded index as it plans (`AppendOnlyFileStoreScan.filterByStats`)
  and its read opens the `.index` file (`RawFileSplitRead.createFileReader` through
  `FileIndexEvaluator`), so a file the latter rules out is in the plan and yields no row — the
  index was read, the data was not, and the row says `skipped when read, not when planned`. A
  primary-key table's scan tests an embedded index only under deletion vectors
  (`KeyValueFileStore.newScan` passes `fileIndexReadEnabled && deletionVectorsEnabled`), and its
  read consults either index only on a split it reads raw: `paimonRawConvertible` follows
  `MergeTreeSplitGenerator.splitForBatch` — every file when all are above level 0 without `-D`
  rows under deletion vectors, `first-row` or one level; otherwise sections of intersecting key
  ranges (`IntervalPartition`) **packed into `source.split.target-size` splits at
  `source.split.open-file-cost` each**, a split raw only when it holds one file. Packed, not
  sectioned: `fi`'s two level-0 files share no key and still read as one merge split, because two
  small sections fit one 128 MiB split, so `v = 'dog'` reads both and their indexes are never
  opened — the file says `its file index is not consulted` — while `k = 4 AND v = 'dog'` leaves
  one alone, raw, and its embedded filter skips it when read. `fa` is the append twin, written
  for this: bloom filters on `k` and `v` at 1,000 items (a 1,290-byte `.index`) then 100
  (embedded), and a 43-byte value so xxHash64's 32-byte stripe is walked. The oracle is
  `paimon-scan-plans.scala` twice over — the plan's files, and `FileIndexPredicate` over each
  kept file's index with the split's `rawConvertible()` — and `PaimonFileIndexPruningTest` holds
  eleven filters to what a read reads rows from: the plan's files less those a raw read's index
  rules out. Two mutations were run and caught: swapping the hash halves in the probe (a present
  value read as absent), and deciding raw-ness per section rather than per split (`fi`'s files
  skipped where Paimon merges them). The section's headline counts index skips apart from bound
  skips, and states the rule once above the file table where a drawn file carries an index.
  **The `bitmap` index is read too, and it is a dictionary** (`model/PaimonBitmapIndex.kt`): one
  Roaring bitmap per distinct value and one for null, so `=` is answered exactly rather than as a
  possibility, and `<>`, `IS NULL` and `IS NOT NULL` are answered as `BitmapFileIndex.Reader`
  answers them — a `<>` is the value's rows flipped over the row count, empty only when every
  row holds the value, nulls counted among the rows. The v2 layout (the default) is a directory
  of blocks sorted by the type's comparator and read block by block, `BinaryString`'s unsigned
  byte order for a string; an offset below zero is one row, `-1 - row`, written in place of a
  bitmap. A column with several indexes is ruled out by any of them, which is
  `FileIndexPredicate` and-ing their results. `fb` is the fixture — red/green/null, all red, all
  null, one embedded and two `.index` files, `n` under both a bitmap and a bloom filter — and
  its oracle is `FileIndexPredicate` asked for *every* file (`indexAll` in the script), because
  the plan settles most negatives by the statistics first and only `c = 'orange'`, inside file
  1's bounds, is the dictionary's own skip. **Which is where the two formats' file stages were
  found to differ**: a column that is null in every row satisfies no comparison, and both
  `InclusiveMetricsEvaluator.containsNullsOnly` and Paimon's `NullFalseLeafBinaryFunction` skip
  the file for `=`, `<`, `<=`, `>`, `>=` and a prefix — now the shared stage's rule — while for
  `<>` Paimon skips it and Iceberg's `notEq` answers "might match" before it looks, so
  `paimonAllNullNegations` adds Paimon's reading to Paimon's files only, and the shared reason
  says which format does what
- **A consumer is why an expiry stopped short, and it is one JSON file.** `consumer/consumer-<id>`
  holds `nextSnapshot`, the snapshot a streaming reader will consume next, and
  `expire_snapshots` keeps that snapshot and everything after it — the `cs` fixture asked for
  `retain_max = 1` on three commits and got two snapshots back. `PaimonUnifiedTableModel.consumers`
  reads them, `TableSummary.consumers` lists them (null on Iceberg, empty on a Paimon table with
  no `consumer/`) with whether `snapshot/` still holds the bookmarked snapshot — a reader that
  has fallen behind an expiry is the row that leads — and the referenced-files walk covers the
  whole table directory now that nothing under it is unread.
- **A branch is another line of commits over the same manifests and data, not a nested table.**
  `branch/branch-<name>/` holds its own `snapshot/`, `schema/` and `tag/` and **no `manifest/`**:
  the `br` fixture settled that a commit to a branch writes its manifests into the table's
  `manifest/` and its data file into the same bucket directory main writes to, and only the
  snapshot file lands under `branch/`. That is why `PaimonUnifiedBranch` is read by the same
  `readPaimonBranch` main is — metadata root and table root are one directory for main and two
  for a branch — through the one `PaimonManifestCache`, and why the referenced-files walk has to
  follow branches: before it did, the branch's data file was the table's one "orphan", in the
  direction that gets a file deleted. **Snapshot ids are per branch.** A branch created from a tag
  starts with that snapshot (and the tag) copied verbatim and numbers on from it, so main and
  `dev` both hold a `snapshot-2` that are different commits; the node ids carry the branch
  (`psnap_dev_2`, `pml_dev_2_delta`) and main's stay unqualified, and the copied snapshot hangs
  the *same* manifest node main's does because the cache keyed it by file name. A branch created
  empty has a `schema/` and no `snapshot/` at all, which is its normal state and not a read
  error. On the canvas each branch takes a column of its own through `paimonSnapshotTracks` —
  main in track 0, branches in name order — under the same `spreadSnapshotBranches` shift the
  Iceberg columns use, and `snapshotColumns` names the columns from `columnLabelsOf`: `main` for
  the table's own `snapshot/`, because Paimon records no refs and a directory is the only branch
  fact it has. `TableSummary.branches` is null on Iceberg (refs live on the metadata node) and an
  empty list on a Paimon table with no `branch/`, so the panel says "no branches" only where the
  format keeps them there. The change fingerprint stats every branch's three directories and
  `tag/`, or a branch commit — which touches nothing at the table root — never reloads the table.

### Extending for new table formats
All format-specific models implement the `FormatTableModel` sealed interface.
The `sealed` keyword ensures the compiler flags every `when` that needs a new case.

1. Add enum value to `TableFormat` and detection to `TableFormatDetector`
2. Create `@Serializable` schema data classes (e.g., `DeltaSchema.kt`)
3. Create reader (reuse `AvroReader.readAvro<T>()` for Avro files)
4. Create unified model implementing `FormatTableModel` (`*UnifiedModel.kt`)
5. Create graph builder returning `GraphBuildResult` (`*GraphBuilder.kt`)
6. Add `when` case in `GraphLayoutService.layoutGraph()` (single dispatch point)
7. Add `when` case in `AppState.loadTableModel()` (single dispatch point)
8. Add `GraphNode` subtypes to `GraphTypes.kt`, and give each one an `AggregationKind` in
   `aggregationKind()` — a node type with no kind is never aggregated, which is right for errors
   and wrong for anything that fans out
9. Add node rendering to `NodeComponents.kt`, and a panel in the `*NodePanels.kt` file for its format, dispatched from `NodeDetailsContent`
10. Add post-processing comparators to `GraphLayoutService.enforceChronologicalVerticalOrder()`
11. Add alignment/overlap layers to `alignParentsWithChildren()` / `preventOverlaps()`
12. Add format badge to `Sidebar.kt`

Steps 1-7 are clean single-point changes. Steps 8-12 require adding `when` cases (compiler-enforced via sealed types).

## The app icon

`tools/icon/GenerateIcon.java` draws the mark and writes `desktop/src/main/resources/icon/`:
seven PNG sizes, plus `icon.icns` and `icon.ico`. Run it from the repo root
(`java tools/icon/GenerateIcon.java desktop/src/main/resources/icon`) after changing the drawing;
**the outputs are committed**, because a packaging step that draws its own icon is a step that can
fail on a machine nobody has tested it on. Both containers are written by hand rather than by
`iconutil` or an image library — each is a header plus one typed chunk per size, and `iconutil`
exists only on macOS, which would leave the Windows icon regenerable on a Mac and nowhere else.
`build.gradle.kts` points each platform at its own container; `Main.kt` sets the *running* window's
icon separately, because jpackage stamps the bundle and a `Window` with no icon falls back to the
toolkit's default duke. Every dimension in the drawing is a fraction of the canvas, so 16px and
1024px are the same drawing rather than two that resemble each other.

## Related documentation

- `docs/ARCHITECTURE.md` — layer diagram, data flow, threading model, extension points
- `TODO.md` — bugs, feature ideas, infrastructure tasks
- `CHANGELOG.md` — version history
- `REVIEW.md` — current code-review backlog with status per finding
