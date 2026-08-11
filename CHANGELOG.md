# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
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
