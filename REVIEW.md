# Iceberg Lens — Consolidated Review (2026-04-25)

This document combines the prior bug-hunt of 2026-03-25 and the deep review of 2026-04-25 into a single backlog. Findings are grouped by severity and tagged with their semantic group (A–G) for batch fixing.

Format per finding: **id — title** (group). Location, Issue, Fix.

---

## TL;DR — Top priorities

1. **Paimon parity gaps** (C-1, C-2, H-3) — snapshot filter dead, fingerprint always "missing", no row inspector. Largest user-visible regressions.
2. **AppState concurrency** (T-1, T-2, T-5) — three known threading bugs from prior hunt remain.
3. **Render hot path** (H-1, H-2, H-4) — Path allocation per edge per frame, O(E) inside drawEdge, JSON re-highlighted every recomp.

Suggested fix order:
| Group | Bundle |
|---|---|
| A | Paimon parity (C-1, C-2, H-3, T-3, T-4, M-9, M-11, M-12) |
| B | Threading (T-1, T-2, T-5, M-10) |
| C | Render perf (H-1, H-2, H-4, H-5, M-6, L-9) |
| D | Path/numeric correctness (H-6, H-7, L-7, L-6) |
| E | UX polish (H-8, H-9, H-10, M-2, M-3, M-4, M-5, M-13) |
| F | Latent failures (M-7, M-8, L-15, L-16) |
| G | Cleanup (L-1..L-5, L-8, L-10..L-14, L-17, T-6, T-8) |

---

## Critical (functional regressions, ship-blockers)

### C-1 — Snapshot filter is silently dead for Paimon (A)
**Files:** `ui/SnapshotFilter.kt:26`, `ui/AppState.kt:103–121`

`snapshotFilterOptions` and `computeVisibleNodeIdsForSnapshotFilter` use `filterIsInstance<GraphNode.SnapshotNode>()`. Paimon's snapshots are `GraphNode.PaimonSnapshotNode` and never collected.

**Effect:** open any Paimon table → filter widget is empty.

**Fix:** introduce a snapshot abstraction (sealed marker or list union). `computeVisibleNodeIdsForSnapshotFilter` must walk Paimon's chain (`PaimonSnapshotNode` → `PaimonManifestListNode` → `PaimonManifestNode` → `PaimonDataFileNode` → `RowNode`).

### C-2 — `computeTableFingerprint` always returns "missing" for Paimon (A)
**File:** `ui/AppState.kt:370–381`

Inspects only `tablePath/metadata/*.metadata.json`. Paimon has no `metadata/` dir. Combined with `AppState.kt:439–440`, any reload after the first load returns the cached session indefinitely.

**Fix:** dispatch by detected format inside `computeTableFingerprint` and inspect `snapshot/snapshot-*` for Paimon.

---

## High (visible bugs, parity gaps, real perf hotspots)

