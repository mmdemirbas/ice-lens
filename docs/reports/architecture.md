---
title: Architecture decision
summary: One headless core, several shells. Why the desktop-vs-web question resolves to a server rather than a browser rewrite, with the measured budgets and dependency costs behind it.
---

> [!TLDR]
> **The UI can be shared between desktop and web. That is not where the cost is, and for this
> app sharing it is probably the wrong trade.**
>
> - Every library in the data layer is JVM-only — avro4k, Avro, DuckDB JDBC, ELK, `java.nio`. A browser deployment needs a server that reads **and lays out**. That server is most of the work; the frontend only decides what sits on top of it.
> - So: extract a headless `core`, keep Compose Desktop as the primary shell, and make shell #2 a **local HTTP server** — which also serves the CLI, the IDE plugin, and any future browser client.
> - Derivation traces are not a feature bolted on later. Every production system makes the explanation a by-product of the *single* evaluation that produces the value. The hook point already exists in this codebase.

## The decision {#decision}

```oku-diagram
{"src":"flowchart TD\n  subgraph CORE[\"core — headless, no Compose\"]\n    R[\"readers<br/>Iceberg · Paimon · Delta · Hudi\"]\n    D[\"decoders<br/>Appendix D · partitions · stats\"]\n    A[\"analysis<br/>summaries · prune sim · topology\"]\n    L[\"layout<br/>ELK\"]\n  end\n  subgraph IO[\"storage — pluggable\"]\n    F[\"local FS\"]\n    S[\"s3 / obs\"]\n    H[\"webhdfs\"]\n    C[\"catalogs\"]\n  end\n  IO --> CORE\n  CORE --> DESK[\"Compose Desktop<br/>in-process · shell #1\"]\n  CORE --> SRV[\"HTTP server<br/>shell #2\"]\n  SRV --> CLI[\"CLI\"]\n  SRV --> IDE[\"IDE plugin\"]\n  SRV --> WEB[\"browser UI<br/>deferred\"]\n  style WEB stroke-dasharray: 4 3","caption":"The server is not the web UI. It is the substrate three other shells sit on, and it is what makes deploy-next-to-data possible without a browser rewrite."}
```

**What changes now:** pull readers, model, decoders, graph building and layout into a `core`
module with no Compose dependency, behind an interface the UI calls. `AppState.kt` already
holds the business logic, so this is a module split rather than a redesign.

**What stays:** Compose Desktop as the primary shell. It is Stable, it works, and it is the
only shell that gets direct `java.nio` and DuckDB JDBC with no server at all — which is the
product's actual selling point.

**What comes next:** a local HTTP server over the same core, on the model DuckDB's own `ui`
extension uses — a native process, all cores, all files, nothing leaving the machine.

## Why not share the Compose UI {#why-not-web-ui}

I went in expecting "write the UI once, ship both". The evidence says the mechanism works and
the trade is bad here.

Compose Multiplatform for web is **Beta**, not Stable, as of today — promoted in 1.9.0
(Sept 2025), still listed Beta while Android/iOS/Desktop are Stable, and the Kotlin roadmap
carries no dated Stable-web item. WasmGC support is fine now, Safari 18.2+ included. But three
things decide it, and none is the renderer:

```oku-compare-grid
{"cards":[{"t":"The data layer cannot cross","b":"avro4k 2.10.0 publishes only platform.type=jvm variants. Avro, DuckDB JDBC, ELK and java.nio are Java-only. A browser build needs a server doing all reading and all layout — so the shared \"UI\" would be receiving pre-laid-out coordinates, and the interesting half of GraphCanvas is server-side already.","verdict":"out"},{"t":"Canvas rendering costs Ctrl+F","b":"Compose web renders to a canvas. That means no find-on-page, no browser translation, no text extraction, no extension interop. For a tool whose users read manifest paths and snapshot IDs and paste them into a Spark shell, losing find-on-page is a regression in the primary workflow, not a polish item.","verdict":"out"},{"t":"~4.3 MB gzip before your code","b":"Measured on the JetSnack demo JetBrains links from their own examples page: 4.3 MB gzip, 12.4 MB uncompressed of runtime. Against a tool people open to glance at one table.","verdict":"out"},{"t":"An HTTP server over the same core","b":"Serves the deploy-next-to-data case, the CLI, the IDE plugin and any later browser client from one artifact. No renderer bet, no Beta dependency, and the desktop app keeps its no-server path.","verdict":"in"}]}
```

