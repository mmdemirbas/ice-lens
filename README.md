# Iceberg Lens

[![CI](https://github.com/mmdemirbas/ice-lens/actions/workflows/ci.yml/badge.svg)](https://github.com/mmdemirbas/ice-lens/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/mmdemirbas/ice-lens)](https://github.com/mmdemirbas/ice-lens/releases)
[![License](https://img.shields.io/github/license/mmdemirbas/ice-lens)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-blue)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose_Desktop-1.10-blue)](https://www.jetbrains.com/lp/compose-multiplatform/)

Read-only desktop UI for inspecting **Apache Iceberg** and **Apache Paimon** table internals
from a local filesystem — the metadata tree, snapshot by snapshot, down to sample rows.

Point it at a table directory and it renders the structure engines walk on every query:
metadata files, snapshots, manifest lists, manifests, data and delete files, and the rows
inside them — as a graph you can click through, next to an inspector showing every field the
format records.

- **Read-only** -- never modifies tables or metadata
- **Local-first** -- loads tables from folders on your machine
- **Offline-friendly** -- works without external services or catalogs
- **Two formats** -- Apache Iceberg (v1/v2) and Apache Paimon, auto-detected per directory
- **Cross-platform** -- macOS, Windows, Linux

### What the numbers mean

Table-format figures are easy to state and easy to get wrong, because both formats share
structure on purpose: one manifest is referenced by every snapshot that carries its files
forward. Iceberg Lens reports two sets side by side, each labelled:

| Group | Answers |
|---|---|
| **Current snapshot** | What the table holds now — the manifest closure of `current-snapshot-id`, live entries only. Its record count is what `SELECT count(*)` should agree with, before delete files are applied. |
| **All retained history** | What is still on disk — every manifest and data file reachable from any retained snapshot, deduplicated. This is the "what can I not expire yet" view. |

Caps are always named. When the graph draws a subset of a manifest's entries, the card says
so and the inspector lists all of them.

## Screenshots

| Fullscreen | Overview |
|---|---|
| ![Fullscreen](assets/screenshots/fullscreen.png) | ![Overview](assets/screenshots/overview.png) |

## Features

- Interactive graph of the metadata tree — metadata files, snapshots, manifest lists, manifests, data files, delete files, sample rows
- Inspector panel with every field the format records, parent/child navigation, JSON highlighting, and copy-to-clipboard buttons
- Schema evolution view -- diffs between schema versions (added/dropped/renamed columns, type changes)
- Table properties inspector -- property changes tracked across metadata versions
- Snapshot filtering -- select snapshots to isolate their subgraph
- Workspace tree for multiple warehouses and tables, with a format badge per table
- Movable, dockable tool window panels (left/right/top/bottom)
- Dark mode with theme-aware node colors
- Keyboard shortcuts for zoom, fit, re-layout, and undo
- Auto-reload from filesystem
- In-app cheat sheet (About > Cheat Sheet)
- Viewport culling for large graph performance

### Format coverage

| | Iceberg | Paimon |
|---|---|---|
| Detection | `metadata/` with `*.metadata.json` | `snapshot/` + `schema/` |
| Metadata | metadata.json, snapshot log, metadata log, refs, partition specs, sort orders, statistics entries | `snapshot/snapshot-N`, `schema/schema-N` |
| Manifests | manifest list (Avro) → manifest (Avro), data / delete split | base / delta / changelog manifest lists → manifests |
| Files | data, positional-delete, equality-delete | data files with LSM level and bucket |
| Rows | Parquet / ORC / Avro via DuckDB, capped at 50 per file | same |

Paimon has no Iceberg-style positional or equality delete files; removals are `_KIND=1`
manifest entries, reported as *entries recording a removal* rather than as delete files.

## Quick start

### Requirements

- Java 17+ (JDK)

### Download

Prebuilt installers are available on [GitHub Releases](https://github.com/mmdemirbas/ice-lens/releases):

| Platform | Format |
|---|---|
| macOS | `.dmg` |
| Windows | `.msi` |
| Linux | `.deb` |

### Run from source

```bash
./gradlew run
```

### Build

```bash
./gradlew build
```

### Test

```bash
./gradlew test
```

## Usage

1. Click **Add to Workspace** (sidebar or empty state button).
2. Choose a warehouse folder (contains multiple tables) or a single table folder (`metadata/` for Iceberg, `snapshot/` + `schema/` for Paimon).
3. Select a table from the Workspace panel.
4. Explore graph nodes -- click to inspect, drag to rearrange.
5. Click a node to see details in the **Inspector** panel.

### Toolbar

| Action | Description |
|---|---|
| Pan / Select mode | Toggle between canvas panning and marquee selection |
| Zoom controls | Zoom in/out, reset to 100%, fit graph to view |
| Re-apply Layout | Recompute node positions from scratch |
| Snapshot Filter | Show only nodes connected to selected snapshots |
| Dark Mode | Toggle light/dark theme |
| About | Version info, diagnostic copy, cheat sheet |

### Keyboard shortcuts

| Shortcut | Action |
|---|---|
| Ctrl/Cmd + = / + | Zoom in |
| Ctrl/Cmd + - | Zoom out |
| Ctrl/Cmd + 0 | Reset zoom to 100% |
| Ctrl/Cmd + Shift + F | Fit graph to view |
| Ctrl/Cmd + L | Re-apply layout |
| Ctrl/Cmd + Z | Undo node drag |
| Ctrl/Cmd + Scroll | Zoom at cursor |
| Scroll | Pan canvas |
| Click node | Select |
| Ctrl/Cmd + Click | Multi-select |
| Drag (Select mode) | Marquee select |
| Double-click empty area | Toggle all panels |
| Double-click node | Toggle inspector |

## Limitations

Stated plainly, because a tool you inspect internals with has to be honest about its own:

- **Local filesystem only.** No catalog integration (Hive, Glue, REST, Nessie, Polaris) and no
  object-store reads (S3, GCS, ADLS, HDFS). Cloud URIs in metadata are shown as recorded but
  not followed.
- **Iceberg v1 and v2.** Format version 3 is parsed without error but its additions are not
  modelled: deletion vectors, row lineage, and the variant / geometry / geography types.
- **Partition values are not decoded.** `data_file.partition` is a spec-defined nested record
  whose shape depends on the table's partition spec, and the reader does not yet read it. The
  inspector's Partition column shows `N/A` for every entry.
- **Manifest field summaries are not read.** `manifest_file.partitions` — the per-field bounds
  that drive manifest pruning — is not modelled, so you cannot see why a manifest was skipped.
- Sample rows are best-effort: they depend on the file being present locally and readable by
  DuckDB, and are capped at 50 rows per file.
- Row loading may be slow for tables with many data files when "Show Rows" is enabled.

## Release

Release assets are built by GitHub Actions and uploaded to GitHub Releases.

```bash
./release.sh 1.0.2
```

The script validates the working tree, checks the version in `build.gradle.kts`, runs a local build, creates a git tag, and pushes. The CI release workflow then builds macOS `.dmg`, Windows `.msi`, and Linux `.deb` installers.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for build instructions, code style, and PR process.

## License

Apache-2.0. See [LICENSE](LICENSE).
