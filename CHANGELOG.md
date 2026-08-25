# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **A snapshot now says what its commit did.** The panel reports the files that commit added and
  removed, and puts every figure beside the same figure from the snapshot's own `summary` — the
  engine's account of its own work, which sits in `metadata.json` where nothing on the read path
  checks it. A compaction reads: 1 data file added, 5 removed, 6 records added, 8 removed, 5,851
  bytes removed, each agreeing with what Spark recorded. Attribution is by
  `manifest_file.added_snapshot_id`, because an entry's status belongs to the snapshot that
  created its manifest — counting statuses across a snapshot's whole closure would credit every
  commit with all of its ancestors' work.

- **A v3 deletion vector's rows are now readable.** Iceberg 1.8.1 writes deletes for a
  format-version-3 table as a Roaring bitmap inside a Puffin blob, and the panel could say where
  that blob sat and nothing about what was in it. The blob is now opened and decoded, so the
  inspector lists the row positions the vector marks — run-folded, so a compacted file reads
  `0-3999` rather than four thousand lines. Two figures the writer recorded sit beside the count
  decoded from the bytes: the blob's own CRC-32, and the manifest's `record_count`, which a scan
  plans against without ever opening the Puffin file. Nothing on a read path compares either, so
  this does. The reader is written against the Puffin and Roaring specs rather than against the
  checked-in fixture, which holds one array container with one position in it; the array/bitset
  boundary at 4,096 and the run container are pinned by containers built from the spec text.
- **A row a deletion vector removes is drawn as deleted.** The sampled rows of the data file a
  vector covers now carry the word, a strike through their values, and the same fade a pruned node
  gets. The position each row is judged against is asked of DuckDB (`file_row_number`) rather than
  taken from the order the rows arrived in — a scan may return them in any order and nothing in
  the result would say that it had — and it is kept off the row as a column, because it is
  DuckDB's answer about the file rather than something the table declares.

### Fixed
- **A collapsed group was losing the line that says it is a control.** `GroupNode` declared a 58dp
  base and the plainest group card measures 60.5, so any group standing for exactly its members —
  no hidden subtree, no read errors — drew "NOT DRAWN", its count, and then nothing: the
  "Double-click to open" hint went under the card's own border, where Compose clips nothing and
  reports no error. It survived the render check because every group the capture drew happened to
  stand for a subtree, and so declared the taller height. `CardHeightTest` now sweeps every node
  of every fixture in both filter states rather than one instance per kind, which is what reached
  the shape that was broken; the capture now draws it too.
- **A deletion vector is named as one.** It declares `content = 1` exactly as a v2 positional
  delete file does, so both cards read `POS DELETE` and only the `.puffin` extension told them
  apart. The card, its tooltip and the inspector title now say `DELETE VECTOR`, from one function
  rather than the three copies of the decision that existed before.
- **A file card's row count says what the number counts.** `record_count` is rows held for a data
  file, rows removed for a positional delete or a deletion vector, and predicate tuples for an
  equality delete — where one tuple can remove thousands of rows. All three read `1 row`; they now
  read `1 row`, `deletes 1 row` and `1 equality row`.
- **A file node reserves 68dp instead of 60.** `FILE 5: DELETE VECTOR — NOT READ` is the longest
  first line a file card can draw and it wraps at 200dp, and the verdict is appended when a filter
  is on — after the layout that reserved the height. The card was losing its last line silently,
  which is the failure `CardHeightTest` exists for and which it did not see, because it measured
  no pruned card and no vector. It measures both now.

- **Pruning now answers a file count, not just a manifest count.** Iceberg prunes twice — a
  manifest by the partition summaries its list records, then a file by the bounds it records about
  its own columns — and only the first stage was modelled. The panel now reports both, and leads
  with the file line, because "would read 1 of 4 data files" is the number a reader arrives with;
  a query reports how many files it read and never which. Three consequences worth knowing:
  an **unpartitioned** table is no longer told there is nothing to do here, since its file bounds
  still prune; a **bucketed** column, which no range can rule a manifest out on, still eliminates
  files, because file bounds are the plain source values; and `IS NOT NULL` can be settled at the
  file stage, which records how many values it holds and how many are null, where a manifest
  summary records only that a null exists somewhere. A file under a ruled-out manifest reads
  `not reached` rather than `skipped` — a scan never opens it, so the verdict is not its own — and
  the canvas fades every node the query does not touch, files included.

