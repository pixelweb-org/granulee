#!/bin/sh
# Fills a local ClickHouse with a small "analytics" schema that has things for Granulee to find:
# daily partitions, default codecs on big text columns, Nullable columns, a skip index, and a
# query_log where most queries filter on event_type while the sorting key leads with user_id.
#
#   docker run -d --name granulee-test-ch -p 127.0.0.1:18123:8123 \
#     -e CLICKHOUSE_USER=granulee_test -e CLICKHOUSE_PASSWORD=granulee_test \
#     -e CLICKHOUSE_DEFAULT_ACCESS_MANAGEMENT=1 clickhouse/clickhouse-server:25.8
#   dev/seed.sh
set -eu
URL="${CH_URL:-http://localhost:18123}"
AUTH="${CH_USER:-granulee_test}:${CH_PASSWORD:-granulee_test}"
ROWS="${ROWS:-6000000}"

ch() { curl -sS --fail-with-body -u "$AUTH" "$URL/?max_partitions_per_insert_block=0" --data-binary "$1"; }

ch "CREATE DATABASE IF NOT EXISTS analytics"
ch "CREATE DATABASE IF NOT EXISTS scratch"
ch "DROP TABLE IF EXISTS analytics.events"
ch "DROP TABLE IF EXISTS analytics.page_views"
ch "DROP TABLE IF EXISTS analytics.users"

ch "CREATE TABLE analytics.events
(
    event_time DateTime,
    event_date Date DEFAULT toDate(event_time),
    user_id UInt64,
    session_id String,
    event_type String,
    url String CODEC(ZSTD(1)),
    referrer String,
    properties String,
    duration_ms Int64,
    revenue Nullable(Float64),
    country Nullable(String),
    INDEX idx_session session_id TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = MergeTree
PARTITION BY toYYYYMMDD(event_time)
ORDER BY (user_id, event_time)"

ch "INSERT INTO analytics.events (event_time, user_id, session_id, event_type, url, referrer, properties, duration_ms, revenue, country)
SELECT
    now() - toIntervalSecond(rand(1) % (120 * 86400)),
    rand64(2) % 200000,
    toString(generateUUIDv4()),
    concat('event_', toString(rand(3) % 37)),
    concat('https://shop.example.com/products/', toString(rand(4) % 5000), '?ref=', toString(rand(5) % 40)),
    concat('https://www.search-', toString(rand(6) % 300), '.example/q?term=', toString(rand(7) % 20000)),
    concat('{\"plan\":\"', ['free', 'pro', 'team'][rand(8) % 3 + 1], '\",\"experiment\":\"exp_', toString(rand(9) % 50),
           '\",\"viewport\":{\"w\":', toString(1000 + rand(10) % 900), ',\"h\":', toString(600 + rand(11) % 500),
           '},\"cart\":[', toString(rand(12) % 9000), ',', toString(rand(13) % 9000), '],\"locale\":\"en-', toString(rand(14) % 30), '\"}'),
    rand(15) % 3600000,
    if(rand(16) % 10 = 0, (rand(17) % 100000) / 100, NULL),
    ['DE', 'US', 'FR', 'GB', 'NL', 'ES', 'IT', 'PL', 'SE', 'JP'][rand(18) % 10 + 1]
FROM numbers($ROWS)"

ch "CREATE TABLE analytics.page_views
(
    view_time DateTime CODEC(Delta, ZSTD(1)),
    user_id UInt64,
    path LowCardinality(String),
    title String,
    load_ms UInt32
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(view_time)
ORDER BY (path, view_time)"

ch "INSERT INTO analytics.page_views
SELECT now() - toIntervalSecond(rand(1) % (90 * 86400)), rand64(2) % 200000, concat('/section/', toString(rand(3) % 400)),
       concat('Page title number ', toString(rand(4) % 3000), ' of the example shop'), rand(5) % 8000
FROM numbers(intDiv($ROWS, 3))"

ch "CREATE TABLE analytics.users (user_id UInt64, signed_up DateTime, email String, plan LowCardinality(String))
ENGINE = ReplacingMergeTree ORDER BY user_id"
ch "INSERT INTO analytics.users SELECT number, now() - toIntervalDay(number % 900), concat('user', toString(number), '@example.com'),
    ['free', 'pro', 'team'][number % 3 + 1] FROM numbers(200000)"

# A workload for query_log: mostly filters on event_type and a date range, a few lookups by user_id.
i=0
while [ $i -lt 30 ]; do
  ch "SELECT event_type, count() FROM analytics.events WHERE event_type = 'event_$i' AND event_time >= now() - INTERVAL 7 DAY GROUP BY event_type FORMAT Null"
  ch "SELECT toDate(event_time) AS d, sum(revenue) FROM analytics.events WHERE event_type = 'event_$((i % 37))' AND event_time >= now() - INTERVAL 30 DAY GROUP BY d FORMAT Null"
  ch "SELECT url, count() AS c FROM analytics.events WHERE event_type = 'event_$((i % 5))' AND event_date = today() GROUP BY url ORDER BY c DESC LIMIT 50 FORMAT Null"
  [ $((i % 6)) -eq 0 ] && ch "SELECT * FROM analytics.events WHERE user_id = $((i * 31)) AND event_time >= now() - INTERVAL 90 DAY ORDER BY event_time FORMAT Null"
  [ $((i % 5)) -eq 0 ] && ch "SELECT country, uniq(user_id) FROM analytics.events WHERE event_date >= today() - 30 GROUP BY country FORMAT Null"
  i=$((i + 1))
done
ch "SYSTEM FLUSH LOGS"
ch "SELECT 'events rows', count() FROM analytics.events FORMAT TSV"
