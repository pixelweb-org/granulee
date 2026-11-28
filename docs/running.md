# Running Granulee

Granulee is one process: a Spring Boot application that serves the API and the web page on port 8080 and
keeps its own state in one directory: a SQLite database file and a key file. It needs no database server.

## With Docker

Every release is published as an image for amd64 and arm64:

```bash
docker run -d --name granulee -p 127.0.0.1:8080:8080 -v granulee-data:/data ghcr.io/pixelweb-org/granulee:latest
```

The first start creates the user `admin` with a random one-time password and prints it once, to standard
output:

```bash
docker logs granulee
```

Open http://localhost:8080, sign in as `admin` with that password and choose your own; nothing else is
reachable before you have. Then add a server under **Data sources**. The port is bound to this machine
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
| `GRANULEE_RESET_ADMIN` | empty | A user name. At start that account gets a new one-time password, printed once like the first one, and becomes an active admin. For a lost admin password; remove the variable again after that start. |
| `SERVER_PORT` | `8080` | The port to listen on. |
| `SERVER_FORWARD_HEADERS_STRATEGY` | unset | Set it to `native` behind a reverse proxy that terminates TLS and sends `X-Forwarded-Proto`, so the session cookie is marked `Secure`. Only then: without a proxy in front, anyone could send those headers. |
| `TZ` | the container's | Time zone for scheduled scans and log lines. |

A key for `GRANULEE_SECRET_KEY`:

```bash
openssl rand -base64 32
```

## The data directory

| File | What |
| --- | --- |
| `granulee.db` | A SQLite database with everything Granulee keeps: the users (passwords as Argon2id hashes) and their sessions, the registered servers (ClickHouse passwords AES-GCM encrypted), the last scan of each source, size over time, dismissed recommendations, and the trials with their results. While Granulee runs, `granulee.db-wal` and `granulee.db-shm` lie next to it. |
| `secret.key` | The key that encrypts the stored ClickHouse passwords, when `GRANULEE_SECRET_KEY` is not set. |

Back up the whole directory, with Granulee stopped or with `sqlite3 granulee.db ".backup copy.db"` while
it runs. Without the key (the file or the variable) the stored ClickHouse passwords cannot be read any
more and have to be entered again. The trial tables themselves live in the scratch database of the
ClickHouse server, not here.

### Coming from 0.20 or earlier

Up to 0.20 Granulee had no users and kept its state as JSON files (`sources.json`, `snapshots/`,
`history.json`, `dismissed.json`, `trials.json`). This release does not read them: it starts empty, with
the `admin` of a first start. The old files are left where they are and can be deleted. Add your servers
again under **Data sources**; `secret.key` is still used. Granulee no longer knows the trials of the old
installation, so their tables stay in the scratch database until you drop them there: they are named
`<table>_trial_<id>_base` and `<table>_trial_<id>_cand`.

## Users

Under **Users** an admin

- adds a user, as `User` or `Admin`, with a one-time password. The admin passes that password on; the user
  has to choose their own at the first sign-in. **Generate** makes one up in the browser.
- deactivates and activates a user. A deactivated user is signed out at once and cannot sign in. Their data
  sources, scans and trials are kept; their scheduled scans pause until the account is active again, and a
  trial that is already running finishes.
- resets a user's password to a new one-time password, which signs that user out everywhere.

The last active admin cannot be deactivated, and nobody deactivates their own account. Users are not
deleted.

Every user has their own data sources, and with them their own scans, size history, recommendations, usage
and index pages and trials. Nobody sees or uses another user's, an admin included: somebody else's source
or trial answers exactly like one that does not exist. Two users may add the same ClickHouse server; each
then has a source of their own for it.

Everyone changes their own password under **Change password** in the sidebar. A password has at least 12
characters; there are no other rules. If the only admin's password is lost, start Granulee once with
`GRANULEE_RESET_ADMIN=admin` and read the new one-time password from the log.

## Security

Without a session only two things answer: `/api/health` and the sign-in. (The files the page is made of
are served to anyone; they hold no data.) A signed-in user can, on their own data sources only,

- read table schemas and the texts of logged queries,
- start trials, which copy sampled rows into the scratch database,
- run the `ALTER TABLE` statements of a trial on the production table, if the ClickHouse user of that
  source is allowed to.

How the sign-in is protected:

- Passwords are stored as Argon2id hashes. The first admin password and the one `GRANULEE_RESET_ADMIN`
  makes are printed once and stored nowhere else.
- A session is a random token in an `HttpOnly`, `SameSite=Lax` cookie; the database holds only its hash.
  It ends after seven days without use, after thirty days at the latest, at **Sign out**, and when the
  account is deactivated or gets a new password. Sessions survive a restart.
- Requests that change something need a CSRF token.
- After five failed sign-ins in a row a user name has to wait a minute, and longer after each further
  failure, up to fifteen minutes; an address after twenty. Nothing is locked for good.

What it does not do, so:

- Granulee speaks plain HTTP. Put a reverse proxy with TLS in front of it before anyone reaches it over a
  network you do not trust, and set `SERVER_FORWARD_HEADERS_STRATEGY=native` then. Without TLS, passwords
  and session cookies travel in the clear.
- An admin cannot see another user's data sources, but can reset that user's password and sign in with
  it. Make admins of people you would trust with every registered server.
- Whoever can read the data directory has the encrypted ClickHouse passwords and, unless
  `GRANULEE_SECRET_KEY` is set elsewhere, the key to them. That key is one for the installation, not one
  per user.
- There are no API tokens: a script signs in like a browser does (`dev/add-source.sh` shows how).
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