### T-1 — Stale `currentGraph` in `reapplyCurrentLayout` (B)
**Location:** `AppState.kt:515–548` (was: TASK.md #1)

`currentGraph` captured on the main thread is used inside the coroutine after the `requestId != loadRequestId` check. A concurrent `loadTable()` completing between check and use rebuilds a graph from stale nodes/edges and writes it to the UI via `setGraphModelAndBump(relaid)`.

**Impact:** Visual corruption when switching tables while snapshot filters are active.

**Fix:** Re-read `graphModel` after the `requestId` check, or pass the graph through the session cache.

### T-2 — Non-atomic `loadRequestId` increment (B)
**Location:** `AppState.kt:430–431, 521–522, 574–575` (was: TASK.md #2)

`val requestId = loadRequestId + 1; loadRequestId = requestId` is a read-modify-write. `@Volatile` (added in `c54ebf6`) guarantees visibility, not atomicity. Two rapid calls can produce duplicate request IDs, breaking the staleness guard.

**Fix:** Replace with `AtomicLong.incrementAndGet()`.

### T-3 — Stale graph retained when deleted table has no cache and `forceReloadFromFs=true` (A)
**Location:** `AppState.kt:493–498` (was: TASK.md #3)

When a table is deleted and no cached session exists, if `forceReloadFromFs=true`, `setGraphModelAndBump(null)` is skipped. UI continues showing previous table's graph while `errorMsg` says the table was deleted.

**Fix:** Always call `setGraphModelAndBump(null)` when the table is gone and there's no cache.

### T-4 — `PaimonManifestListNode` gets snapshot's `simpleId` (A)
**Location:** `PaimonGraphBuilder.kt:139` (was: TASK.md #4)

`simpleId` assigned to manifest-list nodes comes from the snapshot counter. All manifest lists (base, delta, changelog) under the same snapshot share the same `simpleId`.

**Fix:** Add a dedicated `nextManifestListSimpleId` counter.

### T-5 — Missing `ensureActive()` in `reapplyCurrentLayout` (B)
**Location:** `AppState.kt:524–565, 577–599` (was: TASK.md #5)

`loadTable()` got three `ensureActive` calls (lines 437/445/448) but neither branch of `reapplyCurrentLayout` has any. A cancelled coroutine can finish expensive layout work unnecessarily.

**Fix:** Add `ensureActive()` between `withContext` blocks in both branches.

### H-1 — `Path` object allocated every edge every frame (C)
**File:** `ui/GraphCanvas.kt:210`

Inside `DrawScope.drawEdge`, `val path = Path().apply { ... }` allocates per edge per frame. ~30k allocations/s at 500 visible edges × 60Hz.

**Fix:** Hoist `val path = remember { Path() }` to the canvas scope; call `path.reset()` at the top of `drawEdge`.

### H-2 — `drawEdge` does O(E) `count { fromId == … }` per edge per frame (C)
**File:** `ui/GraphCanvas.kt:183–186`

Two `graph.edges.count { … }` linear scans per snapshot→manifest edge inside the draw lambda. O(E²) per frame.

**Fix:** Extend the existing `remember(graph.nodes, graph.edges)` block (already builds lane maps two lines above) to also produce `outCountBySource: Map<String,Int>` and `inCountByTarget: Map<String,Int>`. Replace the `.count { }` calls with O(1) lookups.

### H-3 — No sample-row / changelog inspector for Paimon nodes (A)
**File:** `ui/NodeDetails.kt` — Paimon branches at lines 1225, 1290, plus PaimonManifestListNode/PaimonManifestNode/PaimonSchemaNode

Iceberg branches all call `RecursiveDataTableSection` (lines 627, 726, 966, 1058, 1178, 1197). None of the Paimon branches do. `collectDescendantRows` (200–214) also only descends through `is GraphNode.FileNode`, so even if invoked, `currentFile` is `null`.

**Fix:** add `is GraphNode.PaimonDataFileNode -> visit(child.id, child)` to `collectDescendantRows`. Call `RecursiveDataTableSection` from each Paimon inspector branch.

### H-4 — `jsonToAnnotatedString` rebuilt every recomposition (C)
**File:** `ui/NodeDetails.kt:941`

`Text(text = jsonToAnnotatedString(rawJson))` runs O(len) syntax highlight on every recomposition. 100KB+ JSON for long-lived tables.

**Fix:** `val highlighted = remember(rawJson) { jsonToAnnotatedString(rawJson) }`.

### H-5 — `persistWindowState` writes Preferences on every `componentMoved` (C)
**File:** `Main.kt:171–180`

Tens of synchronous Preferences writes per second during window drag.

**Fix:** Debounce. Coalesce events into a single deferred flush 500 ms after the last event.

### H-6 — `normalizeFilePath` silently drops authority for `file://host/path` (D)
**File:** `model/IcebergPaths.kt:21–24`

`URI("file://server/share/path").getPath()` returns `/share/path`; the host is silently lost. Resolved paths point to the wrong place with no error.

**Fix:** When `URI.getHost()` is non-empty and non-`localhost`, reconstruct as `\\server\share\…` on Windows, or surface a clear error on Unix.

### H-7 — `ManifestListEntry.sequenceNumber` typed as `Int`, spec is `long` (D)
**File:** `model/IcebergSchema.kt:118–119`

```kotlin
@SerialName("sequence_number") val sequenceNumber: Int? = null,
@SerialName("min_sequence_number") val minSequenceNumber: Int? = null,
```

`Snapshot.sequenceNumber` is correctly `Long?` (line 102). Mirror is `Int?`. Tables with sequence > 2³¹ overflow on parse and sort wrong.

**Fix:** Change to `Long?`. Update comparators and tests.

### H-8 — Tool-window bar icons have no tooltips (E)
**File:** `ui/ToolWindow.kt:58–63`

Side icon strip uses plain `.clickable {}`. `contentDescription` is for screen readers, not hover. Users cannot tell which icon is which without trial-clicking.

**Fix:** Wrap icon `Box` in `TooltipArea` with `toolWindowTitle(id)`.

### H-9 — Multi-select inspector shows zero information (E)
**File:** `ui/NodeDetails.kt:521–530`

"N Nodes Selected" + drag hint is the entire content. A common power-user task — Ctrl-click two snapshots to compare timestamps — reaches a dead end.

**Fix:** Render a compact summary table (one row per selected node — id + key per type).

### H-10 — Snapshot-filter dropdown / sidebar search not scrollable (E)
**Files:** `ui/Sidebar.kt`, snapshot-filter widget in `ui/App.kt`

Long-lived tables produce flat checkbox lists with no `verticalScroll`. Items overflow the panel and become unreachable on small monitors.

**Fix:** `LazyColumn` for both — gives scroll and virtualization.

---

## Medium

### M-2 — Keyboard shortcuts not shown in toolbar tooltips (E)
**File:** `ui/App.kt:317–332`

Only About-dialog cheat sheet lists shortcuts. Append `(Ctrl+Shift+F)` etc. to tooltips for buttons with shortcuts.

### M-3 — Zoom-in shortcut binds only to `Key.Equals` (E)
**File:** `ui/App.kt:223–226`

`Cmd+=` works on US layouts. On AZERTY/QWERTZ, `=` requires Shift, conflicting with `!keyEvent.isShiftPressed` guard. Add `Key.Plus`/`Key.NumPadAdd` matches.

### M-4 — ErrorNode stack trace has no scroll bound and no copy button (E)
**File:** `ui/NodeDetails.kt:1210–1224`

40-line trace pushes content off-screen; no copy.

**Fix:** `heightIn(max = 300.dp).verticalScroll(...)` plus a copy IconButton.

### M-5 — WideTable timestamp cells truncate epoch line silently (E)
**Files:** `ui/NodeDetails.kt:869`, `ui/NodeComponents.kt:153–159`

3-line timestamp overflows fixed-width cells. Add `formatTimestampShort()`.

### M-6 — ELK post-processing repeats `filterIsInstance` 20+ times on full node list (C)
**File:** `service/GraphLayoutService.kt:517–593`

`alignParentsWithChildren` and `preventOverlaps` each call `nodesById.values.filterIsInstance<...>()` once per node type. ~20 full traversals.

**Fix:** Single `groupBy { it::class }` partition at the start of each function.

### M-7 — `sessionCache` is unbounded (F)
**File:** `ui/AppState.kt:96`

Each `TableSession` is 50–100 MB for large tables; switching across many tables in one session OOMs.

**Fix:** Bounded LRU (cap 4–5 sessions; LinkedHashMap.removeEldestEntry suffices).

### M-8 — WorkspaceItem serialization breaks on paths containing `;` (F)
**Files:** `ui/AppState.kt:151, 202`, `model/WorkspaceTypes.kt:21–28`

Outer separator `;` is legal in Unix paths; round-trip breaks.

**Fix:** Percent-encode `;` and `|` in serialized values, decode on load. Migrate prior values gracefully.

### M-9 — Path-traversal check missing in Paimon model (A)
**Files:** `model/PaimonUnifiedModel.kt:239–251` vs `model/UnifiedModel.kt:180–189`

Iceberg validates resolved data file paths stay under `normalizedDataRoot`; Paimon does not.

**Fix:** Mirror the Iceberg check in `resolveDataFilePath`.

### M-10 — `loadTableModel` and `loadTable` duplicate format dispatch (B)
**File:** `ui/AppState.kt:362–368` vs `447–452`

CLAUDE.md documents `loadTableModel` as the single dispatch point. `loadTable` has its own inline `when(format)`.

**Fix:** `loadTable` should call `loadTableModel(...)`.

### M-11 — Paimon `buildTableSummary` hardcodes delete counts to zero (A)
**File:** `service/PaimonGraphBuilder.kt:277–305`

`deleteManifestCount`, `posDeleteFileCount`, `eqDeleteFileCount` are `0` regardless of input.

**Fix:** Count via `PaimonManifestEntry.kind` and `operationKind`. Wire into `TableNode` summary.

### M-12 — Paimon manifest dedup is only at entry level (A)
**File:** `service/PaimonGraphBuilder.kt:158–180`

A new `PaimonManifestNode` is created per reference; only the inner file-entry loop is guarded. Same manifest under multiple manifest lists creates duplicate nodes.

**Fix:** Mirror Iceberg's `manifestPathToId` map.

### M-13 — Multi-select hint always shows "Drag any selected node…" regardless of mode (E)
**File:** `ui/NodeDetails.kt:521–530`

Hint is only relevant in Select mode.

**Fix:** Gate on `selectMode`, or fold into H-9 fix.

---

## Low

- **L-1** — "Original Size (100%)" tooltip implies recentering. Rename "Reset Zoom (100%)". (E) `ui/App.kt:317–324`
- **L-2** — "Reveal in Finder" silently fails for cloud paths (`s3://…`). (E) `ui/NodeDetails.kt:379–407`
- **L-3** — About-dialog tab indicators are FontWeight only. Use `TabRow`/`Tab`. (E) `ui/AboutDialog.kt:41–48`
- **L-4** — Workspace items lack `pointerHoverIcon(MOVE_CURSOR)`. (E) `ui/Sidebar.kt:209–241`
- **L-5** — `enforceChronologicalVerticalOrder` does five sequential O(N) passes. (C) `service/GraphLayoutService.kt:252–294`
- **L-6** — `flattenGraph` removes nodes from `visited` after processing. Stack-overflows on cycle. (D) `ui/NavigationTree.kt:245`
- **L-7** — `parsePosition` silently truncates `Long.toInt()`. Pos-delete with row >2³¹ correlates to wrong data row. (D) `service/GraphLayoutService.kt:181–184`
- **L-8** — Iceberg uses `toError(stage, path, throwable)`; Paimon inlines `e.message ?: "Unknown error"` 11× without `simpleName` fallback. (G)
- **L-9** — Visible nodes/edges filtered inline in `BoxWithConstraints`. Wrap in `derivedStateOf`. (C) `ui/GraphCanvas.kt:408–421`
- **L-10** — `PaimonGraphBuilder.buildGraph` KDoc references `GraphLayoutService.layoutNodes`; should be `layoutGraph`. (G) `service/PaimonGraphBuilder.kt:31`
- **L-11** — `PaimonNodeCard` has silent `else -> Text(node.id)` fallback. (G) `ui/NodeComponents.kt:604–606`
- **L-12** — "PFILE" card vs "PAIMON FILE" tooltip vs `pdf_` ID — three labels. (G)
- **L-13** — `manifestContentRank` duplicated across `IcebergGraphBuilder.kt:490–494` and `NodeDetails.kt:100–104`. (G)
- **L-14** — `IcebergGraphBuilder.kt:27–28` orphan KDoc comment for extracted `GraphBuildResult`. (G)
- **L-15** — `getConnection()` reads `connection` outside synchronized block. Currently safe; mark `private`. (F) `service/SampleRowReader.kt:27–34`
- **L-16** — `AppState.init` calls `scanForTables()` synchronously. Slow filesystems block startup. (F) `ui/AppState.kt:143–188`
- **L-17** — `formatCount` pins `Locale.US`. Likely intentional. (G) `ui/NodeComponents.kt:79–81`

---

## Prior bug-hunt status (2026-03-25)

| # | Title | Status | Group |
|---|---|---|---|
| 1 | Stale `currentGraph` in `reapplyCurrentLayout` | Still present → **T-1** | B |
| 2 | Non-atomic `loadRequestId` increment | Still present → **T-2** | B |
| 3 | Stale graph when deleted+forceReloadFromFs+no cache | Still present → **T-3** | A |
| 4 | `PaimonManifestListNode.simpleId` reuses snapshot's | Still present → **T-4** | A |
| 5 | Missing `ensureActive()` in `reapplyCurrentLayout` | Still present → **T-5** | B |
| 6 | Vacuous `||` assertion in `GraphLayoutServiceTest` (line 82) | **T-6**: verify and fix `||` → `&&` | G |
| 7 | `substringBeforeLast` on no-slash path | **Fixed** (replaced by `resolveForceRelative`) | — |
| 8 | `.toInt()` truncation in `GraphCanvas.kt:558` | **T-8**: change to `.roundToInt()`. Cosmetic only. | G |

---

## Notable non-issues

Where reviewer suspicion did not pan out:

- `GraphModel.nodeById` lazy val — correctly cached.
- `extents` in `GraphCanvas` — correctly wrapped in `derivedStateOf`.
- `Dispatchers.Default` for ELK layout — right call.
- `SampleRowReader` 50-row cap — enforced at SQL `LIMIT`.
- Snapshot filter persistence across format switches — clears correctly via `syncSnapshotFilterFromSnapshotIds()`.
- `WorkspaceItem.serialize` `|` collision — `split("|", limit = 2)` is safe; the `;` outer separator (M-8) is the real issue.
