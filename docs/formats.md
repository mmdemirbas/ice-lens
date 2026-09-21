---
title: Formats and limitations
order: 30
summary: What is read of Iceberg and of Paimon, level by level, and what the tool does not do.
---

> [!TLDR]
> Iceberg v1–v3 and Paimon are detected per directory and read down to sample rows via DuckDB; no catalog is spoken to, and the limits are stated plainly because a tool you inspect internals with has to be honest about its own.

## Format coverage

| | Iceberg | Paimon |
|---|---|---|
| Detection | `metadata/` with `*.metadata.json` | `snapshot/` + `schema/` |
| Metadata | metadata.json (v1–v3), snapshot log with rollbacks marked, metadata log, refs with retention, partition specs, sort orders, table and partition statistics files, row lineage (`next-row-id`, `first-row-id`, `added-rows`) | `snapshot/snapshot-N`, `schema/schema-N`, tags, branches, consumers, the index manifest (hash indexes and deletion vectors), `ANALYZE` statistics |
| Manifests | manifest list (Avro) → manifest (Avro), data / delete split, partition summaries decoded against each manifest's own spec | base / delta / changelog manifest lists → manifests, replayed delta-over-base; the changelog read per key as the stream a consumer received |
| Files | data, positional-delete, equality-delete, v3 deletion vectors (Puffin, decoded), partition tuples and column bounds decoded against the manifest's own schema, inherited sequence numbers and row ids | data files with LSM level and bucket, key and value bounds, file indexes (bloom filter, bitmap and bit-sliced, decoded), external paths, row tracking, data-evolution patch files paired with the file they patch, deletion vectors decoded from the index file |
| Rows | Parquet and Avro via DuckDB (ORC has no DuckDB reader, and the card says so), capped at 50 per file, with `_row_id` and deleted rows marked, and what a read returns when the schema has moved on since the file — by field id, with v3 initial defaults and the name mapping, nested fields included | same, with `_ROW_ID`, the `+I` / `-U` / `+U` / `-D` kind of each key-value row, rows a deletion vector marks struck, and the read projection by the schema the file's own `_SCHEMA_ID` names |

Paimon has no Iceberg-style positional or equality delete files; removals are `_KIND=1`
manifest entries, reported as *entries recording a removal* rather than as delete files, and
row-level deletes on a table with `deletion-vectors.enabled` are vectors in the index file.

## Limitations

Stated plainly, because a tool you inspect internals with has to be honest about its own:

- **No catalog integration.** Hive, Glue, REST, Nessie and Polaris are not spoken to; a table
  is opened by its location. S3, GCS and R2 are read through DuckDB with a key that is never
  persisted; HDFS and ADLS are not.
- **Iceberg v3 is modelled up to what Spark 3.5 can write.** Deletion vectors, row lineage and
  the `added-rows` allocation are read from real tables; the variant / geometry / geography /
  `timestamp_ns` types and column defaults are parsed without error but have no fixture, because
  no engine in the fixture toolchain writes them yet.
- **A v2 positional delete file is not mapped to row cards.** Its targets are one per row and
  known only after reading the file, so the rows it removes are counted behind a click and a
  sampled row's panel asks the file for its own position behind another; a v3 deletion vector
  marks the cards.
- **Equality deletes are evaluated only for a row in hand.** They match by value, so nothing in
  the metadata links one to a data file; the delete panel says which data files one *may* reach,
  and only the row lookup or a sampled row's panel, which have the row, can say whether one
  removes it.
- Sample rows are best-effort: they depend on the file being present and readable by DuckDB,
  and are capped at 50 rows per file, five drawn per data file.
- Row loading may be slow for tables with many data files when "Show Rows" is enabled.
