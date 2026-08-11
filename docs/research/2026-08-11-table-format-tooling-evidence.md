# Evidence Base: Iceberg Lens Positioning

**Date:** 2026-08-11 · **Subject:** iceberg-lens / ice-lens (github.com/mmdemirbas/ice-lens, Apache-2.0, 9 stars)
**Method:** five research lanes (tooling, pain, spec, catalog, distribution), each lane's absence claims re-verified adversarially. Evidence tiers inline: `[run]` executed and observed · `[src]` read in source · `[doc]` read in vendor/project documentation · `[weak]` secondhand or search-level.

Two standing caveats that apply throughout, stated once:

- **Issue-tracker theme counts are keyword matches over title+body, not curated classifications.** They overlap and they track project age and triage policy as much as difficulty. Treat them as ordering signals, not measurements.
- **A registry search establishes "not found by these queries", not "does not exist".** Where a negative is load-bearing, the wording below says which of the two it is.

---

## 0. Scan claims that verification changed

These matter because several of them remove or weaken an argument the project might otherwise rest on.

| Original scan claim | Verification outcome | Corrected position |
|---|---|---|
| IceGraph is the only tool rendering Iceberg metadata as a node-link graph | **REFUTED (partly)** | `dikshantks/fern` also does: ReactFlow nodes for snapshots and manifests with `lineage-` and `snap-manifest-` edges [src]. No tool found renders the full five-level chain. |
| No installed desktop app browses Iceberg catalog metadata | **REFUTED** | At least three exist (IceTop, icescope, fredfleet), all v0.1.x, 0–4 stars, all first released 2026 [run]. The original search carried `stars:>5` filters. |
| The Iceberg project declined to adopt an official UI | **NARROWED** | Issue #10980 was auto-closed by the stale bot on 2025-04-16 with `state_reason: not_planned`; no maintainer decision exists [run]. The *no official UI* half holds. |
| No maintained standalone Puffin inspector CLI | **REFUTED** | `ebyhr/puffin-tools` prints blob types, DV `deletedRows`, theta-sketch `ndv`; tracks Iceberg releases through 2026-01 [src][run]. |
| Identifying which files a query kept requires re-running the predicate by hand | **REFUTED** | `TableScan.filter(...).planFiles()` and PyIceberg `scan.plan_files()` return survivors directly [doc][src]. Only the **skipped** set and the **reason** are unavailable. |
| OSS Delta documents no SQL command enumerating data files | **NARROWED** | `GENERATE symlink_format_manifest` does — it writes paths to storage rather than returning rows [doc]. |
| Trino does not support row-level DML on Iceberg v3 | **REFUTED** | Trino 480 (2026-03-24) added create/write/delete, optimize, column defaults and row lineage for v3; connector docs are stale [doc][src]. |
| Snowflake blocks external-engine writes to managed v3 tables | **REFUTED** | GA 2026-05-26, 19 days after the note the scan quoted [doc]. |
| `iceberg-diag` is the only OSS Iceberg health CLI | **NARROWED** | `aidancorrell/frost` is active (Apache-2.0, 14 metadata-only checks) but 0 stars and four months old [weak]. |
| No public measurement of local vs catalog usage | **NARROWED** | A practitioner survey reports *prevalence* (Local Development/Testing 42.9%, Hadoop catalog 17.9%), undisclosed sample size, ~28 respondents implied by 3.6% granularity [doc]. No *time-split* measurement found. |
| Azure Artifact Signing unavailable to a Turkey-based individual | **NARROWED** | **Public Trust** certificates unavailable (individuals limited to US/Canada; Turkey not on the org list either). **Private Trust** available but not publicly rooted, so useless for distribution [doc]. |

One spec-surface correction worth carrying: **the Iceberg spec page the web serves is not the 1.11.0 spec.** `iceberg.apache.org/spec/` renders `site/docs/spec.md` from `main` and currently carries the Version 4 section, `content_stats` (12 occurrences) and relative paths (14), while the version selector reads "Latest (1.11.0)". The tagged `apache-iceberg-1.11.0` file has none of that [run]. A tool built by reading "the spec" on the website is building against unadopted v4 text.

---

## 1. Landscape

### 1.1 The lead fact

**Metadata-graph visualization for Iceberg already exists in open source, in two projects, and neither is dead.**

- **IceGraph** (`YanivZalach/IceGraph`, MIT, created 2026-02-21, last push 2026-08-10, 16 stars). README: "hierarchical, graph-based view of Iceberg metadata"; "Interactive exploration of metadata, snapshots, manifests, and files across table branches"; "Snapshot & Metadata Lineage". React web UI on :5050 plus a Python CLI client. Iceberg only, **format version 2 only**. Reads **exclusively through a Spark Connect backend** (`SPARK_REMOTE`) — not a local filesystem, not a catalog directly. Caps: 2000 snapshots, 5000 data files, 15 parallel graph computations [doc] https://github.com/YanivZalach/IceGraph
- **fern** (`dikshantks/fern`, last push 2026-07-10). `SnapshotDAG.tsx` imports ReactFlow and builds `type: 'snapshot'` (`snap-<id>`) and `type: 'manifest'` (`manifest-<n>`) nodes with `lineage-` edges between snapshots and `snap-manifest-` edges snapshot→manifest [src]. Data files are never graph nodes; `manifest_list_path` is a text label; the manifest tree is a chevron-expand indented list, not node-link. https://github.com/dikshantks/fern/blob/master/frontend/src/components/Visualization/SnapshotDAG.tsx

**No tool found renders the full chain (metadata.json → snapshot → manifest list → manifest → data/delete file → rows) as node-link.** That is a search result across five GitHub query phrasings plus source reads of the named candidates, not an executed exhaustive proof. Supporting checks: Nimtable's `package.json` declares only recharts and a repo-wide search for reactflow/xyflow/dagre returns 0 hits [src]; Amoro's `amoro-web` declares only echarts with no `type: "graph"` series [src]; LakeVision's README lists one time-series chart and no graph/tree/D3/cytoscape/mermaid mention [doc].

