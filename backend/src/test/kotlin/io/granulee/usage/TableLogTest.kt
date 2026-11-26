package io.granulee.usage

import io.granulee.clickhouse.FakeClickHouse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableLogTest {
    @Test
    fun `a query shape carries the hash it can be found again by`() {
        val ch = FakeClickHouse().answer(
            "GROUP BY normalized_query_hash",
            mapOf(
                "q" to "SELECT count() FROM analytics.events WHERE user_id = ?", "h" to "12297829382473034410", "c" to 30L, "median_ms" to 4.0, "p95_ms" to 9.0,
                "total_ms" to 120L, "avg_rows" to 1000L, "avg_bytes" to 8000L, "avg_result" to 1L, "last_seen" to 1_795_000_000L,
            ),
        )
        val shape = TableLog(ch, 30, listOf("analytics.events")).shapes(selects = 60).single()
        // Larger than a JSON number holds exactly, so it travels as text.
        assertEquals("12297829382473034410", shape.hash)
        assertEquals(0.5, shape.share)
    }

    @Test
    fun `the latest run of a shape is looked up by its hash, among the table's own SELECTs`() {
        val ch = FakeClickHouse().answer(
            "normalized_query_hash = 12297829382473034410",
            mapOf("q" to "SELECT count() FROM events_buf WHERE user_id = 7", "d" to "analytics"),
        )
        val log = TableLog(ch, 30, listOf("analytics.events", "analytics.events_buf"))
        assertEquals("SELECT count() FROM events_buf WHERE user_id = 7" to "analytics", log.example("12297829382473034410"))
        val sql = ch.sent.single().sql
        assertTrue("hasAny(tables, ['analytics.events', 'analytics.events_buf'])" in sql, sql)
        assertTrue("query_kind = 'Select'" in sql && "ORDER BY event_time DESC" in sql && "LIMIT 1" in sql, sql)
        // Nothing in the window any more.
        assertNull(TableLog(FakeClickHouse(), 30, listOf("analytics.events")).example("1"))
    }
}
