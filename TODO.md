# Roadmap

---

## The IDE plugin's zip is 106 MB, and 80 MB of that is DuckDB

`intellij/build/distributions/iceberg-lens-*.zip` bundles all of `:core`, and `duckdb_jdbc` alone
is 80 MB of native libraries for every platform. The tool window does not currently use DuckDB —
it reads no sample rows and opens no remote table, both of which are reached only from the desktop
shell — so excluding it would take the plugin to roughly 25 MB.

It is **not** excluded, deliberately. `SampleRowReader` and `ObjectStorage` are on `:core`'s public
surface, so an exclusion turns "a feature the plugin does not use yet" into a `NoClassDefFoundError`
the first time somebody wires one up, and that failure would land on a user rather than on a build.
The honest fixes are one of:

- split `:core` so the DuckDB-backed readers are a module the plugin can leave out, which also
  makes the `core` boundary say what it means; or
- ship a platform-specific DuckDB (`duckdb_jdbc` has per-OS classifiers) once the plugin needs it.

Neither is worth doing before the plugin has a reason to read a data file.

---

## Format coverage gaps

These are the differences between "renders the metadata tree" and "answers the questions a
table-format engineer opens a debugger for". Ordered by how often the question comes up.

- **Scan pruning is answered end to end, and what is left is the operators nothing records a bound
  for.** Both stages are modelled — manifests by partition summary, files by their own column
  bounds — and the panel reports which and why (`model/ScanPruning.kt`). The filter is a boolean
  expression (`model/ScanFilter.kt`) written either as rows or as a `WHERE` clause
  (`model/ScanFilterParser.kt`), with `OR`, `NOT`, grouping, `IN`, `BETWEEN` and `LIKE`. Three
  operators decline on purpose rather than guess, each with its reason on screen: every non-equality
  comparison on a `bucket[N]` field, since a range of bucket numbers says nothing about a range of
  values; a pattern that pins no leading text; and `NOT LIKE` where a transform folded many values
  into one. What has no way in at all is a comparison **between two columns**, and nothing recorded
  in a manifest could answer one anyway.

- **Iceberg v3 is half-modelled.** A deletion vector's Puffin blob is now opened and its
  positions decoded (`service/PuffinReader.kt`), so the inspector answers which rows a vector
  deletes rather than only where the blob sits. What is still unsurfaced: **row lineage**
  (`first-row-id`, `added-rows`, `_row_id`, `_last_updated_sequence_number` — **checked**: the v3
  fixture's `metadata.json` carries no row-lineage field at all, so Iceberg 1.8.1 writes none and
  this needs a newer image rather than different code here) and the **variant /
  geometry / geography / timestamp_ns** types. What the vector work does *not* cover: an Iceberg
  **positional delete** file (v2) marks no rows, because its targets are one per row and only
  known after reading the file — the same reason there is no `e_dv_*`-style edge for it.

- **Delete-file targeting is drawn where the format records it, and only there.** A v3 deletion
  vector's `referenced_data_file` is now an edge (`e_dv_*`, withheld from ELK). The two cases
  that have no edge to draw are the interesting remainder: a positional delete file names its
  targets one per row, so the link exists but at row granularity and only after reading the file;
  an equality delete has no target at all. Drawing the first would mean reading every delete row
  at graph-build time, which is the cost aggregation exists to avoid.

- **The branch columns are exercised at three branches now, and it found a defect.**
  `example/iceberg/default/branched3` forks three times at three different points, and building it
  showed the main line changing column halfway down with the trunk's column labelled `staging` —
  `lineageChildren` gave the parent's column to whichever child was written first, which on any
  long-lived branch is not the trunk. Fixed by preferring the `main` tip's ancestors in the sibling
  order. What is still not covered is a branch forked from another *branch*: `CREATE BRANCH` takes
  the table's current snapshot and the `AS OF VERSION` form needs a snapshot id that is not known
  until the script has run, so it needs a second pass over the fixture.

