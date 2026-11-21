# Contributing

Issues and pull requests are welcome.

## Before you start

For anything larger than a fix, open an issue first and say what you want to change. That saves both of
us work if it does not fit.

## Working on it

[Development](docs/development.md) has the layout, how to run it locally with sample data, and the tests.
In short:

```bash
make clickhouse seed   # a local ClickHouse with sample data
make dev               # backend and frontend
make test              # backend tests, frontend type check and build
```

## What a change needs

`main` is protected: a change gets there through a pull request that the maintainer has approved and whose
`test` check is green. The check runs the same as `make test`.

- `make test` passes.
- Behaviour that decides something (a rule, generated SQL, a parser, a verdict) has a unit test. Most of
  that code is plain functions that need no server.
- The REST API and the files in the data directory stay compatible: `frontend/src/api/types.ts` is the
  API contract, and `StoredFilesTest` pins the file format. If you extend either, say so in the pull
  request.
- Commit messages follow [Conventional Commits](https://www.conventionalcommits.org) (`fix: …`,
  `feat: …`, `docs: …`, `refactor: …`). The changelog and the version are derived from them.

## Reporting a bug

Say which Granulee version (`GET /api/health`) and which ClickHouse version, what you did, what you
expected and what happened. For a wrong recommendation or trial result, the table's engine and keys and
the query involved help most. Leave out anything from your data you would not publish.
