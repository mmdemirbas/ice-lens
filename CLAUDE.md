# CLAUDE.md

## Project overview

**Iceberg Lens** is a read-only desktop application for inspecting Apache Iceberg table structure from local filesystems. It renders an interactive graph visualization (table → metadata → snapshots → manifests → data files → sample rows) alongside a detailed inspector panel.

## Tech stack

- **Kotlin 2.3.10** + **JetBrains Compose Desktop 1.10.1** + **Material3**
- **Apache Avro 1.12.1** / **Avro4k 2.10.0** — manifest list & manifest file deserialization
- **DuckDB JDBC 1.4.4.0** — Parquet/ORC/Avro sample row queries
- **Eclipse ELK 0.11.0** — layered graph layout engine
- **Kotlinx Serialization 1.10.0** — JSON metadata parsing
- **Gradle 9.0.0** with Kotlin DSL; requires **Java 17+**

## Architecture

```
src/main/kotlin/
├── Main.kt                    # Entry point, window state persistence (multi-monitor aware)
├── model/
│   ├── IcebergSchema.kt       # @Serializable Iceberg data classes (metadata, snapshot, manifest, data file)
│   ├── IcebergPaths.kt        # Shared path utilities (normalizeFilePath, metadataVersionFromFileName)
│   ├── UnifiedModel.kt        # Aggregated data layer — reads & links all Iceberg artifacts into a tree
│   ├── GraphTypes.kt          # GraphModel (with nodeById), GraphNode (sealed), GraphEdge
│   ├── WorkspaceTypes.kt      # WorkspaceItem sealed class (Warehouse / SingleTable), serialization
│   └── ToolWindowTypes.kt     # ToolWindowAnchor enum, ToolWindowConfig
├── service/
│   ├── IcebergReader.kt       # JSON/Avro file reading (readTableMetadata, readManifestList, readManifestFile)
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet, ORC, Avro — max 50)
│   ├── IcebergGraphBuilder.kt # Iceberg-specific graph construction: UnifiedTableModel → nodes + edges
│   ├── GraphLayoutService.kt  # Format-agnostic ELK layout + post-processing (ordering, alignment, overlap prevention)
│   └── TableFormatDetector.kt # Directory-based table format detection (Iceberg / Paimon / Unknown)
└── ui/
    ├── App.kt                 # Main composable — app state, layout orchestration, keyboard shortcuts
    ├── AboutDialog.kt         # About dialog with version info, diagnostics, cheat sheet
    ├── Theme.kt               # Color schemes, dark surface detection, selection highlight
    ├── CommonComponents.kt    # Reusable widgets: draggable dividers, toolbar group/icon button
    ├── FormatUtils.kt         # Timestamp formatting, long set serialization
    ├── WorkspaceUtils.kt      # Iceberg table detection (via TableFormatDetector), workspace dedup, table scanning
    ├── SnapshotFilter.kt      # Snapshot filter data model and graph filtering logic
    ├── GraphCanvas.kt         # Interactive graph: zoom/pan, node selection/drag, marquee, mini-map, viewport culling
    ├── NodeComponents.kt      # Node card composables (Table/Metadata/Snapshot/Manifest/File/Row/Error) + tooltip + copy buttons
    ├── NodeDetails.kt         # Inspector panel — detailed metadata, JSON highlighting, changelogs, sample rows
    ├── Sidebar.kt             # Workspace panel — add/remove roots, search, drag-to-reorder
    ├── NavigationTree.kt      # Structure tree view — flatten graph, search, expand/collapse
    └── ToolWindow.kt          # Draggable tool window bars and panes
```

## Build & run

```bash
./gradlew run          # Run the application
./gradlew build        # Build
./gradlew packageDmg   # macOS installer
./gradlew packageMsi   # Windows installer
./gradlew packageDeb   # Linux installer
```

## Key conventions

- State is persisted via `java.util.prefs.Preferences` under `com.github.mmdemirbas.icelens`
- All data access is read-only — no table modifications
- Node colors are hardcoded per node type in `NodeComponents.kt` (`getGraphNodeColor` / `getGraphNodeBorderColor`)
- Dark mode detection uses `perceivedBrightness()` (0.2126R + 0.7152G + 0.0722B < 0.5)
- Graph layout flow: `UnifiedTableModel` → `IcebergGraphBuilder.buildGraph()` → nodes/edges → `GraphLayoutService.layoutNodes()` → `GraphModel` → `GraphCanvas`
- `GraphModel.nodeById` provides a lazy `Map<String, GraphNode>` — use it instead of `nodes.find`/`nodes.associateBy`
- File paths are resolved relative to the metadata directory using `resolveForceRelative()`
- Workspace serialization uses a simple `W|path` / `T|path` format joined by `;`
- `normalizeFilePath` handles `file:` URIs, Windows backslashes, UNC paths, and passes through cloud URIs as-is