### Changed
- **A verdict column now marks only the exception.** Every leading cell in the pruning and tally
  tables was bold, because each row supplied a colour and `WideTable` bolded any cell that had
  one — including the neutral colour that exists to say "ordinary". The ordinary rows now pass no
  colour at all and read as body text, which leaves `SKIPPED` as the one bold, green cell in its
  column.
- **The toolbar is its own file.** 266 lines came out of `App.kt`'s thousand-line composable into
  `Toolbar.kt`, with the snapshot-filter menu — half of it by line count — split off again into
  its own composable. It is stateless: it reads values and reports intent, and every write to
  `java.util.prefs` stays with the caller that owns the value. The question the extraction had to
  answer was which of `App`'s mutable state the toolbar actually touched, which could not be read
  off the old code; it was eight things, and the signature now says so.
- **The two canvas modes say what they do.** The toolbar's tooltips read "Pan mode — drag the
  empty canvas to move the view" and "Select mode — drag the empty canvas to select what it covers
  (hold Shift to add or remove)". They named the modes before, which tells a reader nothing about
  the one thing the modes disagree about: a node is dragged and the wheel pans in either. Tooltip
  text now wraps at 280dp instead of running off the window.
- **Five text sizes instead of eight.** Every size in the desktop shell now comes from
  `TypeScale`, at a ratio near 1.2 — 10 / 12 / 14 / 17 / 21. What was there before ran from 8sp
  to 16sp in steps averaging 1.09x, picked one call site at a time, which is under the difference
  at which a size reads as deliberate: the screen had eight sizes and one apparent level. Card
  text is slightly larger throughout as a result, and the inspector's section titles and node
  header are now distinct from body text rather than a little heavier than it.

### Added
- **The app has an icon.** A lens set down on a stack of records: the bars are the metadata rows
  the app reads, and under the ring they turn accent-coloured. Two shapes, which is about what
  survives being drawn at 32 pixels. `tools/icon/GenerateIcon.java` draws it and writes every size
  the three installers want, including the `.icns` and `.ico` containers — both written directly
  rather than shelled out to `iconutil`, which exists only on macOS and would have made the
  Windows icon regenerable on a Mac and nowhere else. Deliberately not an iceberg and not Apache
  Iceberg's mark: the app reads Paimon too, and the name is already an open trademark question.
- **The workspace answers to the keyboard**, with one rule the other two lists do not need:
  moving the cursor does not open a table. On the canvas and in the tree the selection *is* the
  cursor, because selecting costs nothing; here, opening a table reads its whole metadata tree off
  disk, so holding Down through a warehouse of forty tables would load forty of them. The cursor
  moves, and Enter opens what it is on. Arrows expand and collapse a warehouse as they do
  everywhere else, and the cursor is drawn only while the list holds the keyboard, so it cannot be
  mistaken for a second selection.
- **The structure tree answers to the arrow keys too**, with the keymap every file browser uses:
  up and down move a line whatever its depth, right opens a closed line and otherwise steps into
  it, left closes an open one and otherwise steps out to its parent. The selection is the cursor,
  so there is no second highlight to keep in step and the existing scroll-into-view already brings
  a keyboard move on screen. The rules are stated against the flattened list rather than against
  the graph — the first child of an open line **is** the line below it — so the keyboard cannot
  disagree with what is drawn. Clicking a row hands the list the keyboard as well, and the list
  shows a border while it holds focus.
- **The graph answers to the arrow keys.** Left and right move to the parent or the child drawn
  across from you; up and down walk the column. Nothing selected and the first arrow lands on the
  root. The step is decided from where the nodes are actually drawn rather than from the order a
  comparator would put them in, which is what makes a fork behave: a branch has a column of its
  own, so walking down the main line does not step sideways into a commit that merely sits at a
  similar height. Lineage and deletion-vector edges are not steps — they are annotations over the
  tree, which is why they are drawn dashed, and following them would make one keystroke mean two
  different things by "parent". Bare arrows only, so Cmd+Left still means "back" and Alt+Arrow
  still moves by word in the field beside the canvas.
- **Inspector sections fold.** Every titled section in the inspector — 39 of them across the node
  types — now carries a caret and folds away, and the panel header has one button that collapses
  or expands the lot. Folded, a table's panel is a list of what the table holds: eight lines
  instead of ten thousand device-independent pixels of scrolling to find out that "Table
  Properties" is down there. The fold state is keyed on the stable half of the title, so a
  section whose heading carries a live count ("Manifest Entries (12)") does not silently re-open
  when the count moves, and it is held for the panel rather than per node, because a reader who
  folds "Raw metadata.json" away means it for the table.