- **A statistics blob's sketch is never decoded.** The `.stats` container is opened now and its
  footer shown against what `metadata.json` records (`model/TableStatistics.kt`), so a stale record
  or a cleaned-up file is visible. What is not done is reading the theta sketch itself — it needs
  the datasketches library, and it yields no figure the `ndv` property does not already carry, so
  the only thing it would add is catching an `ndv` that disagrees with its own sketch.
- **`partition-statistics` is typed but has never been seen with a value in it.** Iceberg 1.8.1 —
  the version in the fixture image — has no `compute_partition_stats` procedure, so
  `docs/fixtures/stats.sql` cannot produce one and the three modelled fields are written from the
  spec with no oracle. Needs a newer Iceberg in the image, not a different statement.

---

## Bugs

- **Pinch zoom not working** — trackpad two-finger pinch gesture doesn't fire on all platforms. Needs platform-specific testing.

---

## Aggregation

The graph draws a page of siblings per parent and folds the rest into an expandable group
(`GraphAggregation`). The page size is a setting, expanding a whole group is one action, the
canvas states what it is not drawing, and rows go through the same pass as everything else. What
that leaves open:

- **The badge's menu is captured as items, not as a menu.** `GraphOptionsMenuItems` is a
  composable of its own and `badge-menu-1.png` renders three states of it. What is still not
  covered is the popup itself — its position, and whether it fits on a short window. Seeding
  `menuOpen` and rendering the real `DropdownMenu` was measured: it drew at one scene height and
  not at another, so the popup needs a different technique than an `ImageComposeScene`.


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

- **The delete side is answered, metadata first and then contents.** `model/DeleteAssignment.kt`
  pairs each delete file with the data files it can reach — by sequence number and by the
  `file_path` bounds a positional delete records about itself — so a *dangling* delete is named as
  one, and `SampleRowReader.queryDeletedRowCount` then reads only the narrowed candidates: one
  `count(DISTINCT pos)` gives how many of a data file's rows are gone, which is what makes a **live
  row count** possible at all. Equality deletes stay out of both: they match by value, so no bound
  and no path links them to any file, and counting what they remove means evaluating a predicate
  over the data rather than reading the delete.

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
- **Paimon's per-entry drill-down — done, as a replay trace.** The Iceberg panel lists what each
  entry contributed and which rule dropped it; Paimon now lists what each entry *did to the live
  set* and what state it met, which is the only honest form the question takes when an entry's
  meaning depends on the entries before it. Four effects — added, replaced, removed, removed
  (absent) — emitted by `replayPaimonSnapshot` under a `traceFor`, so the trace and the figures
  come out of one walk and the test asserts the trace sums to the contribution it explains.

  `example/paimon/db.db/dv` now reaches `REMOVED` on Spark-written bytes three ways — two upgrade
  compactions (`DELETE` at level 0, `ADD` of the same file at level 5, contributing nothing) and
  the deletion vector's commit (a pure removal, contribution −1/−3 against the snapshot's own
  `deltaRecordCount`). That fixture is also what showed the sum-oracle's limit: a `DELETE` read as
  an `ADD` kept the trace and the contribution in perfect agreement, because both come from one
  walk, and only the writer's figures caught it. Still unreached by any real table: `REPLACED`
  (an `ADD` over a file still live — needs an overwrite) and `REMOVED_ABSENT` (a `DELETE` of a
  file the base never listed — needs a re-applied or rolled-back commit). Both stay as constructed
  snapshots, which is the right level for a rule this repository owns.

---

## Code quality

- **`App.kt` is ~850 lines and the next block is the tool-window layout.** The toolbar is out
  (`Toolbar.kt`), along with `AppState` and `AboutDialog`. What remains inline is the
  three-pane arrangement with its drag handles and persisted sizes — about 300 lines, and unlike
  the toolbar it is not a component with a boundary: the panes, the dividers and the visibility
  flags are one interlocking thing. Extracting it means designing that boundary first.

---

## Performance

