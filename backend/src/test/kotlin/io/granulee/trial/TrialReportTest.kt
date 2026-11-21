package io.granulee.trial

import io.granulee.facts.ColumnFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrialReportTest {
    private fun column(name: String, type: String, codec: String = "", kind: String = "") = ColumnFacts(name, type, codec, 0, 0, kind, "", false, false, null)

    private val live = LiveTable(
        engineFull = "MergeTree ORDER BY id", partitionKey = "", sortingKey = "id", primaryKey = "id", indexGranularity = null,
        rows = 10_000_000, compressedBytes = 8_000_000_000,
        columns = listOf(column("id", "UInt64"), column("url", "String", "CODEC(ZSTD(1))"), column("day", "Date", kind = "ALIAS")),
        indexes = emptyList(),
    )
    private val request = TrialRequest(columns = listOf(TrialColumn("id", "UInt64", ""), TrialColumn("url", "String", "ZSTD(3)")), sortingKey = "id")

    private fun sizes(compressed: Long, url: Long, indexBytes: Long = 0) = TableSizes(
        rows = 100_000, compressed = compressed, uncompressed = 4000, onDisk = compressed + 50, parts = 1, primaryKeyBytes = 64,
        columns = mapOf("id" to ColumnSize(200, 800), "url" to ColumnSize(url, 3200)), indexBytes = indexBytes,
    )

    private fun query(label: String, baseMs: Double?, candMs: Double?, share: Double = 0.1, plainMs: Double? = null, runs: Both<List<Double>>? = null) = TrialQueryResult(
        label, "SELECT 1", share, baseMs, candMs, 1000, 500, if (baseMs == null) "failed" else null,
        baseRuns = runs?.base, candRuns = runs?.cand, plainMs = plainMs, plainReadRows = plainMs?.let { 2000 },
    )

    private fun measured(
        queries: List<TrialQueryResult> = emptyList(), plain: TableSizes? = null, baseInsertMs: Double? = null, mergeMs: Both<Double?> = Both(null, null),
        cand: TableSizes = sizes(800, 600),
    ) = TrialMeasurements(
        live = live, sizes = Both(sizes(1000, 800), cand), plainSizes = plain, scanMs = mapOf("url" to Both(12.0, 9.0)), queries = queries,
        baseDdl = "CREATE base", candDdl = "CREATE cand", alterDdl = "ALTER", plainDdl = if (plain == null) "" else "CREATE plain",
        baseInsertMs = baseInsertMs, candInsertMs = 500.0, mergeMs = mergeMs, plainMergeMs = plain?.let { 30.0 }, codecs = emptyList(), copiedRows = 100_000,
    )

    private fun metric(result: TrialResult, key: String) = result.metrics.first { it.key == key }

    @Test
    fun `sizes are projected from the sample onto the whole table`() {
        val result = TrialReport.result(request, measured())
        assertEquals(100_000, result.rows)
        assertEquals(10_000_000, result.totalRows)
        assertEquals(8_000_000_000, result.projectedBaseBytes)
        assertEquals(6_400_000_000, result.projectedCandBytes)
        assertEquals(listOf("CREATE base", "CREATE cand", "ALTER", ""), listOf(result.baseDdl, result.candDdl, result.alterDdl, result.plainDdl))
        assertEquals("The candidate is 20.0% smaller.", result.verdict)
    }

    @Test
    fun `without replayed queries, inserts and a merge only sizes are measured`() {
        val result = TrialReport.result(request, measured())
        assertEquals(listOf("size", "ratio", "parts", "pk"), result.metrics.map { it.key })
        assertEquals(listOf(1000.0, 800.0), listOf(metric(result, "size").baseline, metric(result, "size").candidate))
        assertEquals(listOf(4.0, 5.0), listOf(metric(result, "ratio").baseline, metric(result, "ratio").candidate))
        assertEquals("Parts after full merge", metric(result, "parts").label)
        assertEquals("Active parts", metric(TrialReport.result(request.copy(optimizeFinal = false), measured()), "parts").label)
        assertTrue(result.metrics.all { it.plain == null })
    }

    @Test
    fun `every measure that was taken becomes a metric, in a fixed order`() {
        val queries = listOf(query("Q1", 10.0, 5.0, plainMs = 20.0), query("Q2", 30.0, 15.0), query("Q3", null, null))
        val result = TrialReport.result(
            request,
            measured(queries, plain = sizes(2000, 1800), baseInsertMs = 250.0, mergeMs = Both(40.0, 60.0), cand = sizes(800, 600, indexBytes = 77)),
        )
        assertEquals(listOf("size", "ratio", "query", "read", "parts", "insert", "merge", "indexes", "pk"), result.metrics.map { it.key })
        val median = metric(result, "query")
        assertEquals("Median query, 2 replayed", median.label)
        assertEquals(listOf(20.0, 10.0, 20.0), listOf(median.baseline, median.candidate, median.plain))
        assertEquals(listOf(1000.0, 500.0, 2000.0), metric(result, "read").let { listOf(it.baseline, it.candidate, it.plain) })
        assertEquals(listOf(400_000.0, 200_000.0), metric(result, "insert").let { listOf(it.baseline, it.candidate) })
        assertEquals(listOf(40.0, 60.0, 30.0), metric(result, "merge").let { listOf(it.baseline, it.candidate, it.plain) })
        assertEquals(listOf(0.0, 77.0), metric(result, "indexes").let { listOf(it.baseline, it.candidate) })
        assertEquals(2000.0, metric(result, "size").plain)
        assertEquals("CREATE plain", result.plainDdl)
    }

    @Test
    fun `columns are listed largest first, with the candidate's definition only where it differs`() {
        val result = TrialReport.result(request, measured(plain = sizes(2000, 1800)))
        // The ALIAS column holds no data and is left out.
        assertEquals(listOf("url", "id"), result.columns.map { it.name })
        val url = result.columns.first()
        assertEquals(listOf("String · ZSTD(1)", "String · ZSTD(3)"), listOf(url.baseline, url.candidate))
        assertEquals(listOf(800L, 600L, 3200L, 1800L), listOf(url.baseBytes, url.candBytes, url.baseUncompressedBytes, url.plainBytes))
        assertEquals(listOf(12.0, 9.0), listOf(url.baseScanMs, url.candScanMs))
        val id = result.columns.last()
        assertEquals("UInt64 · LZ4", id.baseline)
        assertNull(id.candidate)
        assertNull(id.baseScanMs)
        assertNull(TrialReport.result(request, measured()).columns.first().plainBytes)
    }

    private fun verdict(sizeFactor: Double, vararg queries: TrialQueryResult) = TrialReport.result(
        request, measured(queries.toList(), cand = sizes((1000 * sizeFactor).toLong(), 600)),
    ).verdict

    @Test
    fun `the verdict words the size`() {
        assertEquals("The candidate is the same size as the baseline.", verdict(0.996))
        assertEquals("The candidate is 25.0% smaller.", verdict(0.75))
        assertEquals("The candidate is 12.5% larger.", verdict(1.125))
    }

    @Test
    fun `the verdict words the speed of the median query`() {
        assertEquals("The candidate is 20.0% smaller and answers the median query about as fast.", verdict(0.8, query("Q1", 100.0, 97.0)))
        assertEquals("The candidate is 20.0% smaller and answers the median query 2.0× faster.", verdict(0.8, query("Q1", 100.0, 50.0)))
        assertTrue(verdict(0.8, query("Q1", 50.0, 100.0)).startsWith("The candidate is 20.0% smaller and answers the median query 2.0× slower."))
    }

    @Test
    fun `query patterns that get slower beyond the noise are named, heaviest first`() {
        val one = verdict(0.8, query("Q1", 10.0, 5.0, share = 0.6), query("Q2", 10.0, 30.0, share = 0.25))
        assertTrue(one.endsWith(" One query pattern gets slower: Q2, 10 ms → 30 ms (25% of the workload)."), one)
        val two = verdict(0.8, query("Q1", 10.0, 20.0, share = 0.1), query("Q2", 10.0, 30.0, share = 0.3))
        assertTrue(two.endsWith(" 2 query patterns get slower: Q2, Q1."), two)
    }

    @Test
    fun `slower by a millisecond, by a tenth, or within the spread of the runs is not called slower`() {
        val steady = "The candidate is 20.0% smaller and answers the median query"
        // 0.9 ms more.
        assertEquals("$steady 1.9× slower.", verdict(0.8, query("Q1", 1.0, 1.9)))
        // 9 % more.
        assertEquals("$steady about as fast.", verdict(0.8, query("Q1", 100.0, 104.0)))
        // The runs of the two tables overlap.
        assertEquals("$steady 2.0× slower.", verdict(0.8, query("Q1", 10.0, 20.0, runs = Both(listOf(8.0, 10.0, 25.0), listOf(12.0, 20.0, 22.0)))))
    }
}
