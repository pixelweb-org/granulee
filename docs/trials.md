# Trials

A trial answers "what would this change do on my data" without touching the production table.

## What a trial does

1. It creates two tables in the scratch database: a **baseline** with today's schema and a **candidate**
   with your changes (keys, column types, codecs, skip indexes added or dropped).
2. It copies the same sample of production rows into both.
3. It merges both fully and reads sizes per column.
4. It measures: a full scan of every column, insert throughput, and the most frequent logged SELECTs
   replayed against both tables.
5. It works out the statements that would bring the production table to the candidate.

Options on the setup page add to that:

- **Compare codecs for the largest columns** builds one more table that holds those columns once per
  codec worth trying and weighs every variant. See [Codecs](features.md#codecs).
- **Also measure plain defaults** builds a third table with no skip indexes, no projections and the
  server's default codec on every column, with today's keys and types. That shows how much the tuning the
  table already has buys.

A trial may also change nothing: with one of these two options ticked it measures today's table as it is.

Trial tables keep the production table's engine without replication (a `ReplicatedReplacingMergeTree`
becomes a `ReplacingMergeTree` with the same arguments), have wide parts and carry no projections. So
logged queries with `FINAL` replay, and a merge drops the rows it would drop in production. Where the
candidate changes the sorting key of such a table, the two trial tables can end up with different row
counts, and the result says so.

## The sample

You choose how many rows and how they are picked: evenly across the table, the most recent partitions, or
by a `WHERE` condition of your own. The setup page proposes 1% of the table, at least 10 million rows and
at most 100 million, and shows the scratch space that needs. Too small a sample compresses differently
from the whole table; the page warns when it is.

## How queries are compared

A replayed query runs several times on each table (five by default), the tables taking turns, and the
first run of each only warms up. Granulee keeps every run, the median, and the CPU time from
`system.query_log`.

Short queries vary from run to run by more than many a schema change moves them. So:

- A query counts as **clearly faster** or **clearly slower** only when no run on one table took as long as
  a run on the other. Where the runs overlap, the result says "no clear difference" instead of naming a
  factor.
- Timings measured once (inserts, merges) count as better or worse only outside the server's run-to-run
  noise, which is measured from the replayed queries and is never taken to be less than ±10%.

Each replayed query is also planned with `EXPLAIN` on both tables, so the result shows which indexes
narrowed it, the granules read and the read streams. On servers that have `EXPLAIN ANALYZE` it is run once
more with it: planning and execution time, peak memory, and for every step the rows in and out, its share
of the time and the threads at work.

## Reading the result

A finished trial is one verdict that stays in view, and six steps under it.

| Step | What it shows |
| --- | --- |
| Overview | Every measure sorted into better, worse and no clear difference. Each row leads to the step with the detail. |
| What changed | One card per change with the old and the new definition, its effect on size and on a full scan. |
| Size | Every column on all tables, with the change against the reference. |
| Codecs | The columns a smaller codec was measured for, every variant, and picks for a follow-up trial. |
| Speed | Every replayed query on one scale; the picked one with its runs, what it read, and both plans. |
| Apply | The statements for the production table and what the change is projected to save. |

Where the trial built the plain-defaults table, the verdict has a switch: compare the candidate with
today's table or with plain defaults. It changes the reference on every step.

"Export report" saves the whole result as a Markdown file.

## Applying a candidate

The last step shows the statements that apply the candidate to the production table, computed against the
table as it is at that moment:

- `ALTER TABLE` for column, codec and index changes. After you tick the confirmation, Granulee runs them
  one after the other, if the source's user has `ALTER` on the table. A statement that has run is not
  undone if a later one fails.
- A **rebuild script** when a key changes: a new table, the copy, and the swap. Granulee only shows it.
  Copying every row needs writes paused and enough free disk, and that is yours to time.

Once applied, the page offers `OPTIMIZE … FINAL` for the table, so existing parts are rewritten with the
new codecs right away instead of when they next merge, and shows whether it is still running.

## Cleaning up

A trial keeps its tables and its result until you remove it. Removing a trial drops its tables from the
scratch database. The trials list shows how much scratch space each one holds.
