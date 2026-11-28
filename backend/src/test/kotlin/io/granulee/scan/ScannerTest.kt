package io.granulee.scan

import io.granulee.InMemoryHistoryStorage
import io.granulee.InMemorySnapshotStorage
import io.granulee.NotFoundException
import io.granulee.clickhouse.FakeClickHouse
import io.granulee.clickhouse.Row
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScannerTest {
    private val gib = 1L shl 30
    private val target = ScanTarget("s1", "local", includeDatabases = "", excludeDatabases = "tmp_*", scratchDatabase = "scratch", queryLogWindowDays = 30)

    private fun table(database: String, name: String): Row = mapOf(
        "database" to database, "name" to name, "engine" to "MergeTree", "engine_full" to "MergeTree ORDER BY id",
        "partition_key" to "", "sorting_key" to "id", "primary_key" to "id",
    )

    /** A server with three MergeTree tables, of which the scan covers one, and a Buffer table in front of it. */
    private fun server() = FakeClickHouse()
        .answer("WHERE engine LIKE '%MergeTree'", table("analytics", "events"), table("scratch", "events_trial_1_base"), table("tmp_load", "staging"))
        .answer(
            "FROM system.parts\nWHERE active",
            mapOf("database" to "analytics", "table" to "events", "rows" to 5_000_000, "compressed" to 40 * gib, "uncompressed" to 400 * gib, "on_disk" to 41 * gib, "parts" to 12, "partitions" to 3),
        )
        .answer("name = 'estimates.cardinality'", mapOf("c" to 0))
        .answer(
            "FROM system.columns\nWHERE database NOT IN",
            mapOf("database" to "analytics", "table" to "events", "name" to "id", "type" to "UInt64", "compression_codec" to "CODEC(ZSTD(1))", "compressed" to gib, "uncompressed" to 8 * gib, "default_kind" to "", "default_expression" to "", "is_in_sorting_key" to 1, "is_in_partition_key" to 0),
            mapOf("database" to "analytics", "table" to "events", "name" to "payload", "type" to "String", "compression_codec" to "", "compressed" to 39 * gib, "uncompressed" to 392 * gib, "default_kind" to "", "default_expression" to "", "is_in_sorting_key" to 0, "is_in_partition_key" to 0),
        )
        .answer("WHERE engine = 'Buffer'", mapOf("database" to "analytics", "name" to "events_buf", "engine_full" to "Buffer('analytics', 'events', 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))

    private val logged: Array<Row> = arrayOf(
        mapOf("t" to "analytics.events", "q" to "SELECT count() FROM analytics.events WHERE id = 1", "nq" to "SELECT count() FROM analytics.events WHERE id = ?", "d" to "default", "c" to 60, "total" to 60),
        mapOf("t" to "analytics.events_buf", "q" to "SELECT payload FROM events_buf WHERE id > 5", "nq" to "SELECT payload FROM events_buf WHERE id > ?", "d" to "analytics", "c" to 40, "total" to 40),
    )

    @Test
    fun `a scan covers the wanted databases and counts a Buffer table's queries for the table behind it`() {
        val ch = server().answer("FROM system.query_log\n    WHERE event_date >= today() - 30", *logged)
        val snapshot = Scanner().scan(ch, target, Instant.parse("2026-11-21T10:00:00Z"))

        assertEquals("s1", snapshot.sourceId)
        assertEquals("2026-11-21T10:00:00Z", snapshot.scannedAt)
        assertEquals(QueryLogStatus("ok"), snapshot.queryLog)
        assertTrue(snapshot.queryLogReadable)
        val events = snapshot.tables.single()
        assertEquals("analytics" to "events", events.database to events.name)
        assertEquals(listOf(5_000_000L, 40 * gib, 12L, 3L), listOf(events.rows, events.compressedBytes, events.activeParts, events.partitions))
        assertEquals(listOf("id", "payload"), events.columns.map { it.name })
        assertTrue(events.columns.first().inSortingKey)
        assertEquals(listOf("analytics.events_buf"), events.buffers)
        assertEquals(100, events.queryTotal)
        assertEquals(listOf(60L, 40L), events.queries.map { it.count })
        assertEquals("analytics", events.queries.last().database)
        // The rules ran on what was read: the large text column on the default codec is a finding.
        assertEquals(1, events.findings)
        assertEquals(mapOf("Codec" to 1), events.findingsByCategory)
        assertTrue(events.savingsBytes in 1..events.compressedBytes)
    }

    @Test
    fun `each logged shape is analysed once, in the database of the session that ran it`() {
        val ch = server().answer("FROM system.query_log\n    WHERE event_date >= today() - 30", *logged)
        Scanner().scan(ch, target, Instant.now())
        val analysed = ch.sent.filter { it.sql.startsWith("EXPLAIN QUERY TREE") }
        assertEquals(listOf("default", "analytics"), analysed.map { it.database })
        assertTrue(analysed.all { it.settings["max_rows_to_read"] == "1" })
    }

    @Test
    fun `a query log that cannot be read leaves sizes and codecs and says why`() {
        val ch = server().refuse("FROM system.query_log", "Not enough privileges. To execute this query, it's necessary to have the grant SELECT ON system.query_log")
        val snapshot = Scanner().scan(ch, target, Instant.now())
        assertEquals("denied", snapshot.queryLog?.state)
        assertTrue(!snapshot.queryLogReadable)
        val events = snapshot.tables.single()
        assertNull(events.queryTotal)
        assertTrue(events.queries.isEmpty())
        assertEquals(1, events.findings)
    }

    @Test
    fun `a readable log with nothing in it is told from an idle table`() {
        val empty = Scanner().scan(server().answer("SELECT count() AS c FROM system.query_log", mapOf("c" to 0)), target, Instant.now())
        assertEquals("empty", empty.queryLog?.state)
        assertEquals(0, empty.tables.single().queryTotal)

        val idle = Scanner().scan(server().answer("SELECT count() AS c FROM system.query_log", mapOf("c" to 812)), target, Instant.now())
        assertEquals("ok", idle.queryLog?.state)
    }

    @Test
    fun `include and exclude lists take wildcards, and the scratch database is never scanned`() {
        val all = databaseFilter("", "", "scratch")
        assertTrue(all("analytics") && !all("scratch"))
        val some = databaseFilter("analytics, logs_*", "logs_tmp, *_old", "scratch")
        assertEquals(listOf(true, true, false, false, false), listOf("analytics", "logs_2026", "logs_tmp", "analytics_old", "other").map(some))
        // A dot in a name is a dot, not "any character".
        assertTrue(!databaseFilter("a.b", "", "scratch")("axb"))
    }
}

class SizeHistoryTest {
    private val today = LocalDate.parse("2026-11-21")

    @Test
    fun `a day has one point per source, and days older than 120 fall out`() {
        val points = listOf(
            HistoryPoint("2026-07-01", "a", 1),
            HistoryPoint("2026-11-20", "a", 10),
            HistoryPoint("2026-11-21", "a", 11),
            HistoryPoint("2026-11-21", "b", 20),
        )
        val next = SizeHistory.withPoint(points, HistoryPoint("2026-11-21", "a", 12), today)
        assertEquals(listOf(HistoryPoint("2026-11-20", "a", 10), HistoryPoint("2026-11-21", "b", 20), HistoryPoint("2026-11-21", "a", 12)), next)
    }

    @Test
    fun `the history is kept across restarts and forgets a removed source`() {
        val store = InMemoryHistoryStorage()
        SizeHistory(store).apply {
            record("a", 10, today)
            record("b", 20, today)
        }
        val reloaded = SizeHistory(store)
        assertEquals(listOf("a", "b"), reloaded.all().map { it.sourceId })
        reloaded.forget("a")
        assertEquals(listOf(HistoryPoint("2026-11-21", "b", 20)), SizeHistory(store).all())
    }
}

class SnapshotStoreTest {
    @Test
    fun `snapshots are kept per source across restarts`() {
        val store = InMemorySnapshotStorage()
        val snapshots = SnapshotStore(store)
        assertNull(snapshots.of("a"))
        snapshots.save(Snapshot(sourceId = "a", scannedAt = "2026-11-21T10:00:00Z"))
        snapshots.save(Snapshot(sourceId = "b"))

        val reloaded = SnapshotStore(store)
        assertEquals("2026-11-21T10:00:00Z", reloaded.of("a")?.scannedAt)
        assertNotNull(reloaded.of("b"))
        reloaded.forget("a")
        assertNull(SnapshotStore(store).of("a"))
    }

    @Test
    fun `a table the last scan did not see is not found, with the source's name in the message`() {
        val snapshots = SnapshotStore(InMemorySnapshotStorage())
        val e = assertFailsWith<NotFoundException> { snapshots.requireTable("a", "prod", "analytics", "events") }
        assertEquals("Table analytics.events was not in the last scan of prod. Scan the source first.", e.message)
    }
}