## Known quirks

- `App.kt` is ~1500 lines — `AboutDialog` has been extracted but toolbar and `AppState` remain inline
- Shared UI utilities (dark surface detection, selection highlight color) live in `ui/Theme.kt`
- Shared path utilities (`normalizeFilePath`, `metadataVersionFromFileName`) live in `model/IcebergPaths.kt`
- Node card text colors (`NodeCardTextPrimary`, `NodeCardTextSecondary`) are hardcoded light-mode colors, not theme-aware
- `GraphNode.x`/`y` are plain `var Double` used during layout only; UI reads from `GraphModel.positions` (Compose-observable `mutableStateMapOf`)
- `GraphModel.initialPositions` (immutable Map) is thread-safe for background layout; `positions` must only be written on the main thread
- `SampleRowReader` uses a `synchronized` lock for DuckDB connection safety across threads
- `sessionCache` is a `ConcurrentHashMap` for safe access from coroutines
- Dependencies are managed via Gradle version catalog (`gradle/libs.versions.toml`)
- ProGuard is enabled for release builds with keep rules in `proguard-rules.pro`
- Tests use JUnit 5 via `kotlin-test-junit5`; run with `./gradlew test`

## Node ID conventions

Graph node IDs follow these patterns — important for understanding `IcebergGraphBuilder`:

- `table_root` — the single table root node
- `meta_<filename>` — metadata nodes (e.g., `meta_v1.metadata.json`)
- `snap_<snapshotId>` — snapshot nodes
- `man_<n>` — manifest nodes (incrementing counter, stable per manifest path)
- `file_<manId>_<simpleId>_<index>` — file nodes
- `row_<fId>_<index>` — row nodes
- `err_<seq>_<hash>_<hash>` — error nodes

Edge IDs: `e_table_*`, `e_snap_*`, `e_man_*`, `e_file_*`, `e_row_*`, `e_err_*`.

## Known issues and tech debt

1. **`App.kt` is ~1500 lines** — toolbar and `AppState` class still inline
2. **No integration tests** — no Avro/JSON fixture files exist in `src/test/resources/`
3. **`@Suppress("DEPRECATION")` on avro4k** — `decodeFromGenericData` API may change

## Testing

```bash
./gradlew test                                # All tests
./gradlew test --tests "*.PerformanceTest"    # Specific test class
./gradlew test --tests "*.IcebergPathsTest"   # Specific test class
```

80+ tests across 14 files. Gaps: no tests for `IcebergReader`, `UnifiedModel`, or end-to-end integration.

## Extending for new table formats (Paimon)

### Reusable as-is
- `GraphTypes.kt` — add new `GraphNode` subtypes
- `SampleRowReader.kt` — Paimon data files are also Parquet/ORC
- `IcebergReader.readAvro<T>()` — generic Avro reader, works for any `@Serializable` schema
- `GraphLayoutService.layoutNodes()` — format-agnostic layout engine
- `TableFormatDetector` — add `PAIMON` detection (presence of `snapshot/` + `schema/` dirs)
- All `ui/` files — canvas, inspector, theme, sidebar are format-agnostic

### Needs abstraction
- `UnifiedModel.kt` — currently Iceberg-only factory functions calling `IcebergReader`
- `WorkspaceTypes.kt` — detection already uses `TableFormatDetector`; add Paimon case

### Extension steps
1. `model/PaimonSchema.kt` — Paimon data classes (snapshot JSON, schema JSON, manifest Avro)
2. `service/PaimonReader.kt` — Paimon file parsing
3. `model/PaimonUnifiedModel.kt` — Paimon → unified model bridge
4. Add Paimon node types to `GraphTypes.kt` (e.g., `BucketNode`, `PaimonSnapshotNode`)
5. Implement `PaimonGraphBuilder` (parallel to `IcebergGraphBuilder`)
6. Add `TableFormat.PAIMON` detection to `TableFormatDetector`
7. Add Paimon rendering to `NodeComponents.kt` and `NodeDetails.kt`

## Related documentation

- `docs/ARCHITECTURE.md` — layer diagram, data flow, threading model, extension points
- `docs/REVIEW_CHECKLIST.md` — 8 structured code review rounds
- `TODO.md` — bugs, feature ideas, infrastructure tasks
- `CHANGELOG.md` — version history