> [!NOTE]
> **The one thing that would reverse this**, and it is worth pricing rather than dismissing:
> DuckDB-Wasm can query Parquet in the browser. If sampling moved there and the metadata
> readers were reimplemented in multiplatform Kotlin — kotlinx-serialization already is; Avro
> would need a hand-rolled multiplatform reader — a genuinely serverless browser build becomes
> possible, reading from S3 over HTTP range requests with no backend at all. That is a
> different, arguably better product. It also means replacing avro4k **and** ELK. Not the
> first move; not ruled out.

## What the graph can actually carry {#graph-budgets}

Measured on Compose Multiplatform 1.10.1 through an offscreen scene at 1600×1000. The binding
constraint is not Skia and not ELK — it is **one Compose subtree per node**.

```oku-chart
{"type":"grouped-bar","title":"Frame cost by node count (ms) — 16.7ms is the 60fps budget","categories":["100 nodes","1,000 nodes","2,000 nodes"],"series":[{"label":"Composed node cards","color":"warn","values":[6.4,14.6,42.3]},{"label":"Same count as Canvas rects","color":"accent","values":[1.9,3.0,4.0]}]}
```

Removing `pointerInput` changed nothing measurable above 500 nodes, so composition, measure and
layout of the card is the cost — not hit-testing, which is where I would have looked first.

ELK's layered layout on a metadata-tree shape turns superlinear past about 9,000 nodes, and
**edge routing dominates**: at 8,889 nodes `SPLINES` cost 706 ms against `POLYLINE`'s 293 ms.

```oku-chart
{"type":"bar","title":"ELK 0.11.0 layered layout (ms, best observed)","rows":[{"label":"1,071 nodes","value":80,"display":"80 ms"},{"label":"4,445 nodes","value":320,"display":"320 ms"},{"label":"8,889 nodes","value":511,"display":"511 ms"},{"label":"17,777 nodes","value":1700,"display":"1.7 s"},{"label":"35,553 nodes","value":7700,"display":"7.7 s"}]}
```

> [!WARNING]
> These are **upper bounds, not clean-room numbers**. The measuring host was under heavy load
> and the offscreen scene rasterises on CPU rather than through a GPU-backed surface. The
> ratios between the two series are the usable part; the absolute milliseconds are not.

**Budgets to design against:** 500 composed cards on screen (1,000 ceiling), 20,000 canvas
primitives, ELK input capped at 5,000 nodes. Stroked cubic edges are the canvas-side limit —
budget 1,000–2,000 per frame and draw straight lines beyond that.

The single lever that helps everywhere is **aggregation with expand-on-demand**: one
*"1,204 data files"* node that expands into its children. It lowers the ELK input, the
composable count and the edge count at the same time, and it is a model-layer change rather
than a rendering one.

## Derivation traces: fold with witness {#provenance}

You asked for every computed number to explain itself. The research question was how to do that
without the explanation drifting away from the value it explains.

Every production system I could read solves it the same way: **the explanation is a by-product
of the single evaluation that produces the value.** Spark's generated code increments the metric
on the line immediately before it emits the row. Salsa records dependencies as a side effect of
reading inputs. ProvSQL appends the provenance column to the same query's target list. None of
them derives the explanation separately — because a second derivation is a second
implementation, and two implementations disagree eventually.

So the pattern is not "compute, then explain". It is **derive the total by folding the
explanation**:

