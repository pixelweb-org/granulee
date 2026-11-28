# Security

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through
[GitHub's security advisories](https://github.com/pixelweb-org/granulee/security/advisories/new) for this
repository. Say what you found, how to reproduce it and which version it is in (`GET /api/health` tells).

## What to know before you run it

Granulee has its own users. Nothing but the health check and the sign-in answers without a session, and a
signed-in user reaches only their own data sources: the schemas and logged query texts of those ClickHouse
servers, trials on them, and a trial's `ALTER TABLE` statements if the source's ClickHouse user is allowed
to run them. It speaks plain HTTP, so put a reverse proxy with TLS in front of it before it is reachable
over a network you do not trust, and give its ClickHouse users only the grants you want it to use.

Worth reporting in particular: any way to read or use another user's data source, scan or trial, to reach
`/api/**` without a session, or to learn a password. [Running Granulee](docs/running.md#security) and
[ClickHouse access](docs/clickhouse-access.md) have the details.
