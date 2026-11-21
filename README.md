# Granulee

Finds storage and schema improvements on ClickHouse servers and measures them before you apply them.

- **Scans** read system tables only (`system.tables`, `system.parts`, `system.columns`,
  `system.parts_columns`, `system.data_skipping_indices`, `system.projections`, `system.projection_parts`,
  `system.query_log`). Table data is not read.
- **Recommendations** cover partitioning, sorting keys (from what `query_log` shows queries filter on),
  codecs, data types and skip indexes. Savings are estimates.
- **What a query filters on** comes from ClickHouse itself: the scan runs `EXPLAIN QUERY TREE` for the most
  frequent logged SELECTs, which resolves aliases, subqueries, CTEs and joins to the table's own columns.
  The query is analysed, not run, and a read limit keeps a scalar subquery from reading rows. Where the
  server cannot analyse a query (no `SELECT` on the table, a query on a view), Granulee reads the columns
  off the query text after `WHERE` instead.
- **Without a query log** the scan still covers sizes, codecs, types, partitioning and file counts.
  Granulee says per source why the log is unavailable (switched off, no read access, empty) and shows
  the steps to switch it on.
- **Usage** shows how a table was queried, from `system.query_log`: SELECT and INSERT volume, which
  columns are read and filtered on, the most frequent query shapes and who runs them.
- **Skip indexes** are measured, not guessed: the table's "Indexes & projections" tab plans the logged
  queries with `EXPLAIN indexes = 1` and shows what each existing index drops. Columns that many queries
  filter on outside the sorting key become proposals for a new index, which a trial then tests.
- **Projections** are listed on the same tab with their query, rows, size on disk, the parts they are
  built in and how many logged SELECTs ClickHouse answered from them.
- Opening an index or a projection there lists the query shapes that use it, each with its plan: the
  index steps in order and the parts and granules every step left, how the primary index was searched,
  whether the read runs in sorting-key order and what ClickHouse moved to `PREWHERE`.
- **Keys** on the same tab show how many logged SELECTs the primary key and the partition key narrow.
  For the primary key it tells binary search from generic exclusion search: the second means a filter
  that does not start at the first sorting-key column.
- **Candidate skip indexes** are estimated before anything is built, on servers that have
  `EXPLAIN WHATIF`: each candidate becomes a hypothetical index in a session, and ClickHouse says how many
  granules of every logged query it would skip. The scan's own proposals are listed to start with. This
  reads table data (ClickHouse builds the candidate in memory) and only runs when you ask for it.
- **Codecs** are chosen by measuring, not by rule: a trial can build one more scratch table that holds the
  largest columns once per codec worth trying, in the candidate's row order, and weigh each variant. The
  report names the codec to use per column (the cheapest to read back within 3% of the smallest; the one in
  place stays when it is among them), shows every variant, and gives the `ALTER` statements.
- **Trials** build a baseline and a candidate table in a scratch database, copy the same sample of
  rows into both, merge them, and compare sizes, column scans, insert throughput and replayed queries.
  A replayed query runs several times on each table, the tables taking turns. The result shows the median,
  the fastest and slowest run and the CPU time from `query_log`. Where the runs of the two tables overlap,
  it says "no clear difference" instead of naming a factor.
  Each replayed query is also planned with `EXPLAIN` on both tables, so the result shows which indexes
  narrowed it, the granules read and the read streams. On servers that have `EXPLAIN ANALYZE` it is run
  once more with it: the result then shows planning and execution time, peak memory, and for every step
  the rows in and out, its share of the time and the threads at work.
  The result also shows the statements that would apply the candidate to the production table:
  `ALTER TABLE` for column, codec and index changes, a rebuild script when a key changes.
  "Apply to production" on a finished trial shows those statements again, computed against the table as
  it is now, and runs the `ALTER`s after you confirm (the source's user needs `ALTER` on the table).
  A rebuild is only shown, never run. Once applied, the trial page offers `OPTIMIZE … FINAL` for the table,
  so existing parts are rewritten with the new codecs, and shows whether it is still running.

## Layout

| Path | What |
| --- | --- |
| `backend/` | Kotlin, Spring Boot 4, ClickHouse Java client v2 (HTTP). Serves `/api/**`. |
| `frontend/` | React 19, Vite, TypeScript. Estilo (Compra Contínua) look, light only. |
| `frontend/src/api/types.ts` | The API contract. `backend/.../Api.kt` mirrors it. |
| `dev/seed.sh` | Fills a local ClickHouse with sample data that has findings. |

Granulee keeps its own state as JSON files in `backend/data/` (override with `GRANULEE_DATA_DIR`):
sources, the last scan per source, size history, dismissed recommendations and trials. ClickHouse
passwords are stored AES-GCM encrypted; the key is `GRANULEE_SECRET_KEY` (base64, 32 bytes) or, if
unset, a key file created in the data directory.

## Run

Needs JDK 21 and Node 22 or newer.

```bash
make dev
```

That starts the backend on http://localhost:8080 and the frontend dev server on
http://localhost:5173, which proxies `/api` to the backend. Ctrl+C stops both. Open
http://localhost:5173 and add a server under **Data sources**.

`make` lists the other targets: `make backend` and `make frontend` start one of the two,
`make test` runs the tests, `make build` makes one jar with the frontend inside and `make image`
the container image.

## A local ClickHouse to try it on

```bash
make clickhouse seed
```

This starts a ClickHouse 25.8 container on port 18123 and fills it with a sample schema that has
findings, including a query-log workload. With Granulee running, register it as a source:

```bash
make source
```

Or add it by hand: HTTP, `localhost`, port `18123`, user and password `granulee_test`, trials
allowed. `make stop` stops the container; `make clean` removes it together with Granulee's local
state in `backend/data`.

## ClickHouse user

```sql
CREATE USER granulee_ro IDENTIFIED WITH sha256_password BY '…';
GRANT SELECT ON system.* TO granulee_ro;

-- For trials (they copy a sample of the table into the scratch database), for the index checks and so
-- that the scan can ask ClickHouse what the logged queries filter on:
GRANT SELECT ON analytics.* TO granulee_ro;
GRANT CREATE TABLE, DROP TABLE, INSERT, SELECT, OPTIMIZE ON scratch.* TO granulee_ro;
```

## Limits

- Only MergeTree-family tables are scanned. Only HTTP and HTTPS endpoints are supported.
- Distinct-value estimates (used for the LowCardinality rule) exist only on servers that report
  `estimates.cardinality` in `system.parts_columns`.
- Skip-index usage is not in system tables, so index findings are "review" items by size.
- Trial tables are plain `MergeTree` with wide parts, whatever the production engine is, and carry no
  projections. A rebuild script recreates the table's projections.

## Releases

Commits follow [Conventional Commits](https://www.conventionalcommits.org). On every push to `main`,
[release-please](https://github.com/googleapis/release-please) keeps a release PR open that collects them
and bumps `version.txt` and `CHANGELOG.md`. Merging that PR tags `vX.Y.Z`, creates the GitHub release and
starts the image build: Jib builds the image with the frontend inside and pushes it to the registry in the
repository variable `IMAGE_REPOSITORY`, tagged `latest`, the version and the short commit. Both workflows
run on the self-hosted runner, which has `NEXUS_USERNAME` / `NEXUS_PASSWORD` in its environment.

```sh
cd backend
./gradlew jibDockerBuild -PwithFrontend   # granulee:latest in the local Docker daemon
docker run -p 8080:8080 -v granulee-data:/data granulee:latest
```

`deploy/compose.server.yaml` is the stack that runs it on the server. `GET /api/health` answers with the
running version.
