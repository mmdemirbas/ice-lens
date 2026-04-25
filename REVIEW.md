# Iceberg Lens — Review Backlog (final)

This document was the consolidated backlog from the 2026-03-25 bug-hunt
and the 2026-04-25 deep review. All actionable items have been
addressed across seven semantic commits plus one follow-up. Items that
were intentionally deferred (with rationale) and the residual findings
from the second review iteration are listed at the end.

The original backlog text has been condensed into a status table. For
the original detail with file:line and reasoning, see the git history
of this file or the commit messages.

---

## Status

| Group | Items | Commit | Status |
|---|---|---|---|
| A | Paimon parity (C-1, C-2, H-3, T-3, T-4, M-9, M-11, M-12) | `fafd0bb` | done |
| B | Threading/staleness (T-1, T-2, T-5, M-10) | `a5ecd95` | done |
| C | Frame-budget perf (H-1, H-2, H-4, H-5, M-6) | `546560b` | done |
| D | Path/numeric correctness (H-6, H-7, L-7) | `d038d67` | done |
| E | UX polish (H-8, H-9, H-10, M-2, M-3, M-4, M-5, M-13) | `0dc63d2` | done |
| F | Latent failures (M-7, M-8, L-15) | `2ebd385` | done |
| G | Cleanup (L-1, L-2, L-3, L-4, L-8, L-10..L-14, T-6, T-8) | `a7991e7` | done |
| – | Second-iteration follow-ups | `<this commit>` | done |

Test suite: **295 tests, all green** (was 270 at session start).

---

## Per-finding outcome

### Critical
- **C-1** snapshot filter dead for Paimon → A. `asSnapshotFilterOption()` covers both formats.
- **C-2** `computeTableFingerprint` always "missing" for Paimon → A. Dispatches by format; falls back to `snapshot/`+`schema/`.

### High (open in original review)
- **T-1** stale `currentGraph` in reapply → B. Re-read after staleness check.
- **T-2** non-atomic `loadRequestId` → B. `AtomicLong.incrementAndGet`.
- **T-3** stale graph on deleted+forceReload+no-cache → A. Always reset.
- **T-4** Paimon manifest list `simpleId` reused → A. Dedicated counter.
- **T-5** missing `ensureActive` in reapply → B. Added between hops.
- **H-1** `Path` allocated per edge per frame → C. `remember { Path() }` + `reset()`.
- **H-2** O(E) `count{}` per edge per frame → C. Out/in counts memoized.
- **H-3** no Paimon row inspector → A. `RecursiveDataTableSection` wired into Paimon branches.
- **H-4** `jsonToAnnotatedString` per recompose → C. `remember(rawJson, colors)`.
- **H-5** Preferences write per drag pixel → C. Swing Timer 500ms debounce.
- **H-6** `file://host/path` drops authority → D. UNC reconstruction.
- **H-7** `ManifestListEntry` Int sequence numbers → D. Widened to Long; comparator sentinels updated.
- **H-8** tool window icons no tooltip → E. `HoverTooltip` wrapper.
- **H-9** multi-select empty inspector → E. Per-node summary table.
- **H-10** snapshot filter not scrollable → E. `heightIn(max=420dp).verticalScroll`.

### Medium
- **M-2** shortcuts not in tooltips → E. Toolbar tooltips updated.
- **M-3** zoom shortcut on non-US layouts → E. Already had `Key.Plus` / `Key.NumPadAdd`.
- **M-4** stack trace no scroll/copy → E. `heightIn(max=320dp)` + Copy button.
- **M-5** timestamp truncation in WideTable → E. `formatTimestampShort()`.
- **M-6** repeated `filterIsInstance` in layout → C. Single `groupBy { it::class }` pass.
- **M-7** unbounded `sessionCache` → F. 5-entry LRU.
- **M-8** workspace path `;`/`|` collision → F. Percent-encode `%`, `;`, `|`.
- **M-9** Paimon path traversal check → A. Mirrors Iceberg's check.
- **M-10** duplicated format dispatch → B. Single `loadTableModel` entry point.
- **M-11** Paimon delete counts → A (initial), then revised in follow-up: ADD entries → `dataFileCount`; DELETE log entries do not map to Iceberg pos/eq deletes.
- **M-12** Paimon manifest dedup → A. `manifestPathToId` mirror of Iceberg.
- **M-13** multi-select hint not mode-aware → E. Subsumed by H-9.

