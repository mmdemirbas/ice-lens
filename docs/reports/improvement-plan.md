---
title: Improvement plan
summary: What to build next in ice-lens, why, and in what order — grounded in what was measured this session and what the tooling research established.
---

> [!TLDR]
> The tool's differentiator is **decoding**, not drawing. Nothing in the field turns an Iceberg
> `lower_bound` byte array into a typed value — rivals render it as hex, base64, or drop it.
> That one capability unlocks partition values, predicate explanation, and delete topology.
>
> - **Correctness is now fixed and pinned.** Summary counts described the traversal, not the table; load time scaled with commit history at 7.3×. Both measured, both closed, both regression-tested.
> - **Build order follows one dependency.** The bound decoder is the hinge — three of the four capabilities below are blocked on it and cheap after it.
> - **Self-contained is the constraint that defines the product.** PyIceberg cannot open the table in this repo's own `example/` directory. ice-lens can.

## Where we are {#where-we-are}

Five commits landed this session. Two were correctness, one was performance, and all three of
those started as measurements rather than as code review — which is why they are worth
repeating as a method, not just as a changelog.

```oku-chart
{"type":"grouped-bar","title":"Table load time — identical contents, 10 manifests, 2000 entries (ms)","categories":["4 snapshots","32 snapshots"],"series":[{"label":"Before","color":"warn","values":[99,720]},{"label":"After","color":"accent","values":[20,25]}]}
```

Every snapshot in that fixture carries the same ten manifests forward, which is what a real
commit produces. Before the fix, load time tracked the number of snapshots: 8× the commits over
identical data cost 7.3× the time, because a manifest referenced by N snapshots was parsed N
times. A table retaining 100 snapshots over 500 manifests was doing 50,000 manifest reads to
show 500 manifests.

The same root cause produced the counting bug: both formats share structure deliberately, and
the loader treated every reference as a separate object.

```oku-table
{"headers":["What was wrong","How it was found","State"],"rows":[["Summary counts multiplied with commit history — a 2-commit fixture reported 4 manifest entries where 2 exist","Wrote the fixture the way Iceberg actually writes it, ran it, watched it fail","Fixed · `adf288e`"],["`status=DELETED` entries counted as live data files; delete-file `record_count` added to the table's row count","Same fixture set","Fixed · `adf288e`"],["Missing `version-hint.text` rendered a red TABLE READ ERROR on every catalog-managed table","Read the spec's ownership of the file; a test was *asserting* the error","Fixed · `0962869`"],["Per-manifest cap hid entries in the graph **and** the inspector, silently","Read the inspector's row source","Fixed · `0962869`"],["Manifest re-read once per referencing snapshot","Benchmarked the real load path against files on disk","Fixed · `0ae4ffe`"],["Test suite had almost no oracle — every Avro fixture written with the schema derived from the class under test","Noticed writer and reader schema were the same object","Partly closed · `07efc4e`"]]}
```

