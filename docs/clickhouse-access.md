# ClickHouse access

Granulee talks to ClickHouse over HTTP or HTTPS as one user per registered server. What that user may do
decides what Granulee can do. Start with the first block and add the others for the features you want.

## Grants per feature

### Scans and recommendations

```sql
CREATE USER granulee IDENTIFIED WITH sha256_password BY '…';
GRANT SELECT ON system.* TO granulee;
```

A scan reads `system.tables`, `system.parts`, `system.columns`, `system.parts_columns`,
`system.data_skipping_indices`, `system.projections`, `system.projection_parts`, `system.replicas`,
`system.disks` and `system.query_log`. Table data is not read.

Sizes, codecs, types, partitioning and file counts need nothing more. This user cannot change anything.

### What queries filter on, index and projection checks

```sql
GRANT SELECT ON analytics.* TO granulee;   -- once per database Granulee should look at
```

With `SELECT` on a table Granulee can ask ClickHouse about the logged queries on it:

- `EXPLAIN QUERY TREE` during a scan, to learn which columns a query filters on. The query is analysed,
  not run; a read limit of one row keeps a scalar subquery inside it from reading data.
- `EXPLAIN indexes = 1` on the table's "Indexes & projections" tab, to see what each index, projection
  and key drops. This plans the query and reads index marks, not rows.
- `EXPLAIN WHATIF` when you ask for an estimate of candidate skip indexes (servers that have it). This one
  **does read table data**: ClickHouse builds the hypothetical index in memory. It only runs on request.

Without this grant Granulee falls back to reading the columns off the query text after `WHERE`.

### Trials

```sql
CREATE DATABASE IF NOT EXISTS scratch;
GRANT CREATE TABLE, DROP TABLE, INSERT, SELECT, OPTIMIZE ON scratch.* TO granulee;
GRANT ALTER SETTINGS ON scratch.* TO granulee;   -- only for tables with JSON, Dynamic or Variant columns
```

together with `SELECT` on the table a trial is run for. A trial

- creates its tables in the scratch database (a baseline and a candidate, plus a few more for the optional
  measures),
- copies a sample of rows from the production table into them with `INSERT … SELECT`,
- runs `OPTIMIZE … FINAL` on the trial tables,
- replays logged SELECTs against the trial tables, each several times,
- reads `system.query_log` for the CPU time of those runs (it tries `SYSTEM FLUSH LOGS` first and simply
  waits a few seconds where it may not).

The production table is only read. The scratch database is named per source and trials must be switched
on for a source before any of this happens. Removing a trial drops its tables.

### Applying a candidate

```sql
GRANT ALTER ON analytics.events TO granulee;      -- per table you want to apply to
GRANT OPTIMIZE ON analytics.events TO granulee;   -- for "Run OPTIMIZE FINAL" after applying
```

Only needed if you want Granulee to run the statements. Without it, the last step of a trial still shows
them and you copy them. A change that needs a rebuild (a new sorting or partition key) is never run by
Granulee, whatever the grants.

## The query log

Most of what Granulee says about how a table is used comes from `system.query_log`. A scan looks at a
window of it (30 days by default, set per source). If the log is switched off, empty, or the user may not
read it, the scan still covers everything it can see from the system tables, and the source's page says
which of the three it is and how to change it.

Query texts from the log end up in Granulee's data directory: for every query shape one example as it
ran, with its literal values, because that is what gets planned and replayed. The pages show the
normalized form, with literals replaced by `?`. Treat the data directory as you treat the query log.

## Load on the server

- **Scans** are a handful of queries on system tables, plus one `EXPLAIN QUERY TREE` per frequent query
  shape, within a time budget.
- **The index tab** plans up to a few dozen logged queries with `EXPLAIN` when you open it.
- **Trials** are real work: an `INSERT … SELECT` of the sample, full merges of the trial tables and the
  replay. The sample size is yours to choose; the setup page proposes 1% of the table, at least 10 million
  rows, and shows the scratch space it will need.

Replayed queries, index checks and estimates each run with a time limit.

## Replicated and cloud tables

Trial tables are created with the production table's engine but without replication: a
`ReplicatedReplacingMergeTree` becomes a `ReplacingMergeTree` with the same arguments. They exist on the
one server Granulee talks to.
