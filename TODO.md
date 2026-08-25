# Roadmap

---

## Format coverage gaps

These are the differences between "renders the metadata tree" and "answers the questions a
table-format engineer opens a debugger for". Ordered by how often the question comes up.

- **Pruning is answered for a filter, but the filter is a form and not a clause.** Both stages are
  modelled now — manifests by partition summary, files by their own column bounds — and the panel
  reports which and why (`model/ScanPruning.kt`). Two gaps remain. `bucket[N]` equality is
  evaluated now (`model/BucketTransform.kt`, checked against the buckets Spark recorded at two
  bucket counts), and every other operator on a bucket field still declines, correctly — a range of
  bucket numbers says nothing about a range of values. A `WHERE` clause would be more familiar than
  the form and is worth having, at the cost of a second place where a literal is read. And **the
  predicates are a conjunction only**: there is no `OR`, no `NOT` and no grouping, which is fine
  for "why did this read so much" and wrong for reproducing a real query's plan.

- **Iceberg v3 is half-modelled.** A deletion vector's Puffin blob is now opened and its
  positions decoded (`service/PuffinReader.kt`), so the inspector answers which rows a vector
  deletes rather than only where the blob sits. What is still unsurfaced: **row lineage**
  (`first-row-id`, `added-rows`, `_row_id`, `_last_updated_sequence_number`) and the **variant /
  geometry / geography / timestamp_ns** types. What the vector work does *not* cover: an Iceberg
  **positional delete** file (v2) marks no rows, because its targets are one per row and only
  known after reading the file — the same reason there is no `e_dv_*`-style edge for it.

- **Delete-file targeting is drawn where the format records it, and only there.** A v3 deletion
  vector's `referenced_data_file` is now an edge (`e_dv_*`, withheld from ELK). The two cases
  that have no edge to draw are the interesting remainder: a positional delete file names its
  targets one per row, so the link exists but at row granularity and only after reading the file;
  an equality delete has no target at all. Drawing the first would mean reading every delete row
  at graph-build time, which is the cost aggregation exists to avoid.

- **The branch columns have never been seen with three branches open at once.** Branches take
  separate columns (`service/SnapshotTracks.kt`), lineage edges are dashed, and each column now
  carries its branch name above it (`snapshotColumns`), so a divergence reads as one and the
  column can be read without following the dashes back.
  `example/iceberg/default/branched` covers a single fork, which is where the fixture stops and
  where the column assignment stops being exercised — the reuse rule, the held reservation and
  the header layout all have their interesting cases at three. A second branched fixture needs
  docker, same as the row-lineage and statistics-file gaps below.

- **Statistics and partition-statistics files are untyped.** Held as `List<JsonElement>` and
  rendered as raw JSON; the Puffin blobs they point at (NDV sketches, etc.) are never opened.

- **Data-file paths now resolve recorded-first, like the manifests.** What is left is that the
  two rules are written twice — `resolveRecordedOrRelative` for metadata, an inline branch in
  `UnifiedManifest` for data files, because the fallbacks genuinely differ (file name against the
  metadata dir; sub-path rebuilt under the table root). A shared function taking the fallback as
  a parameter would keep the recorded-first half in one place.

---

## Bugs

- **Pinch zoom not working** — trackpad two-finger pinch gesture doesn't fire on all platforms. Needs platform-specific testing.

---

## Aggregation

The graph draws a page of siblings per parent and folds the rest into an expandable group
(`GraphAggregation`). The page size is a setting, expanding a whole group is one action, the
canvas states what it is not drawing, and rows go through the same pass as everything else. What
that leaves open:

- **The page size is offered as six fixed choices** (`AppState.GRAPH_PAGE_SIZE_CHOICES`), with no
  way to type a number. `updateGraphPageSize` accepts anything between 2 and 2,000, so the
  restriction is the menu's alone.

- **Changing the page size drops every cached session but the one on screen.** Correct — a graph
  drawn at the old size disagrees with the badge above it — but it means the next visit to
  another table re-reads it from disk. Rebuilding those graphs from their retained table models
  would keep the read.

- **The badge's menu is the one control no capture covers.** `DropdownMenu` opens from state a
  render never reaches — `menuOpen` is a `remember` inside the composable — so the page-size
  check mark, the "Draw all N nodes" item and its disabled states are asserted by
  `AppStateAggregationTest` and looked at by nobody. Hoisting `menuOpen` to a parameter would
  make it capturable, at the cost of a parameter that exists for the test.


