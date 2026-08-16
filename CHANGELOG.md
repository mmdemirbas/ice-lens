# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
