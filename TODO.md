# Roadmap

---

## Bugs

- **Pinch zoom not working** — trackpad two-finger pinch gesture doesn't fire on all platforms. Needs platform-specific testing.

---

## Code quality

- **Split `App.kt` further** — currently ~1500 lines. Extract: `Toolbar` composable (~250 lines), `AppState` state holder class (consolidate ~30 state vars and ~10 inner functions). `AboutDialog` has already been extracted.

---

## Performance

- **Profile Compose rendering for large graphs** — ELK layout is fast (benchmarked up to 4000 nodes at ~1.3s). Viewport culling is in place but Compose rendering at scale (thousands of visible nodes simultaneously) has not been measured. Consider level-of-detail rendering or node virtualization if it becomes a bottleneck.

---

## UI / UX

- **App icon** — no custom icon; installers use the default Java icon. Need `.icns`/`.ico`/`.png` assets and `nativeDistributions { iconFile.set(...) }` in build.gradle.kts.

- **Typography consistency** — 8 different font sizes used ad-hoc. Define a scale (e.g. 10, 12, 14, 16sp) and apply uniformly. FileNode/RowNode cards at 8-9sp are too small at default zoom.

- **Collapsible inspector sections** — TableNode inspector has 8+ sections stacked vertically. Add expand/collapse chevrons per section.

- **Pan/Select mode clarity** — tooltips should explain behavior, not just name (e.g. "Drag to scroll the canvas" vs "Drag to marquee-select nodes").

- **Filtered state badge** — show "Showing X of Y nodes" on the canvas when snapshot filter is active.

- **Accessibility** — keyboard navigation (Tab/arrows in graph, tree, sidebar), visible focus indicators for keyboard users.

---

## New features

- **Search & filter on graph** — filter visible nodes by content type (data/delete), file format, partition values, file name pattern, or snapshot operation.

- **Export** — graph as PNG/SVG; node details as JSON/CSV.

- **Snapshot diff / compare** — structured comparison of two snapshots: added/removed manifests and files, net record count change.

- **Different layout algorithms** — top-to-bottom, force-directed, or compact tree as alternatives to the current left-to-right layered layout.

- **Remote storage** — read metadata from S3, HDFS, ADLS, GCS (via Hadoop FileSystem API or cloud SDKs).

- **Nested warehouse scanning** — recursive discovery of Iceberg tables in deeper directory structures (currently only one level deep).

- **IntelliJ IDEA plugin** — repackage as a tool window plugin via `ComposePanel`.

- **Auto-update** — "Check for updates" button in About dialog that queries GitHub Releases API.

- **Crash reporting** — uncaught exception handler that writes to a log file and shows "Copy error details" dialog.

- **Telemetry (opt-in)** — anonymous usage analytics to inform feature prioritization.

- **Apache Paimon support** — second table format. Architecture is prepared: `IcebergGraphBuilder` extracted, `TableFormatDetector` in place, `GraphLayoutService.layoutNodes()` is format-agnostic.

---

## Testing

- **Integration tests** — create minimal Iceberg table fixtures (metadata JSON + manifest Avro) in `src/test/resources/` and test the full pipeline: read -> model -> layout -> verify node/edge counts.

- **IcebergReader tests** — test Avro deserialization with real manifest list and manifest file samples.

---

## Build & infrastructure

- **macOS code signing** — without signing, macOS shows "unidentified developer" warning. Requires Apple Developer Program ($99/year).

- **Reproducible builds** — pin transitive dependency versions via Gradle lockfiles.

- **Documentation site** — GitHub Pages with installation guide, user guide with annotated screenshots, and troubleshooting FAQ.

- **.idea/ cleanup** — IDE-specific files tracked in git from before `.gitignore` rule. Run `git rm -r --cached .idea/` to untrack.

- **Monitor `material-icons-extended-desktop`** — pinned to `1.7.3` (latest available) while rest of Compose is `1.10.x`. Update when a newer version is published.
