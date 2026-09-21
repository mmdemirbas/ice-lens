---
title: Everything it does
order: 20
summary: The full feature list, grouped by the level of the tree each answer lives on.
---

> [!TLDR]
> Twenty-seven things the app answers, in six groups: reading the structure; what a scan, a commit or a read does; deletes, rows and their fate; checks against the bytes; maintenance planned the way the engine plans it; and the tool itself.

## Reading the structure

- Interactive graph of the metadata tree — metadata files, snapshots, manifest lists, manifests, data files, delete files, sample rows — with branches drawn in their own columns and long sibling runs folded into pages that say what they hide
- Inspector panel with every field the format records, each recorded figure beside the same figure counted from the bytes, JSON highlighting, and copy-to-clipboard buttons
- Schema evolution view -- diffs between schema versions (added/dropped/renamed columns, type changes); table properties tracked across metadata versions

## What a scan, a commit or a read does

- **What a scan would skip**: a filter (`WHERE`-clause or form, with `AND` / `OR` / `NOT` / `IN` / `BETWEEN` / `LIKE`) evaluated against manifest partition summaries and file column bounds, with the term that proved each skip
- **What a commit did** — folded from the manifests it wrote, checked against its own summary — and **what is different between any two snapshots**, on either format, with one side pinned and the other stepped through history
- A file's history on either format — the commit that added it, the one that removed it, and the retained snapshots that still list it live and so keep it on disk
- Both directions of the orphan question, behind a click on the table panel: what is under the table that no metadata names, and what the retained snapshots need that is not there — each missing file with the snapshots that read it
- Which snapshot a read as of a time lands on, the way each engine resolves it — including the abandoned commit a rolled-back table's log still points a time at
- The Iceberg metadata a Paimon table writes beside its own under `metadata.iceberg.storage` — whether the export is current, and which of the table's live files an Iceberg reader sees, with the rule that explains each one it does not

## Deletes, rows and their fate

- Delete files paired with the data files they reach, dangling deletes named, deletion vectors decoded to the rows they mark, and the live row count behind a click
- **Row lookup** on either format: the rows a filter matches, read from the files it leaves, each with its fate — live, or deleted by which vector, positional delete or equality delete on Iceberg; live, vector-marked, a retraction, or superseded by which later write on Paimon — and, one click further, the same rows traced through the retained snapshots on `main`, with the commit that put them in, changed them or removed them
- **A sampled row's own fate**, on its panel: on Iceberg the delete files paired with its file asked for it; on Paimon the merge engine over its key's records — `deduplicate`, `first-row`, `partial-update` with sequence groups, `aggregation` — or, under data evolution, the row as a read stitches it from the file and its patches
- What `SELECT count(*)` returns as of a snapshot, on either format — on Iceberg the delete files applied per data file, on Paimon the merge over each bucket's files under the table's merge engine, less retractions and vector-marked keys, and the level-0 files a batch read never opens — beside the row total the snapshot records

## Checks against the bytes

- One click checks every figure the metadata records against the same figure counted — the metadata file's own ids and lengths, manifest counts and lengths, commit summaries, snapshot totals, Paimon record counts, each file's partition against its own bounds — over the whole table
- Per-snapshot partition breakdown, largest first; per-partition and table statistics files opened and shown against their records
- A data file's recorded bounds, counts, size and split offsets checked against the file itself — its rows, its size on disk and its Parquet row groups — behind a click on the file, and over every live file under the integrity check
- Why a column has the statistics it has, on either format: each column's `write.metadata.metrics.*` or `metadata.stats-mode` mode with the rule that set it — the default, a per-column override, the sort-column promotion, a column limit, a per-level mode — and whether the file records that shape

## Maintenance, planned the way the engine plans it

- What `expire_snapshots` would remove and what keeps the rest — a ref on Iceberg, a consumer or the retention bounds on Paimon — and which files that frees: by which cleanup on Iceberg, and past which tag on Paimon; rollbacks read off the snapshot log
- A Paimon bucket as its LSM tree — sorted runs against the compaction trigger — and what the next flush would compact, the way `UniversalCompaction` picks it
- What `rewrite_data_files` would rewrite at a snapshot, and what the next commit would do to its manifest list — both planned the way Iceberg's own planners decide it, and checked against rewrites and merges the fixtures ran
- A maintenance summary on the table panel: what each procedure would do if run now, one line each, with the panel that explains it

## The tool itself

- Find on the graph (`Ctrl/Cmd + F`) by path, partition, operation, branch or error text; arrow-key navigation over the drawing
- Export the graph as SVG, PNG or JSON, and the file inventory as CSV
- Four layouts (layered left-to-right, top-to-bottom, tree, force-directed); snapshot filtering to isolate a subgraph
- Workspace tree for multiple warehouses and tables, local or in object storage, with a format badge per table; auto-reload on change
- Movable, dockable tool window panels; dark mode; keyboard shortcuts; an in-app cheat sheet (About > Cheat Sheet)
- A crash leaves a report with the deepest cause first, in a dialog that can be copied from