---

## Derivation traces

The table summary's `current` and `history` figures are folded from a per-manifest ledger and
the inspector prints it (see `StatsDerivation` in `model/GraphTypes.kt`). Nothing else is
covered yet, and each of these is a computed number a reader currently has to trust:

Covered so far: the manifest's recorded counts against the same figures folded from its entries
(`manifestTallies`), the per-entry ledger under them (`model/ManifestLedger.kt`), the decoded
bound beside the bytes and the field id it came from, the snapshot summary marked as reported
rather than computed, and a per-manifest verdict on the snapshot's manifest-list overview.

What is left:

- **The delete side's ledger stops at one delete file.** "Read the file" on a positional delete
  file now names every data file it deletes from and how many rows out of each
  (`SampleRowReader.queryPositionalDeleteTargets`, aggregated by DuckDB so an unbounded read is
  one click). What has no equivalent is the other direction: standing on a **data file**, how many
  of its rows are deleted and by which delete files. That is a scatter-gather over every delete
  file in the snapshot rather than one file open, so it needs its own decision about where the
  cost sits. Equality deletes stay out of both — they match by value with no link to any file.
- **Cross-manifest deduplication is invisible from a manifest — and measurement says the case is
  empty where the panel would show it.** The drill-down scopes deduplication to the manifest on
  screen and says so; naming *which* manifest counted a file first would need the table's claim
  map plumbed to the manifest node. Counted before building it, across all eight Iceberg
  fixtures: entry-level duplicates in the **current** reading, which is what the manifest panel
  shows (`liveEntriesOnly = true`), are **0 on every fixture** — inside one snapshot's closure a
  data file is listed by exactly one manifest, so there is nothing for the annotation to say. The
  five that exist are all in `mor`'s **history** reading, from its compaction, where old and new
  manifests both name a path; that reading is shown on the *table* node, whose ledger already
  names `firstCountedIn` per manifest but not per file. So the useful version of this is the
  file-level claim on the **table's** history derivation, not on the manifest panel, and a
  fixture with two manifests listing one file inside a single snapshot would be needed before the
  manifest-panel version explains anything at all.
- **Paimon has no per-entry drill-down, and cannot have the shared one.** The accumulation now
  lives in `model/PaimonReplay.kt` and emits its per-manifest contributions and its live file set
  from one walk, so the comparison and the figures cannot disagree. What is still missing is the
  level below: the Iceberg panel lists what each *entry* contributed and which rule dropped it
  (`manifestLedger`), and Paimon has no equivalent because its entries are not independently
  decidable — a `_KIND=1` entry's effect depends on what the base already put in the map. The
  honest Paimon version is a per-entry **replay trace** (what the map held before, what this entry
  did to it), which is a different shape from the Iceberg ledger and should not pretend otherwise.

---

## Code quality

- **`App.kt` is ~850 lines and the next block is the tool-window layout.** The toolbar is out
  (`Toolbar.kt`), along with `AppState` and `AboutDialog`. What remains inline is the
  three-pane arrangement with its drag handles and persisted sizes — about 300 lines, and unlike
  the toolbar it is not a component with a boundary: the panes, the dividers and the visibility
  flags are one interlocking thing. Extracting it means designing that boundary first.

---

## Performance

- **Profile Compose rendering for large graphs** — ELK layout is fast (benchmarked up to 4000 nodes at ~1.3s). Viewport culling is in place but Compose rendering at scale (thousands of visible nodes simultaneously) has not been measured. Consider level-of-detail rendering or node virtualization if it becomes a bottleneck.

---

## UI / UX

- **The Paimon cards still reserve more than they use.** Every card's height is now a bound rather
  than a sample — each `Text` a table's content can lengthen is capped, and `CardHeightTest`'s
  stress pass measures the capped worst — and the Iceberg kinds were tightened against it. The
  Paimon ones were not: `PaimonManifestListNode` measures 38dp of a declared 80, `PaimonSchemaNode`
  50 of 80, `PaimonManifestNode` 60 of 80, `PaimonSnapshotNode` 62 of 84. The reason for leaving
  them is the fixture — there is exactly one Paimon table checked in, with one snapshot, so a
  conditional line that never appears in it would be invisible to the measurement. A second Paimon
  fixture (more snapshots, a changelog manifest list, an ANALYZE commit) settles it, and the
  numbers are printed on every run either way.

