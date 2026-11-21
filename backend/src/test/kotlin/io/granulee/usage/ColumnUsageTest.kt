package io.granulee.usage

import io.granulee.facts.ColumnFacts
import io.granulee.facts.QueryFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColumnUsageTest {
    private fun column(name: String, bytes: Long, key: Boolean = false, kind: String = "") = ColumnFacts(name, "String", "", bytes, bytes * 4, kind, "", key, false, null)
    private fun shape(query: String, count: Long) = UsageQuery(query, count, 0.0, 1.0, 2.0, 3, 4, 5, 6, "2026-11-21T10:00:00Z")

    private val known = listOf(column("id", 100, key = true), column("country", 300), column("payload", 900), column("day", 0, kind = "ALIAS"))
    private val shapes = listOf(shape("SELECT count() FROM t WHERE country = ?", 60), shape("SELECT payload FROM v WHERE x = ?", 20))

    @Test
    fun `columns are listed most read first, each with the share of SELECTs that read and filter on it`() {
        val columns = ColumnUsage.of(known, emptyMap(), shapes, readBy = mapOf("country" to 60, "id" to 80), selects = 100)
        assertEquals(listOf("id", "country", "payload"), columns.map { it.name })
        assertEquals(listOf(0.8, 0.6, 0.0), columns.map { it.readShare })
        // Of the 80 runs of the listed shapes, 60 filter on country by their text.
        assertEquals(listOf(0.0, 0.75, 0.0), columns.map { it.filterShare })
        assertTrue(columns.first().inSortingKey)
    }

    @Test
    fun `ClickHouse's analysis of a shape goes before its text`() {
        // By its text the second shape filters on x; the scan knows it is the view's name for payload.
        val analysed = mapOf(shapes[1].query to QueryFacts("q", shapes[1].query, 20, filters = mapOf("payload" to setOf("eq"))))
        val columns = ColumnUsage.of(known, analysed, shapes, readBy = emptyMap(), selects = 100)
        assertEquals(0.25, columns.first { it.name == "payload" }.filterShare)
    }

    @Test
    fun `without a scan of the table only the columns the log names are known`() {
        val columns = ColumnUsage.of(emptyList(), emptyMap(), shapes, readBy = mapOf("b" to 5, "a" to 9), selects = 10)
        assertEquals(listOf("a" to 0.9, "b" to 0.5), columns.map { it.name to it.readShare })
        assertTrue(columns.all { it.type.isEmpty() && it.compressedBytes == 0L })
    }

    @Test
    fun `a column no SELECT read is unread, unless it is part of the sorting key or there were no SELECTs at all`() {
        val columns = ColumnUsage.of(known, emptyMap(), shapes, readBy = mapOf("country" to 60), selects = 100)
        assertEquals(listOf("payload"), ColumnUsage.unread(columns, selects = 100).map { it.name })
        assertTrue(ColumnUsage.unread(columns, selects = 0).isEmpty())
    }
}