- **Compose rendering was measured, and it is not the bottleneck.** `CanvasRenderPerformanceTest`
  draws N cards with every one of them inside the viewport. Steady-state cost is **linear** at about
  6µs a node above 500, so a 16ms frame holds roughly **2,600 visible nodes**; 4,000 draws at about
  24ms a frame, which is degraded but not a stall. The first frame is the expensive one at about
  0.23ms a node — 4,000 nodes ≈ 1s, the same order as ELK's layout at that size — so opening a
  large table costs roughly twice what the layout alone suggested, and panning it costs almost
  nothing. What the app actually draws is far below this: aggregation caps siblings per parent, and
  600 nodes is 5ms a frame. **Level-of-detail rendering and node virtualisation are not justified**
  on these numbers. What would change the answer: a page size in the thousands, or a card that
  starts costing materially more to draw. Not measured here: on-screen frame time, which adds vsync,
  present and GPU compositing on top of the CPU work these numbers cover.

---

## UI / UX

- **The Paimon cards are tightened — done, once a second fixture existed.** `CardHeightTest` now
  sweeps `test` and `dv`, and the worst it measures is the same for both, so the heights were cut
  to measured-plus-four like the Iceberg kinds: manifest list 80→42, schema 80→54, manifest 80→64,
  snapshot 84→66. The manifest-list card also stopped printing the words "Manifest List" under an
  eyebrow that already said `PAIMON DELTA`; the noun is the eyebrow and the value line is the
  count of manifests the list names, which is on the node now (`manifestCount`) because the
  children it draws may be a page. `cl` has since added a changelog manifest list, an `OVERWRITE`
  and an `ANALYZE` commit, and the sweep measures the same worst on all three fixtures.

- **`TableNode` and `ErrorNode` have 11dp and 16dp of reserve.** Measured and bounded; left alone
  because neither is a repeated node — a graph draws one table root and, on a healthy table, no
  errors at all, so the space costs nothing a reader scrolls past.

- **Only the group card is swept for width, and the middle ground was checked rather than
  swept.** `GroupCardWidthTest` covers it because its whole content is app-composed vocabulary;
  every other card prints a path or a name the table decides, where ellipsis is the design. The
  lines that mix a fixed label with a table value (`Current Snap: <id>`, `Stage: <stage>`,
  `Target: <file>`, `PK: <keys>`) all put the label first in a single `Text`, so ellipsis takes the
  value's tail and the label survives; the value is either bounded (a `Long`) or `maxLines`-capped.
  A sweep would measure the label surviving on every card, which is the case already.

- **The identity table at the top of a panel still cannot be folded, and now does not need to
  be — on the table node.** Decided from `table-node-folded-1.png` rather than argued: the
  identity is what the reader selected the node to see, so folding it was the wrong fix. What was
  wrong was its contents. Three of the table's eleven rows were timestamps, each rendering local,
  UTC and epoch, so they were nine lines and about 600dp of the ~1,300dp standing between
  "Collapse all" and the list it produces — and none of the three is identity. They are now a
  folded `Table Times` section and the folded panel fits a screen. The other node kinds were
  checked and are a different case: a snapshot's `Timestamp` and a Paimon data file's
  `Creation Time` are recorded, singular, and part of what identifies the artifact.
  `MetadataNode` was the one left long and is now decided, on the same evidence: nine of its
  twenty-one rows were the sizes of sections drawn right below them, so the count moved onto the
  section (`CountedSection`) and the rows went. Twelve remain — four that name the file, one
  timestamp, three allocation counters and four pointers into the sections — none duplicated
  anywhere else. What is still unasked is whether the counters and pointers are identity or
  content; they were kept because each is a single fact a reader opens a metadata version for.

- **The keyboard list is closed.** Delete / Backspace and `Alt + Up / Down` edit the workspace
  list, `KeyboardReachTest` drives Tab through the tool-window bar and a pane's close, and
  `focusRing` now draws on the inspector's copy buttons too. The filter form's controls were left
  with Material's own indication: a `TextButton`'s state layer is drawn in the accent and reads as
  focus, while an icon button's is drawn in the icon's muted tint and does not, which is where the
  ring is worth having.

  Two things this settled that were wrong before. **Focus does follow the scroll** — a panel
  several screens tall brings the focused control into view on its own, so the ring cannot land off
  screen, and `a copy button draws focus, and the panel scrolls to keep it in view` counts the
  ring's ink at three tab depths to say so. And **the byte-identical captures that justified the
  ring in the first place were an artifact of the test harness**: `ImageComposeScene.render()`
  defaults `nanoTime` to the constant `0`, so every animation in every capture was drawn at time
  zero and Material's focus fade never ran. The ring is kept, for the reason above rather than the
  one recorded at the time; the harness now passes a clock.

