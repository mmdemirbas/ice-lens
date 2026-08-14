# Roadmap

---

## Format coverage gaps

These are the differences between "renders the metadata tree" and "answers the questions a
table-format engineer opens a debugger for". Ordered by how often the question comes up.

- **Manifest field summaries are not read.** `manifest_file.partitions` carries per-partition-
  field lower/upper bounds and null/NaN flags — the data that decides whether a manifest is
  skipped during planning. Without it the tool can show *that* a manifest exists but not *why*
  the planner would or would not open it.

- **Iceberg v3 is unmodelled.** Parsed without error — now *verified* rather than assumed,
  against `example/iceberg/default/v3` — but none of its additions are surfaced: deletion
  vectors (`content_offset` / `content_size_in_bytes` / `referenced_data_file` on the data file,
  with the vector living in a Puffin blob), row lineage (`first-row-id`, `added-rows`,
  `_row_id`, `_last_updated_sequence_number`), and the variant / geometry / geography /
  timestamp_ns types. Iceberg 1.8.1 writes deletion vectors for a v3 merge-on-read table, so the
  fixture exercises the real thing; today a vector is distinguishable from a v2 delete file only
  by its `.puffin` extension, since both declare `content = 1`.

- **Equality deletes are read but not explained.** `equalityIds` is parsed and shown, and
  `example/iceberg/default/eqdel` covers it. What is missing is the UI statement: an equality
  delete applies by predicate over identifier fields, so the format contains *no* link between
  it and the data files it affects. The inspector should say that where a reader would look for
  the link, rather than leaving an empty relationship to be misread as "none".

- **Branch topology is drawn as a chain, not as a shape.** Lineage edges and `refs` now exist
  (`GraphEdge.affectsLayout`, `SnapshotNode.refs`), so the graph shows the commit history and
  which snapshots a branch or tag holds. What it does not yet do is *lay out* a branch as a
  branch: a table with two branches draws both chains through the same vertical ordering, so
  the fork is legible only by following the edges. No fixture has branches yet either.

- **Statistics and partition-statistics files are untyped.** Held as `List<JsonElement>` and
  rendered as raw JSON; the Puffin blobs they point at (NDV sketches, etc.) are never opened.

- **`TableMetadata.lastSequenceNumber` is `Int?`** where the spec says `long`. Unreachable in
  practice (it would need 2^31 commits) but it is a plain type error against the spec.

- **Two path-resolution strategies coexist.** `resolveForceRelative` deliberately discards the
  recorded directory and re-resolves every manifest list and manifest against the local
  `metadata/` dir, which is what makes a table copied down from S3 openable. It also means a
  table using `write.metadata.path`, or any layout where metadata does not sit beside the
  data, resolves to the wrong place and reports a missing file. Worth trying the recorded path
  first and falling back.

---

## Bugs

- **Pinch zoom not working** — trackpad two-finger pinch gesture doesn't fire on all platforms. Needs platform-specific testing.

---

## Derivation traces

The table summary's `current` and `history` figures are folded from a per-manifest ledger and
the inspector prints it (see `StatsDerivation` in `model/GraphTypes.kt`). Nothing else is
covered yet, and each of these is a computed number a reader currently has to trust:

- **Manifest node summaries** — the per-manifest counts on `ManifestNode` and its card.
- **Column statistics** — a bound is decoded from bytes against a schema; the trace would name
  the schema key, the field id and the raw bytes it came from. The raw bytes are already shown,
  which is half of it.
- **Snapshot summary counters** — read from the snapshot's own `summary` map, so the trace is
  provenance ("Iceberg wrote this") rather than derivation. Worth marking as such: it is the
  one place where a number the tool shows was not computed by the tool.
- **Drill-down below a manifest** — the design allows re-running the accumulator scoped to one
  manifest, giving a per-entry ledger on demand. Not wired up.

---

## Code quality

- **Extract `Toolbar` from `App.kt`** — `App.kt` is ~1k lines; the toolbar (~250 lines) is the
  largest remaining inline block. `AppState` and `AboutDialog` have already been extracted.

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

---

## Testing

- **The suite has almost no oracle.** Every Avro fixture except `example/` is written with
  `Avro.schema<T>()` — the schema derived from the Kotlin class being tested — so writer and
  reader schemas are the same object. Those tests prove our reader agrees with our writer and
  nothing more; a field typed against the wrong spec width would pass them all and fail on
  every real table. `RealTableFixtureTest` and `PartitionDecodingTest` read the checked-in
  `example/` tables as the only genuine oracles.

  The checked-in tables, each regenerated by a script in `docs/fixtures/` whose header carries
  the container invocation and the traps in it:

  | Fixture | Covers |
  |---|---|
  | `default/test` | the minimal case: one snapshot, one manifest, unpartitioned, v2 |
  | `default/parted` | eight partition fields — identity on string and `decimal(9,2)`, `bucket`, `truncate`, all four time transforms — two snapshots, a pre-epoch row, a negative decimal |
  | `default/mor` | merge-on-read v2: three positional delete files, six commits including a `rewrite_data_files` compaction that leaves two of them dangling |
  | `default/eqdel` | both delete kinds in one table — one positional and one equality delete file, the latter written with Iceberg's own `EqualityDeleteWriter` |
  | `default/v3` | format-version 3 with two deletion vectors (Puffin), the v3 representation of what `mor` carries as parquet |
  | `default/evolved` | three manifest schemas in one table — `int`→`long`, `float`→`double`, a column renamed then dropped, one added |
  | `paimon/db.db/test` | a real Flink/Paimon table |

  **Still missing:** a partitioned table whose *spec* changed — `evolved` covers the schema side
  of the resolution rule, and `partitionResultType` has the same trap on the partition side with
  no fixture where the manifest's spec differs from the table's current one. Also: a table with
  `write.metadata.path` set, which is the layout `resolveForceRelative` gets wrong; and branches
  and tags in `refs`.

- **The rendered inspector is checked by eye, not asserted.** `InspectorRenderTest` proves the
  panel composes without throwing and writes PNGs to look at, but its only assertion about the
  drawing is that the image is not blank. Layout invariants worth pinning numerically: the
  scrollbar exists exactly when the table is wider than the panel, and the leading columns fit
  within the panel width. Neither is expressible without measuring the composition.

- **Iceberg pipeline fixtures on disk** — Iceberg pipeline tests currently write Avro
  fixtures at runtime via `avro4k`. Snapshotting representative fixtures into
  `src/test/resources/iceberg-fixtures/` (alongside the existing Paimon fixtures) would
  speed up tests and pin Avro schema details against drift.

---

## Build & infrastructure

- **macOS code signing** — without signing, macOS shows "unidentified developer" warning. Requires Apple Developer Program ($99/year).

- **Reproducible builds** — pin transitive dependency versions via Gradle lockfiles.

- **Documentation site** — GitHub Pages with installation guide, user guide with annotated screenshots, and troubleshooting FAQ.

- **Monitor `material-icons-extended-desktop`** — pinned to `1.7.3` (latest available) while rest of Compose is `1.10.x`. Update when a newer version is published.
