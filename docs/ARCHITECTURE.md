# Architecture

## System overview

ice-lens is a single-module Kotlin Compose Desktop application. All code lives in one Gradle module under `src/main/kotlin/` with three packages: `model`, `service`, and `ui`, plus the `app` package for the entry point.

## Data flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Filesystem                                                             │
│  ├── metadata/*.metadata.json    (Iceberg table metadata)               │
│  ├── metadata/*.avro             (Iceberg manifest lists, manifests)    │
│  ├── snapshot/snapshot-*         (Paimon snapshot JSON)                 │
│  ├── schema/schema-*             (Paimon schema JSON)                  │
│  ├── manifest/*                  (Paimon manifest list + manifest Avro) │
│  └── data/**/*.parquet|.orc      (data files — shared by both formats)  │
└──────────────┬──────────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  PARSING LAYER                           │
│                                          │
│  AvroReader.readAvro<T>()               │  ← Shared Avro deserialization
│                                          │
│  Iceberg:                                │
│  IcebergReader.readTableMetadata()       │  ← JSON → TableMetadata
│  IcebergReader.readManifestList()        │  ← Avro → ManifestListEntry
│  IcebergReader.readManifestFile()        │  ← Avro → ManifestEntry
│                                          │
│  Paimon:                                 │
│  PaimonReader.readSnapshot()             │  ← JSON → PaimonSnapshot
│  PaimonReader.readSchema()               │  ← JSON → PaimonSchema
│  PaimonReader.readManifestList()         │  ← Avro → PaimonManifestFileMeta
│  PaimonReader.readManifest()             │  ← Avro → PaimonManifestEntry
│                                          │
│  SampleRowReader.querySampleRows()       │  ← DuckDB → Map<String, Any>
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  MODEL LAYER                             │
│                                          │
│  Iceberg:                                │
│  UnifiedTableModel()                     │  Walks metadata dir, parses all files
│    └── UnifiedSnapshot()                 │  Resolves manifest list paths
│          └── UnifiedManifest()           │  Resolves data file paths
│                └── UnifiedDataFile       │  Lazy row loading
│                                          │
│  Paimon:                                 │
│  PaimonUnifiedTableModel()               │  Walks snapshot/ + schema/ dirs
│    └── PaimonUnifiedSnapshot             │  Resolves base/delta/changelog manifests
│          └── PaimonUnifiedManifest       │  Resolves manifest files
│                └── PaimonUnifiedDataFile  │  Lazy row loading
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  GRAPH CONSTRUCTION (format-specific)    │
│                                          │
│  IcebergGraphBuilder.buildGraph()        │  ← Iceberg model → nodes + edges
│  PaimonGraphBuilder.buildGraph()         │  ← Paimon model → nodes + edges
│    - Creates GraphNode/GraphEdge list    │
│    - Builds TableSummary statistics      │
│    - Manages node ID registry            │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  LAYOUT ENGINE (format-agnostic)         │
│                                          │
│  GraphLayoutService.layoutGraph()        │  ← public entry; dispatches by format
│    → format-specific buildGraph()        │
│    → layoutNodes(): generic ELK pass     │
│    1. Create ELK graph from nodes/edges  │  ← generic
│    2. Run ELK layered layout             │  ← generic
│    3. enforceChronologicalVerticalOrder   │  ← generic post-processing
│    4. alignParentsWithChildren            │  ← generic post-processing
│    5. preventOverlaps                     │  ← generic post-processing
│                                          │
│  Output: GraphModel                      │
│    ├── nodes: List<GraphNode>            │
│    ├── edges: List<GraphEdge>            │
│    ├── initialPositions: Map<String,Offset>  │
│    └── positions: mutableStateMapOf      │  ← Compose-observable
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  UI LAYER (Compose Desktop)              │
│                                          │
│  App.kt           State management, toolbar, keyboard shortcuts, layout orchestration
│  AboutDialog.kt   About dialog with version info, diagnostics, cheat sheet
│  GraphCanvas.kt   Zoomable/pannable canvas, node/edge rendering, viewport culling
│  NodeComponents.kt   Per-type node card rendering, copy-to-clipboard
│  NodeDetails.kt      Per-type inspector panel rendering
│  Sidebar.kt          Workspace tree, table selection
│  NavigationTree.kt   Parent/child tree navigation in inspector
│  ToolWindow.kt       Dockable panel framework
│  SnapshotFilter.kt   Snapshot filter model
│  Theme.kt            Color schemes, constants
│  CommonComponents.kt Shared components (section headers, etc.)
│  FormatUtils.kt      Timestamp formatting
│  WorkspaceUtils.kt   Directory scanning (via TableFormatDetector)
└──────────────────────────────────────────┘
```

## Node type hierarchy

```
GraphNode (sealed class)
├── TableNode                Root node per table (shared by both formats)
├── MetadataNode             Iceberg: one per metadata.json file
├── SnapshotNode             Iceberg: one per snapshot
├── ManifestNode             Iceberg: one per manifest file (data or delete)
├── FileNode                 Iceberg: one per data/delete file entry
├── PaimonSnapshotNode       Paimon: one per snapshot (with commitKind)
├── PaimonSchemaNode         Paimon: one per schema version
├── PaimonManifestListNode   Paimon: one per manifest list (base/delta/changelog)
├── PaimonManifestNode       Paimon: one per manifest file
├── PaimonDataFileNode       Paimon: one per data file entry (with LSM level, ADD/DELETE)
├── RowNode                  One per sample row (lazy-loaded, shared by both formats)
└── ErrorNode                One per read error (inline in graph)
```

Iceberg graph edges (parent → child):
```
TableNode → MetadataNode → SnapshotNode → ManifestNode → FileNode → RowNode
```

Paimon graph edges (parent → child):
```
TableNode → PaimonSnapshotNode → PaimonManifestListNode(base|delta|changelog)
                                    → PaimonManifestNode → PaimonDataFileNode → RowNode
           → PaimonSchemaNode (sibling edge from snapshot)
