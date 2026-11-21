# Running Granulee

Granulee is one process: a Spring Boot application that serves the API and the web page on port 8080 and
keeps its own state as files in one directory. It needs no database of its own.

## With Docker

Every release is published as an image for amd64 and arm64:

```bash
docker run -d --name granulee -p 127.0.0.1:8080:8080 -v granulee-data:/data ghcr.io/pixelweb-org/granulee:latest
```

Open http://localhost:8080 and add a server under **Data sources**. The port is bound to this machine
only; see [Security](#security) before you change that.

| Tag | What |
| --- | --- |
| `latest` | The newest release. |
| `0.19.4` | That release. Pin one if you want to decide when to update. |
| a short commit hash | The commit a release was built from. |

To update, pull the new image and recreate the container. The volume keeps your sources, scans and
trials.

```bash
docker pull ghcr.io/pixelweb-org/granulee:latest
docker rm -f granulee
docker run -d --name granulee -p 127.0.0.1:8080:8080 -v granulee-data:/data ghcr.io/pixelweb-org/granulee:latest
```

## With Docker Compose

[`deploy/compose.yaml`](../deploy/compose.yaml) is a starting point: the published image, the data in a
named volume, a health check, the port bound to this machine.

```bash
docker compose -f deploy/compose.yaml up -d
```

`VERSION=0.19.4 docker compose …` pins a release.

## From a checkout

The repository has a `Dockerfile` that builds everything from source with nothing but Docker:

```bash
git clone https://github.com/pixelweb-org/granulee.git
cd granulee
docker build -t granulee .
docker run -d --name granulee -p 127.0.0.1:8080:8080 -v granulee-data:/data granulee
```

## As a jar

Needs a JDK (17 or newer to run Gradle, which fetches JDK 21 itself if it is missing) and Node 22 or
newer to build, and Java 21 to run.

```bash
make build
java -jar backend/build/libs/granulee-*.jar
```

`make build` puts the web page into the jar. State goes to `./data` unless `GRANULEE_DATA_DIR` says
otherwise.

## Configuration

Everything is set through environment variables.

| Variable | Default | What |
| --- | --- | --- |
| `GRANULEE_DATA_DIR` | `./data` (`/data` in the image) | Where Granulee keeps its state. |
| `GRANULEE_SECRET_KEY` | empty | Base64 of 32 random bytes, the key that encrypts stored ClickHouse passwords. Empty: Granulee creates `secret.key` in the data directory on first start. |
| `SERVER_PORT` | `8080` | The port to listen on. |
| `TZ` | the container's | Time zone for scheduled scans and log lines. |

A key for `GRANULEE_SECRET_KEY`:

```bash
openssl rand -base64 32
```

## The data directory

| File | What |
| --- | --- |
| `sources.json` | The registered servers. Passwords are stored AES-GCM encrypted. |
| `secret.key` | The encryption key, when `GRANULEE_SECRET_KEY` is not set. |
| `snapshots/` | The last scan of each source. |
| `history.json` | Size over time, one point per scan. |
| `dismissed.json` | Recommendations you dismissed. |
| `trials.json` | Trials and their results. |

Back up the whole directory. Without the key (the file or the variable) the stored passwords cannot be
read any more and have to be entered again. The trial tables themselves live in the scratch database of
the ClickHouse server, not here.

## Security

Granulee has **no login and no user management**. Whoever can open the page can

- read table schemas and the texts of logged queries of every registered server,
- start trials, which copy sampled rows into the scratch database,
- run the `ALTER TABLE` statements of a trial on the production table, if the ClickHouse user of that
  source is allowed to.

So:

- Do not expose it to the internet. Bind it to localhost, keep it inside a private network or VPN, or
  put a reverse proxy with authentication in front of it.
- Give the ClickHouse user only what you want Granulee to be able to do. With a user that can only read
  the system tables, Granulee can scan and recommend and nothing else. See
  [ClickHouse access](clickhouse-access.md).
- Set `GRANULEE_SECRET_KEY` from a secret store if the data directory is on shared storage, so the key
  does not lie next to the passwords it encrypts.

## Adding a server

Under **Data sources**:

| Field | What |
| --- | --- |
| Protocol, host, port | The HTTP or HTTPS endpoint of ClickHouse (8123 and 8443 by default). The native protocol is not supported. |
| User, password | See [ClickHouse access](clickhouse-access.md). |
| Include / exclude databases | Comma-separated. `system` and `information_schema` are excluded to start with. |
| Schedule | How often it is scanned: every 6 hours, daily, weekly or only by hand. |
| Query log window | How many days of `system.query_log` a scan looks at. |
| Allow sample trials, scratch database | Switches trials on for this server and names the database their tables are built in. |

"Test connection" checks the endpoint and the credentials before anything is saved.

## Health

`GET /api/health` answers `{"status":"UP","version":"…"}` with the running version.
