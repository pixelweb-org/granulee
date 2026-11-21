package io.granulee.explain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reports under resources/analyze are what ClickHouse 26.9 printed for EXPLAIN ANALYZE indexes = 0. */
class ExplainAnalyzeTest {
    private fun report(name: String): QueryAnalyze =
        ExplainAnalyze.parse(checkNotNull(javaClass.getResource("/analyze/$name.txt")) { "missing fixture $name" }.readText())

    private fun near(expected: Double, actual: Double?) = assertTrue(actual != null && kotlin.math.abs(actual - expected) <= expected * 1e-9 + 1e-12, "expected $expected, got $actual")

    @Test
    fun `the summary gives planning, execution, rows read and memory`() {
        val r = report("aggregate")
        near(7.60, r.planningMs)
        near(2.62, r.executionMs)
        near(507_760.0, r.readRows)
        near(7.01 * 1024 * 1024, r.peakMemoryBytes)
        assertNull(r.error)
    }

    @Test
    fun `every step has its rows, time and threads, the stages of a step added up`() {
        val r = report("aggregate")
        assertEquals(
            listOf("Expression (Project names)", "Limit (preliminary LIMIT)", "Sorting (Sorting for ORDER BY)", "Expression ((Before ORDER BY + Projection))", "Aggregating"),
            r.steps.take(5).map { it.name },
        )
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), r.steps.map { it.depth })

        val limit = r.steps[1]
        near(5.0, limit.rowsIn)
        assertNull(limit.timeMs)

        val aggregating = r.steps[4]
        near(13_470.0, aggregating.rowsIn)
        near(10.0, aggregating.rowsOut)
        near(0.38663 + 0.03796, aggregating.timeMs)
        near(0.148 + 0.015, aggregating.timeShare)
        near(1.71, aggregating.threads)
        assertEquals(12, aggregating.maxThreads)

        val read = r.steps.last()
        assertEquals("ReadFromMergeTree (analytics.events)", read.name)
        near(0.0, read.rowsIn)
        near(13_470.0, read.rowsOut)
        near(1.89, read.timeMs)
        near(0.723, read.timeShare)
    }

    @Test
    fun `both sides of a join are steps under it`() {
        val r = report("join")
        val join = assertNotNull(r.steps.firstOrNull { it.name.startsWith("Join") })
        near(499_400.0, join.rowsIn)
        near(299_400.0, join.rowsOut)
        near(3.01 + 2.53, join.timeMs)
        near(6.24, join.threads)
        val reads = r.steps.filter { it.name.startsWith("ReadFromMergeTree") }
        assertEquals(listOf("ReadFromMergeTree (analytics.events)", "ReadFromMergeTree (analytics.users)"), reads.map { it.name })
        assertTrue(reads.all { it.depth > join.depth })
        // The join's own lines ("e[…] ⋈ u[…]", "Cost: …") are no steps.
        assertTrue(r.steps.none { it.name.contains("⋈") || it.name.startsWith("Cost") })
    }

    @Test
    fun `a text that is no report gives nothing and does not throw`() {
        val r = ExplainAnalyze.parse("Code: 62. DB::Exception: Syntax error")
        assertNull(r.planningMs)
        assertTrue(r.steps.isEmpty())
    }
}
