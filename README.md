# Granulee

Finds storage and schema improvements on ClickHouse servers and measures them before you apply them.

- **Scans** read system tables only (`system.tables`, `system.parts`, `system.columns`,
  `system.parts_columns`, `system.data_skipping_indices`, `system.query_log`). Table data is not read.
- **Recommendations** cover partitioning, sorting keys (from what `query_log` shows queries filter on),
  codecs, data types and skip indexes. Savings are estimates.
- **Trials** build a baseline and a candidate table in a scratch database, copy the same sample of
  rows into both, merge them, and compare sizes, column scans, insert throughput and replayed queries.
  The result also shows the statements that would apply the candidate to the production table:
  `ALTER TABLE` for column, codec and index changes, a rebuild script when a key changes.
  "Apply to production" on a finished trial shows those statements again, computed against the table as
  it is now, and runs the `ALTER`s after you confirm (the source's user needs `ALTER` on the table).
  A rebuild is only shown, never run.

## Layout

| Path | What |
| --- | --- |
| `backend/` | Kotlin, Spring Boot 4, ClickHouse Java client v2 (HTTP). Serves `/api/**`. |
| `frontend/` | React 19, Vite, TypeScript. ClickHouse Neon Volt look, dark only. |
| `frontend/src/api/types.ts` | The API contract. `backend/.../Api.kt` mirrors it. |
| `dev/seed.sh` | Fills a local ClickHouse with sample data that has findings. |

Granulee keeps its own state as JSON files in `backend/data/` (override with `GRANULEE_DATA_DIR`):
sources, the last scan per source, size history, dismissed recommendations and trials. ClickHouse
passwords are stored AES-GCM encrypted; the key is `GRANULEE_SECRET_KEY` (base64, 32 bytes) or, if
unset, a key file created in the data directory.

## Run

Needs JDK 21 and Node 22 or newer.

```bash
cd backend && ./gradlew bootRun
```

```bash
cd frontend && npm install && npm run dev
```

Open http://localhost:5173 and add a server under **Data sources**. The dev server proxies `/api`
to `http://localhost:8080`.

One jar with the frontend inside:

```bash
cd backend && ./gradlew bootJar -PwithFrontend
```

## A local ClickHouse to try it on

```bash
docker run -d --name granulee-test-ch -p 127.0.0.1:18123:8123 -e CLICKHOUSE_USER=granulee_test -e CLICKHOUSE_PASSWORD=granulee_test -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 clickhouse/clickhouse-server:25.8
```

```bash
dev/seed.sh
```

Then add a source: HTTP, `localhost`, port `18123`, user and password `granulee_test`, trials allowed.

## ClickHouse user

```sql
CREATE USER granulee_ro IDENTIFIED WITH sha256_password BY '…';
GRANT SELECT ON system.* TO granulee_ro;

-- Only for trials (they copy a sample of the table into the scratch database):
GRANT SELECT ON analytics.* TO granulee_ro;
GRANT CREATE TABLE, DROP TABLE, INSERT, SELECT, OPTIMIZE ON scratch.* TO granulee_ro;
```

## Limits

- Only MergeTree-family tables are scanned. Only HTTP and HTTPS endpoints are supported.
- Distinct-value estimates (used for the LowCardinality rule) exist only on servers that report
  `estimates.cardinality` in `system.parts_columns`.
- Skip-index usage is not in system tables, so index findings are "review" items by size.
- Trial tables are plain `MergeTree` with wide parts, whatever the production engine is.

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