```oku-annotated-code
{"src":"// The ledger IS the number.\ndata class ManifestContribution(\n    val manifestPath: String,\n    val delta: ContentStats,\n    val entriesSuppressedAsDuplicate: Int,\n    val firstCountedIn: String?,   // non-null when this manifest was already counted\n)\n\nclass ContentStatsAccumulator(private val liveEntriesOnly: Boolean) {\n    private val contributions = mutableListOf<ManifestContribution>()\n\n    fun build(): ContentStats =\n        contributions.fold(ContentStats()) { acc, c -> acc + c.delta }\n}","lang":"kotlin","annotations":[{"id":1,"content":"The total is <em>derived from</em> the contribution list, not computed alongside it. Drop a contribution and the number on screen changes — so the explanation cannot silently disagree with the value. That is the whole anti-drift property, and it costs one fold.","match":"contributions.fold(ContentStats()) { acc, c -> acc + c.delta }"},{"id":2,"content":"Recording suppressed duplicates is what makes the deduplication legible. \"1,204 files\" and \"we skipped this manifest because snapshot 3 already counted it\" are the same fact seen from two directions — and it is precisely the fact that was wrong before today's fix.","match":"val entriesSuppressedAsDuplicate: Int,"},{"id":3,"content":"One contribution per manifest, not per entry. Drilling below a manifest re-invokes the same accumulator scoped to that manifest, rather than storing millions of rows — so memory tracks manifests while explanation depth stays unlimited.","match":"val manifestPath: String,"}]}
```

The hook point already exists. `ContentStatsAccumulator` — written earlier today to fix the
summary miscount — is already a single-traversal fold with exactly one classification site, and
`TableSession` already retains the source model in its LRU, so drill-down needs no extra
retention. This is a change of a few dozen lines, not a redesign.

## Dependency budget {#dependencies}

All sizes measured. Two of the three storage paths were verified by execution against live
services, not read off documentation.

```oku-table
{"headers":["Capability","Choice","Cost","How established"],"rows":[["S3 / OBS / MinIO","AWS SDK v2 `s3` + `url-connection-client`, netty and apache5 excluded","**8.36 MB** / 32 jars","`run` — listed keys and did a `bytes=-8` range read returning Parquet's `PAR1` footer magic against live MinIO"],["S3, alternative","DuckDB `httpfs` — already bundled","**0 MB**","Needs a spike: `read_blob` materialises the whole object, so a 200 MB file is a full download where the SDK issues a footer range read"],["HDFS","WebHDFS / HttpFS over the JDK's own HTTP client","**0 MB**","`run` — LISTSTATUS, GETFILESTATUS and OPEN with offset+length against apache/hadoop:3.4.1, using curl and no client jars"],["Iceberg REST — plus Nessie, Polaris, Unity","Own client: five GETs on `java.net.http.HttpClient`","**0 MB**","All four speak the same protocol; `loadTable` embeds the same TableMetadata document already parsed off disk"],["AWS Glue","Same client + SigV4","**4 MB**","Via Glue's Iceberg REST endpoint, not `GlueCatalog`"],["Delta Lake","Hand-parse: NDJSON commits + Parquet checkpoints through bundled DuckDB","**0 MB**, ~3–4 weeks","Fully specified in PROTOCOL.md (3,106 lines at v4.3.1)"],["Hudi","`hudi-common` 1.2.0 — zero `org.apache.hadoop` references, pure-Java HFile reader","**75.5 MB**, 54 MB of it rocksdbjni","`run` — jdeps over the jar"],["Hive Metastore","`hive-metastore` + `hadoop-common`","**90 MB** / 149 jars","Drags guava 14.0.1 (2013), derby, datanucleus, and a jackson that conflicts with iceberg-core's"]]}
```

**Rejected, with reasons**, because each looks like the obvious choice until you price it:

- **Iceberg `S3FileIO`** — 25 MB, and it hard-requires `kms` *and* `sts` on the classpath purely
  for static initialisation; removing either throws before any I/O happens.