### Low
- **L-1** Reset Zoom tooltip wording → E.
- **L-2** Reveal silent on cloud paths → G. `isLocalPath()` guard.
- **L-3** About dialog tab indicator → G. `primaryContainer` background on active tab.
- **L-4** Workspace drag cursor → G. `pointerHoverIcon(MOVE)`.
- **L-5** `enforceChronologicalVerticalOrder` repeated passes — **deferred** (premature optimization without profiling data).
- **L-6** `flattenGraph` cycle — **rejected as false positive**. Cycle protection at top of `traverse`.
- **L-7** `parsePosition` truncation → D. Reject out-of-Int range.
- **L-8** Paimon error helper → G. Reuses `UnifiedModel.toError` (now `internal`).
- **L-9** `derivedStateOf` for visibleNodes — **deferred** (the inline computation already only re-runs on observed state changes).
- **L-10** Paimon doc `layoutNodes` → `layoutGraph` → G.
- **L-11** PaimonNodeCard silent fallback → G. Marker prefix on the `else`.
- **L-12** "PFILE" vs "PAIMON FILE" → G. Card label aligned with tooltip.
- **L-13** `manifestContentRank` duplication → G. NodeDetails delegates to IcebergGraphBuilder.
- **L-14** Orphan KDoc → G.
- **L-15** `SampleRowReader.getConnection` lock contract → F. Documented.
- **L-16** Startup IO on main thread — **deferred** (recursive scans already capped; full async refactor is a larger task).
- **L-17** `Locale.US` pinning — **intentional** (consistency for power-user data engineers).
- **T-6** vacuous `||` in `GraphLayoutServiceTest` → G.
- **T-7** Iceberg `substringBeforeLast` — already fixed pre-session.
- **T-8** `.toInt()` truncation in GraphCanvas → G. `.roundToInt()`.

---

## Second-iteration follow-ups

A fresh review pass after the seven group commits surfaced four issues:

1. **PaimonGraphBuilder mislabeled DELETE log entries as `posDeleteFileCount`** — fixed. Paimon's `_KIND=1` is a manifest log entry recording removal, not a positional-delete file. The TableNode summary now shows truthful counts (0 pos-del / 0 eq-del for any Paimon table); ADD entries populate `dataFileCount`; DELETE entries are visible via `manifestEntryCount` and `deleteManifestCount`.
2. **`GraphLayoutService` manifest comparator sentinels not widened with H-7** — fixed. Both `sequenceNumber` and `minSequenceNumber` null sentinels now use `Long.MAX_VALUE` to match the field type after H-7.
3. **`PropertiesEvolutionSection` double-counted keys present in both old and new version** — fixed. `(setA + setB)` is a `List` with duplicates; switched to `union`.
4. **Duplicate `kotlin.math.roundToInt` import** — fixed. Compiler-warning level only.

---

## Tests added in this session

| File | What |
|---|---|
| `SnapshotFilterTest` | `asSnapshotFilterOption` for Iceberg / Paimon / non-snapshot; Paimon snapshot inclusion in walk |
| `AppStateTest` | Paimon fingerprint detection + reactivity; bounded LRU eviction past max size |
| `AppStateTableLoadingTest` | T-2 rapid sequential loads end on latest graph; T-1 cache hit after reapply; T-3 deleted-table forceReload graph replacement |
| `PaimonGraphBuilderTest` | T-4 distinct manifest list `simpleId`; M-12 manifest dedup across lists; M-11 add/delete classification (Paimon-correct) |
| `PaimonPipelineTest` | M-9 path-traversal flagged in Paimon |
| `IcebergPathsTest` | H-6 `file://host/...`, `file://localhost/...`, `file:///...` |
| `IcebergSchemaTest` | H-7 `ManifestListEntry` accepts `Long > Int.MAX_VALUE` |
| `WorkspaceTypesTest` | M-8 round-trip with `;`, `|`, `%` in path |
| `FormatUtilsTest` | M-5 `formatTimestampShort` is single-line |

---

## Notable non-issues (verified during the deep review)

- `GraphModel.nodeById` lazy val: correctly cached.
- `extents` in `GraphCanvas`: correctly wrapped in `derivedStateOf`.
- `Dispatchers.Default` for ELK layout: right call.
- `SampleRowReader` 50-row cap: enforced at SQL `LIMIT`.
- Snapshot filter persistence across format switches: clears via `syncSnapshotFilterFromSnapshotIds`.
- `WorkspaceItem.serialize`: inner `|` collision was safe via `split("|", limit = 2)`; the issue was the outer `;` separator (M-8).

---

## Deferred items (with rationale)

- **L-5 / L-9** — micro-optimizations whose return on investment requires actual profiling data we don't have.
- **L-16** — moving `scanForTables` off the main thread requires the UI to handle "not yet scanned" state across all workspace consumers; out of scope for a polish pass.
- **L-17** — `Locale.US` pinning is consistent and matches power-user expectations.