---

## New features

- **Search on the graph — done; filtering deliberately not.** `Ctrl/Cmd+F` opens a find bar on the
  canvas. Content type, file format, partition values, file name and snapshot operation are all
  queries that work, because `GraphSearch.searchableText` gives each node kind a vocabulary rather
  than reusing the one-line label the tree prints.

  The *filter* half of this item was not built, and the reason is worth keeping. Hiding
  non-matching nodes fights the rule the group cards exist to enforce — nothing leaves the graph
  silently — and would need a second "why is this missing" story alongside aggregation and the
  snapshot filter. Highlight-and-step answers the question a reader actually arrives with ("where
  is this file?") without removing anything. If filtering is wanted later it should be a third
  named reason in the status badge's arithmetic, not a fourth way for a node to vanish.

- **Export — done.** Graph as PNG and SVG, the graph as JSON, and the file inventory as CSV, from a
  menu in the toolbar. The node-details half of the original item became the CSV: a spreadsheet of
  every data and delete file with path, format, records, bytes and partition is what a reader
  actually wants outside the app, where a dump of one node's panel is not.

- **Snapshot compare exists, and its way in is selection.** `model/SnapshotDiff.kt` answers "what
  is different between these two" for any pair, and selecting two snapshots on the canvas opens
  it. Two things are unfinished. Selection is the only route — there is no way to pin one snapshot
  and step the other through history, which is what comparing a branch against successive points
  on `main` wants. And the comparison is Iceberg-only: `PaimonSnapshotNode` has no
  a second snapshot to compare against: `example/paimon/db.db/test` has exactly **one commit**, so
  the two-snapshot path is exercised only against an empty other side and the real pair is
  untested. Writing a second commit needs Flink in docker — the same blocker as the Paimon card
  heights and the third-branch fixture.

- **Different layout algorithms — done, with one limit stated.** Layered left-to-right (default),
  layered top-to-bottom, tree and force-directed, from a toolbar menu, persisted.

  The limit: the layered post-processing — chronological ordering, parent alignment, overlap
  prevention, the branch column — runs only under the left-to-right layout, because every pass is
  defined against that axis. The other three are ELK's own output. Transposing the passes for the
  downward layout is a real piece of work and would mainly buy the branch column back; it is worth
  doing if the downward layout gets used, and `GraphLayoutAlgorithm.refinesLayers` is where it
  attaches.

- **Remote storage — done for object storage, open for HDFS and ADLS.** `s3://`, `gs://`, `gcs://`
  and `r2://` are read through a `java.nio` `FileSystemProvider` over DuckDB
  (`service/ObjectFileSystem.kt`). `hdfs://` and `abfs://` are not: neither is a DuckDB scheme, so
  each needs its own provider, and HDFS in particular drags in the Hadoop client.

- **IntelliJ IDEA plugin — done, and *not* through `ComposePanel`.** That was the plan and it does
  not work: IntelliJ ships its own Skiko, a plugin cannot override a platform class, and bundling
  Compose produces `UnsatisfiedLinkError` on the first text layout. The plugin draws with the IDE's
  own `Tree` and `JBTable` over `:core` — see the architecture note in CLAUDE.md.

- **Auto-update** — "Check for updates" button in About dialog that queries GitHub Releases API.

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
  | `default/stats` | a Puffin statistics file — four theta sketches, one per column |
  | `default/branched3` | three branches forked at three points, plus a tag on the trunk's tip |
  | `default/expired` | four commits, then `expire_snapshots(retain_last => 1)` — three expired snapshots the older metadata versions still list |
  | `default/maint` | merge-on-read, then `rewrite_position_delete_files` (two dangling deletes dropped) and `rewrite_manifests` (created 2, kept 0) |
  | `default/v1` | format-version 1, upgraded to 2 in place — v1 manifests under v2 metadata, and a delete after the upgrade |
  | `paimon/db.db/test` | a real Flink/Paimon table, with a `HASH` index |
  | `paimon/db.db/dv` | a Spark-written primary-key table with a deletion vector |
  | `paimon/db.db/cl` | `changelog-producer = input`: changelog files, an overwrite, an `ANALYZE` with statistics |
  | `paimon/db.db/tg` | a tag, then `expire_snapshots` — a snapshot retained by its tag only |
  | `paimon/db.db/pt` | partitioned by a date and a string — the `_PARTITION` decoder's oracle is the directory layout |

  **Still missing:** both path layouts are built at runtime rather than checked in — the
  `write.metadata.path` one by `RecordedPathResolutionTest`, the data-outside-the-table one by
  `PathResolutionTest` — since each is a rearrangement of the minimal fixture rather than a new
  table. The data-file one is synthetic in a way the metadata one is not: its manifest is written
  by `avro4k` rather than by an engine, so it proves the resolver and not the shape a real
  `write.data.path` table has.

- **The rendered inspector is mostly checked by eye; one invariant is now a number.** Every
  `WideTable`'s leading column is asserted to fit the panel it is drawn in, over the 28 tables the
  six narrow panels draw (`LocalWideTableProbe`). The other invariant that item named — that the
  scrollbar appears exactly when the table overflows — was looked at and left: `WideTable` shows it
  on `horizontalState.maxValue > 0`, so asserting it would be asserting Compose's `horizontalScroll`
  contract. What stays eye-only is everything a number cannot state.

  Card clipping is no longer in this bucket: `CardHeightTest` draws each card inside
  `LocalCardHeightSlack` and asserts the content fitted the height its node declares. What remains
  eye-only is everything a number cannot state — whether the lines that fit are the right lines,
  in the right order, at weights a reader can rank.

- **Iceberg pipeline fixtures on disk — checked 2026-09-05, and neither half of the case holds.**
  The idea was to snapshot the runtime-written `avro4k` fixtures into
  `src/test/resources/iceberg-fixtures/` to speed the tests up and pin Avro schema details against
  drift.

  *Speed:* measured empty. The core suite is **29.6s, of which 24.6s is `ElkScalingBench`**; every
  class that writes Avro at runtime is under 0.2s and most under 0.08s. There is no time here to
  recover.

  *Drift:* already covered, and better. The classes these fixtures exercise — `ManifestEntry`,
  `DataFile`, `ManifestFile` — are read from **eight engine-written tables** in `example/` on every
  run. Freezing our own writer's bytes would pin today's schema against tomorrow's; the real tables
  pin the reader against what Iceberg actually writes, which is the stronger claim and is already
  being made.

  And most of what these tests construct is *deliberately malformed* — a truncated file, an empty
  one, zero-record Avro, a corrupt manifest, a version-hint that exists but cannot be read. There
  the construction **is** the specification of the case, and a checked-in blob would hide what
  "truncated" means from the next reader. Writing them at runtime is the right call rather than a
  compromise. Closed.

---

## Build & infrastructure

- **macOS code signing** — without signing, macOS shows "unidentified developer" warning. Requires Apple Developer Program ($99/year).

- **Reproducible builds — done.** `dependencyLocking` is on for every configuration and the
  lockfiles are committed: 272 modules on desktop, 78 on core, versions that were previously
  decided by conflict resolution at each build. `./gradlew resolveAndLockAll --write-locks`
  regenerates after a dependency change, and the build fails until it is run.

- **Documentation site** — GitHub Pages with installation guide, user guide with annotated screenshots, and troubleshooting FAQ.

- **`material-icons-extended-desktop` stays at `1.7.3`, and there is nothing to update to.** Checked
  2026-09-05: the JetBrains coordinate on Maven Central stops at `1.7.3`; the androidx coordinate
  on Google Maven (`androidx.compose.material:material-icons-extended-desktop`) reaches `1.7.8` and
  stops there too. The icons library is frozen at 1.7.x upstream and coexists with Compose `1.10.x`
  either way, so the five patch versions on the other coordinate are the whole difference.
