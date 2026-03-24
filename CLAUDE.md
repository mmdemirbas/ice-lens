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

```
src/main/kotlin/
├── Main.kt                    # Entry point, window state persistence (multi-monitor aware)
├── model/
│   ├── IcebergSchema.kt       # @Serializable Iceberg data classes (metadata, snapshot, manifest, data file)
│   ├── IcebergPaths.kt        # Shared path utilities (normalizeFilePath, metadataVersionFromFileName)
│   ├── UnifiedModel.kt        # Aggregated data layer — reads & links all Iceberg artifacts into a tree
│   ├── PaimonSchema.kt        # @Serializable Paimon data classes (snapshot, schema, manifest list, manifest entry)
│   ├── PaimonUnifiedModel.kt  # Aggregated Paimon data layer — reads & links snapshots, schemas, manifests
│   ├── GraphTypes.kt          # GraphModel (with nodeById), GraphNode (sealed incl. Paimon types), GraphEdge
│   ├── WorkspaceTypes.kt      # WorkspaceItem sealed class (Warehouse / SingleTable), serialization
│   └── ToolWindowTypes.kt     # ToolWindowAnchor enum, ToolWindowConfig
├── service/
│   ├── AvroReader.kt          # Shared Avro file reader (reified readAvro<T>), used by both Iceberg and Paimon
│   ├── IcebergReader.kt       # Iceberg JSON/Avro reading (delegates Avro to AvroReader)
│   ├── PaimonReader.kt        # Paimon JSON snapshot/schema + Avro manifest list/manifest reading
│   ├── SampleRowReader.kt     # DuckDB JDBC queries for sample rows (Parquet, ORC, Avro — max 50)
│   ├── IcebergGraphBuilder.kt # Iceberg-specific graph construction: UnifiedTableModel → nodes + edges
│   ├── PaimonGraphBuilder.kt  # Paimon-specific graph construction: PaimonUnifiedTableModel → nodes + edges
│   ├── GraphLayoutService.kt  # Format-agnostic ELK layout + post-processing (ordering, alignment, overlap prevention)
│   └── TableFormatDetector.kt # Directory-based table format detection (Iceberg / Paimon / Unknown)
└── ui/
    ├── App.kt                 # Main composable — app state, layout orchestration, keyboard shortcuts
    ├── AboutDialog.kt         # About dialog with version info, diagnostics, cheat sheet
    ├── Theme.kt               # Color schemes, dark surface detection, selection highlight
    ├── CommonComponents.kt    # Reusable widgets: draggable dividers, toolbar group/icon button
    ├── FormatUtils.kt         # Timestamp formatting, long set serialization
    ├── WorkspaceUtils.kt      # Table detection (via TableFormatDetector for Iceberg/Paimon), workspace dedup, table scanning
    ├── SnapshotFilter.kt      # Snapshot filter data model and graph filtering logic
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
./gradlew packageDmg   # macOS installer
./gradlew packageMsi   # Windows installer
./gradlew packageDeb   # Linux installer
```

## Key conventions

- State is persisted via `java.util.prefs.Preferences` under `com.github.mmdemirbas.icelens`
- All data access is read-only — no table modifications
- Node colors are hardcoded per node type in `NodeComponents.kt` (`getGraphNodeColor` / `getGraphNodeBorderColor`)
- Dark mode detection uses `perceivedBrightness()` (0.2126R + 0.7152G + 0.0722B < 0.5)
- Graph layout flow (Iceberg): `UnifiedTableModel` → `IcebergGraphBuilder.buildGraph()` → nodes/edges → `GraphLayoutService.layoutNodes()` → `GraphModel` → `GraphCanvas`
- Graph layout flow (Paimon): `PaimonUnifiedTableModel` → `PaimonGraphBuilder.buildGraph()` → nodes/edges → `GraphLayoutService.layoutNodes()` → `GraphModel` → `GraphCanvas`
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

1. **`App.kt` is ~1500 lines** — toolbar and `AppState` class still inline
2. **No integration tests** — no Avro/JSON fixture files exist in `src/test/resources/`
3. **`@Suppress("DEPRECATION")` on avro4k** — `decodeFromGenericData` API may change

## Testing

```bash
./gradlew test                                # All tests
./gradlew test --tests "*.PerformanceTest"    # Specific test class
./gradlew test --tests "*.IcebergPathsTest"   # Specific test class
```

100+ tests across 16 files. Gaps: no tests for `IcebergReader`, `UnifiedModel`, or end-to-end integration.

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

### Extending for new table formats
- Add new `GraphNode` subtypes to `GraphTypes.kt`
- Create reader (reuse `AvroReader.readAvro<T>()` for Avro files)
- Create unified model (`*UnifiedModel.kt`) and graph builder (`*GraphBuilder.kt`)
- Add format detection to `TableFormatDetector`
- Add node rendering to `NodeComponents.kt` and `NodeDetails.kt`
- Add post-processing cases to `GraphLayoutService` (ordering, alignment, overlap prevention)
- Update `App.kt` to handle the new format in layout calls

## Related documentation

- `docs/ARCHITECTURE.md` — layer diagram, data flow, threading model, extension points
- `docs/REVIEW_CHECKLIST.md` — 8 structured code review rounds
- `TODO.md` — bugs, feature ideas, infrastructure tasks
- `CHANGELOG.md` — version history
