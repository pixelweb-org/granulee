package io.granulee.explorer

import io.granulee.NotFoundException
import io.granulee.clickhouse.FakeClickHouse
import io.granulee.facts.TableFacts
import io.granulee.recommendation.Finding
import io.granulee.recommendation.Recommendation
import io.granulee.scan.HistoryPoint
import io.granulee.scan.Snapshot
import io.granulee.source.SourceRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplorerTest {
    private fun source(id: String, name: String) = SourceRecord(id, name, "http", "localhost", 8123, "u", "", "", "", "daily", 30, true, "scratch")

    private fun table(database: String, name: String, compressed: Long) =
        TableFacts(database, name, "MergeTree", "", "", "id", "id", 0, compressed, compressed * 5, compressed, 1, 1, emptyList(), emptyList())

    @Test
    fun `the growth chart has one series per source that has a history, over the last twelve scan days`() {
        val history = (1..14).map { HistoryPoint("2026-11-%02d".format(it), "a", it * 10L) } + HistoryPoint("2026-11-05", "b", 500)
        val chart = growthChart(listOf(source("a", "prod"), source("b", "staging"), source("c", "new")), history)
        assertEquals((3..14).map { "11-%02d".format(it) }, chart.categories)
        assertEquals(listOf("prod", "staging"), chart.series.map { it.name })
        assertEquals((3..14).map { it * 10.0 }, chart.series[0].values)
        // A day without a scan carries the size of the scan before it; before the first scan there is nothing.
        assertEquals(listOf(0.0, 0.0) + List(10) { 500.0 }, chart.series[1].values)
        assertTrue(growthChart(listOf(source("a", "prod")), emptyList()).let { it.categories.isEmpty() && it.series.isEmpty() })
    }

    @Test
    fun `databases are summed from their tables, largest first`() {
        val snapshot = Snapshot(tables = listOf(table("logs", "a", 10), table("analytics", "events", 100), table("analytics", "users", 5)))
        assertEquals(
            listOf(DatabaseSummary("analytics", 2, 105, 525), DatabaseSummary("logs", 1, 10, 50)),
            databaseSummaries(snapshot),
        )
        assertEquals(emptyList(), databaseSummaries(null))
    }

    private val meta = mapOf(
        "engine" to "MergeTree", "engine_full" to "MergeTree ORDER BY id TTL ts + toIntervalDay(30) SETTINGS index_granularity = 8192, ttl_only_drop_parts = 1",
        "partition_key" to "", "sorting_key" to "id", "primary_key" to "id", "storage_policy" to "default", "comment" to "", "create_table_query" to "CREATE TABLE …",
    )

    @Test
    fun `a table's page is read from the server, with the findings of the last scan on its columns`() {
        val ch = FakeClickHouse()
            .answer("FROM system.tables WHERE database = 'analytics' AND name = 'events'", meta)
            .answer("AS pk_allocated", mapOf("rows" to 1000, "compressed" to 400, "uncompressed" to 2000, "on_disk" to 420, "parts" to 3, "partitions" to 2, "pk" to 64, "pk_allocated" to 128, "marks" to 12, "marks_bytes" to 96, "index_bytes" to 7))
            .answer("ORDER BY data_compressed_bytes DESC", mapOf("name" to "url", "type" to "String", "compression_codec" to "", "compressed" to 300, "uncompressed" to 1500), mapOf("name" to "id", "type" to "UInt64", "compression_codec" to "CODEC(Delta(8), ZSTD(1))", "compressed" to 100, "uncompressed" to 500))
            .answer("GROUP BY partition, partition_id", mapOf("partition" to "202611", "compressed" to 250, "parts" to 2), mapOf("partition" to "202610", "compressed" to 150, "parts" to 1))
            .answer("WHERE engine = 'Buffer'", mapOf("database" to "analytics", "name" to "events_buf", "engine_full" to "Buffer('analytics', 'events', 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        val finding = Finding(Recommendation("codec:url", "medium", "Codec", "online", "t", "b", "e", "sql", 100, "url"), null)

        val detail = TableDetailReader.read(ch, source("s1", "prod"), "analytics", "events", listOf(finding))
        assertEquals(listOf("s1", "prod", "analytics", "events"), listOf(detail.sourceId, detail.sourceName, detail.database, detail.name))
        assertEquals("ts + toIntervalDay(30)", detail.ttl)
        assertEquals(listOf(Setting("index_granularity", "8192"), Setting("ttl_only_drop_parts", "1")), detail.settings)
        assertEquals(listOf(1000L, 400L, 3L, 2L, 64L, 128L, 12L, 96L, 7L), listOf(detail.rows, detail.compressedBytes, detail.activeParts, detail.partitions, detail.primaryKeyBytesInMemory, detail.primaryKeyBytesInMemoryAllocated, detail.marks, detail.marksBytes, detail.skipIndexBytes))
        assertEquals(listOf(ColumnInfo("url", "String", "LZ4 (default)", 300, 1500, "Codec"), ColumnInfo("id", "UInt64", "Delta(8), ZSTD(1)", 100, 500, null)), detail.columns)
        // The partitions are read newest first and shown oldest first.
        assertEquals(listOf("202610", "202611"), detail.partitionStats.map { it.partition })
        assertEquals(1, detail.findings)
        assertEquals(listOf("analytics.events_buf"), detail.buffers)
        assertNull(detail.files)
        assertTrue(detail.projections.isEmpty() && detail.indexes.isEmpty())
    }

    @Test
    fun `a table the server does not have is not found`() {
        val e = assertFailsWith<NotFoundException> { TableDetailReader.read(FakeClickHouse(), source("s1", "prod"), "analytics", "nope", emptyList()) }
        assertEquals("Table analytics.nope does not exist on prod", e.message)
    }
}