- **`ResolvingFileIO`** — unusable without Hadoop at all: it implements `HadoopConfigurable`, so
  instantiating it throws `NoClassDefFoundError`.
- **`iceberg-core` RESTCatalog** — 15 MB, and its default `ResolvingFileIO` throws at
  `catalog.initialize()` unless you supply your own FileIO. We already parse the metadata JSON
  it would hand back.
- **`delta-kernel-defaults`** — 65.5 MB, 47.8 MB of it Hadoop, and Kernel deliberately hides the
  physical `_delta_log` inventory that an inspector exists to draw.

## What this means for the plan {#revised-plan}

```oku-step-flow
{"steps":[{"t":"Extract core","b":"Readers, model, decoders, graph building and layout into a Compose-free module behind an interface. Pays under every downstream choice, unblocks CLI, server, IDE plugin and headless testing. The trap to avoid: GraphModel currently holds Compose-observable positions, so the transport type and the UI type must be separated with an explicit authority rule rather than left to drift."},{"t":"Metadata legibility — your stated top pain","b":"Wire the decoder in: partition values, typed bounds, per-column stats. AvroReader must expose file metadata and the raw partition record, which it currently discards. Then the raw-vs-decoded toggle, and search within a manifest."},{"t":"Provenance through the engine","b":"Fold-with-witness in the accumulators, contribution lists on TableSummary, progressive drill-down in the inspector. Do this before the analysis features multiply, because retrofitting provenance across a dozen computations costs far more than building the first three with it."},{"t":"Aggregation nodes","b":"One node per group with expand-on-demand. The measured budgets make this the difference between a graph that opens a production table and one that does not."},{"t":"Remote sources","b":"AWS SDK v2 for S3/OBS (spike DuckDB httpfs first — it may be free), WebHDFS for HDFS, own REST client for the four catalogs that share a protocol."},{"t":"Server shell","b":"Local HTTP server over core. Deploy-next-to-data, and the substrate for CLI and IDE plugin."},{"t":"Delta, then Hudi","b":"Delta hand-parsed at zero dependency cost. Hudi last: its on-disk layer needs a Hudi-native log-block format, an HFile-based metadata table and an LSM timeline — the genuinely expensive one."}]}
```

## Open decisions {#open}

Three things I would not decide for you.

```oku-table
{"headers":["Decision","The trade","My lean"],"rows":[["**Server shell before browser UI?**","The server unblocks CLI, IDE plugin and deploy-next-to-data at once, and defers the Beta-renderer bet. A browser UI is the thing a corporate evaluator sees first, though.","Server first. The browser UI is a rendering decision that gets cheaper once the API exists, and more informed once you can see how much of the app is view-model versus rendering."],["**Is Hudi worth 75.5 MB and the hardest on-disk format of the four?**","It completes the set, and 54 MB of that is rocksdbjni which may be excludable. But Hudi's own tracker is the largest of the four and its format needs three bespoke readers.","Last, and only after Delta ships. Consider shipping it as an optional download rather than in the base installer."],["**Spike DuckDB for object storage before writing S3 code?**","If `read_blob` and `glob` can serve the byte path, S3 costs 0 MB instead of 8.36 and reuses the engine already bundled. If it materialises whole objects, it is unusable for 200 MB Parquet files.","Spike it. One measurement against a real bucket decides an 8 MB dependency and a whole code path."]]}
```

Two things worth stating plainly because they cut against my earlier advice:

- **I proposed a CLI as a distribution hedge.** With the server shell, the CLI stops being a
  separate product and becomes a thin client over the same API — which is both cheaper and a
  better answer to your point that a CLI can't match the desktop experience.
- **The graph budgets are the constraint I most underestimated.** Design targets of 500 composed
  nodes on screen and 5,000 nodes into ELK are far below what a production Iceberg table
  contains. Aggregation is not a nice-to-have for scale; it is the only way the graph opens a
  real table at all.