- **A card that outgrows its node now fails a test.** `CardHeightTest` draws all thirteen card
  shapes with 400dp of room to spare and requires each to have fitted inside what its node
  declares. This was the missing half of a change to a text size: a card drawn at its own height
  does not report that it wanted more — the `Column` simply does not place its last child, every
  `Text` clips itself to what it was measured at, and nothing is painted outside the border for a
  pixel probe to find. Measuring the same card with slack is what makes the overflow a number.
- **Four more numbers explain themselves.** A manifest's inspector now says how its counted
  figures were reached — every entry takes a place in the entry count because that figure
  measures what a scan reads, and what it adds beyond that depends on two rules — and lists the
  entries that added nothing, with the reason and what each would have added. It is the same
  `manifestLedger` the table's totals are folded from, called with deduplication scoped to the
  one manifest and saying so. A column statistic's decoded bound now sits directly beside the
  bytes it was decoded from, with the field id, and names the schema it was read against: the two
  used to be at opposite ends of an eleven-column table, so the value was on screen and its
  evidence four columns past the panel edge. A snapshot's summary is marked as the one set of
  figures the tool reports rather than computes. And a snapshot's manifest-list overview carries
  a "Summary" column saying whether each manifest's recorded counts match its own entries.
- **A fork is drawn as a fork.** A branch now gets its own column inside the snapshot layer,
  assigned the way `git log --graph` does it: a commit takes the column its parent kept for it,
  the first child continues in the parent's, and every later child opens a column and holds it
  empty until the drawing reaches it — so the two branches read as parallel rather than as one
  list with an edge reaching back over it. A table with no branches is untouched: every commit
  lands in column 0 and the pass returns before moving a node. Lineage edges, and the v3
  deletion-vector edges beside them, are now dashed. They are the only edges whose two ends can
  sit side by side, and drawn solid they were indistinguishable from the parent-child edges
  crossing the same gap.
- **Enter a filter, see which manifests a scan would skip.** The table inspector takes a
  conjunction of conditions — a column, an operator, a literal — and reports, per manifest,
  whether a query carrying that filter would open it, with the term and the numbers that decided
  it: "d_day — 2024-03-06 is above 2024-03-05 … 2024-03-05". The skipped manifests fade on the
  canvas, and each manifest's own inspector shows every term's outcome against the bounds it was
  measured with. Iceberg computes this during scan planning and then discards it — a query
  reports how many files it read, never which it skipped or which predicate did the skipping —
  so it is not available from any engine surface. Each manifest is evaluated against **its own**
  partition spec, the same rule the bounds follow. The literal is bridged to the partition value
  only through order-preserving transforms; `bucket[N]` reports that it did not evaluate rather
  than a verdict that might be wrong, because pruning equality on a bucket means reproducing
  Iceberg's murmur3 and there is no oracle here for it. `SKIPPED` is a proof, "would be read" is
  only the absence of one, and a manifest nothing could be evaluated against is counted apart
  from both.
- **The canvas states what it is not drawing, and how much of a page it draws is a setting.** A
  badge in the bottom-left corner reads "Drawing 431 of 6,180 nodes" and, under it, why the rest
  are missing — "5,749 inside 37 collapsed groups", "335 removed by the snapshot filter". The two
  reasons are counted separately because they are undone separately, and each missing node is
  attributed to one of them, so the figures sum to the whole table rather than double-counting.
  Clicking the badge sets the page size, which was previously a constant only a recompile could
  change; changing it clears the expansion (a group id names a page *at a size*) and drops every
  cached graph but the one on screen (a graph drawn at the old size disagrees with the badge over
  it). The group inspector gains "Show all N manifests" beside "Show the next page", because
  5,000 manifests is 208 double-clicks at 24 to a page.

- **The graph draws a page of siblings and says what it is not drawing.** Under one parent, the
  first 24 siblings of a kind are drawn; the rest become one node that states how many there
  are and opens on a double-click, revealing a page at a time. This replaces both halves of the
  old behaviour: everything else was drawn without limit, which no production table survives,
  and data files were capped at ten per manifest, which made a manifest holding 5,000 files
  look exactly like one holding ten. The pass is a pure function over a finished graph, so both
  formats get it from one implementation. Three properties it holds, each pinned by a test that
  fails without it: a manifest shared by several snapshots survives when only one of them
  collapses it (removal is by reachability, not by subtree); every node is either drawn or
  claimed by exactly one group, so the counts add up to what actually went; and a read error is
  never folded into a group, with any error that leaves under a collapsed manifest counted on
  the group and shown in red on its card. Sample rows are now read after aggregation, for the
  data files that survived, rather than for every file in the table.
