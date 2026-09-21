---
title: Quick start
order: 10
summary: Download an installer or run from source; point it at a table; the same engine from the command line.
---

> [!TLDR]
> Java 17+. Installers for macOS, Windows and Linux on GitHub Releases, or `./gradlew run`. Add a warehouse or table folder to the workspace and click through the graph. `icelens` is the same engine for scripts and CI.

## Download {#download}

```oku-table
{"headers":["Platform","Installer","The command line inside it"],"rows":[["macOS","`.dmg`","`/Applications/IcebergLens.app/Contents/MacOS/icelens` — `ln -s` it into `/usr/local/bin`"],["Windows","`.msi`","`icelens.exe` beside `IcebergLens.exe` in the install directory, with a console"],["Linux","`.deb`","`/opt/iceberglens/bin/icelens`"]]}
```

Prebuilt installers are on [GitHub Releases](https://github.com/mmdemirbas/ice-lens/releases); they carry their own Java runtime. Building from source needs a Java 17+ JDK.

## From source {#source}

```bash
./gradlew run      # the desktop app
./gradlew build    # build
./gradlew test     # tests
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
| Layout | Layered left-to-right or top-to-bottom, tree, or force-directed |
| Export | The graph as SVG, PNG or JSON; the file inventory as CSV |
| Find | Open the find bar on the canvas |
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
| Ctrl/Cmd + F | Find on the graph |
| Arrow keys | Move the selection to the nearest node in that direction |
| Ctrl/Cmd + Z | Undo node drag |
| Ctrl/Cmd + Scroll | Zoom at cursor |
| Scroll | Pan canvas |
| Click node | Select |
| Ctrl/Cmd + Click | Multi-select |
| Drag (Select mode) | Marquee select |
| Ctrl/Cmd + 1 / 2 / 3 | Show or hide the Workspace / Structure / Inspector tool window |
| Double-click empty area | Toggle all panels |
| Double-click node | Toggle inspector |

## Command line

The same engine from a terminal, for a script, a cron job or a CI step:

```bash
./gradlew :cli:installDist                      # builds cli/build/install/icelens/bin/icelens
icelens summary /wh/db/orders                   # versions, snapshots, the current figures
icelens tree /wh/db/orders --depth 2            # metadata → snapshots → manifests → files, with node ids
icelens show /wh/db/orders snap_8331894 --json  # one node's rows, deferred ones read
icelens check /wh/db/orders                     # every recorded figure against the same figure counted;
                                                # exit 1 on a disagreement, so a pipeline can gate on it
icelens check /wh/db/orders --files             # and every live data file and statistics file opened too;
                                                # a file that cannot be read is exit 1 as well
icelens lookup /wh/db/orders "id = 42"          # the rows a filter matches, each with its fate:
                                                # live, or deleted/superseded by which delete or write
icelens export /wh/db/orders --format csv --out files.csv   # the file inventory; svg and json too
```

`--json` on `summary`, `tree`, `show`, `check` and `lookup` prints the same as an object. Standard
output carries the answer only; anything the engine logs goes to standard error.

The installers carry the same command beside the app, over the app's own runtime, so a machine
with Iceberg Lens installed has `icelens` with no Java of its own — a link onto the `PATH` is
all it takes:

| Installer | Where it is |
|---|---|
| macOS `.dmg` | `/Applications/IcebergLens.app/Contents/MacOS/icelens` — `ln -s` it into `/usr/local/bin` |
| Linux `.deb` | `/opt/iceberglens/bin/icelens` |
| Windows `.msi` | `icelens.exe` beside `IcebergLens.exe` in the install directory, with a console |
