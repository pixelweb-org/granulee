# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through
[GitHub's security advisories](https://github.com/pixelweb-org/granulee/security/advisories/new) for this
repository. Say what you found, how to reproduce it and which version it is in (`GET /api/health` tells).

## What to know before you run it

Granulee has no login. Anyone who can reach its page can read the schemas and logged query texts of the
registered ClickHouse servers, start trials, and run a trial's `ALTER TABLE` statements if the source's
ClickHouse user is allowed to. Keep it off the public internet and give its ClickHouse user only the
grants you want it to use. [Running Granulee](docs/running.md#security) and
[ClickHouse access](docs/clickhouse-access.md) have the details.
