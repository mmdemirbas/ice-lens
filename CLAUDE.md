# CLAUDE.md

## Project overview

**Iceberg Lens** is a read-only desktop application for inspecting Apache Iceberg and Apache Paimon table structure from local filesystems. It renders an interactive graph visualization (table → metadata → snapshots → manifests → data files → sample rows) alongside a detailed inspector panel.

## Tech stack

- **Kotlin 2.3.10** + **JetBrains Compose Desktop 1.10.1** + **Material3**
- **Apache Avro 1.12.1** / **Avro4k 2.10.0** — manifest list & manifest file deserialization
- **DuckDB JDBC 1.4.4.0** — Parquet/ORC/Avro sample row queries
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
│   ├── PartitionDecoder.kt    # partition-spec parsing, transform result types, DecodedPartition
│   ├── BucketTransform.kt     # Iceberg's bucket[N], via the same Guava murmur3 the writer uses
│   ├── SnapshotDiff.kt        # Two snapshots' live file sets, and the set difference between them
│   ├── PaimonReplay.kt        # Paimon's delta-over-base replay: per-manifest figures, the file set, and a per-entry trace — one walk
│   ├── SnapshotFilter.kt      # Snapshot filter options and graph filtering (pure graph work — core, not UI)
│   ├── ManifestTally.kt       # manifest_file's six counts against the same figures folded from its entries
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
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet, ORC, Avro — max 50)
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
    ├── App.kt                 # Thin UI layer — layout, keyboard shortcuts, LaunchedEffects (delegates to AppState)
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
    ├── NodeDetails.kt         # Inspector panel — detailed metadata, JSON highlighting, changelogs, sample rows
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
  stat and five nodes per data file in the table
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
  so the inspector can say it
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
  is the same idea as `manifestTallies` a level up — `SnapshotChangeTest` checks 81 such pairs
  across eight checked-in tables and is the suite's strongest oracle, because nothing here
  produced any of the summaries. Two things the fixtures settled that a reading of the spec did
  not: **`added-dvs` is a breakdown of `added-delete-files`, not a v3 replacement for it** (one
  vector records both as 1, so summing them double-counts), and **`added-files-size` counts a
  deletion vector's `content_size_in_bytes`, not its Puffin file's size** — one container holds a
  blob per data file it covers, so charging the container once per vector counts the same bytes
  repeatedly
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
  listed, because on any real table they are almost all of it
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
  The colour marks `REPLACED` and `REMOVED_ABSENT` only: adding and removing are both ordinary in a
  compaction, while a rewrite whose record delta is a *difference* and a removal that **found
  nothing to remove** are the two rows invisible in every figure above them
- **A recorded figure is shown against the same figure counted.** `manifestTallies` in
  `model/ManifestTally.kt` puts each of `manifest_file`'s six counts beside what the manifest's
  own entries add up to. A scan trusts those counts without opening the manifest and nothing on
  the read path checks them, so the inspector does. It is also the suite's only assertion that
  compares what this code decoded against what Iceberg recorded about the same bytes — a status
  misread or an entry dropped shows up as a disagreement on a checked-in table
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
  against the label printed on it.
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
  as skipped would credit the wrong term and double-count it against the file stage
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
- Spec constants live in `IcebergSchema.kt` (`ManifestContent`, `ManifestEntryStatus`,
  `DataFileContent`) and `PaimonSchema.kt` (`PaimonEntryKind`). Prefer them over 0/1/2 literals
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
  on the card. Never add a cap that isn't visible in the UI
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
  for one set of things drift the first time a node type is added. It is deliberately not what
  `GraphSearch.searchableText` answers — a label is one line chosen to fit a row, so a manifest
  reads by its add count and cannot be found by its path, which is right for a label and wrong for
  a search. `GraphTree.details` is *not* shared: the desktop inspector is a panel per node kind
  with tallies and drill-downs, and the tool window is a docked strip answering "what am I looking
  at", so a shorter list is the design rather than a subset
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
  the `SeekableByteChannel` that `Files.newByteChannel` returns for *any* filesystem
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
  the right of the snapshots over by what the branch took. It returns before touching a node when
  the highest column is 0, so a linear history draws exactly where it drew before. It also stands
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
- Tests use JUnit 5 via `kotlin-test-junit5`; run with `./gradlew test`

## Node ID conventions

### Iceberg (`IcebergGraphBuilder`)

- `table_root` — the single table root node
- `meta_<filename>` — metadata nodes (e.g., `meta_v1.metadata.json`)
- `snap_<snapshotId>` — snapshot nodes
- `man_<n>` — manifest nodes (incrementing counter, stable per manifest path)
- `file_<manId>_<simpleId>_<index>` — file nodes
- `row_<fId>_<index>` — row nodes
- `err_<seq>_<hash>_<hash>` — error nodes

Edge IDs: `e_table_*`, `e_snap_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`, plus two that
record a relationship without shaping the layout (`affectsLayout = false`): `e_lineage_*` between
snapshots and `e_dv_*` from a v3 deletion vector to the data file its `referenced_data_file`
names. Both run between nodes of one layer, which is exactly why ELK must not see them.

### Paimon (`PaimonGraphBuilder`)

- `table_root` — the single table root node (shared with Iceberg)
- `pschema_<id>` — Paimon schema nodes
- `psnap_<id>` — Paimon snapshot nodes
- `pml_<snapshotId>_<kind>` — manifest list nodes (kind = base/delta/changelog)
- `pman_<n>` — manifest nodes (incrementing counter)
- `pdf_<manId>_<simpleId>_<index>` — data file nodes
- `row_<fId>_<index>` — reuses existing RowNode

Edge IDs: `e_table_*`, `e_schema_*` (sibling), `e_ml_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`.

## Known issues and tech debt

1. **`App.kt` is ~850 lines** — business logic lives in `AppState.kt` and the toolbar in
   `Toolbar.kt`; what is left is the window, the tool-window layout and the `LaunchedEffect`s.
2. **`@Suppress("DEPRECATION")` on avro4k** — `decodeFromGenericData` API may change

## Testing

```bash
./gradlew test                                # All tests
./gradlew :core:test --tests "*.PerformanceTest"   # Specific test class
./gradlew :core:test --tests "*.IcebergPathsTest"  # Specific test class
```

~729 tests across 80 files (521 in :core, 203 in :desktop, 5 in :intellij) covering full pipelines for both formats (Avro fixtures
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
| `default/v3` | `FormatV3FixtureTest` | format-version 3 with deletion vectors |
| `default/evolved` | `SchemaEvolutionFixtureTest` | three manifest schemas — `int`→`long`, `float`→`double`, a rename and a drop |
| `default/respec` | `PartitionSpecEvolutionTest` | two partition specs — dropped, rebucketed, `days`→`months` |
| `default/branched` | `BranchedFixtureTest` | a fork, five refs, ten metadata versions |
| `paimon/db.db/test` | `RealTableFixtureTest` | a real Flink/Paimon table |

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
also on the classpath.

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
9. Add node rendering to `NodeComponents.kt` and `NodeDetails.kt`
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
