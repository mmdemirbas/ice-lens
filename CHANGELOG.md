# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
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
