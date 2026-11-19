package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerTest {
    private val gib = 1L shl 30

    private fun column(name: String, type: String, codec: String = "", compressed: Long = gib, uncompressed: Long = 8 * gib, key: Boolean = false, cardinality: Long? = null) =
        ColumnFacts(name, type, codec, compressed, uncompressed, "", "", key, false, cardinality)

    private fun events(
        columns: List<ColumnFacts>,
        partitions: Long = 12,
        partitionKey: String = "toYYYYMM(event_time)",
        sortingKey: String = "user_id, event_time",
        queries: List<QueryFacts> = emptyList(),
        queryTotal: Long? = null,
        indexes: List<IndexFacts> = emptyList(),
    ) = TableFacts(
        "analytics", "events", "ReplicatedMergeTree",
        "ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}') PARTITION BY $partitionKey ORDER BY ($sortingKey) SETTINGS index_granularity = 8192",
        partitionKey, sortingKey, sortingKey, 4_820_000_000, columns.sumOf { it.compressedBytes }, columns.sumOf { it.uncompressedBytes },
        columns.sumOf { it.compressedBytes }, 1284, partitions, columns, indexes, queries, queryTotal,
    )

    private fun ids(table: TableFacts) = Analyzer.analyze(table, 30).map { it.rec.id }

    @Test
    fun `large default-codec text column gets ZSTD`() {
        val findings = Analyzer.analyze(events(listOf(column("properties", "String", compressed = 68 * gib, uncompressed = 1024 * gib))), 30)
        val rec = findings.single().rec
        assertEquals("codec:properties", rec.id)
        assertEquals("online", rec.changeKind)
        assertTrue(rec.sql.contains("MODIFY COLUMN `properties` String CODEC(ZSTD(3))"))
        assertEquals(ColumnChange("properties", null, "ZSTD(3)"), findings.single().plan?.column)
    }

    @Test
    fun `column with an explicit codec is left alone`() {
        assertEquals(emptyList(), ids(events(listOf(column("url", "String", codec = "CODEC(ZSTD(1))")))))
    }

    @Test
    fun `low cardinality string becomes a type recommendation, not a codec one`() {
        val rec = Analyzer.analyze(events(listOf(column("event_type", "String", cardinality = 37))), 30).single().rec
        assertEquals("type:event_type", rec.id)
        assertEquals("mutation", rec.changeKind)
        assertTrue(rec.sql.contains("LowCardinality(String)"))
    }

    @Test
    fun `timestamps get Delta`() {
        val rec = Analyzer.analyze(events(listOf(column("event_time", "DateTime", key = true))), 30).single().rec
        assertTrue(rec.sql.contains("CODEC(Delta, ZSTD(1))"))
    }

    @Test
    fun `many small daily partitions suggest monthly ones with a rebuild`() {
        val table = events(listOf(column("url", "String", codec = "CODEC(ZSTD(1))", compressed = 300 * gib)), partitions = 412, partitionKey = "toYYYYMMDD(event_time)")
        val finding = Analyzer.analyze(table, 30).single()
        assertEquals("high", finding.rec.severity)
        assertEquals("toYYYYMM(event_time)", finding.plan?.partitionKey)
        assertTrue(finding.rec.sql.contains("ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}')\nPARTITION BY toYYYYMM(event_time)"))
    }

    @Test
    fun `sorting key follows what queries filter on`() {
        val columns = listOf(
            column("user_id", "UInt64", codec = "CODEC(ZSTD(1))", key = true),
            column("event_time", "DateTime", codec = "CODEC(Delta, ZSTD(1))", key = true),
            column("event_type", "LowCardinality(String)", codec = "CODEC(ZSTD(1))"),
        )
        val queries = listOf(
            QueryFacts("q", "SELECT count() FROM analytics.events WHERE event_type = ? AND event_time >= ?", 780),
            QueryFacts("q", "SELECT * FROM analytics.events WHERE user_id = ?", 60),
        )
        val finding = Analyzer.analyze(events(columns, queries = queries, queryTotal = 1000), 30).single()
        assertEquals("sorting-key", finding.rec.id)
        assertEquals("high", finding.rec.severity)
        assertEquals("event_type, user_id, event_time", finding.plan?.sortingKey)
        assertEquals(1, finding.rec.queries.size)
    }

    @Test
    fun `no sorting key advice without query_log`() {
        val columns = listOf(column("user_id", "UInt64", codec = "CODEC(ZSTD(1))", key = true))
        assertEquals(emptyList(), ids(events(columns, queryTotal = null)))
    }

    @Test
    fun `sizeable skip index is put up for review`() {
        val index = IndexFacts("idx_session", "session_id", "bloom_filter(0.01)", 4, 2 * gib, 3 * gib)
        val finding = Analyzer.analyze(events(listOf(column("url", "String", codec = "CODEC(ZSTD(1))", compressed = 100 * gib)), indexes = listOf(index)), 30).single()
        assertEquals("review", finding.rec.severity)
        assertEquals("idx_session", finding.plan?.dropIndex)
    }

    @Test
    fun `helpers`() {
        assertEquals(listOf("event_type", "toDate(event_time, 'UTC')", "user_id"), splitTopLevel("event_type, toDate(event_time, 'UTC'), user_id"))
        assertEquals("a, b", normalizeKey("(a, b)"))
        assertEquals("(a), (b)", normalizeKey("(a), (b)"))
        assertEquals("String", Types.base("LowCardinality(Nullable(String))"))
        assertEquals("LZ4 (default)", Types.codecLabel(""))
        assertEquals("Delta(4), ZSTD(1)", Types.codecLabel("CODEC(Delta(4), ZSTD(1))"))
        assertEquals("300 GiB", Fmt.bytes(300 * gib))
        assertEquals("2.34 TiB", Fmt.bytes((2.34 * 1024 * gib).toLong()))
    }

    @Test
    fun `logged queries are pointed at the trial table`() {
        val target = "`scratch`.`events_trial_7f3a_base`"
        assertEquals(
            "SELECT count() FROM $target WHERE events_count > 1",
            rewriteQuery("SELECT count() FROM analytics.events WHERE events_count > 1;", "analytics", "events", target),
        )
        assertEquals(
            "SELECT e.url FROM $target AS e",
            rewriteQuery("SELECT e.url FROM `analytics`.`events` AS e FORMAT JSON", "analytics", "events", target),
        )
        assertEquals("SELECT x FROM $target", rewriteQuery("SELECT x FROM events", "analytics", "events", target))
    }
}