The consequence: **"we draw a graph" is no longer an unclaimed position.** What remains unclaimed is depth (below the manifest), format breadth, and the access path (bare filesystem, no engine).

### 1.2 By interface

**SQL metadata tables — the incumbent, zero-install where an engine already exists.**

| Engine | Iceberg surface | Notes |
|---|---|---|
| Spark | `history, metadata_log_entries, snapshots, entries, files, data_files, delete_files, manifests, partitions, position_deletes, refs` + `all_*` family | Metadata tables accept `TIMESTAMP AS OF` / `VERSION AS OF` [doc] |
| Trino 483 | `$properties, $history, $metadata_log_entries, $snapshots, $manifests, $all_manifests, $partitions, $files, $entries, $all_entries, $refs` | Widest per-column-typed set checked; lacks `position_deletes` and the `all_files` family [doc] |
| StarRocks ≥3.4.1 | `$history, $metadata_log_entries, $snapshots, $manifests, $partitions, $files, $refs`; v4.1 adds v3 `_row_id`, `_last_updated_sequence_number` | Same `$`-suffix syntax as Trino [doc] |
| Snowflake | `ICEBERG_TABLE_FILES`, `ICEBERG_TABLE_SNAPSHOT_REFRESH_HISTORY`, `SYSTEM$GET_ICEBERG_TABLE_INFORMATION` | No `$`-suffix syntax documented on the pages read; **not** an executed negative [doc] |
| Paimon (Flink/Spark) | `snapshots, schemas, options, audit_log, binlog, ro, files, file_indexes, file_key_ranges, tags, branches, consumers, manifests, aggregation_fields, partitions, buckets, statistics, table_indexes, row_tracking` + `sys.*` | More per-table system tables than Iceberg, including LSM `level` as a first-class column [doc] |
| Trino Delta | `$history, $partitions, $properties` only, plus hidden `$path`/`$file_size`/`$file_modified_time` columns | Per-file *paths* obtainable; per-file min/max/null stats are not [doc] |
| Hudi | `CALL` procedures (`show_fsview_latest`, `show_logfile_metadata`, `stats_wa`, …) and `hudi-cli` | The only first-party interactive CLI among the four formats [doc] |

Every one of these returns rows. The parent/child relation between metadata.json, manifest list, manifest and data file must be reconstructed by the reader through joins on `snapshot_id` and manifest path.

**Libraries.** PyIceberg `table.inspect.{snapshots, partitions, entries, refs, manifests, metadata_log_entries, history, files, data_files, delete_files}`, all but `snapshots` and `refs` accepting `snapshot_id` [doc]. delta-rs `get_add_actions(flatten=True)` → path, size, mtime, record counts, per-column stats [doc]. iceberg-rust has `inspect::MetadataTable`; the DataFusion-exposed set found was snapshots + manifests only [weak].

**Embedded SQL, no engine.** DuckDB's `iceberg` extension is the lowest-friction path to manifest entries and per-file column stats with no Spark and no catalog: `iceberg_metadata` (one row per manifest entry), `iceberg_snapshots`, `iceberg_column_stats`, `iceberg_partition_stats`, `iceberg_table_properties`, `iceberg_load_table_response`, `iceberg_scan`, `iceberg_to_ducklake` [run, DuckDB v1.5.5 osx_arm64]. It also `ATTACH`es REST catalogs directly (OAuth2 default). Two frictions the marketing does not carry: the repo README says "This extension is experimental. APIs and behavior may change", and **extensions are downloaded from extensions.duckdb.org at first use** — iceberg 32.5 MB, aws 19.1, httpfs 15.3, avro 7.5 — so an offline or air-gapped machine silently lacks the capability [run].

**CLIs.** `pyiceberg` CLI and `iceberg-go` CLI both stop at table-level description; neither walks manifests or data files [doc]. `hudi-cli` is the deepest first-party shell. `ebyhr/puffin-tools` inspects Puffin files standalone [src]. `Upsolver/iceberg-diag` archived read-only 2025-09-16 [doc]. `aidancorrell/frost` is a four-month-old metadata-only health CLI with no vendor API [weak]. Below the table format: `avro-tools getschema/getmeta/tojson` (how manifests get read by hand today) and `parquet-cli meta/schema/inspect`.

**Web UIs.**

| Tool | Formats | Access path | Presentation | Health |
|---|---|---|---|---|
| Nimtable | Iceberg | Catalog only (REST, Glue, S3 Tables, JDBC, HMS, Polaris, Unity, Lakekeeper); Docker Compose :3000 | Lists + charts; "visualize snapshot and file distribution" undefined in docs | 471 stars, last push 2026-01-12 (7 months) [doc][run] |
| IceGraph | Iceberg **v2 only** | Spark Connect only | **Node-link graph** | 16 stars, active [doc] |
| fern | Iceberg | — | Snapshot+manifest node-link; indented manifest tree | last push 2026-07-10 [src] |
| LakeVision (IBM) | Iceberg | PyIceberg catalog | Tabular lists + one time-series chart | 52 stars, last push 2026-08-06 [doc] |
| Amoro (ASF incubating) | **Iceberg + Paimon** + Mixed-Iceberg/Hive | Registered catalogs, dashboard :1630 | Tables and lists; only diagram is architecture | 1.2k stars; **last release v0.8.1-incubating, 2025-09-11** [doc] |
| iceberg.rest | Iceberg | REST catalog | Tabs + schema-evolution visual diff | 4 stars [doc] |
| iceberg-metadata-insights | Iceberg | Trino | Plotly histograms, snapshot timeline | 17 stars, no commits in ~16 months [run] |