```

## Iceberg metadata tree (what we parse)

```
Table (directory)
└── metadata/
    ├── v1.metadata.json           ← TableMetadata
    │   └── snapshots[]:
    │       └── manifest-list: path ← ManifestListEntry[] (Avro)
    │           └── manifest_path:   ← ManifestEntry[] (Avro)
    │               └── data_file:
    │                   └── file_path ← Parquet/ORC file on disk
    ├── v2.metadata.json
    ├── snap-*.avro                 (manifest list files)
    ├── *.avro                      (manifest files)
    └── version-hint.text           (optional, points to latest metadata version)
```

## Paimon metadata tree (what we parse)

```
Table (directory)
├── snapshot/
│   ├── EARLIEST                   (text file: earliest snapshot number)
│   ├── LATEST                     (text file: latest snapshot number)
│   └── snapshot-N                 ← PaimonSnapshot (JSON)
│       ├── schemaId → schema/schema-N
│       ├── baseManifestList → manifest/manifest-list-*   ← PaimonManifestFileMeta[] (Avro)
│       │                         └── manifest-*          ← PaimonManifestEntry[] (Avro)
│       │                               └── _FILE._FILE_NAME → data file
│       ├── deltaManifestList → manifest/manifest-list-*  (new changes in this snapshot)
│       └── changelogManifestList → manifest/manifest-list-* (optional)
├── schema/
│   └── schema-N                   ← PaimonSchema (JSON)
├── manifest/
│   ├── manifest-list-*            (Avro — manifest list files)
│   └── manifest-*                 (Avro — manifest files)
└── bucket-N/ or [partition=value]/bucket-N/
    └── data-*.orc                 (data files)
```

## Threading model

```
Main thread (Compose UI thread):
  - All Compose state reads/writes
  - GraphModel.positions mutations
  - User interaction handlers

Dispatchers.Default (background):
  - File I/O (reading metadata, manifests, data files)
  - UnifiedModel construction
  - ELK graph layout computation
  - Post-processing (reorder, align, overlap prevention)

Dispatchers.IO:
  - Not explicitly used; DuckDB calls happen inside synchronized block
