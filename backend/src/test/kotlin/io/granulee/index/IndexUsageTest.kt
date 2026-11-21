package io.granulee.index

import io.granulee.explain.IndexStep
import io.granulee.explain.QueryExplain
import io.granulee.facts.IndexFacts
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.sql.TrialIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexUsageTest {
    private val facts = TableFacts(
        "analytics", "events", "MergeTree", "", partitionKey = "toYYYYMM(ts)", sortingKey = "id, ts", primaryKey = "", rows = 0, compressedBytes = 0,
        uncompressedBytes = 0, bytesOnDisk = 0, activeParts = 0, partitions = 0, columns = emptyList(),
        indexes = listOf(IndexFacts("idx_country", "country", "set(100)", 4, 1000, 2000), IndexFacts("idx_unused", "url", "bloom_filter", 1, 500, 900)),
        queryTotal = 100,
    )

    private fun shape(name: String, count: Long) = QueryFacts("SELECT $name", "SELECT $name ?", count)

    private fun step(kind: String, name: String, before: Long, after: Long, condition: String? = "x = 1", algorithm: String? = null) =
        IndexStep(kind, name, condition, 10, 5, before, after, algorithm)

    private val byKey = PlannedShape(shape("a", 50), QueryExplain(steps = listOf(step("partition", "toYYYYMM(ts)", 800, 400), step("primary", "id, ts", 400, 40, algorithm = "binary search"))))
    private val byIndex = PlannedShape(
        shape("b", 30),
        QueryExplain(steps = listOf(step("minmax", "ts", 800, 800, condition = "true"), step("primary", "id, ts", 800, 600, algorithm = "generic exclusion search"), step("skip", "idx_country", 600, 60))),
    )
    private val fromProjection = PlannedShape(shape("c", 15), QueryExplain(steps = listOf(step("primary", "country", 20, 2)), projection = "by_country"))
    private val failed = PlannedShape(shape("d", 5), QueryExplain(error = "Unknown table"))
    private val planned = listOf(byKey, byIndex, fromProjection, failed)

    private val usage = IndexUsageReport.of(
        facts, planned, projections = setOf("by_country", "by_day"), projectionReads = mapOf("by_day" to listOf(shape("a", 7))),
        proposals = listOf(TrialIndex("idx_url", "`url`", "bloom_filter(0.01)", 4)), whatIf = true,
    )

    @Test
    fun `the shapes are counted, the failed ones too`() {
        assertEquals(listOf(4, 1), listOf(usage.checkedShapes, usage.failedShapes))
        assertEquals(100, usage.selects)
        assertNull(usage.message)
        assertEquals(listOf("SELECT a", "SELECT b", "SELECT c", "SELECT d"), usage.queries.map { it.query })
        assertEquals(listOf(0.5, 0.3, 0.15, 0.05), usage.queries.map { it.share })
        assertTrue(usage.whatIf)
        assertEquals(listOf("idx_url"), usage.proposals.map { it.name })
    }

    @Test
    fun `a skip index counts the granules it was handed and left, over the shapes that use it`() {
        val (country, unused) = usage.indexes
        assertEquals(listOf(1, 0.3, 600L, 60L, listOf(1)), listOf(country.shapes, country.share, country.granulesBefore, country.granulesAfter, country.queries))
        assertEquals(listOf(0, 0.0, 0L, 0L, emptyList<Int>()), listOf(unused.shapes, unused.share, unused.granulesBefore, unused.granulesAfter, unused.queries))
        assertEquals(500, unused.compressedBytes)
    }

    @Test
    fun `the keys count for reads of the table itself, and a step that filters nothing does not count`() {
        val (primary, partition) = usage.keys
        // Without a primary key of its own the sorting key is the primary key. The read from the projection is left out.
        assertEquals(listOf("primary", "id, ts", 2, 0.8), listOf(primary.kind, primary.expression, primary.shapes, primary.share))
        assertEquals(listOf(0.5, 0.3), listOf(primary.binaryShare, primary.genericShare))
        assertEquals(listOf(1200L, 640L, listOf(0, 1)), listOf(primary.granulesBefore, primary.granulesAfter, primary.queries))
        // The minmax step of the second shape has the condition "true": it narrows nothing.
        assertEquals(listOf("partition", "toYYYYMM(ts)", 1, 0.5, listOf(0)), listOf(partition.kind, partition.expression, partition.shapes, partition.share, partition.queries))
        assertNull(partition.binaryShare)
    }

    @Test
    fun `a table without a partition key has no partition usage`() {
        assertEquals(listOf("primary"), IndexUsageReport.of(facts.copy(partitionKey = ""), planned, emptySet(), emptyMap(), emptyList(), false).keys.map { it.kind })
    }

    @Test
    fun `a projection is used by the shapes planned onto it and by those the log says read it`() {
        assertEquals(listOf(ProjectionUsage("by_country", listOf(2)), ProjectionUsage("by_day", listOf(0))), usage.projections)
    }

    @Test
    fun `each shape is planned once, most frequent first, the ones that read a projection included`() {
        val twice = shape("a", 50)
        val shapes = IndexUsageReport.shapesToPlan(listOf(shape("b", 30), twice), mapOf("p" to listOf(shape("a", 9), shape("z", 40))))
        assertEquals(listOf("SELECT a" to 50L, "SELECT z" to 40L, "SELECT b" to 30L), shapes.map { it.query to it.count })
    }

    @Test
    fun `without logged queries the indexes are listed with nothing counted, and why`() {
        val none = IndexUsageReport.none(facts, "No SELECT on this table in the last 30 days of query_log.")
        assertEquals("No SELECT on this table in the last 30 days of query_log.", none.message)
        assertEquals(listOf("idx_country", "idx_unused"), none.indexes.map { it.name })
        assertTrue(none.indexes.all { it.shapes == 0 } && none.queries.isEmpty() && !none.whatIf)
    }
}

