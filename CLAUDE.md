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
```

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
├── model/
│   ├── IcebergSchema.kt       # @Serializable Iceberg data classes (metadata, snapshot, manifest, data file)
│   ├── IcebergPaths.kt        # Shared path utilities (normalizeFilePath, metadataVersionFromFileName)
│   ├── UnifiedModel.kt        # Aggregated data layer — reads & links all Iceberg artifacts into a tree
│   ├── PaimonSchema.kt        # @Serializable Paimon data classes (snapshot, schema, manifest list, manifest entry)
│   ├── PaimonUnifiedModel.kt  # Aggregated Paimon data layer — reads & links snapshots, schemas, manifests
│   ├── GraphTypes.kt          # Point, GraphModel (nodeById, layoutPositions, groups), GraphNode (sealed incl. Paimon + GroupNode), AggregationKind, GraphEdge, TableSummary/ContentStats
│   ├── IcebergTypes.kt        # Iceberg type model + parser (field-id → type, from a manifest's own schema)
│   ├── SingleValueDecoder.kt  # Appendix D: bytes + type → DecodedValue (bounds, partition values)
│   ├── PartitionDecoder.kt    # partition-spec parsing, transform result types, DecodedPartition
│   ├── SnapshotFilter.kt      # Snapshot filter options and graph filtering (pure graph work — core, not UI)
│   ├── ManifestTally.kt       # manifest_file's six counts against the same figures folded from its entries
│   ├── ManifestLedger.kt      # Per-entry: what it added to a manifest's figures, or which rule dropped it
│   ├── ScanPruning.kt         # Predicate → which manifests a scan would skip, and which term did it
│   ├── GraphNavigation.kt     # Arrow keys → the next node, decided from where the nodes are drawn
│   └── WorkspaceTypes.kt      # WorkspaceItem sealed class (Warehouse / SingleTable), serialization
├── service/
│   ├── AvroReader.kt          # Shared Avro file reader (reified readAvro<T>), used by both Iceberg and Paimon
│   ├── IcebergReader.kt       # Iceberg JSON/Avro reading (delegates Avro to AvroReader)
│   ├── PaimonReader.kt        # Paimon JSON snapshot/schema + Avro manifest list/manifest reading
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet, ORC, Avro — max 50)
│   ├── IcebergGraphBuilder.kt # Iceberg-specific graph construction: UnifiedTableModel → nodes + edges
│   ├── PaimonGraphBuilder.kt  # Paimon-specific graph construction: PaimonUnifiedTableModel → nodes + edges
│   ├── GraphAggregation.kt    # Format-agnostic: long sibling runs → one expandable GroupNode
│   ├── SiblingOrder.kt        # One order per kind — read by layout AND by aggregation
│   ├── SnapshotTracks.kt      # Which column each snapshot draws in, so a fork reads as a fork
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
    ├── Sidebar.kt             # Workspace panel — add/remove roots, search, drag-to-reorder, format badges (ICE/PMN)
    ├── NavigationTree.kt      # Structure tree view — flatten graph, search, expand/collapse
    ├── ScanPruningSection.kt  # The filter form and its per-manifest verdicts, in the table inspector
    ├── GraphStatusBadge.kt    # Canvas overlay: how much of the table is drawn, and the page size
    └── ToolWindow.kt          # Draggable tool window bars and panes
```

## Build & run

