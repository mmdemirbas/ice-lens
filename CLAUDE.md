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
│   ├── GraphTypes.kt          # Point, GraphModel (nodeById, layoutPositions), GraphNode (sealed incl. Paimon types), GraphEdge, TableSummary/ContentStats
│   ├── IcebergTypes.kt        # Iceberg type model + parser (field-id → type, from a manifest's own schema)
│   ├── SingleValueDecoder.kt  # Appendix D: bytes + type → DecodedValue (bounds, partition values)
│   ├── SnapshotFilter.kt      # Snapshot filter options and graph filtering (pure graph work — core, not UI)
│   └── WorkspaceTypes.kt      # WorkspaceItem sealed class (Warehouse / SingleTable), serialization
├── service/
│   ├── AvroReader.kt          # Shared Avro file reader (reified readAvro<T>), used by both Iceberg and Paimon
│   ├── IcebergReader.kt       # Iceberg JSON/Avro reading (delegates Avro to AvroReader)
│   ├── PaimonReader.kt        # Paimon JSON snapshot/schema + Avro manifest list/manifest reading
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet, ORC, Avro — max 50)
│   ├── IcebergGraphBuilder.kt # Iceberg-specific graph construction: UnifiedTableModel → nodes + edges
│   ├── PaimonGraphBuilder.kt  # Paimon-specific graph construction: PaimonUnifiedTableModel → nodes + edges
│   ├── GraphLayoutService.kt  # Format-agnostic ELK layout + post-processing (ordering, alignment, overlap prevention)
│   └── TableFormatDetector.kt # Directory-based table format detection (Iceberg / Paimon / Unknown)

desktop/src/main/kotlin/
├── Main.kt                    # Entry point, window state persistence (multi-monitor aware)
└── ui/
    ├── AppState.kt            # Business logic: workspace mgmt, table loading, caching, snapshot filter (testable, no UI)
    ├── App.kt                 # Thin UI layer — layout, keyboard shortcuts, LaunchedEffects (delegates to AppState)
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
- Graph layout flow: `FormatTableModel` → `GraphLayoutService.layoutGraph()` dispatches to format-specific builder → `GraphBuildResult` → `layoutNodes()` → `GraphModel` → `GraphCanvas`
- `GraphModel.nodeById` provides a lazy `Map<String, GraphNode>` — use it instead of `nodes.find`/`nodes.associateBy`
- File paths are resolved relative to the metadata directory using `resolveForceRelative()`
- Workspace serialization uses `W|path` / `T|path` items joined by `;`. The path component
  percent-encodes `%`, `;`, and `|` so paths containing those characters round-trip safely.
- `normalizeFilePath` handles `file:` URIs (including `file://host/path` authority,
  reconstructed UNC-style as `//host/path`), Windows backslashes, UNC paths, and passes
  through cloud URIs (`s3://`, `hdfs://`, `gs://`, `abfs://`, …) as-is
- `loadRequestId` is an `AtomicLong`; the cache-hit branch in `loadTable` also bumps it so
  any in-flight load/reapply coroutine fails its staleness check and bails out
- **Summary figures are always deduplicated.** Both formats share structure on purpose (one
  manifest is referenced by every snapshot that carries its files forward; every Iceberg
  snapshot is re-listed in every later metadata.json), so a per-visit counter multiplies with
  commit history. `TableSummary` carries two `ContentStats`: `current` (manifest closure of
  `current-snapshot-id`, live entries only) and `history` (everything reachable from any
  retained snapshot, deduplicated by manifest and data-file path). `manifestEntryCount` is
  status-blind in both — it measures scan cost — while file/record/byte totals cover live
  entries only. Delete-file `record_count` goes to `deleteRecordCount`, never `recordCount`.
- Spec constants live in `IcebergSchema.kt` (`ManifestContent`, `ManifestEntryStatus`,
  `DataFileContent`) and `PaimonSchema.kt` (`PaimonEntryKind`). Prefer them over 0/1/2 literals
- `versionHint` is nullable — `version-hint.text` exists only for HadoopCatalog/HadoopTables
  tables, so absence is normal and must not be reported as a read error
- Graph builders cap child nodes per manifest (`MAX_FILES_PER_MANIFEST`), but `ManifestNode`
  and `PaimonManifestNode` carry *every* entry in `entries` with `shownEntryCount` recording
  how many the graph drew. The inspector lists all of them; cards disclose the cap. Never add
  a cap that isn't visible in the UI
- `formatCount` / `formatBytes` / `formatBytesExact` live in `ui/FormatUtils.kt` — do not add
  private copies to a UI file. Byte units are binary and labelled as such (KiB, not KB)

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

Edge IDs: `e_table_*`, `e_snap_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`.

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

1. **`App.kt` is ~1k lines** — toolbar still inline; business logic already extracted to `AppState.kt`. Toolbar extraction tracked in `TODO.md`.
2. **`@Suppress("DEPRECATION")` on avro4k** — `decodeFromGenericData` API may change

## Testing

```bash
./gradlew test                                # All tests
./gradlew :core:test --tests "*.PerformanceTest"   # Specific test class
./gradlew :core:test --tests "*.IcebergPathsTest"  # Specific test class
```

~340 tests across 27 files (259 in :core, 81 in :desktop) covering full pipelines for both formats (Avro fixtures
written at runtime via `avro4k`), error recovery, layout post-processing, AppState
lifecycle, snapshot filter behaviour for both formats, and `SampleRowReader` with real
Parquet files. Paimon end-to-end fixtures live in `core/src/test/resources/paimon-fixtures/`.

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
8. Add `GraphNode` subtypes to `GraphTypes.kt`
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