class WhatIfSummaryTest {
    private val candidates = listOf(TrialIndex("h_dur", "duration_ms", "minmax", 2), TrialIndex("h_type", "event_type", "set(10)", 4), TrialIndex("h_bad", "nope", "minmax", 1))
    private val refused = mapOf("h_dur" to null, "h_type" to null, "h_bad" to "Missing columns: 'nope'")

    private fun shape(name: String, count: Long) = QueryFacts("SELECT $name", "SELECT $name ?", count)

    private val answers = listOf(
        WhatIfAnswer(
            shape("a", 60),
            Result.success(
                WhatIfReport(
                    parts = 4, granules = 367,
                    estimates = listOf(WhatIfEstimate("h_dur", "applicable", granulesAfter = 184, source = "empirical", sampled = true), WhatIfEstimate("h_type", "not_applicable", reason = "Index cannot filter this predicate")),
                ),
            ),
        ),
        WhatIfAnswer(shape("b", 30), Result.success(WhatIfReport(parts = 2, granules = 100, estimates = listOf(WhatIfEstimate("h_dur", "applicable", granulesAfter = 10, source = "statistical"))))),
        WhatIfAnswer(shape("c", 10), Result.failure(IllegalStateException("Timeout exceeded"))),
    )
    private val summary = WhatIfSummary.of(candidates, refused, answers, queryTotal = 200)

    @Test
    fun `every shape is listed with what it reads today, or why it could not be asked`() {
        assertEquals(listOf(3, 1), listOf(summary.checkedShapes, summary.failedShapes))
        assertEquals(100, summary.selects)
        assertNull(summary.message)
        assertEquals(listOf(367L, 100L, null), summary.shapes.map { it.granules })
        assertEquals(listOf(null, null, "Timeout exceeded"), summary.shapes.map { it.error })
        assertEquals(listOf(0.3, 0.15, 0.05), summary.shapes.map { it.share })
    }

    @Test
    fun `a candidate adds up the shapes it can filter`() {
        val dur = summary.candidates[0]
        assertEquals(listOf(2, 0.45, 467L, 194L), listOf(dur.shapes, dur.share, dur.granulesBefore, dur.granulesAfter))
        assertEquals(listOf(0, 1), dur.uses.map { it.shape })
        assertEquals(listOf(true, false), dur.uses.map { it.sampled })
        assertNull(dur.error)
    }

    @Test
    fun `a candidate that cannot filter a shape is listed with the reason, one the server refused with its error`() {
        val (_, type, bad) = summary.candidates
        assertEquals(listOf(0, 0.0, 0L), listOf(type.shapes, type.share, type.granulesBefore))
        assertEquals(listOf(false to "Index cannot filter this predicate"), type.uses.map { it.applicable to it.reason })
        assertEquals("Missing columns: 'nope'", bad.error)
        assertTrue(bad.uses.isEmpty())
    }

    @Test
    fun `when nothing can be estimated the candidates come back as they were asked, with the reason`() {
        val none = WhatIfSummary.none(candidates, "local has no EXPLAIN WHATIF. A trial measures a new index instead.")
        assertEquals("local has no EXPLAIN WHATIF. A trial measures a new index instead.", none.message)
        assertEquals(listOf("h_dur", "h_type", "h_bad"), none.candidates.map { it.name })
        assertTrue(none.candidates.all { it.error == null && it.uses.isEmpty() } && none.shapes.isEmpty())
        assertEquals("Missing columns: 'nope'", WhatIfSummary.none(candidates, null, refused).candidates.last().error)
    }
}
