package io.granulee.trial

import io.granulee.facts.ColumnFacts
import io.granulee.facts.IndexFacts
import io.granulee.sql.TrialIndex
import io.granulee.sql.Types
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
    fun `a rebuild keeps the projections, a trial table has none`() {
        val table = live.copy(
            projections = listOf(
                ProjectionFacts("per_country", "SELECT country, count() GROUP BY country"),
                ProjectionFacts("by_time", "SELECT * ORDER BY event_time", mapOf("index_granularity" to "4096")),
                ProjectionFacts("old_one", ""),
            ),
        )
        val sql = migrationSql("analytics", "events", table, request(sortingKey = "(event_time, user_id)"))
        assertTrue("    PROJECTION `per_country` (SELECT country, count() GROUP BY country),\n" in sql, sql)
        assertTrue("    PROJECTION `by_time` (SELECT * ORDER BY event_time) WITH SETTINGS (index_granularity = 4096)\n)" in sql, sql)
        assertTrue("-- The new table gets the 2 projections of the old one" in sql, sql)
        assertTrue("-- Projection old_one is missing below" in sql, sql)
        assertFalse("PROJECTION `old_one`" in sql, sql)
        assertFalse("PROJECTION" in createSql("`scratch`.`t`", table, null, "MergeTree", ""))
    }

    @Test
    fun `the table with plain defaults keeps keys and types and drops codecs and indexes`() {
        val untuned = live.copy(columns = live.columns.map { it.copy(codec = "") }, indexes = emptyList(), projections = emptyList())
        val out = createSql("`scratch`.`t`", untuned, null, "MergeTree", "")
        assertFalse("CODEC" in out, out)
        assertFalse("INDEX" in out, out)
        assertTrue("`properties` String,\n" in out, out)
        assertTrue("PARTITION BY toYYYYMM(event_time)\nORDER BY (user_id, event_time)" in out, out)
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
    fun `a migration is one of three kinds, and only an ALTER carries statements to run`() {
        assertEquals(Migration.None, migration("analytics", "events", live, request()))
        assertEquals("", Migration.None.sql)
        val alter = migration("analytics", "events", live, request(dropIndexes = listOf("idx_country")))
        assertTrue(alter is Migration.Alter && alter.statements.single().endsWith("DROP INDEX `idx_country`"), alter.toString())
        val rebuild = migration("analytics", "events", live, request(partitionKey = "toYYYYMMDD(event_time)"))
        assertTrue(rebuild is Migration.Rebuild && rebuild.sql.contains("the partition key changes") && rebuild.statements.isEmpty(), rebuild.toString())
    }

    @Test
    fun `a rebuild of a table whose primary key is a prefix of its sorting key keeps the primary key while the sorting key stays`() {
        val table = live.copy(primaryKey = "user_id")
        assertTrue("PRIMARY KEY (user_id)\nORDER BY (user_id, event_time)" in createSql("`scratch`.`t`", table, null, "MergeTree", ""))
        // With a new sorting key the old primary key may no longer be its prefix: it is left to ClickHouse.
        assertFalse("PRIMARY KEY" in createSql("`scratch`.`t`", table, request(sortingKey = "event_time, user_id"), "MergeTree", ""))
    }
}