> [!NOTE]
> The oracle gap is the one still open. `RealTableFixtureTest` now reads the checked-in
> `example/` tables, which real Spark and Flink wrote — but those are one snapshot, one
> manifest, one unpartitioned file, no deletes, v2. Widening them is listed in
> [Phase 0](#phase-0) because everything else depends on being able to trust a green suite.

## What actually makes this tool different {#differentiator}

The research examined five projects that inspect Iceberg metadata. The capability matrix is
narrow in a specific place.

```oku-table
{"headers":["Project","Opens a bare directory","Depth","Bounds shown as","Formats"],"rows":[["**ice-lens**","yes — and relocated tables","metadata → snapshot → manifest → file → **rows**","raw (typed decode is Phase 1)","Iceberg + Paimon"],["icescope","warehouse root only; `vN.metadata.json` names only","down to data files","hex string","Iceberg"],["IceGraph","no — Spark Connect mandatory","4 levels, stops above rows","dropped from schema","Iceberg"],["fern","no — Hive/Glue web app","snapshot + manifest DAG","base64","Iceberg"],["IceTop","no — needs `~/.pyiceberg.yaml`","table level","not exposed","Iceberg"],["fredfleet","no — REST catalogs only","table level","not exposed","Iceberg"]]}
```

Two things follow, and only one of them is the one I expected.

```oku-insight
{"b":"Not one of the five turns a lower/upper bound into a typed value. icescope hex-encodes them, fern base64s them, IceGraph drops them from its schema entirely. Decoding is the empty space."}
```

The second is about access, and it is sharper than "we work offline". This repo's own
`example/` table records `file:/data/iceberg/default/test` — the path inside the container
that wrote it. That directory does not exist on this machine. PyIceberg 0.11.1 opens the
`metadata.json` and then fails with `FileNotFoundError` on `files`, `entries`, `manifests`,
`partitions` and `plan_files`, because it resolves recorded paths literally. ice-lens reads
the same table completely, because `resolveForceRelative` re-resolves each path against the
local metadata directory.

```oku-diagram
{"src":"flowchart LR\n  T[\"Table handed to you<br/>as a directory\"] --> P{\"Path recorded in<br/>metadata.json\"}\n  P -->|\"literal resolve\"| X[\"PyIceberg / icescope<br/>FileNotFoundError\"]\n  P -->|\"try recorded,<br/>fall back local\"| Y[\"ice-lens<br/>reads the tree\"]\n  style X stroke-dasharray: 4 3","caption":"A copied, downloaded or bug-report table has paths from the machine that wrote it. Re-resolving them is what makes it openable at all."}
```

That behaviour was filed as a defect earlier in this session — *two path-resolution strategies
coexist* — and the measurement reversed the judgement. It is the most valuable thing the tool
does for the case you actually hit. It is still worth improving: try the recorded path first,
fall back, and **show which resolution was used**, so a `write.metadata.path` layout works too.

## The build plan {#plan}

One dependency orders everything. Iceberg's bound bytes carry no self-description — decoding
requires resolving a type first, and the type comes from **the schema the manifest carries in
its own Avro key-value metadata**, not the table's current schema. Get that wrong and the
values are silently incorrect across schema evolution.

```oku-diagram
{"src":"flowchart TD\n  D[\"Phase 1<br/>Appendix-D bound &amp; partition decoder\"]\n  D --> A[\"Partition values<br/>replaces the hardcoded N/A\"]\n  D --> B[\"Phase 2<br/>Why was this file read?\"]\n  D --> C[\"Phase 3<br/>Delete-file topology\"]\n  D --> E[\"Phase 4<br/>Snapshot diff by partition\"]\n  B --> F[\"manifest-level prune<br/>via field summaries\"]\n  B --> G[\"file-level prune<br/>via bounds + null counts\"]","caption":"The decoder is the hinge: a few hundred mostly table-driven lines that unlock four capabilities."}
```

### Phase 0 — make green mean something {#phase-0}

Before building on the suite, widen the oracle. Generate real fixtures with Spark and Flink and
check them in: a partitioned table, a merge-on-read table with positional **and** equality
deletes, several commits including a compaction, and a v3 table. Without these, a type that
disagrees with the spec passes every test and fails on every real table.

### Phase 1 — decode what the format records {#phase-1}

The Appendix-D decoder, then everything it feeds directly.

```oku-table
{"headers":["Item","Why it matters","Difficulty"],"rows":[["Bound decoder — int/long/float/double/date/time/timestamp(_ns) LE, string UTF-8, uuid BE, **decimal unscaled two's-complement, variable length, scale from schema**","The hinge. Everything below depends on it","Moderate, bounded — traps are decimal length and v1 partition field-id conflicts"],["`data_file.partition` decode against the manifest's own spec","Kills the hardcoded `\"N/A\"`; the most-wanted field when debugging a scan","Low once bounds work"],["`manifest_file.partitions` field summaries","Per-manifest bounds — the data that decides whether a manifest is opened at all","Low once bounds work"],["Per-column stats panel: min / max / null count / NaN count / value count / column size","Currently parsed and shown as opaque key-value blobs","Low"],["Show which path resolution was used per file","Makes relocated-table handling legible instead of magic","Low"]]}
```

### Phase 2 — why did my query read that? {#phase-2}

The capability with no competitor, and the one the issue trackers point at: planning/manifest
is the second-largest Iceberg theme. Iceberg itself reports pruning as **counters only** —
`ManifestGroup` hands each filter a counter and discards the rejected entry, and neither it nor
`InclusiveMetricsEvaluator` logs anything. Which files a scan *kept* is one `planFiles()` call.
Which it *skipped*, and why, is available from no surface at any price.

```oku-annotated-code
{"src":"ice-lens · predicate box\n\n  ts >= '2026-01-01' AND country = 'TR'\n\nmanifest-3   SKIPPED   partitions[0].upper_bound(ts) = 2025-12-14  <  2026-01-01\nmanifest-5   kept      2 of 412 files survive\n\n  00042-....parquet   SKIPPED   upper_bound(ts) = 2025-11-30  <  2026-01-01\n  00043-....parquet   kept      4,096 rows · 12.1 MiB\n  00044-....parquet   SKIPPED   lower_bound(country) = 'US'  >  'TR'\n\n3 of 411 data files survive pruning\nsimulating InclusiveMetricsEvaluator semantics @ Iceberg 1.11.0","lang":"text","annotations":[{"id":1,"content":"Manifest-level prune runs first, against <code>manifest_file.partitions</code> field summaries. Skipping here avoids opening the manifest at all — which is the cost that dominates planning on wide tables.","match":"manifest-3   SKIPPED"},{"id":2,"content":"The reason names the column <em>and</em> the bound that rejected it. A count tells you pruning happened; this tells you why, which is what lets you fix a partition spec or a sort order.","match":"upper_bound(ts) = 2025-11-30  <  2026-01-01"},{"id":3,"content":"Labelled as a simulation, pinned to a named Iceberg version. This is not the engine's decision and must never claim to be — see Risks.","match":"simulating InclusiveMetricsEvaluator semantics @ Iceberg 1.11.0"}]}
```

The honesty requirement is structural, not cosmetic: this reproduces evaluator semantics, it
does not observe them. A reconciliation mode — diff the simulation against a `planFiles()`
result the user pastes in — makes the tool's own error visible rather than hidden.

### Phase 3 — where did my deletes go? {#phase-3}

Delete-file and merge-on-read is the **largest** theme in the Iceberg tracker since 2025.

```oku-chart
{"type":"bar","title":"apache/iceberg issues since 2025-01-01, by theme (keyword match, overlapping)","rows":[{"label":"delete file / DV / merge-on-read","value":193},{"label":"query planning / manifest","value":153},{"label":"cross-engine","value":120},{"label":"schema / partition evolution","value":100},{"label":"small files / compaction","value":84},{"label":"expire snapshots / orphan","value":53}]}
```

Three tiers, and the third is a real limit that the UI must state rather than imply:

```oku-step-flow
{"ordered":false,"steps":[{"t":"v3 deletion vectors — direct edge","b":"referenced_data_file is a required pointer; content_offset and content_size_in_bytes must match the Puffin footer exactly. Drawing the edge is parsing we already do. Opening the blob for cardinality needs a small Puffin reader: PFA1 magic, JSON footer, deletion-vector-v1 payload, portable Roaring bitmap, CRC-32."},{"t":"v2 positional deletes — via bounds","b":"The link is the delete file's file_path column bounds, so this is blocked on the Phase 1 decoder and free afterwards."},{"t":"Equality deletes — no edge exists","b":"They apply by predicate over identifier fields, not by file reference. No amount of metadata reading produces a file-to-file edge. The view must say so explicitly; drawing nothing and letting the reader infer absence is how a tool teaches something false."}]}
```

### Phase 4 — what changed, and what did compaction do? {#phase-4}

Snapshot lineage is already parsed and drawn as nothing. `parent-snapshot-id` becomes an edge;
branches and tags from `refs` become labelled anchors on the resulting DAG. Low difficulty, and
it makes the graph structurally honest about being a commit history.

Snapshot diff for **any two** snapshots — not just adjacent ones — with added / removed /
retained sets grouped by partition, and size and record-count distributions.

> [!WARNING]
> **The format does not record which input files produced which output file.** A compaction's
> snapshot summary carries counters, never an input→output mapping. "Before set / after set /
> partition-level aggregation" is honest. A fan-in diagram implying provenance is a fabrication,
> and it would be the most convincing-looking thing on the screen.

### Phase 5 — scale, health, and getting answers out {#phase-5}

Lazy and streaming load so a table with thousands of manifests opens progressively rather than
after everything is parsed. Health checks the metadata already supports: small-file
distribution, manifest bloat, delete-file backlog, orphan candidates, partition skew. Export of
any view to CSV/JSON so an answer can leave the app and land in an issue.

## Constraints to bake in now {#constraints}

Cheap today, expensive later. Each comes from a specific finding.

```oku-table
{"headers":["Constraint","Why","Source"],"rows":[["**Do not hard-code Avro as the manifest container.** Keep the manifest reader behind a format-dispatch seam","Iceberg v4's Java writer defaults manifests to **Parquet**. DuckDB is already bundled, so the reader exists","v4 spec + Java writer defaults"],["**Do not treat `file_path` as absolute.** Scheme test, then join","v4 permits relative paths resolved against a table location that may be absent from metadata entirely. `normalizeFilePath` / `resolveForceRelative` already sit at the right seam","v4 spec"],["**Build against a file-shaped abstraction, not raw Avro records**","v4 replaces manifest lists with a Root Manifest, inlines deletion vectors onto the data entry, and moves `first_row_id` into a `tracking` struct. A concept-level prune explanation survives that; a record-level one does not","v4 structural PR"],["**Every cap visible in the UI**","Already applied to per-manifest entries. Make it a rule, not a fix","This session"],["**No new runtime dependency without deleting one**","Self-contained is the product. Installers are already 193 MB","User requirement"],["**Sample-row reader needs an explicit \"format not supported\" path**","Paimon 2.0 adds `.row`, `mosaic` and `vortex` data formats DuckDB cannot read. Opt-in only, but a stack trace is the wrong answer","Paimon 2.0.0 source diff"]]}
```

## What we are deliberately not doing {#not-doing}

```oku-compare-grid
{"cards":[{"t":"Catalog integration","b":"REST / Glue / Hive / Nessie. The REST loadTable response carries table metadata only — walking below the snapshot level still means reading manifest files from storage. It would add auth, network and object-store dependencies to a tool whose defining property is needing none of them.","verdict":"out"},{"t":"Cross-engine disagreement","b":"120 issues, and out of reach: answering it requires running two engines. A read-only local tool cannot.","verdict":"out"},{"t":"Compaction provenance","b":"Not in the format. See Phase 4.","verdict":"out"},{"t":"Chasing v4 now","b":"Not adopted, no target release, Java's default write version is still 2, and the structural PR has been blocked since April. v1–v3 tables never change and dominate the installed base well past this plan's horizon. Prepare the seams; do not build to the spec.","verdict":"out"},{"t":"Decoding bounds","b":"The one capability no examined tool has, and the dependency for three others.","verdict":"in"},{"t":"Predicate explanation","b":"Recurs heavily, served nowhere, derivable from data already parsed.","verdict":"in"}]}
```

## Risks {#risks}

```oku-table
{"headers":["Risk","Mitigation"],"rows":[["**The prune explainer is a simulation.** Showing \"Trino would skip this file\" and being wrong is worse than showing nothing","Label it with the Iceberg version whose semantics it reproduces; ship the reconciliation mode in the same release, not later"],["**Bound decoding has silent failure modes.** Wrong type resolution produces plausible wrong values, not errors","Resolve types from the manifest's own embedded schema, never the table's current schema. Test with fixtures written under two different partition specs. Property-test the round-trip"],["**v1 partition field-id conflicts.** v1 did not track partition field ids; the reference implementation assigned them sequentially from 1000, and the spec calls this out as a source of type conflicts across specs","Handle explicitly and surface ambiguity rather than guessing"],["**Scope.** Five phases is a lot for one maintainer","Phase 1 alone is a complete, shippable improvement. Each phase is independently useful — nothing here needs the next phase to be worth having"]]}
```

## Open questions {#open}

Ordered by what they would change.

```oku-table
{"headers":["Question","Why it matters","Cost to resolve"],"rows":[["Does the current UI render well after this session's inspector changes?","Not visually verified — this session could not capture the window","Open the app, click a table node"],["Should the predicate box accept SQL-ish text, or structured column/op/value pickers?","Parsing is real work; a picker is faster to build and harder to misread","A sketch and your reaction"],["Is a CLI worth having alongside the desktop app?","The derivation engine is useful in a shell and in CI. It also sidesteps signing entirely","Decide once Phase 2 exists"],["Which Iceberg version's evaluator semantics to pin","Determines the reconciliation baseline","Pick when Phase 2 starts"]]}
```

Two findings that matter for later, recorded so they are not rediscovered:

- **The name is a policy problem.** `ICEBERG` is a registered ASF trademark (US class 9,
  software) and the policy prohibits Apache marks in third-party product branding. "Iceberg
  Lens", `packageName = "IcebergLens"` and `bundleID = com.iceberglens.desktop` are that form.
  Kept as a codename by your call; the rename is a tracked work item, not a decision needed now.
- **Signing is cheap; distribution reach is not.** Apple Developer Program is 99 USD/year and
  Certum's open-source certificate is €49 — about $156/year for warning-free installers on both
  platforms. But Homebrew requires 75 stars, or 225 for a self-submission by the repo owner, so
  certificates buy clean installs on direct download, not shelf space. Channels with no
  notability bar: Flathub, AUR, Scoop Extras, winget, and a third-party Homebrew tap.