```bash
./gradlew run          # Run the application
./gradlew build        # Build
./gradlew :desktop:packageDmg   # macOS installer
./gradlew :desktop:packageMsi   # Windows installer
./gradlew :desktop:packageDeb   # Linux installer
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
- **A recorded figure is shown against the same figure counted.** `manifestTallies` in
  `model/ManifestTally.kt` puts each of `manifest_file`'s six counts beside what the manifest's
  own entries add up to. A scan trusts those counts without opening the manifest and nothing on
  the read path checks them, so the inspector does. It is also the suite's only assertion that
  compares what this code decoded against what Iceberg recorded about the same bytes — a status
  misread or an entry dropped shows up as a disagreement on a checked-in table
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
- **The page size is a setting, and two things travel with a change of it.** `AppState.graphPageSize`
  is persisted and feeds `AggregationPolicy`; `GraphStatusBadge` on the canvas both states the
  figures and offers the choices. Changing it clears `expandedGroupIds` — a group id names a page
  *at a size*, so the same id means a different set of siblings at a different one — and evicts
  every cached session but the one on screen, which is rebuilt from its retained table model.
  Restoring a graph drawn at another page size puts a drawing on screen that disagrees with the
  badge above it
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
  zero, and a probe reporting 0dp reads as a card that fits
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
- **Arrow-key navigation is defined against where the nodes are drawn**, not against a
  comparator. `model/GraphNavigation.kt` takes a `positionOf` lambda rather than reading
  `layoutPositions`, so the canvas passes its own `NodePositions` and a reader who has dragged a
  node navigates the drawing they made. Left and right follow the edges to the parent or child
  whose vertical centre is nearest; up and down move to the nearest node whose horizontal span
  **overlaps** yours, which is the column as the eye reads it and is what keeps a walk down the
  main line out of a branch column drawn at a similar height. Only structural edges are followed
  — an `isSibling` edge joins two nodes at one depth, and an `affectsLayout = false` edge is an
  annotation, so neither answers "what contains this"
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
- **Layout post-processing runs ordering, then alignment, then ordering again.** Alignment moves
  a parent to its children's centre, which overrides the order the first pass set — so the
  vertical order of snapshots was decided by ELK's manifest placement until the second pass
  existed. Ordering is a constraint and alignment a preference; the constraint goes last. Any new
  post-processing step needs placing against that rule
- **`manifest_file.partitions` pairs with the partition spec positionally** — the summaries
  carry no field ids. A spec of a different length decodes to nothing rather than mislabelling
  fields, because a bound attributed to the wrong partition field reads as an answer
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
- **`WideTable` column order is load-bearing, and so are its widths.** The inspector panel is
  far narrower than the table, and the reader sees the leftmost columns and nothing else until
  they scroll — so the answer goes first and identifiers follow it. Pass `columnWidths`: one
  width for every column spends the panel on the narrow ones and truncates the wide ones. The
  scrollbar appears only when the content overflows, and it is the only signal that more columns
  exist — do not remove it

## Known quirks

- Shared UI utilities (dark surface detection, selection highlight color) live in `ui/Theme.kt`
- Shared path utilities (`normalizeFilePath`, `metadataVersionFromFileName`) live in `model/IcebergPaths.kt`
- Node card text colors (`NodeCardTextPrimary`, `NodeCardTextSecondary`) are hardcoded light-mode colors, not theme-aware
- `GraphNode.x`/`y` are plain `var Double` used during layout only; UI reads from `GraphModel.positions` (Compose-observable `mutableStateMapOf`)
- `GraphModel.initialPositions` (immutable Map) is thread-safe for background layout; `positions` must only be written on the main thread
- `SampleRowReader` uses a `synchronized` lock for DuckDB connection safety across threads
- `sessionCache` is a 5-entry LRU (`Collections.synchronizedMap` over a `LinkedHashMap`
  with `removeEldestEntry`) — bounds memory across many table switches
- Dependencies are managed via Gradle version catalog (`gradle/libs.versions.toml`)
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

~496 tests across 52 files (383 in :core, 113 in :desktop) covering full pipelines for both formats (Avro fixtures
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
every panel is not inside a `Section` and so does not fold, which no assertion was going to say.

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

## Related documentation

- `docs/ARCHITECTURE.md` — layer diagram, data flow, threading model, extension points
- `TODO.md` — bugs, feature ideas, infrastructure tasks
- `CHANGELOG.md` — version history
- `REVIEW.md` — current code-review backlog with status per finding
