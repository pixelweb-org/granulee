package io.granulee.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EngineFullTest {
    private val full = "ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}') PARTITION BY toYYYYMM(event_time) " +
        "ORDER BY (user_id, event_time) SAMPLE BY user_id TTL event_time + toIntervalDay(90) SETTINGS index_granularity = 4096, ttl_only_drop_parts = 1"

    @Test
    fun `the engine clause ends where the keys begin`() {
        assertEquals("ReplicatedMergeTree('/clickhouse/tables/{shard}/analytics/events', '{replica}')", engineClause(full))
        assertEquals("MergeTree", engineClause("MergeTree ORDER BY id"))
        assertEquals("MergeTree", engineClause("MergeTree"))
        // A clause word inside the engine's arguments is no clause.
        assertEquals("ReplacingMergeTree(if(a, ORDER BY x, b))", engineClause("ReplacingMergeTree(if(a, ORDER BY x, b)) ORDER BY id"))
    }

    @Test
    fun `what follows the sorting key is kept clause by clause`() {
        assertEquals("SAMPLE BY user_id\nTTL event_time + toIntervalDay(90)\nSETTINGS index_granularity = 4096, ttl_only_drop_parts = 1", engineTail(full))
        assertEquals("", engineTail("MergeTree ORDER BY id"))
    }

    @Test
    fun `TTL, settings and index granularity are read off engine_full`() {
        assertEquals("event_time + toIntervalDay(90)", engineTtl(full))
        assertEquals(listOf("index_granularity" to "4096", "ttl_only_drop_parts" to "1"), engineSettings(full))
        assertEquals("4096", engineIndexGranularity(full))
        assertNull(engineTtl("MergeTree ORDER BY id SETTINGS index_granularity = 8192"))
        assertEquals(emptyList(), engineSettings("MergeTree ORDER BY id"))
        assertNull(engineIndexGranularity("MergeTree ORDER BY id"))
    }

    @Test
    fun `a Buffer table names the table behind it`() {
        assertEquals("flight_tracker.movement_data_optimized", bufferTarget("flight_tracker", "Buffer('flight_tracker', 'movement_data_optimized', 1, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals("logs.events", bufferTarget("logs", "Buffer(currentDatabase(), 'events', 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals("logs.events", bufferTarget("logs", "Buffer('', events, 16, 10, 100, 10000, 1000000, 10000000, 100000000)"))
        assertEquals(null, bufferTarget("logs", "MergeTree ORDER BY id"))
    }
}
