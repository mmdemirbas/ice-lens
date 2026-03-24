# Architecture

## System overview

ice-lens is a single-module Kotlin Compose Desktop application. All code lives in one Gradle module under `src/main/kotlin/` with three packages: `model`, `service`, and `ui`, plus the `app` package for the entry point.

## Data flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Filesystem                                                             │
│  ├── metadata/*.metadata.json    (Iceberg table metadata)               │
│  ├── metadata/*.avro             (manifest lists, manifests)            │
│  └── data/**/*.parquet           (data/delete files)                    │
└──────────────┬──────────────────────────────────────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  PARSING LAYER                           │
│                                          │
│  IcebergReader.readTableMetadata()       │  ← JSON → TableMetadata
│  IcebergReader.readManifestList()        │  ← Avro → ManifestListEntry
│  IcebergReader.readManifestFile()        │  ← Avro → ManifestEntry
│  ParquetReader.queryParquet()            │  ← DuckDB → Map<String, Any>
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  MODEL LAYER                             │
│                                          │
│  UnifiedTableModel()                     │  Walks metadata dir, parses all files
│    └── UnifiedSnapshot()                 │  Resolves manifest list paths
│          └── UnifiedManifest()           │  Resolves data file paths
│                └── UnifiedDataFile       │  Lazy row loading via ParquetReader
│                                          │
│  Data classes:                           │
│  UnifiedWarehouseModel                   │
│  UnifiedTableModel                       │
│  UnifiedMetadata                         │
│  UnifiedSnapshot                         │
│  UnifiedManifest                         │
│  UnifiedDataFile                         │
│  UnifiedRow                              │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  GRAPH CONSTRUCTION (format-specific)    │
│                                          │
│  IcebergGraphBuilder.buildGraph()        │  ← Iceberg model → nodes + edges
│    - Creates GraphNode/GraphEdge list    │
│    - Builds TableSummary statistics      │
│    - Manages node ID registry            │
│                                          │
│  (Future: PaimonGraphBuilder)            │
└──────────────┬───────────────────────────┘
               │
               ▼
┌──────────────────────────────────────────┐
│  LAYOUT ENGINE (format-agnostic)         │
│                                          │
│  GraphLayoutService.layoutNodes()        │
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
├── TableNode          Root node per table
├── MetadataNode       One per metadata.json file
├── SnapshotNode       One per snapshot
├── ManifestNode       One per manifest file (data or delete)
├── FileNode           One per data/delete file entry
├── RowNode            One per sample row (lazy-loaded)
└── ErrorNode          One per read error (inline in graph)
```

Graph edges are directional (parent → child) following the Iceberg metadata tree:
```
TableNode → MetadataNode → SnapshotNode → ManifestNode → FileNode → RowNode
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

Serialized to preferences as `W|/path` or `T|/path`, separated by `;`.

Detection logic (via `TableFormatDetector`):
- `TableFormat.ICEBERG`: has `metadata/` subdirectory with `*.metadata.json`
- `TableFormat.UNKNOWN`: no recognized markers (Paimon detection not yet implemented)
- Warehouse: contains directories detected as tables

## Extension points for new formats

### Format detection
`TableFormatDetector.kt` — add new `TableFormat` enum values and detection logic:
- Iceberg: `metadata/` with `*.metadata.json` (implemented)
- Paimon (future): `snapshot/` + `schema/` directories

### Reader layer
- `IcebergReader.readAvro<T>()` is already generic — works for any Avro file if you provide matching `@Serializable` data classes
- New format: add `PaimonReader` with Paimon-specific Avro schemas and JSON parsing

### Model layer
- `UnifiedModel.kt` functions (`UnifiedTableModel()`, `UnifiedSnapshot()`, etc.) are Iceberg-specific factory functions
- New format: create parallel `PaimonUnifiedModel.kt` or refactor to a `TableFormatLoader` interface

### Graph construction
- `IcebergGraphBuilder` handles Iceberg-specific graph construction (nodes + edges)
- `GraphLayoutService.layoutNodes()` is format-agnostic — accepts any list of nodes + edges
- To add Paimon: create `PaimonGraphBuilder` following `IcebergGraphBuilder` pattern
- Layout engine + post-processing are shared code

### UI rendering
- `NodeComponents.kt` — add rendering for new node subtypes (match on sealed class)
- `NodeDetails.kt` — add inspector panels for new node types
- `Theme.kt` — add colors/badges for new concepts (LSM levels, commit kinds)

## File format handling

| File type | Reader | Library |
|-----------|--------|---------|
| `.metadata.json` | `IcebergReader.readTableMetadata()` | `kotlinx-serialization-json` |
| `.avro` (manifest list) | `IcebergReader.readManifestList()` | `avro4k` + Apache Avro |
| `.avro` (manifest) | `IcebergReader.readManifestFile()` | `avro4k` + Apache Avro |
| `.parquet`/`.orc`/`.avro` (data/delete) | `SampleRowReader.querySampleRows()` | DuckDB JDBC |

Avro deserialization: `avro4k` maps `@Serializable` Kotlin data classes to Avro schemas via `Avro.schema<T>()`, then `Avro.decodeFromGenericData()` converts `GenericRecord` → typed instance. The `readAvro<T>()` function is fully reified and reusable for any Avro schema.

## Build system

Single-module Gradle build with:
- Kotlin JVM plugin
- JetBrains Compose plugin
- Kotlin Serialization plugin
- Version catalog (`gradle/libs.versions.toml`)
- ProGuard for release builds (currently: no shrink, no optimize, no obfuscate — just rule scaffolding)
- Native distribution targets: `.dmg` (macOS), `.msi` (Windows), `.deb` (Linux)