- **`TableNode` and `ErrorNode` have 11dp and 16dp of reserve.** Measured and bounded; left alone
  because neither is a repeated node — a graph draws one table root and, on a healthy table, no
  errors at all, so the space costs nothing a reader scrolls past.

- **Only the group card is swept for width, and the reason is a real gap.** `GroupCardWidthTest`
  covers it because its whole content is app-composed vocabulary; every other card prints a path
  or a name the table decides, where ellipsis is the design. What is unchecked is the middle
  ground — a card line that mixes a fixed label with a table value (`Current Snap: <id>`,
  `Stage: <stage>`), where the label can be squeezed out by the value with nothing failing.
  Catching that needs the two halves measured separately, which means the card telling the test
  which part is ours.

- **The identity table at the top of a panel still cannot be folded, and now does not need to
  be — on the table node.** Decided from `table-node-folded-1.png` rather than argued: the
  identity is what the reader selected the node to see, so folding it was the wrong fix. What was
  wrong was its contents. Three of the table's eleven rows were timestamps, each rendering local,
  UTC and epoch, so they were nine lines and about 600dp of the ~1,300dp standing between
  "Collapse all" and the list it produces — and none of the three is identity. They are now a
  folded `Table Times` section and the folded panel fits a screen. The other node kinds were
  checked and are a different case: a snapshot's `Timestamp` and a Paimon data file's
  `Creation Time` are recorded, singular, and part of what identifies the artifact.
  `MetadataNode` is the one left long — 22 identity rows, only two of them timestamps, so nothing
  here applies to it and it needs its own decision about what a metadata file's identity is.

- **Keyboard coverage stops at the four navigable surfaces.** The canvas, the structure tree, the
  workspace and the inspector's section headers all take the keyboard and show focus. What has no
  keyboard path yet: removing a workspace root (mouse-only `×`), reordering roots (drag only), the
  tool-window bars, and the copy buttons inside the inspector. None is on a reading path, which is
  why they are last, but "reachable by Tab" is still the bar.

---

## New features

- **Search & filter on graph** — filter visible nodes by content type (data/delete), file format, partition values, file name pattern, or snapshot operation.

- **Export** — graph as PNG/SVG; node details as JSON/CSV.

- **Snapshot compare exists, and its way in is selection.** `model/SnapshotDiff.kt` answers "what
  is different between these two" for any pair, and selecting two snapshots on the canvas opens
  it. Two things are unfinished. Selection is the only route — there is no way to pin one snapshot
  and step the other through history, which is what comparing a branch against successive points
  on `main` wants. And the comparison is Iceberg-only: `PaimonSnapshotNode` has no
  a second snapshot to compare against: `example/paimon/db.db/test` has exactly **one commit**, so
  the two-snapshot path is exercised only against an empty other side and the real pair is
  untested. Writing a second commit needs Flink in docker — the same blocker as the Paimon card
  heights and the third-branch fixture.

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
  | `default/respec` | two partition specs in one table — a dropped field, a rebucketed one, `days` replaced by `months` |
  | `default/branched` | a fork, four refs across five commits, one snapshot with two, and ten metadata versions |
  | `paimon/db.db/test` | a real Flink/Paimon table |

  **Still missing:** both path layouts are built at runtime rather than checked in — the
  `write.metadata.path` one by `RecordedPathResolutionTest`, the data-outside-the-table one by
  `PathResolutionTest` — since each is a rearrangement of the minimal fixture rather than a new
  table. The data-file one is synthetic in a way the metadata one is not: its manifest is written
  by `avro4k` rather than by an engine, so it proves the resolver and not the shape a real
  `write.data.path` table has.

- **The rendered inspector is checked by eye, not asserted.** `InspectorRenderTest` proves the
  panel composes without throwing and writes PNGs to look at, but its only assertion about the
  drawing is that the image is not blank. Layout invariants worth pinning numerically: the
  scrollbar exists exactly when the table is wider than the panel, and the leading columns fit
  within the panel width. Neither is expressible without measuring the composition.

  Card clipping is no longer in this bucket: `CardHeightTest` draws each card inside
  `LocalCardHeightSlack` and asserts the content fitted the height its node declares. What remains
  eye-only is everything a number cannot state — whether the lines that fit are the right lines,
  in the right order, at weights a reader can rank.

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