- **Summary figures explain themselves.** `TableSummary.current` and `.history` are no longer
  stored numbers; they are folded from a per-manifest ledger (`StatsDerivation` /
  `ManifestContribution`) and exposed as getters over it, so a figure cannot disagree with its
  explanation. Each contribution records what one manifest added, how many of its entries named
  a file another manifest had already counted, and — for a manifest a later snapshot re-lists —
  which snapshot counted it first. The table inspector prints the ledger directly under each set
  of figures. Paimon contributions can be negative, because it applies a snapshot's delta
  manifest list over its base and a fold has no other way to express a removal.
- **Delete files, a compaction, and format-version 3 are covered by real tables.** Three new
  fixtures, each checked against Iceberg's own metadata tables rather than against this code:
  `example/iceberg/default/mor` (merge-on-read v2 — three positional delete files and a
  `rewrite_data_files` compaction that leaves two of them dangling),
  `example/iceberg/default/eqdel` (both delete kinds in one table; the equality delete is
  written with Iceberg's own `EqualityDeleteWriter`, since Spark has no SQL that produces one),
  and `example/iceberg/default/v3` (format-version 3, whose merge-on-read deletes are Puffin
  deletion vectors rather than parquet delete files). Before these, `posDeleteFileCount`,
  `eqDeleteFileCount`, `deleteRecordCount` and the `DELETES` manifest branch were decided by
  code no real table had ever run through.
- **A recorded path is used when the file is there.** Manifest lists and manifests resolved by
  discarding the recorded directory and matching the file name against the local `metadata/`
  dir — the behaviour that makes a table copied down from object storage openable, but which
  also meant a `write.metadata.path` layout resolved to a file that was not there. The recorded
  path now wins when it is absolute and exists; everything else falls back exactly as before.
  The strategy used is shown in the snapshot and manifest inspectors.
- **Manifest partition ranges.** `manifest_file.partitions` is read and decoded — the
  per-partition-field bounds a scan intersects with a query predicate to decide whether to open
  a manifest at all. Shown in the manifest inspector above the entries, using the same
  transform-result resolution as the per-file tuples, so a `year` range reads `1969..2025`
  rather than the stored ordinals. The pairing with the spec is positional, so a length
  disagreement decodes to nothing rather than mislabelling every field.
- **A changed partition spec and a branched history are covered by real tables.**
  `example/iceberg/default/respec` has two specs (a dropped field, `bucket(4)`→`bucket(8)`,
  `days`→`months`), closing the partition half of the schema-resolution rule.
  `example/iceberg/default/branched` has a fork, five refs including two tags, a snapshot
  carrying two refs, and ten metadata versions — the first fixture that can tell a numeric
  ordering of `vN.metadata.json` from a lexicographic one.
- **A delete file says what it deletes from.** The inspector answers per kind: a v3 deletion
  vector names its data file and byte range (`referenced_data_file`, `content_offset`,
  `content_size_in_bytes` are now modelled), a v2 positional delete keeps its targets in its own
  `file_path` column, and an equality delete has no recorded target at all — stated where a
  reader would look for the link rather than left blank.
- **Snapshot lineage and refs.** `parent-snapshot-id` is drawn as an edge, and the branches and
  tags pointing at a snapshot appear on its card and in its inspector. Lineage edges carry
  `affectsLayout = false`: a parent is another snapshot, so letting them constrain a layered
  layout puts every commit in its own layer — measured at 2.10x the graph width on a six-commit
  table, growing with history length. Refs are read from the latest metadata version, since
  `main` moves with every commit. The inspector distinguishes a first commit from a parent that
  has been expired away. Snapshots are ordered by lineage rather than timestamp, so each branch
  stays contiguous; on an unbranched history the two orderings are identical.
- **The schema-resolution rule is tested, not just asserted.** `example/iceberg/default/evolved`
  carries three manifest schemas in one table (`int`→`long`, `float`→`double`, a column renamed
  then dropped, another added), so a manifest's own schema genuinely differs from the table's
  current one. Every previous fixture had a single schema, which made the two the same object
  and let a decoder reading either pass.
- **The inspector renders off-screen in the test suite.** `InspectorRenderTest` draws the panel
  into an image with no window and no screen-capture permission, writing PNGs to
  `desktop/build/reports/inspector/`. It is both a regression check no data-level test can make
  — a composable that throws while measuring fails here and nowhere else — and the way the
  drawing can be looked at on a machine where screen capture is unavailable.
- **Partition values are decoded and readable.** `data_file.partition` is a nested Avro record
  whose schema comes from the table's own partition spec, so avro4k's static decode could not
  reach it and the inspector's Partition column had shown a hardcoded `"N/A"`. The file
  inspector now lists each partition field with its transform, source column, result type,
  value and stored bytes, and the manifest-entries table carries the tuple in the same
  `name=value` form Iceberg writes into the data file's path.
- Partition values are decoded against the spec the *manifest* carries in its own Avro file
  metadata, not the table's current spec — a repartitioned table describes each file by the
  spec in force when it was written, and the current one mis-decodes silently.
- Transform result types are resolved rather than assumed: `day` yields a `date` while `year`,
  `month` and `hour` yield `int` ordinals counted from the epoch. All four occupy the same four
  little-endian bytes, so the wrong choice produces a plausible value instead of an error. Both
  readings are shown — a `year` partition for 2024 stores `54` and renders as `2024`.
- `example/iceberg/default/parted`, a partitioned fixture written by Spark 3.5.5 / Iceberg
  1.8.1: eight partition fields across all the transform shapes, two snapshots, a pre-epoch row
  and a negative decimal. The tests assert the decoded tuple equals the directory Iceberg chose
  for each file, which is an oracle this codebase did not produce. Regenerate with
  `docs/fixtures/parted.sql`.
- **Column statistics are decoded and readable.** A manifest stores per-column stats as five
  parallel maps keyed by field id — `column_sizes`, `value_counts`, `null_value_counts`,
  `nan_value_counts`, `lower_bounds`, `upper_bounds` — which the inspector previously showed as
  raw `1:40, 2:56` strings and hex. They are now pivoted into one row per column with bounds
  decoded to values, alongside the raw bytes so a decode can always be checked.
- `AvroReader` keeps each file's Avro key-value metadata. Iceberg records the schema a manifest
  was written against under its `schema` key; that — not the table's current schema — is the only
  correct source for decoding its bounds, and using the current one mis-decodes silently across a
  type change. `UnifiedManifest`, `ManifestNode` and `FileNode` carry it.
- `ColumnStats` / `columnStatsFor()` in core, with an all-null flag so an absent bound on a
  populated column is explained rather than blank.

### Changed
- **Split into `:core` and `:desktop` Gradle modules.** `core` is the headless engine — readers,
  decoders, model, analysis, ELK layout — and may not depend on a UI toolkit; a Gradle check
  fails the build if a Compose or AndroidX artifact reaches its compile classpath. `desktop` is
  Compose over it. A server, CLI or IDE plugin attaches as a sibling of `desktop`.
- `GraphModel.initialPositions` → `layoutPositions: Map<String, Point>`, immutable. The
  Compose-observable drag map moved to `ui/NodePositions`, so a `GraphModel` is now cacheable,
  comparable, serialisable and safe to build off the main thread.
- `SnapshotFilter` moved to core (pure graph work); `ToolWindowTypes` moved to desktop (holds an
  `ImageVector`).

- **The manifest inspector shows each recorded count against the entries it summarises.** The six
  counts in `manifest_file` — added, existing and deleted files and rows — are what a scan reads
  to plan without opening the manifest, and nothing on the read path checks them. They now appear
  beside the same figures folded from the manifest's own entries, with a column saying whether
  the two agree; a count the writer omitted, which v1 permits for all six, reads as "nothing to
  check" rather than as agreement. The section is drawn even when no entries were read, because a
  manifest list claiming three added files over a manifest that yielded nothing is the case most
  worth seeing.
- **A deletion vector is drawn pointing at the file it deletes from.** `referenced_data_file` is
  the only delete-to-data link Iceberg records — a positional delete file names its targets one
  per row, and an equality delete names none — so the graph now draws that one and keeps
  explaining the other two in the inspector. The edge is withheld from ELK, like commit lineage:
  both ends are data files in the same layer, and letting it constrain layering would push the
  referenced file a whole layer to the right of a node it sits beside.

### Fixed
- **The build's one flaky test measured the machine's load rather than the graph builder.**
  `PerformanceTest` compared the *sum* of three unwarmed builds at two table sizes against the
  size ratio, so it failed at a ratio of 25.25 during a build that was rendering Compose scenes
  alongside it and passed three times in a row when run on its own — a red build there meant
  nothing. It now takes the fastest of seven warmed trials at each size, which a busy machine can
  only make slower, never faster. Measured at 5.35 alone, 5.90 and 7.03 under a full concurrent
  build, against a threshold of 22.4.
- **Every card on the graph was drawn over its neighbour on a display scaled past 100%.** The
  canvas positioned each node with `Modifier.offset { IntOffset(...) }`, which is specified in
  device pixels, and sized the card inside it with `Modifier.size(...dp)`. At 100% the two spaces
  agree and nothing is wrong; at 200% every card doubled and none of them moved. The graph model
  is dp, the surface is pixels, and `zoom * density` is now the single conversion between them —
  applied to node placement, edge drawing, drag deltas, the marquee rectangle, culling, the pan
  clamp, zoom-to-fit, scroll-into-view, the tooltip and the mini-map, because all of them shared
  the assumption. Two things came out of the same pass: scroll-into-view scaled the *difference*
  between the node and the viewport centre instead of the node's own coordinate, so it only
  centred correctly at a zoom of exactly 1; and the mini-map's viewport rectangle, which is
  larger than the map whenever the whole table fits on screen, was drawn straight out of the map
  and across the graph, because Compose clips nothing by default.
- **A data file recorded outside the table directory is now read where the table says it is.**
  Manifest lists and manifests have preferred the recorded path since the `write.metadata.path`
  fix; data files were still always rebuilt under the local table root, so a `write.data.path`
  table — or one registered against data written elsewhere — reported every file missing while
  they sat on disk exactly where the manifest said. The fallback is unchanged and still opens a
  table copied down from object storage. The file inspector now shows the path being read and
  which of the two rules produced it.
- **`TableMetadata.lastSequenceNumber` was `Int` where the spec says `long`.** Not a misread but
  a refusal: one out-of-range field and the whole `metadata.json` fails to parse, taking the
  table with it. Every other sequence number in the model was already `Long`.
- **The first page under a snapshot was the wrong page.** Aggregation chose which siblings to
  draw in the order the builder emitted the edges. That is the right order under the snapshot
  that wrote a manifest and the wrong one under every later snapshot that carries it forward,
  since the manifest is emitted once and each later snapshot inherits a position it did not
  choose. `SiblingOrder` now holds one definition of the order per kind, read by layout to decide
  which sibling sits above which and by aggregation to decide which are drawn at all.
- **Sample rows were exempt from the page size.** They are attached after the pass that bounds
  every other kind, so the pass now runs again over them. Five rows per file sits below the
  default page size of 24, so nothing changes there — but the page size is now a setting, and 8
  is one of its choices.
- **Node cards were painting half their lines under their own border.** A card is sized to the
  height its node declares and Compose clips nothing, so the surplus was drawn and never seen.
  Material3's body style carries `lineHeight = 24.sp` and a `Text` that overrides only
  `fontSize` inherits it, so a 9 sp label occupied 24 dp and five lines wanted 136 dp of card.
  The table card's snapshot count and current metadata version, the metadata card's snapshot
  count and current snapshot, and the file card's row count were invisible on every table in
  the app. Card bodies now lay text out by the font's own metrics; no node size changed.
- **"1 rows" on a single-row data file**, and a record count printed without a thousands
  separator beside one that had one.
- **Every row of the manifest-entries table was eight lines tall.** A row is as tall as its
  tallest cell, and at a uniform 180dp the partition tuple and the bounds maps each wrapped to
  the line cap — so two entries did not fit on a screen. Columns are sized to their content and
  the cap is four lines.
- **The inspector's wide tables hid their most important columns.** Partition and Column
  Statistics both led with `Field ID` at the same 180dp every other column got, which in a
  700dp panel left the decoded value and both bounds off the right edge — with no cut, shadow
  or scrollbar to say the table continued. Columns are now sized individually and ordered so
  the answer precedes the identifiers, and a horizontal scrollbar appears when the content
  overflows. Found by rendering the panel and looking at it.
- **The table summary's Oldest / Latest rows were unreadable.** Two three-line timestamps were
  joined into one cell, so the second block's first line ran onto the first block's last, the
  arrow between them landed mid-paragraph, and the second epoch was pushed past `maxLines` and
  never drawn. Split into one row each.
- **Build output was tracked in git.** `.gitignore` carried a root-anchored `/build/`, which
  stopped covering anything when the tree split into `:core` and `:desktop`; 839 build
  artifacts had been committed and every build dirtied the working tree. Now `**/build/`.
- **A manifest whose records carry no `partition` field failed to decode entirely.** Avro
  1.12's `GenericRecord.get(String)` throws for an unknown field rather than returning null, so
  reaching for a field the writer never wrote failed every record in the file — the manifest
  read as empty with each entry reported as a decode error.
- **A missing `version-hint.text` was reported as a table read error.** The file is written
  only by HadoopCatalog / HadoopTables; tables managed by Hive, Glue, REST or Nessie catalogs
  never have one, so the majority of real Iceberg tables opened with a red TABLE READ ERROR
  node attached. Absence is now normal; a file that exists but cannot be read is still
  reported. `versionHint` / `TableSummary.versionHintText` are nullable.
- **Per-manifest caps hid entries silently.** The graph draws at most
  `MAX_FILES_PER_MANIFEST` child nodes per manifest, and the inspector's entry table was built
  from those same children — so a manifest holding 5,000 entries was indistinguishable from
  one holding 10. Manifest nodes now carry every entry: the graph still draws the first ten,
  the card says "10 of 5,231 shown", and the inspector lists all of them.
- **The `showRows` branch re-derived data-file paths a second way**, from a `"/<tableName>/"`
  string marker, while `UnifiedManifest` had already resolved them and the row loader read
  from that resolved path. When the two disagreed, rows were silently dropped for files that
  exist on disk.
- **Table summary counts described the traversal, not the table.** `manifestEntryCount`,
  `dataFileCount`, `posDeleteFileCount`, `eqDeleteFileCount` and `totalRecordCount` were
  incremented per visit while walking metadata → snapshots → manifests → files. Because
  Iceberg shares structure on purpose — one manifest is referenced by every snapshot that
  carries its files forward, and every snapshot is re-listed in every later `metadata.json` —
  these figures multiplied with commit history. A two-commit fixture reported 4 manifest
  entries where 2 exist; the error grows with the number of retained metadata versions.
- `status=DELETED` manifest entries were counted as live data files.
- Delete files' `record_count` (a count of delete records) was added to the table's row count.
- Paimon current-state figures counted every ADD entry ever written instead of applying the
  snapshot's delta manifest list over its base, so a compacted table reported every file it
  had ever held.
- Table node card labelled `manifestEntryCount` as "Data Files".

### Added
- `TableSummary.current` / `TableSummary.history` (`ContentStats`) — the table as it is now
  (manifest closure of `current-snapshot-id`, live entries only; `recordCount` is the figure
  `SELECT count(*)` should agree with before deletes are applied) alongside everything still
  reachable from any retained snapshot (deduplicated by manifest and data-file path — the
  "what can I not expire yet" view). The inspector renders both under their own headings.
- File and record byte totals: `dataSizeBytes` / `deleteSizeBytes` per group, so table size on
  disk is visible without leaving the app.
- `ContentStats.deletedEntryCount` — entries recording a removal (Iceberg `status=DELETED`,
  Paimon `_KIND=1`), counted toward scan cost but contributing no files, records or bytes.
- `formatCount()` / `formatBytes()` / `formatBytesExact()` in `FormatUtils` — thousands
  separators and binary byte units for the figures a data engineer compares against engine
  output. Folds in the private copies that lived in `NodeComponents`, whose byte formatter
  divided by 1024 and labelled the result KB/MB/GB.
- Named spec constants `ManifestContent`, `ManifestEntryStatus`, `DataFileContent`,
  `PaimonEntryKind` replacing bare 0/1/2 literals at the sites touched by this change.
- `TableSummaryAccuracyTest` — pins all three miscounts against fixtures laid out the way the
  formats actually write them.
- Snapshot filter now works for Paimon tables (previously Iceberg-only); `asSnapshotFilterOption()` extension unifies both formats
- Sample-row inspector for Paimon nodes (`RecursiveDataTableSection` invoked from every Paimon inspector branch; `collectDescendantRows` descends through `PaimonDataFileNode`)
- 5-entry LRU session cache (was unbounded — could OOM after enough table switches)
- `formatTimestampShort()` — single-line timestamp for fixed-width table cells
- `HoverTooltip` shared composable; tool-window bar icons now show their title on hover
- Multi-select inspector renders a per-node summary table (type + key field per node type)
- Snapshot-filter dropdown is scrollable (`heightIn(max=420dp).verticalScroll`) for tables with many snapshots
- Stack-trace inspector panel: bounded scroll (`heightIn(max=320dp)`) + Copy button
- Toolbar tooltips include keyboard shortcut hints
- Hover cursor on draggable workspace items
- Apache Paimon table format support (data model, reader, unified model, graph types, graph builder)
- `PaimonSchema.kt` — `@Serializable` data classes for Paimon snapshots, schemas, manifest lists, and manifest entries
- `PaimonReader.kt` — reads Paimon snapshot/schema JSON and manifest list/manifest Avro files
- `PaimonUnifiedModel.kt` — aggregated Paimon table model linking snapshots, schemas, manifests, and data files
- `PaimonGraphBuilder.kt` — builds graph nodes and edges from a Paimon unified table model
- Paimon graph node types: `PaimonSnapshotNode`, `PaimonSchemaNode`, `PaimonManifestListNode`, `PaimonManifestNode`, `PaimonDataFileNode`
- `AvroReader.kt` — shared Avro file reader extracted from `IcebergReader`, reused by both Iceberg and Paimon readers
- Paimon node rendering (cards, tooltips, colors, inspector panels) in `NodeComponents.kt` and `NodeDetails.kt`
- Paimon snapshot color per `commitKind` (APPEND=blue, COMPACT=purple, OVERWRITE=amber, ANALYZE=teal)
- Paimon manifest list color per kind (base=gray, delta=blue, changelog=amber)
- Paimon data file color per operation (ADD=green, DELETE=red)
- `TableFormat.PAIMON` detection in `TableFormatDetector` (presence of `snapshot/` + `schema/` directories)
- `GraphLayoutService.layoutPaimonGraph()` — Paimon-specific layout entry point
- Paimon node post-processing in `GraphLayoutService` (ordering, alignment, overlap prevention)
- Format badge ("ICE"/"PMN") next to table names in workspace sidebar
- Paimon test fixtures (`src/test/resources/paimon-fixtures/`)
- `PaimonSchemaTest` — Paimon JSON deserialization tests (9 tests)
- `PaimonGraphBuilderTest` — Paimon graph construction tests (11 tests)
- Paimon detection tests in `TableFormatDetectorTest` (4 new tests)
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
- `loadRequestId` is now `AtomicLong` (was `@Volatile var Long` with non-atomic increment); cache-hit branch also bumps it so any in-flight load/reapply bails out
- `reapplyCurrentLayout` re-reads `graphModel` after the staleness check (was using a captured reference that could go stale)
- `loadTable` and `reapplyCurrentLayout` route format dispatch through `loadTableModel()` (single dispatch point)
- `sessionCache` field type is now `MutableMap<String, TableSession>` backed by a synchronized LRU
- `WorkspaceItem` serialization percent-encodes `%`, `;`, `|` in path values for safe round-trip
- `ManifestListEntry.sequenceNumber` and `minSequenceNumber` are now `Long?` (Iceberg spec is int64); manifest comparator sentinels widened to `Long.MAX_VALUE`
- `normalizeFilePath` reconstructs `file://host/path` URIs as UNC `//host/path` instead of dropping the host
- `GraphLayoutService.parsePosition` rejects values outside `Int` range instead of silently truncating
- `GraphLayoutService` post-processing partitions nodes by class once per pass (was ~20 `filterIsInstance` scans of the full node list)
- `Path` and snapshot→manifest fan-in/out counts are reused / pre-cached in `GraphCanvas` (eliminates per-edge per-frame allocation and O(E) scans)
- `jsonToAnnotatedString` is wrapped in `remember(rawJson, colors)` so the highlighter no longer runs on every recomposition
- Window position/size persistence is debounced (500ms via `javax.swing.Timer`) instead of one Preferences write per `componentMoved` event
- Paimon `PaimonManifestListNode` uses a dedicated `simpleId` counter (was reusing the snapshot's `simpleId`)
- Paimon manifest nodes deduplicate by `fileName` via `manifestPathToId` (mirrors Iceberg)
- Paimon `TableSummary.dataFileCount` counts ADD entries; `posDeleteFileCount` and `eqDeleteFileCount` stay 0 (Paimon has no positional/equality delete files like Iceberg)
- Paimon `resolveDataFilePath` flags resolved paths that escape the table directory (mirrors Iceberg's path-traversal check)
- `computeTableFingerprint` detects Paimon (`snapshot/` + `schema/`) — was always returning `"missing"` for Paimon, breaking cache invalidation
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
- Stale graph displayed when switching to a deleted table with `forceReloadFromFs` and no cached session (prior fix branch was conditional on `forceReloadFromFs`)
- `Reveal` button hidden for cloud paths (`s3://`, `hdfs://`, `gs://`, `abfs://`, …) where it would silently fail
- About-dialog tab indicators now have a background colour on the active tab (was distinguishable only by font weight, easy to miss)
- `PropertiesEvolutionSection` no longer double-counts keys present in both compared metadata versions
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
