# Development

## Layout

| Path | What |
| --- | --- |
| `backend/` | Kotlin, Spring Boot 4, ClickHouse Java client v2 (HTTP). Serves `/api/**` and, in a release build, the web page. |
| `frontend/` | React 19, Vite, TypeScript. |
| `frontend/src/api/types.ts` | The API contract. The backend's data classes mirror it field for field, each in the package of its feature. |
| `dev/` | Scripts behind the `make` targets; `dev/seed.sh` fills a local ClickHouse with sample data that has findings. |
| `deploy/compose.yaml` | A Compose file to run it. |
| `Dockerfile` | Builds the image from source with nothing but Docker. |

Granulee keeps its own state as JSON files in `backend/data/` (override with `GRANULEE_DATA_DIR`):
sources, the last scan per source, size history, dismissed recommendations and trials. ClickHouse
passwords are stored AES-GCM encrypted; the key is `GRANULEE_SECRET_KEY` (base64, 32 bytes) or, if unset,
a key file created in the data directory.

## Run it locally

Needs a JDK (17 or newer runs Gradle; it fetches the JDK 21 toolchain itself if missing) and Node 22 or
newer. Docker only for the ClickHouse targets.

```bash
make dev
```

That starts the backend on http://localhost:8080 and the frontend dev server on http://localhost:5173,
which proxies `/api` to the backend. Ctrl+C stops both.

`make` lists the other targets:

| Target | What |
| --- | --- |
| `make dev` | Backend and frontend together. |
| `make backend`, `make frontend` | One of the two. |
| `make clickhouse` | A ClickHouse 25.8 container on port 18123 to try Granulee on. |
| `make seed` | Fills it with a sample schema and a query-log workload (drops and recreates the sample tables). |
| `make source` | Registers that ClickHouse in the running Granulee and scans it. |
| `make test` | Backend tests, then the frontend type check and build. |
| `make build` | One jar with the frontend inside, in `backend/build/libs`. |
| `make image` | The container image as `granulee:latest` in the local Docker daemon, built with Jib. |
| `make stop`, `make clean` | Stop the ClickHouse container; remove it together with the local state in `backend/data`. |

The sample ClickHouse has user and password `granulee_test`. To add it by hand instead of `make source`:
HTTP, `localhost`, port `18123`, trials allowed, scratch database `scratch`.

## Backend packages

The backend is one package per feature under `backend/src/main/kotlin/io/granulee/`. A package only uses
the ones above it in this table, so there are no cycles; `PackageDependenciesTest` enforces that.

| Package | What |
| --- | --- |
| `sql` | ClickHouse SQL as text, without a server: quoting, column types, key expressions, `engine_full`, rewriting logged queries, skip index definitions. |
| `clickhouse` | The connection: `ChConnection` (query, exec) and its HTTP implementation, one client per endpoint. |
| `state` | `JsonStore`, the JSON files in the data directory. |
| `facts` | What a scan knows about a table (`TableFacts` and its parts). These classes are the format of `snapshots/*.json`. |
| `explain` | `EXPLAIN indexes = 1`, `EXPLAIN ANALYZE` and `EXPLAIN QUERY TREE`: asking the server and reading what it prints. |
| `recommendation` | The rules (`Analyzer`) that turn table facts into recommendations, and the ones a person dismissed. |
| `scan` | Reads a server's system tables and query log into a snapshot (`Scanner`); keeps the snapshots and the size history. |
| `source` | The registered servers: credentials, health, when they are scanned, `/api/sources`. |
| `trial` | Trials from plan to result: the run (`TrialRun`, `QueryReplay`, `CodecBakeoff`), the DDL and migration SQL, the report, applying a candidate, `/api/trials`. |
| `usage` | How a table was queried, from `system.query_log`. |
| `index` | What skip indexes, projections and keys do for the logged queries, and `EXPLAIN WHATIF` estimates. |
| `explorer` | Overview, databases, the table page and its recommendations. |
| `web` | Error answers, `/api/health`, serving the frontend. |

`io.granulee` itself only holds the application class, the two errors a request can end in and the number
formatting of the texts.

Within a package the controller only maps HTTP to a service. What decides something (the rules, the
migration and DDL text, a trial's result and verdict, the index and usage summaries, the parsers) is in
plain functions and classes that need no Spring, no server and no files, and is tested that way.
Everything that talks to ClickHouse takes a `ChConnection`; the tests hand it a stand-in
(`FakeClickHouse`) and check the statements it is sent.

## Two contracts

- **The API.** `frontend/src/api/types.ts` describes every response. Fields are added, not renamed.
- **The data files.** `StoredFilesTest` loads files as a release wrote them and checks they are written
  back byte for byte. Where a release adds fields, the test names them. An installation must be able to
  upgrade without touching its data directory.

## Frontend

`npm run build` in `frontend/` type-checks and builds. The page design uses a small set of tokens in
`frontend/src/styles/tokens.css` and classes in `app.css`; charts follow one palette (today's table,
candidate, plain defaults) across all pages.

## Releases

Commits follow [Conventional Commits](https://www.conventionalcommits.org). On every push to `main`,
[release-please](https://github.com/googleapis/release-please) keeps a release PR open that collects them
and bumps `version.txt` and `CHANGELOG.md`. Merging that PR tags `vX.Y.Z` and creates the GitHub release.

The `Build image` workflow tests every push to `main`. For a release tag it also builds the image with
Jib, with the frontend inside, and pushes it to the registry named in the repository variable
`IMAGE_REPOSITORY`, tagged `latest`, the version and the short commit. It reads the registry login from
`NEXUS_USERNAME` / `NEXUS_PASSWORD` in the runner's environment. Both workflows only run in this
repository, not in forks.

### Branch protection

`main` only takes pull requests that

- the maintainer has approved (`.github/CODEOWNERS` names who that is),
- pass the `test` check from the `Test` workflow: the backend tests and the frontend type check and build,
  on a GitHub-hosted runner,
- are up to date with `main` and have no open review conversations.

Force pushes and deleting the branch are off. Repository admins can bypass the rules, which the release
flow of a single maintainer needs: nobody can approve their own pull request.

The rules are kept in `.github/branch-protection.json` and applied with

```bash
gh api -X PUT repos/pixelweb-org/granulee/branches/main/protection --input .github/branch-protection.json
```

A pull request from release-please starts no workflow by itself, so the release workflow starts `Test`
for the release branch.

To build the same image yourself:

```bash
cd backend
./gradlew jibDockerBuild -PwithFrontend   # granulee:latest in the local Docker daemon
./gradlew jib -PwithFrontend -Djib.to.image=registry.example.com/granulee   # or push it
```

`REGISTRY_USERNAME` / `REGISTRY_PASSWORD` are used for the push when set.