```

Results are moved from background to main thread via:
```kotlin
withContext(Dispatchers.Default) {
    val graph = GraphLayoutService.layoutGraph(table, showRows)
    withContext(Dispatchers.Main) {
        graphModel = graph
        graph.syncPositionsToUI()
    }
}
```

## Workspace model

The workspace tracks user-added directories:

```
WorkspaceItem (sealed class)
├── Warehouse    Directory containing multiple table subdirectories
└── SingleTable  Directory containing a metadata/ subdirectory
```

Serialized to preferences as `W|/path` or `T|/path`, joined by `;`. Path values percent-encode
`%`, `;`, and `|` so paths containing those characters round-trip safely.

Detection logic (via `TableFormatDetector`):
- `TableFormat.ICEBERG`: has `metadata/` subdirectory with `*.metadata.json`
- `TableFormat.PAIMON`: has both `snapshot/` and `schema/` subdirectories
- `TableFormat.UNKNOWN`: no recognized markers
- Warehouse: contains directories detected as tables (any format)

## Extension points for new formats

To add a new table format (e.g., Delta Lake, Hudi), follow the pattern established by Iceberg and Paimon:

### Format detection
`TableFormatDetector.kt` — add new `TableFormat` enum value and detection logic.

### Reader layer
- `AvroReader.readAvro<T>()` is generic — works for any Avro file with matching `@Serializable` data classes
- Create a `*Reader.kt` with format-specific JSON/Avro parsing (see `IcebergReader`, `PaimonReader`)

### Model layer
- Create `*UnifiedModel.kt` with data classes and factory function (see `UnifiedModel.kt`, `PaimonUnifiedModel.kt`)

### Graph construction
- Create `*GraphBuilder.kt` following the `IcebergGraphBuilder`/`PaimonGraphBuilder` pattern
- `GraphLayoutService.layoutGraph()` dispatches by format and calls back into the format-specific builder; `layoutNodes()` is the format-agnostic ELK pass
- Add post-processing cases (ordering, alignment, overlap) for new node types in `GraphLayoutService`

### UI rendering
- `NodeComponents.kt` — add node card rendering + colors for new `GraphNode` subtypes
- `NodeDetails.kt` — add inspector panels for new node types
- `Sidebar.kt` — add format badge in `WorkspaceRootItem`
- `AppState.loadTableModel()` — single dispatch point for table model construction; add the new format here

## File format handling

| File type | Reader | Library |
|-----------|--------|---------|
| Iceberg `.metadata.json` | `IcebergReader.readTableMetadata()` | `kotlinx-serialization-json` |
| Iceberg `.avro` (manifest list) | `IcebergReader.readManifestList()` | `AvroReader` + `avro4k` |
| Iceberg `.avro` (manifest) | `IcebergReader.readManifestFile()` | `AvroReader` + `avro4k` |
| Paimon `snapshot-*` | `PaimonReader.readSnapshot()` | `kotlinx-serialization-json` |
| Paimon `schema-*` | `PaimonReader.readSchema()` | `kotlinx-serialization-json` |
| Paimon manifest list `.avro` | `PaimonReader.readManifestList()` | `AvroReader` + `avro4k` |
| Paimon manifest `.avro` | `PaimonReader.readManifest()` | `AvroReader` + `avro4k` |
| `.parquet`/`.orc`/`.avro` (data) | `SampleRowReader.querySampleRows()` | DuckDB JDBC |

Avro deserialization: `avro4k` maps `@Serializable` Kotlin data classes to Avro schemas via `Avro.schema<T>()`, then `Avro.decodeFromGenericData()` converts `GenericRecord` → typed instance. The shared `AvroReader.readAvro<T>()` function is fully reified and reusable for any Avro schema.

## Build system

Single-module Gradle build with:
- Kotlin JVM plugin
- JetBrains Compose plugin
- Kotlin Serialization plugin
- Version catalog (`gradle/libs.versions.toml`)
- ProGuard for release builds (currently: no shrink, no optimize, no obfuscate — just rule scaffolding)
- Native distribution targets: `.dmg` (macOS), `.msi` (Windows), `.deb` (Linux)