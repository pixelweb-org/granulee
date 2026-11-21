package io.granulee.explain

import io.granulee.clickhouse.FakeClickHouse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExplainTest {
    @Test
    fun `index steps keep their kind across server versions`() {
        assertEquals("minmax", Explain.stepKind("MinMax"))
        assertEquals("minmax", Explain.stepKind("Min-Max"))
        assertEquals("minmax", Explain.stepKind("Partition Min-Max"))
        assertEquals("partition", Explain.stepKind("Partition"))
        assertEquals("statistics", Explain.stepKind("Statistics"))
        assertEquals("primary", Explain.stepKind("PrimaryKey"))
        assertEquals("skip", Explain.stepKind("Skip"))
    }

    @Test
    fun `a PREWHERE filter is shown without table prefixes and literal types`() {
        assertEquals(
            "and(equals(country, 'DE'), greaterOrEquals(event_time, _CAST(1794980342, 'DateTime')))",
            Explain.readable("and(equals(__table1.country, 'DE'_String), greaterOrEquals(__table1.event_time, _CAST(1794980342_DateTime, 'DateTime'_String)))"),
        )
        assertEquals("equals(user_id, 42)", Explain.readable("equals(__table1.user_id, 42_UInt8)"))
        assertEquals("less(price, 9.5)", Explain.readable("less(__table2.price, 9.5_Decimal(9, 2))"))
    }

    // What ClickHouse 25.8 printed for EXPLAIN json = 1, indexes = 1, actions = 1 of
    // SELECT count() FROM analytics.events AS e INNER JOIN analytics.users AS u ON e.user_id = u.user_id
    // WHERE e.event_type = 'event_0' AND e.event_time >= toDateTime('2026-11-01 00:00:00') AND e.user_id = 42
    private val plan = checkNotNull(javaClass.getResource("/explain/plan-join.json")).readText()

    @Test
    fun `the index steps of the reads of one table are taken from the plan`() {
        val explain = Explain.parse(plan, table = "analytics.events")
        assertEquals(listOf("minmax", "partition", "primary"), explain.steps.map { it.kind })
        assertEquals(listOf("event_time", "toYYYYMMDD(event_time)", "user_id, event_time"), explain.steps.map { it.name })
        val primary = explain.steps.last()
        assertEquals("and((user_id in [42, 42]), (event_time in [1793491200, +Inf)))", primary.condition)
        assertEquals(listOf(38L, 30L, 129L, 30L), listOf(primary.partsBefore, primary.partsAfter, primary.granulesBefore, primary.granulesAfter))
        assertEquals("binary search", primary.searchAlgorithm)
        // The first step starts from everything, the last one is what gets read.
        assertEquals(841, explain.granulesTotal)
        assertEquals(30, explain.granulesRead)
        assertEquals(30, explain.partsRead)
        assertEquals("Default", explain.readType)
        assertEquals("and(equals(event_type, 'event_0'), greaterOrEquals(event_time, _CAST(1793491200, 'DateTime')), equals(user_id, 42))", explain.prewhere)
        assertNull(explain.projection)
        assertNull(explain.error)
    }

    @Test
    fun `without a table every read of the plan counts`() {
        val explain = Explain.parse(plan)
        assertEquals(4, explain.steps.size)
        assertEquals(841 + 24L, explain.granulesTotal)
        assertEquals(30 + 1L, explain.granulesRead)
        assertEquals(listOf("user_id"), Explain.parse(plan, table = "analytics.users").steps.map { it.name })
    }

    @Test
    fun `a table the plan does not read has no steps and no totals`() {
        val explain = Explain.parse(plan, table = "analytics.page_views")
        assertTrue(explain.steps.isEmpty())
        assertNull(explain.granulesTotal)
        assertNull(explain.granulesRead)
    }

    @Test
    fun `the read streams come from the pipeline text`() {
        val pipeline = "(Expression)\nExpressionTransform × 12\n  (ReadFromMergeTree)\n  MergeTreeSelect(pool: ReadPool, algorithm: Thread) × 12 0 → 1"
        assertEquals(12, Explain.parse(plan, pipeline = pipeline).streams)
        assertEquals(1, Explain.parse(plan, pipeline = "(ReadFromMergeTree)\nMergeTreeSelect(pool: ReadPool, algorithm: Thread) 0 → 1").streams)
        assertNull(Explain.parse(plan, pipeline = "(ReadFromStorage)\nSourceFromSingleChunk 0 → 1").streams)
        assertNull(Explain.parse(plan).streams)
    }

    @Test
    fun `a read answered from a projection counts for its table and names the projection`() {
        val fromProjection = plan.replace("\"Description\": \"analytics.events\"", "\"Description\": \"by_type\"")
        assertTrue(fromProjection != plan)
        assertTrue(Explain.parse(fromProjection, table = "analytics.events").steps.isEmpty())
        val explain = Explain.parse(fromProjection, table = "analytics.events", projections = setOf("by_type"))
        assertEquals("by_type", explain.projection)
        assertEquals(3, explain.steps.size)
    }

    @Test
    fun `the plan is asked for with the session's database, and the pipeline only when wanted`() {
        val ch = FakeClickHouse().answer("EXPLAIN json", mapOf("explain" to plan)).answer("EXPLAIN PIPELINE", mapOf("explain" to "MergeTreeSelect × 4"))
        val explain = Explain.of(ch, "SELECT 1 FROM events;", "analytics.events", settings = mapOf("max_execution_time" to "30"), database = "analytics")
        assertEquals(listOf("EXPLAIN json = 1, indexes = 1, actions = 1 SELECT 1 FROM events", "EXPLAIN PIPELINE SELECT 1 FROM events"), ch.sent.map { it.sql })
        assertTrue(ch.sent.all { it.database == "analytics" && it.settings == mapOf("max_execution_time" to "30") })
        assertEquals(4, explain.streams)

        val planOnly = FakeClickHouse().answer("EXPLAIN json", mapOf("explain" to plan))
        assertNull(Explain.of(planOnly, "SELECT 1", pipeline = false).pipeline)
        assertEquals(1, planOnly.sent.size)
    }

    @Test
    fun `a query the server refuses to plan gives the reason instead of a plan`() {
        val explain = Explain.of(FakeClickHouse().refuse("EXPLAIN", "Unknown table"), "SELECT 1 FROM nope")
        assertEquals("Unknown table", explain.error)
        assertTrue(explain.steps.isEmpty())
    }

    @Test
    fun `only the settings the server knows are sent`() {
        val ch = FakeClickHouse().answer("FROM system.settings", mapOf("name" to "use_query_cache"))
        assertEquals(mapOf("use_query_cache" to "0"), Explain.uncached(ch))
        assertEquals(emptyMap(), Explain.planned(FakeClickHouse().refuse("system.settings")))
    }
}
