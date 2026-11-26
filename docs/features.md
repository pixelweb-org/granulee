# What Granulee does, in detail

## Scans

A scan reads system tables only (`system.tables`, `system.parts`, `system.columns`,
`system.parts_columns`, `system.data_skipping_indices`, `system.projections`, `system.projection_parts`,
`system.query_log`). Table data is not read. Every scan is kept as a snapshot, and the size of each
source is recorded over time.

**Without a query log** the scan still covers sizes, codecs, types, partitioning and file counts.
Granulee says per source why the log is unavailable (switched off, no read access, empty) and shows the
steps to switch it on.

## Recommendations

Recommendations cover partitioning, sorting keys, codecs, data types and skip indexes. Each one names
what it found, what it proposes and, where it can, an estimate of the saving. The estimates are just
that; a [trial](trials.md) measures them. A recommendation you do not want can be dismissed.

**What a query filters on** comes from ClickHouse itself: the scan runs `EXPLAIN QUERY TREE` for the most
frequent logged SELECTs, which resolves aliases, subqueries, CTEs and joins to the table's own columns.
The query is analysed, not run, and a read limit keeps a scalar subquery from reading rows. Where the
server cannot analyse a query (no `SELECT` on the table, a query on a view), Granulee reads the columns
off the query text after `WHERE` instead. Sorting-key recommendations rest on this.

## Usage

The Usage tab of a table shows how it was queried, from `system.query_log`: SELECT and INSERT volume,
which columns are read and which are filtered on, the most frequent query shapes and who runs them.

Every query shape has an **Analyse with EXPLAIN** button. It takes the latest run of that shape from the
log, with its real values, and asks ClickHouse how it would run it now: how many granules and parts it
reads, which index steps narrow it and by how much, whether a projection answers it, what moved to
`PREWHERE`, and the pipeline. The query is planned, not executed.

## Indexes and projections

The "Indexes & projections" tab of a table measures what is there instead of guessing.

- **Skip indexes**: the logged queries are planned with `EXPLAIN indexes = 1`, and the tab shows what each
  existing index drops. Columns that many queries filter on outside the sorting key become proposals for
  a new index, which a trial then tests.
- **Projections** are listed with their query, rows, size on disk, the parts they are built in and how
  many logged SELECTs ClickHouse answered from them.
- **Keys**: how many logged SELECTs the primary key and the partition key narrow. For the primary key it
  tells binary search from generic exclusion search: the second means a filter that does not start at the
  first sorting-key column.

Opening an index or a projection lists the query shapes that use it, each with its plan: the index steps
in order and the parts and granules every step left, how the primary index was searched, whether the read
runs in sorting-key order and what ClickHouse moved to `PREWHERE`.

### Candidate skip indexes

On servers that have `EXPLAIN WHATIF`, a candidate index is estimated before anything is built: each
candidate becomes a hypothetical index in a session, and ClickHouse says how many granules of every
logged query it would skip. The scan's own proposals are listed to start with, and you can add your own.

This reads table data (ClickHouse builds the candidate in memory) and only runs when you ask for it.

## Codecs

Codecs are chosen by measuring, not by rule. A codec is only as good as its fit to the values and to the
order they are stored in, so a trial can build one more scratch table that holds the largest columns once
per codec worth trying, in the candidate's row order, and weigh each variant.

The result names the codec to use per column: the cheapest to read back within 3% of the smallest, and
the one in place stays when it is among them. Every variant is shown with its size, ratio and, for the
ones in the running, the time of a full read. Tick the ones you want and "Set up a trial with these
picks" opens a follow-up trial: that trial's candidate with the picked codecs on top, so you see what
they do to inserts and queries together.

## Trials

See [Trials](trials.md).