**Installed desktop apps** (the scan's original "none exist" was wrong):

- `AlexMercedCoder/icetop` — Electron, "Catalog Browser: Visualize your Iceberg catalog structure (Namespaces → Tables). Inspect table schemas, snapshots, manifests, and metadata in detail." Ships dmg/exe/deb/AppImage. Last push 2026-02-21 [run]
- `muhammad-rajib/icescope` — Tauri 2 + Rust. Features include **"Local Hadoop-style Iceberg warehouse support"** and "Manifest-based current snapshot scans". Ships dmg/msi/deb/rpm/AppImage. Last push 2026-07-12 [run]
- `prochac/fredfleet` — Wails (Go + React) on `iceberg-go`. REST catalog, namespace tree, schema viewer, row preview. Last push 2026-08-07 [run]
- DBCode (commercial VS Code extension) — Glue and S3 Tables catalog browsing, no snapshot/manifest level [doc]

All three OSS desktop apps are v0.1.x with 0–4 stars, first released in 2026. A narrower claim — *no established or widely used installed desktop Iceberg browser exists* — still holds.

**IDE extensions.** No extension found on JetBrains Marketplace, VS Code Gallery or Open VSX that walks the linked metadata chain (12 distinct VS Code queries, 8 JetBrains queries, 3 Open VSX queries, 2026-08-11) [run]. Adjacent: JetBrains **Big Data File Viewer** (2,304,810 downloads) opens Parquet/ORC/Avro as tables — which includes manifest files, one at a time, unlinked; **Puffin Reader** (33 downloads, published 2026-03-30) decodes `.puffin` DVs; VS Code **DeltaForge** (11 installs) has a catalog browser plus SQL. No Paimon extension on any registry.

### 1.3 Format coverage

Only one maintained tool covers **Iceberg and Paimon together in one UI: Amoro**, whose last release was 2025-09-11 [doc]. Every other GUI in the survey is Iceberg-only. Paimon's SQL surface is richer than Iceberg's but requires Flink or Spark — the DuckDB-style engine-free path that exists for Iceberg has no Paimon counterpart in this survey.

Paimon's practitioner surface is also thinner than its feature set: **StackOverflow has no `apache-paimon` tag, no tag anywhere containing "paimon", zero questions with "paimon" in the title, and three body matches that are all coincidences** [run, three StackExchange API calls with live quota decrements]. That is a genuine absence, not a taxonomy artifact. For comparison: delta-lake 1421, apache-iceberg 317, apache-hudi 184.

### 1.4 Access: the axis most tools share

Nimtable, LakeVision, iceberg.rest and iceberg-metadata-insights require a catalog or an engine. IceGraph's Spark Connect requirement is confirmed explicitly; the others are inferred from their docs and **were not confirmed by launching them**. The two confirmed bare-path readers in the survey are DuckDB's iceberg extension and `icescope`.

For context on what a catalog *gives* you: the Iceberg REST `loadTable` response contains a `metadata` object that is byte-identical to the on-disk metadata.json (verified by set-and-value comparison against the reference fixture, empty diff) [run] — plus `metadata-location`. It carries each snapshot's manifest-list **location** and the snapshot `summary` counters, and stops there. Of the spec's 23 paths, none is a manifest or file-listing resource [run]. Below the snapshot, every client reads storage itself or uses the optional scan-planning endpoints, which neither reference client enables by default (Java `DEFAULT_ENDPOINTS` = 14 entries, no plan endpoint; PyIceberg 12 entries, same; both default `ScanPlanningMode.CLIENT`) [src].

So: **a catalog does not make the metadata tree easier to see. It makes the root pointer easier to find.** Everything Iceberg Lens renders below `snapshots` requires object reads regardless of catalog.

---

## 2. Where engineers are stuck

Ordering signal, apache/iceberg issues created since 2025-01-01 (keyword match, overlapping, repo total 1362) [run]:

| Theme | Count |
|---|---|
| delete file / deletion vector / merge-on-read | 193 |
| query planning / manifest | 153 |
| cross-engine (trino, dremio, athena, snowflake, starrocks) | 120 |
| schema evolution / partition evolution / partition spec | 100 |
| small files / rewrite_data_files / compaction | 84 |
| expire_snapshots / orphan | 53 |

Within the 327 issues carrying the `question` label: snapshot 80, "metadata table" 48, "delete file" 45, manifest 36, storage 36, trino 32, expire 28 [run]. This label is a usable proxy here specifically because **apache/iceberg has GitHub Discussions disabled** — `hasDiscussionsEnabled: false`, `/discussions` returns 404, and a control query against apache/iceberg-rust returns `true` with 32 discussions, so the query does detect them where they exist [run].

apache/paimon since 2025-01-01 (repo total 677): bucket 136, compaction 77, changelog 56 [run].

### The questions, and how well they are served

| Question | State | Evidence |
|---|---|---|
| What is in my table right now? | **Served well** | 15+ Spark metadata tables, 11 Trino tables, DuckDB `iceberg_metadata`, PyIceberg `inspect.*` — all with time travel [doc] |
| What changed in *this* snapshot? | **Served well** | `entries.status` + `entries.snapshot_id`: one query [doc] |
| What changed between two *non-adjacent* snapshots? | **Awkward** | Requires walking ancestry via `history`/`snapshots` and aggregating; hand-written SQL [doc] |
| How much did the scan prune? | **Served** (counts) | `ScanMetrics` 18 accessors; Spark driver metrics `TaskSkippedDataFiles` etc. surface in the SQL UI [src] |
| **Which** files did the scan keep? | **Served** | `TableScan.filter(...).planFiles()`, PyIceberg `scan.plan_files()`, REST plan endpoint — one documented call. *The scan's original framing that this needed hand SQL was wrong* [doc][src] |
| **Which** files did it skip, and **why**? | **Not served anywhere** | `ManifestGroup` lines 304–377 wrap every prune in `CloseableIterable.filter(counter, iterable, predicate)` — the counter is all the filter is handed; the rejected entry is discarded. Zero `LOG.` statements in `ManifestGroup` or `InclusiveMetricsEvaluator`. Code search for `skippedDataFiles` across 26 files: every one a counter carrier. Skipped set = diff `planFiles()` against `files`; reason = unavailable at any price [src] |
| Why is planning slow? | **Awkward** | trinodb/trino#23451, 21 comments: "stuck in the planning phase for 2 to 3 minutes"; regression traced by the reporter to Trino 451 [run] |
| Where did my delete files go across compaction? | **Awkward → not served** | Recurring 2022–2025: #10312 "Equality delete lost after compact data files" (14 comments), #5058, #9833, #13155. Title-keyword totals: "equality delete" 27, "position delete" 32 [run] |
| Which data file does this DV/delete apply to? | **Served, undocumented** | `BaseFilesTable.schema()` = `DataFile.getType().fields()`, which includes `referenced_data_file`, `content_offset`, `content_size_in_bytes` unconditionally — **but `spark-queries.md` omits all three and never mentions deletion vectors** [src][doc]. The `content` 0/1/2 documentation is complete, however; `FileContent` defines no further file-level values, and DVs appear as `content=1`. *The scan framed that as a doc gap; it is not.* |
| Why didn't expire/orphan cleanup work? | **Awkward, and partly a defect class** | `remove_orphan_files` has `dry_run` and `prefix_mismatch_mode` [doc], but #12819, #16493, #11169, #10907, #15306 are five distinct reports of cleanup leaving or losing files [run] |
| Why do two engines disagree on the same table? | **Not served** | trino#22972 (duplicate rows with DVs), #22393 (deleted records with equality deletes), #17836, #28910, #26262 (HMS/GCS inconsistency, open 13 months) [run] |
| Is v3 safe for my workload? | **Not served** | Three issues within three days in July 2026: #17241 and #17209 (DV MERGE unbounded executor memory; reporter states the identical job is stable on v2 with ~10× more delete files), #17206 ("Can't index multiple DVs", permanent planning failure). #16475 (DV length/offset under-validated → crash/huge-allocation) [run] |
| Which LSM level is this Paimon file in? | **Served well** | `$files.level` is a first-class column [doc] |
| Why is my Paimon bucket config wrong? | **Awkward** | Largest Paimon theme (136); no visualization of bucket→file assignment found [run] |
| Who wrote this commit? | **Not answerable by design** | `SnapshotSummary` carries counters plus `EnvironmentContext` engine-name/version and `spark.app.id`. A person requires custom `snapshot-property.*` [src] |

---

## 3. Gaps

Intersection of §2 (recurs) and §1 (badly served). Four, ordered by how strongly the absence is established.

### 3.0 The structural prerequisite: nothing local decodes Iceberg bounds

This is not itself a gap in the market; it is the reason three of the four gaps below are hard, and it is also the tool's own largest missing capability.

Iceberg's `lower_bound`/`upper_bound` byte arrays carry **no self-description**. Decoding requires resolving a type first, and the type source differs per site [doc, format/spec.md Appendix D]:

- `data_file.lower_bounds`/`upper_bounds`: map key is a schema field id, resolved against **the schema the manifest carries in its own Avro key-value metadata** (`schema`, `schema-id`) — *not* the table's current schema. A tool that already parses metadata.json and reuses the current schema here will silently mis-decode across schema evolution.
- `manifest_file.partitions[i]` (`field_summary`): type is the **result type** of partition field *i* of the spec named by `partition_spec_id` — identity→source type, bucket[N]→int, truncate[W]→source type, year/month/hour→int, day→date (readers must also accept int days-since-epoch), void→source type or int.
- `data_file.partition`: struct type derived from the manifest's partition spec, with **partition field ids as struct field ids**. In v1 partition field ids were not tracked and the reference implementation assigned them sequentially from 1000 — the spec itself calls this out as a source of type conflicts when reading manifests written under multiple specs.

Encoding (Appendix D): int/float 4-byte LE; long/double/time/timestamp(_ns) 8-byte LE; date 4-byte LE days; string UTF-8 with no length prefix; uuid 16-byte BE; **decimal(P,S) unscaled two's-complement big-endian in the minimum number of bytes — variable length per value, scale from the schema**. Bound-only overrides: geometry/geography bounds are a single point as concatenated 8-byte LE doubles (x:y:z:m, with documented omission patterns and `NaN` placeholders); variant bounds are Variant metadata concatenated with a Variant object keyed by normalized JSON paths like `$['location']['latitude']`. Ordering rules: `-0.0` before `+0.0`; `NaN` never written as a bound; for geography `xmin` may exceed `xmax` across the antimeridian.

**Difficulty: moderate and bounded — a few hundred lines, mostly table-driven, with a long tail of correctness traps (decimal length, v1 field-id conflicts, geo/variant overrides).** The right validation is the spec's own type table plus fixtures written under two different partition specs.

**Shelf life:** Iceberg v4 replaces the metrics maps with typed `content_stats` (data_file id 146), so bounds become typed values and the Appendix D decode step disappears for v4 manifests [doc]. v1–v3 tables will outlive that transition by years, and v4 is not adopted — the spec text on `main` says so, and PR #16025 (adaptive metadata tree) was still open on 2026-08-10 [src].

**Why this is the hinge:** decoding bounds and partitions is the single work item that unlocks 3.1, 3.2 and 3.3 below, plus fills the inspector's hardcoded `"N/A"` Partition column. It is currently the tool's largest missing piece and its cheapest route to something no local tool does.

### 3.1 Which files a predicate would skip, and which bound rejected them

**Recurs:** planning/manifest is the second-largest theme (153); the `question` label's second sub-theme is "metadata table" (48). **Served nowhere.** The absence is established by source reading across three surfaces (ScanMetrics, the prune sites in ManifestGroup, the metrics-reporting docs), a code search over all 26 files referencing `skippedDataFiles`, and the absence of any logger in the two relevant classes [src]. It has **not** been established by executing a scan — an `EXPLAIN ANALYZE` in Spark or Trino was not run.

**What would close it:** given a user-typed predicate, evaluate it against `manifest_file.partitions` field summaries (manifest-level prune) and then against `data_file` lower/upper bounds, null counts and NaN counts (file-level prune), and report per-item: kept / skipped, plus the specific column and bound that rejected it.

**The hard part, stated honestly:** this produces a *simulation* of engine behaviour, not the engine's actual decision. Divergence is the correctness risk — an engine may apply residual predicates, partition-spec evolution, delete-file interaction, or version-specific evaluator changes that a re-implementation misses. A tool that shows "Trino would skip this file" and is wrong is worse than no tool. Mitigations: label it as a simulation of `InclusiveMetricsEvaluator` semantics at a named Iceberg version; offer a reconciliation mode that diffs the simulation against `planFiles()` output the user pastes in (which *is* a one-call API), so the tool's own error is visible.

### 3.2 Delete-file → data-file topology

**Recurs:** the largest theme (193), and the largest `question`-label file theme (45). **Served, but as unlinked rows and undocumented columns.**

The spec makes this directly derivable for v3 DVs: a delete manifest entry carries `referenced_data_file` (required for DVs), `content_offset` and `content_size_in_bytes`, which "must exactly match the `offset` and `length` stored in the Puffin footer", with at most one DV per data file per snapshot [doc]. That is an unambiguous edge.

Three tiers of difficulty, and the third is a real limit:

1. **v3 DVs — easy.** `referenced_data_file` is a direct pointer. Drawing the edge is parsing you already do. Opening the blob to show cardinality requires the Puffin reader: `PFA1` magic, JSON footer, `deletion-vector-v1` payload = 4-byte BE length, magic `D1 D3 39 64`, portable 64-bit Roaring bitmap, 4-byte BE CRC-32 [doc]. `ebyhr/puffin-tools` already does exactly this standalone, so this is a known-solvable, ~200-line problem — and also a place where the differentiation is "in the same view as everything else", not "only we can".
2. **v2 position deletes — moderate.** The link is the delete file's `file_path` column bounds, which requires the bound decoding from §3.0.
3. **Equality deletes — not resolvable from metadata.** They apply by predicate over identifier fields, not by file reference. No amount of metadata reading produces a file→file edge. Any view must say so rather than draw nothing and leave the reader to infer absence. Equality-delete confusion is a documented recurring theme (27 titled issues) precisely because the relationship is non-local.

### 3.3 Snapshot diff and the shape of a compaction

**Recurs:** small files/compaction 84; delete-file-lifecycle defects span 2022–2025. **Served awkwardly** for adjacent snapshots (`entries` gives added/removed per snapshot), **unserved** for non-adjacent pairs and for any structural view of the change.

**What is derivable:** for any two snapshots, the added set, the removed set and the retained set, grouped by partition, with size and record-count distributions; and the ancestry path between them from `parent_id` / `history.is_current_ancestor`.

**What is not derivable, and this is a hard limit:** **the format does not record which input files produced which output file.** A compaction's snapshot summary carries counters (`added-data-files`, `removed-dvs`, `manifests-replaced`, `entries-processed`, `source-snapshot-id`…) but no input→output mapping [src, SnapshotSummary.java]. So "the shape of a compaction" is honestly deliverable as *before-set / after-set / partition-level aggregation*, and dishonestly deliverable as a fan-in diagram implying provenance that does not exist. This distinction should be drawn before any pixel is.

### 3.4 Ancestry, branches and tags as structure

**Recurs:** `snapshot` is the top `question`-label sub-theme (80). **Served as rows** — Trino `$history` has `parent_id` and `is_current_ancestor`; `$refs` gives name/type/snapshot-id/retention; `refs` in metadata.json gives the same plus retention policy [doc].

**What would close it:** draw the parent→child edges (already parsed, currently drawn as nothing) and render branch heads and tags as labelled anchors on the resulting DAG.

**Difficulty: low.** The data is in hand. **Differentiation: also low** — fern draws `lineage-` edges between snapshots today, and IceGraph advertises "Snapshot & Metadata Lineage" [src][doc]. This is table stakes for a graph tool, not a position.

### 3.5 Gaps deliberately excluded

- **Cross-engine disagreement** (120 issues) is out of reach for a read-only local tool: answering it requires running two engines.
- **Partition decoding as a feature** (the `"N/A"` column) is table stakes, well served by SQL, and matters here only as the enabler in §3.0.
- **Statistics/Puffin theta sketches** are a small addition once §3.2's Puffin reader exists (`apache-datasketches-theta-v1` carries `ndv`), but no recurring pain signal was found for them.

---

## 4. The hypothesis, tested

**Hypothesis:** the tool currently answers "what is in my table" — which SQL metadata tables also answer, in a terminal, with no install — and its graph only earns its existence if it shows relationships those tables cannot: pruning decisions, ancestry, and the shape of a compaction.

The hypothesis has two clauses. The evidence treats them very differently.

### 4.1 Clause one — "what is in my table" is already well served

**Supported, strongly.** Iceberg exposes 15+ metadata tables in Spark and 11 in Trino, all queryable, filterable, joinable and time-travelable [doc]. DuckDB's extension reaches manifest entries and per-file column statistics with no engine and no catalog [run]. PyIceberg exposes the same as Arrow tables. StarRocks added the Trino syntax in 3.4.1. For Iceberg, the incumbent path is genuinely low-friction.

Two qualifications that narrow it:

- **"No install" is conditional.** DuckDB's iceberg extension is marked experimental by its own README and downloads 32.5 MB from extensions.duckdb.org at first use [run] — an air-gapped or offline machine does not have it. Spark/Trino metadata tables presuppose a cluster or at least a local Spark. The genuinely zero-install claim holds for an engineer who already has an engine open, which is a large share of the audience but not all of it.
- **For Paimon the clause is much weaker.** Paimon's system tables require Flink or Spark; no DuckDB-style engine-free path exists in this survey. And Paimon's practitioner surface is close to empty on StackOverflow [run] while its issue tracker is active (677 issues since 2025-01-01) — a gap between people having problems and people having answers.

### 4.2 Clause two — "the graph earns its existence by showing what SQL cannot"

**Not established. The evidence is thin in both directions, and what evidence there is cuts against the framing more than for it.**

**Against the hypothesis:**

1. **A graph is not an unclaimed position.** Two OSS projects render Iceberg metadata as node-link today [doc][src]. Whatever the graph earns, it is not exclusivity.
2. **No evidence links graph rendering to adoption in this space.** The tools that broke out are terminal tools with individual maintainers (visidata 9,237 stars, harlequin 6,315, pgcli 13,344 — all `uv tool install` / pip / brew, no installer, no signing) and general-purpose clients with companies behind them (DBeaver 51,394 stars, 138,304 macOS Homebrew installs in 365 days, 253,036 downloads of a single Windows point release) [run]. The nearest single-purpose desktop viewer, Tad, has 3,474 stars, 897 brew installs/year, and has been stale 17 months — and its documented entry point is a shell command [run].
3. **The relationships the hypothesis names split three ways.** Ancestry (§3.4) is cheap and already done by competitors. Compaction shape (§3.3) is partly *impossible* — provenance is not in the format. Pruning (§3.1) is the one that is both unserved and derivable, and its value comes from *computation the tool performs*, not from the rendering. A table of "skipped: file X, rejected by upper_bound(ts) < 2026-01-01" would carry the same information.
4. **The hypothesis assumes the differentiator must be the rendering.** An alternative differentiator is present in the evidence and the hypothesis does not consider it: **access**. Every GUI in §1.2 except DuckDB and `icescope` needs a catalog or an engine. IceGraph — the closest graph competitor — needs a running Spark Connect server and supports v2 only. A bare-directory, no-engine, no-network reader is a narrower and better-evidenced position than "graph".

**For the hypothesis:**

1. The gap analysis does agree that the tool's current strongest features (metadata tree walking, snapshot filtering, schema/property diffs, sample rows) all sit inside clause one, and are duplicated by SQL. That half of the hypothesis is right about the *current* state.
2. Relationship-carrying is where the unserved questions cluster (§3.1, §3.2), so redirecting effort from "render more fields" to "derive relationships" follows from the evidence — even if the conclusion "therefore graph" does not.

### 4.3 What the evidence actually supports

A reformulation that fits the evidence better than either the hypothesis or its negation:

> The tool's differentiation comes from **derived answers no interface produces** (skip-with-reason, delete→data topology, snapshot diff) and from **an access path most tools lack** (bare filesystem, no engine, no catalog, offline). The graph is a presentation choice for those answers, not the source of the differentiation, and it is not exclusive.

**Where the evidence is thin, explicitly:** there is no practitioner study of what engineers reach for when Iceberg metadata is wrong. The one public usage measurement is a self-selected survey with an undisclosed sample size (~28 implied), measuring prevalence not time, and not separating "local filesystem" from "local Docker running a REST catalog" [doc]. Puffin Reader's 33 downloads in four months cannot distinguish "the IDE channel does not want this" from "nobody found it". **None of the four positioning options below rests on a measured demand signal, and none can until an experiment from §6 is run.**

---

## 5. Positioning options

### 5.1 Axes, stated before ranking

1. **Demand evidence** — how strongly the questions this option answers recur.
2. **Absence strength** — how well-verified it is that nothing else serves them.
3. **Solo-maintainer cost** — implementation plus the ongoing treadmill (v3 → v4, Paimon 2.0, engine drift).
4. **Distribution friction** — signing, notability gates, install size, discoverability.
5. **Durability** — survival against Iceberg v4, DuckDB, and vendor consoles absorbing the capability.

Axis 3 and 4 are where the current project is worst positioned regardless of option, so they are stated once here rather than repeated:

- Artifacts are **unsigned and un-notarized**. `build.gradle.kts` has no `signing {}`, `notarization {}`, `provisioningProfile` or `entitlementsFile`; `release.yml` runs three `packageRelease*` tasks and uploads, with no codesign/signtool/notarytool step and no signing secret referenced; a repo-wide grep for signing terms returns zero matches [src][run]. On macOS Sequoia the Control-click override was removed — a user must go to System Settings → Privacy & Security → Open Anyway and re-authenticate [weak].
- **Homebrew is closed on both counts.** Homebrew's own source defines cask deprecation reason `fails_gatekeeper_check`, 27 "deprecate unsigned" PRs landed in Aug 2025, and existing unsigned casks are disabled 2026-09-01 [src][run]. Notability requires 75 stars / 30 forks / 30 watchers, or **225 stars for a self-submission by the repo owner**; the repo has 9 stars, 0 forks, 0 subscribers [doc][run].
- **Windows signing is closed cheaply.** Azure Artifact Signing Public Trust certificates are unavailable to individuals outside US/Canada and Turkey is on no list; Private Trust is available and useless for public distribution. OV is $150–300/yr with a mandatory HSM; EV lost its instant-SmartScreen behaviour in 2024 [doc]. **SignPath Foundation** offers free OV-level signing for qualifying OSS projects and Microsoft's page names no geographic restriction — **unverified for a Turkey-based maintainer**.
- **Size:** v1.0.2 ships 193 MB (dmg) / 182 (msi) / 175 (deb), up ~43% from v1.0.0, with 7/5/4 downloads. DBeaver ships a comparable app at 115 MB with a bundled OpenJDK 21 [run]. Compose Multiplatform provides **no auto-update** — verified across the distribution docs, the 1.10.x release notes, a CHANGELOG grep, and the 2022 blog post whose "Online Updates" item is the third-party Conveyor integration, not a JetBrains feature [doc][run].
- **The name is a policy problem in every option.** The ASF marks list registers **ICEBERG** (bare word, US class 9 — computer software) for Apache Iceberg [doc]. The FAQ prohibits Apache marks "in the primary or secondary branding of any third party product", naming the `BigCo Project Thing` form specifically. "Iceberg Lens", `packageName = "IcebergLens"` and `bundleID = com.iceberglens.desktop` are that form. The sanctioned alternative is `<YourName>, Powered by Apache Iceberg` with a homepage link, attribution and a non-endorsement disclaimer. Precedent: kafkacat → kcat in v1.7.0, explicitly "to adhere to the Apache Software Foundation's (ASF) trademark policies" [src], at the cost of its search identity. There is a permission route (trademarks@apache.org); it has not been used. Separately, "Iceberg" as a marketplace search term returns editor color themes with 62,463 and 43,105 installs [run] — a discoverability problem independent of the legal one. **Paimon does not appear on the ASF registered-marks list**, which does not make it free (policy covers unregistered project names) but means the registration evidence differs.

### 5.2 The options

---

**Option A — The prune explainer**

*Bets on:* the one question class that recurs heavily and is served by nothing (§3.1), plus the delete-topology work (§3.2) that shares the same decoding prerequisite (§3.0).

*For:* an engineer debugging why a query read more than it should, or why a delete did not take effect. The author's own job description.

*Demands:* the §3.0 bound/partition decoder; a re-implementation of manifest-level and file-level pruning semantics pinned to a named Iceberg version; a reconciliation mode against `planFiles()` so the tool's own divergence is visible. Ongoing: evaluator semantics change across versions; v4 typed `content_stats` changes the decode path but not the evaluation logic.

*Distribution:* the derivation is valuable in any shell. A CLI (`--predicate "ts < '2026-01-01'"` → per-file kept/skipped/reason) is testable, scriptable, CI-embeddable, and installs without signing. The desktop UI becomes the exploratory surface over the same engine, not the only surface.

*Falsified by:* Iceberg adding collection-valued or reason-carrying scan reports upstream (nothing in `ScanMetrics` suggests it, but the API is theirs); an engine exposing per-file prune reasons via `EXPLAIN ANALYZE` (**untested — see §6**); or users rejecting a simulation as untrustworthy on the first divergence.

*Naming:* an abstract name plus "Powered by Apache Iceberg" resolves the ASF issue and avoids the color-theme collision.

---

**Option B — Local-first, no-engine, multi-format inspector (current shape, sharpened)**

*Bets on:* the access gap. Every surveyed GUI except DuckDB and `icescope` needs a catalog or engine; IceGraph needs Spark Connect and reads v2 only. Plus Paimon, where the SQL alternative costs a Flink or Spark cluster and where the community surface is empty (no SO tag at all).

*For:* someone handed a directory — a bug report attachment, a copied warehouse, an air-gapped cluster, a test fixture. The 42.9% "Local Development / Testing" figure from the survey is directionally supportive but is prevalence, self-reported, n≈28, and does not separate local files from local Docker-with-REST-catalog [doc].

*Demands:* two format treadmills. Iceberg v3 is partly unmodelled today and v4 is moving (PR #16025 open 2026-08-10); Paimon 2.0.0 shipped 2026-08-07 and **whether snapshot version, manifest schema or DV encoding changed in 2.0 is unresolved** — the release body is download links and a compare URL, and the master spec docs are not version-stamped. This is the highest ongoing cost of the four options.

*Distribution:* the installer problem in full, plus a direct 2026 competitor (`icescope`, Tauri, explicitly "Local Hadoop-style Iceberg warehouse support") that has not been run and whose depth is unknown.

*Falsified by:* DuckDB's iceberg extension gaining Paimon support or leaving experimental; any surveyed web UI adding a documented bare-path mode; `icescope` proving to cover the same ground at a fraction of the install size.

*Naming:* multi-format gives an independent reason to drop "Iceberg" from the name — a name covering two formats should not carry one of them.

---

**Option C — Iceberg-only, depth-first on v3/v4**

*Bets on:* v3 adoption pain being the near-term concentration. Three DV issues within three days in July 2026, one reporter stating the identical job is stable on v2 with ~10× more delete files and unstable on v3 [run]. v3 is GA at Snowflake (2026-05-07) and across AWS EMR/Glue/S3 Tables (2025-11-26); Databricks supports it on DBR 18 LTS with four named exclusions (defaults, unknown type, ns timestamps, multi-argument transforms); Trino ships it with the connector docs stale relative to its own code [doc][src]. Adoption is broad enough that the confusion is real.

*For:* engineers on the v2→v3 transition, and for the author's own daily work.

*Demands:* Puffin reader (DV blob, theta sketch), row-lineage inheritance (the four-level chain table `next-row-id` → snapshot `first-row-id` → manifest `first_row_id` → data_file `first_row_id` → `_row_id`, so a data file alone cannot yield a row id), encryption-key metadata, statistics/partition-statistics files. Then v4: relative paths, `content_stats` typed bounds, and an adaptive metadata tree that is **not merged** and whose design document was not read.

*Distribution:* narrower scope makes a CLI or an IDE plugin plausible. Note `ebyhr/puffin-tools` and the IntelliJ Puffin Reader already occupy the single-file Puffin niche.

*Falsified by:* v4 typed bounds removing the decoding barrier that gives a hand-rolled reader its edge; metadata tables absorbing DV columns into documentation (the columns are already in the schema, just undocumented); the July 2026 DV issues turning out to be an EMR-build artifact rather than an Iceberg regression (**unresolved — both reports come from the same EMR 1.10.1-amzn-0 / S3TablesCatalog stack**).

*Naming:* Iceberg-only makes the ASF form harder to avoid honestly, so the "Powered by" construction plus an abstract primary name is the compliant shape.

---

**Option D — Change the channel, not the product**

*Bets on:* distribution being the binding constraint. 9 stars, 5–7 downloads per artifact, unsigned on two platforms, 193 MB, Homebrew closed at 225 stars for self-submission, no auto-update, and a name that collides with a 62,463-install color theme.

*For:* the same users, reached where they already are. Three candidate channels, evidenced very differently:

- **Terminal/CLI.** The only individual-maintained tools above 5k stars in this space are terminal tools (visidata, harlequin), installed by package managers, with no signing and no installer [run]. Even Tad's documented entry point is a shell command.
- **IDE plugin.** Channel size is large (JetBrains Big Data File Viewer 2,304,810 downloads; VS Code Jupyter 107.9M installs) and the specific niche is empty across three registries [run]. But the one Iceberg inspection plugin that exists, Puffin Reader, has 33 downloads in four months — which cannot distinguish an empty channel from an absent demand.
- **DuckDB extension.** Reaches users who already have the engine-free path, but is a rewrite in a different language and ecosystem.

*Demands:* a port or a headless core extraction. Option A's CLI-first shape is the cheapest version of this — same derivation engine, new front door.

*Falsified by:* a CLI or plugin shipping and drawing the same single-digit numbers, which would relocate the constraint from distribution to demand.

*Naming:* the marketplace collision makes an abstract name a functional requirement here, not just a legal one.

### 5.3 Ranking on the stated axes

On **demand evidence + absence strength + durability**: **A > C > B > D**. Option A targets the one question class where the absence was verified across three independent surfaces and where the answer is derivable from data already parsed. C is second because v3 pain is concentrated and current but the format is moving under it. B's absence is real but softer (`icescope` exists, unrun). D targets no question at all — it is a delivery change.

On **solo-maintainer cost + time to a first external user**: **D > A > C > B**. D via the CLI route can ship in days and removes the signing, size and Homebrew problems at once. A's decoder is a few hundred lines with a correctness tail. C signs up for a moving spec. B signs up for two.

**These two orderings disagree on D by three places, and that disagreement is the actual decision.** If the constraint is "nobody knows this exists", D first and A second is the sequence the evidence supports. If the constraint is "it does not yet do anything unavailable elsewhere", A first.

A third axis reverses again: on **fit to the author's stated daily use** (Spark+Iceberg internals at Huawei Cloud), C ranks first — but that axis is a preference input, not evidence, and it should be labelled as such rather than smuggled into an evidence ranking.

**Recommendation, labelled as such and separate from the evidence:** A's derivation engine, delivered first through D's channel — a CLI that takes a table path and a predicate and prints kept/skipped/reason, with the desktop UI as the exploratory surface over the same core. It attacks the best-verified gap using the channel with the best evidence of reaching individual maintainers' users, and it defers the multi-format treadmill (B) and the moving-spec bet (C) until there is a demand signal. **Its basis is §3.1's absence verification and §5.1's distribution evidence, not a measured demand signal — which does not exist for any of the four.**

---

## 6. What the evidence does not settle

Ordered by decision impact per unit of cost.

| Question | Why it matters | Cheapest experiment |
|---|---|---|
| **What does IceGraph's graph actually draw?** Its caps (2000 snapshots, 5000 data files) suggest depth, but only the README was read. | It is the closest competitor. If it renders the full chain, §1.1's remaining unclaimed depth disappears. | `docker run` the image against a fixture table with a Spark Connect server. Hours. |
| **What does `icescope` cover on a local warehouse?** README claims "Local Hadoop-style Iceberg warehouse support"; never run. | It is the only other confirmed bare-path desktop reader — Option B's direct competitor. | Download the dmg, point it at an existing test fixture. Under an hour. |
| **Does any engine expose a per-file prune reason via `EXPLAIN ANALYZE`?** Not tested in either Spark or Trino. | Converts §3.1 from a source-reading absence into an executed one, or kills Option A. | One Spark and one Trino run against a table with known-excluded files. |
| **Can PyIceberg open a bare `metadata.json` with no catalog?** Not established in the dossier. | Determines how strong the "zero-install terminal alternative" really is for local files, which is the core of the hypothesis's clause one. | One Python session. Minutes. |
| **Would SignPath Foundation accept a Turkey-based maintainer's OSS project?** Microsoft names it as free OV-level signing with no stated geography; signpath.io was not fetched. | Resolves the Windows half of the distribution problem, or confirms it closed. | Read signpath.io eligibility; submit if it fits. |
| **Would the ASF grant a naming exception, and does it treat repo names differently from product branding?** Policy is unambiguous; enforcement posture toward a 9-star project is not established. | Determines whether a rename is forced or optional, which affects every option. | One email to trademarks@apache.org. |
| **What changed in Paimon 2.0.0 (2026-08-07)?** Release body is download links; master spec docs are not version-stamped. | Sizes Option B's treadmill. | Diff the `docs/docs/concepts/spec/` tree between the 1.x and 2.0 tags. |
| **Is Amoro still an active ASF podling?** Last release v0.8.1-incubating, 2025-09-11; repo not archived. | It is the only maintained Iceberg+Paimon UI. If it is stalling, Option B's competitive picture changes. | Incubator status page + dev@ list. |
| **Are the July 2026 v3 DV OOM reports an Iceberg regression or an EMR-build artifact?** Both come from EMR 1.10.1-amzn-0 + S3TablesCatalog; neither resolved. | Sizes Option C's bet. | Watch #17241/#17209; or reproduce on stock Iceberg 1.10.1 + Spark 4.0.2. |
| **Does Iceberg v4's adaptive metadata tree break the metadata-table contract?** PR #16025 open on 2026-08-10; the design doc at `s.apache.org/iceberg-single-file-commit` was not read. | If v4 restructures manifests, every reader in this survey rewrites, which resets the competitive field. | Read the design doc and PR #16025's diff. |
| **What would a Compose Desktop installer weigh after jlink module trimming?** Observed 193 MB; the 43% growth from v1.0.0 was never attributed. | Whether size is fixable, and whether it is even an adoption barrier at this scale (unmeasured). | Run `modules(...)` trimming and measure; attribute the growth with a dependency-size report. |
| **Does demand exist at all?** No practitioner study; the one survey is n≈28 self-selected and measures prevalence, not time. | Every option in §5 rests on inferred demand. | Post the tool where the practitioners are (Iceberg Slack, dev@) with one concrete gap answered, and count. Cheapest demand test available, and the only one that produces a signal rather than an inference. |