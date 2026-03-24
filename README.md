# Iceberg Lens

[![CI](https://github.com/mmdemirbas/ice-lens/actions/workflows/ci.yml/badge.svg)](https://github.com/mmdemirbas/ice-lens/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/mmdemirbas/ice-lens)](https://github.com/mmdemirbas/ice-lens/releases)
[![License](https://img.shields.io/github/license/mmdemirbas/ice-lens)](LICENSE)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-blue)](https://kotlinlang.org/)
[![Compose](https://img.shields.io/badge/Compose_Desktop-1.10-blue)](https://www.jetbrains.com/lp/compose-multiplatform/)

Read-only desktop UI to inspect Apache Iceberg table structure from local filesystems.

- **Read-only** -- never modifies tables or metadata
- **Local-first** -- loads tables from folders on your machine
- **Offline-friendly** -- works without external services or catalogs
- **Cross-platform** -- macOS, Windows, Linux

## Screenshots

| Fullscreen | Overview |
|---|---|
| ![Fullscreen](assets/screenshots/fullscreen.png) | ![Overview](assets/screenshots/overview.png) |

## Features

- Interactive graph visualization of Iceberg table structure (metadata, snapshots, manifests, data files, delete files, sample rows)
- Inspector panel with detailed node info, parent/child navigation, JSON highlighting, and copy-to-clipboard buttons
- Schema evolution view -- diffs between schema versions (added/dropped/renamed columns, type changes)
- Table properties inspector -- property changes tracked across metadata versions
- Snapshot filtering -- select snapshots to isolate their subgraph
- Workspace tree for multiple warehouses and tables
- Movable, dockable tool window panels (left/right/top/bottom)
- Dark mode with theme-aware node colors
- Keyboard shortcuts for zoom, fit, re-layout, and undo
- Auto-reload from filesystem
- In-app cheat sheet (About > Cheat Sheet)
- Viewport culling for large graph performance

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
2. Choose a warehouse folder (contains multiple tables) or a single table folder (contains `metadata/`).
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

- Local filesystem only (no catalog integrations or remote object stores yet).
- Sample rows are best-effort -- depends on file availability and format.
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
