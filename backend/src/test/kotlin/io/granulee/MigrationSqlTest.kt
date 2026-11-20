package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationSqlTest {
    private fun column(name: String, type: String, codec: String = "", key: Boolean = false) =
        ColumnFacts(name, type, codec, 0, 0, "", "", key, false, null)

    private val live = LiveTable(
        engineFull = "ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}') PARTITION BY toYYYYMM(event_time) " +
            "ORDER BY (user_id, event_time) TTL event_time + toIntervalDay(90) SETTINGS index_granularity = 8192",
        partitionKey = "toYYYYMM(event_time)",
        sortingKey = "user_id, event_time",
        primaryKey = "user_id, event_time",
        indexGranularity = "8192",
        rows = 1000,
        compressedBytes = 1000,
        columns = listOf(
            column("user_id", "UInt64", key = true),
            column("event_time", "DateTime", key = true),
            column("country", "String"),
            column("properties", "String", "CODEC(ZSTD(1))"),
            column("referrer", "Nullable(String)", "CODEC(LZ4HC(9))"),
        ),
        indexes = listOf(IndexFacts("idx_country", "country", "set(100)", 4, 0, 0)),
    )

    private fun request(
        partitionKey: String = live.partitionKey,
        sortingKey: String = "(user_id, event_time)",
        dropIndexes: List<String> = emptyList(),
        vararg columns: TrialColumn,
    ) = TrialRequest(
        database = "analytics", table = "events", partitionKey = partitionKey, sortingKey = sortingKey, dropIndexes = dropIndexes,
        columns = live.columns.map { c -> columns.firstOrNull { it.name == c.name } ?: TrialColumn(c.name, c.type, Types.codecBody(c.codec)) },
    )

    private fun sql(request: TrialRequest) = migrationSql("analytics", "events", live, request)

    @Test
    fun `an unchanged candidate needs no statements`() {
        assertEquals("", sql(request()))
    }

    @Test
    fun `column and index changes become ALTER TABLE statements`() {
        val out = sql(
            request(
                dropIndexes = listOf("idx_country"),
                columns = arrayOf(
                    TrialColumn("country", "LowCardinality(String)", ""),
                    TrialColumn("properties", "String", "ZSTD(3)"),
                    TrialColumn("referrer", "String", ""),
                ),
            ),
        )
        assertFalse(out.contains("CREATE TABLE"))
        assertTrue(out.contains("ALTER TABLE `analytics`.`events`\n    MODIFY COLUMN `country` LowCardinality(String),\n    MODIFY COLUMN `referrer` String;"), out)
        assertTrue(out.contains("    MODIFY COLUMN `properties` CODEC(ZSTD(3)),\n    MODIFY COLUMN `referrer` REMOVE CODEC;"), out)
        assertTrue(out.contains("    DROP INDEX `idx_country`;"), out)
        assertTrue(out.contains("referrer loses Nullable"), out)
    }

    @Test
    fun `a type and codec change on one column is a single MODIFY`() {
        val out = sql(request(columns = arrayOf(TrialColumn("country", "LowCardinality(String)", "ZSTD(1)"))))
        assertTrue(out.contains("MODIFY COLUMN `country` LowCardinality(String) CODEC(ZSTD(1));"), out)
        assertEquals(1, Regex("ALTER TABLE").findAll(out).count())
    }

    @Test
    fun `a new sorting key is a rebuild that keeps engine, TTL and settings`() {
        val out = sql(request(sortingKey = "event_time, user_id", columns = arrayOf(TrialColumn("country", "LowCardinality(String)", ""))))
        assertFalse(out.contains("ALTER TABLE `"), out)
        assertTrue(out.contains("the sorting key changes"), out)
        assertTrue(out.contains("CREATE TABLE `analytics`.`events_new`"), out)
        assertTrue(out.contains("ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}')\nPARTITION BY toYYYYMM(event_time)\nORDER BY (event_time, user_id)\nTTL event_time + toIntervalDay(90)\nSETTINGS index_granularity = 8192;"), out)
        assertTrue(out.contains("CAST(`country` AS LowCardinality(String)) AS `country`"), out)
        assertTrue(out.contains("EXCHANGE TABLES `analytics`.`events` AND `analytics`.`events_new`;"), out)
    }

    @Test
    fun `a key column that changes type is a rebuild`() {
        val out = sql(request(columns = arrayOf(TrialColumn("user_id", "UInt32", ""))))
        assertTrue(out.contains("user_id is a key column and changes its type"), out)
        assertTrue(out.contains("CREATE TABLE"), out)
    }

    @Test
    fun `an index over several expressions keeps its parentheses`() {
        val table = live.copy(indexes = listOf(IndexFacts("idx_pos", "lat, lon", "set(100)", 2, 0, 0), IndexFacts("idx_fn", "lower(country)", "set(100)", 2, 0, 0)))
        val out = createSql("`scratch`.`t`", table, null, "MergeTree", "")
        assertTrue(out.contains("INDEX `idx_pos` (lat, lon) TYPE set(100) GRANULARITY 2"), out)
        assertTrue(out.contains("INDEX `idx_fn` lower(country) TYPE set(100) GRANULARITY 2"), out)
    }

    @Test
    fun `JSON, Dynamic and Variant types are recognised wherever they are nested`() {
        listOf("JSON", "JSON(max_dynamic_paths = 100)", "Array(JSON)", "Nullable(Dynamic)", "Variant(String, UInt64)", "Map(String, Dynamic)")
            .forEach { assertTrue(hasDynamicSubcolumns(it), it) }
        listOf("String", "LowCardinality(String)", "Array(Nullable(Int64))", "Enum8('JSON' = 1)").forEach { assertFalse(hasDynamicSubcolumns(it), it) }
    }

    @Test
    fun `only ALTERs are runnable statements`() {
        val alter = migration("analytics", "events", live, request(dropIndexes = listOf("idx_country"), columns = arrayOf(TrialColumn("properties", "String", "ZSTD(3)"))))
        assertEquals("alter", alter.kind)
        assertEquals(2, alter.statements.size)
        assertTrue(alter.statements.all { it.contains("ALTER TABLE `analytics`.`events`") && !it.endsWith(";") }, alter.statements.toString())

        val rebuild = migration("analytics", "events", live, request(sortingKey = "event_time, user_id"))
        assertEquals("rebuild", rebuild.kind)
        assertTrue(rebuild.statements.isEmpty())

        assertEquals("none", migration("analytics", "events", live, request()).kind)
    }

    @Test
    fun `a Buffer table names the table behind it`() {
        assertEquals("flight_tracker.movement_data_optimized", bufferTarget("flight_tracker", "Buffer('flight_tracker', 'movement_data_optimized', 1, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals("logs.events", bufferTarget("logs", "Buffer(currentDatabase(), 'events', 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals("logs.events", bufferTarget("logs", "Buffer('', events, 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals(null, bufferTarget("logs", "MergeTree ORDER BY id"))
    }

    @Test
    fun `a replayed query on the Buffer table is pointed at the trial table`() {
        val logged = "SELECT count() FROM flight_tracker.movement_data_buffer WHERE hex = 'abc'"
        val first = rewriteQuery(logged, "flight_tracker", "movement_data_optimized", "`scratch`.`t`")
        assertEquals(logged, first)
        assertEquals("SELECT count() FROM `scratch`.`t` WHERE hex = 'abc'", rewriteQuery(first, "flight_tracker", "movement_data_buffer", "`scratch`.`t`"))
    }

    @Test
    fun `a new skip index is added and materialized, and is part of a rebuild`() {
        val index = TrialIndex("idx_country", "country", "set(100)", 4)
        val alter = migration("analytics", "events", live.copy(indexes = emptyList()), request().copy(addIndexes = listOf(index)))
        assertEquals("alter", alter.kind)
        assertTrue(alter.sql.contains("ADD INDEX `idx_country` country TYPE set(100) GRANULARITY 4;"), alter.sql)
        assertTrue(alter.sql.contains("MATERIALIZE INDEX `idx_country`;"), alter.sql)
        assertEquals(2, alter.statements.size)

        val rebuild = migration("analytics", "events", live.copy(indexes = emptyList()), request(sortingKey = "event_time").copy(addIndexes = listOf(index)))
        assertTrue(rebuild.sql.contains("INDEX `idx_country` country TYPE set(100) GRANULARITY 4"), rebuild.sql)
        assertFalse(rebuild.sql.contains("MATERIALIZE"), rebuild.sql)
    }

    @Test
    fun `predicates on a column are read off the filter text`() {
        val filter = "WHERE mil = ? AND timestamp_sort >= toDateTime(?) AND has(navModes, ?) AND hex IN (?) AND lat != ?"
        assertEquals(setOf("eq"), Analyzer.predicates(filter, "mil"))
        assertEquals(setOf("range"), Analyzer.predicates(filter, "timestamp_sort"))
        assertEquals(setOf("has"), Analyzer.predicates(filter, "navModes"))
        assertEquals(setOf("eq"), Analyzer.predicates(filter, "hex"))
        assertEquals(emptySet(), Analyzer.predicates(filter, "lat"))
    }

    @Test
    fun `the index type follows the predicate and the column`() {
        assertEquals("set(100)", Analyzer.skipIndexType(column("country", "LowCardinality(String)"), setOf("eq")))
        assertEquals("bloom_filter(0.01)", Analyzer.skipIndexType(column("session_id", "String"), setOf("eq")))
        assertEquals("minmax", Analyzer.skipIndexType(column("duration_ms", "Int64"), setOf("range")))
        assertEquals("bloom_filter(0.01)", Analyzer.skipIndexType(column("tags", "Array(String)"), setOf("has")))
        assertEquals(null, Analyzer.skipIndexType(column("url", "String"), setOf("range")))
        assertEquals("minmax", Analyzer.skipIndexType(column("event_date", "Date"), setOf("eq")))
    }
}
