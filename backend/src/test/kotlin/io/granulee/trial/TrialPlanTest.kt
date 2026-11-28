package io.granulee.trial

import io.granulee.facts.ColumnFacts
import io.granulee.facts.IndexFacts
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.source.SourceRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrialPlanTest {
    private val gib = 1L shl 30
    private val source = SourceRecord("s1", "u1", "local", "http", "localhost", 8123, "u", "", "", "", "daily", 30, trialsEnabled = true, scratchDatabase = "scratch")

    private fun column(name: String, type: String, codec: String = "", kind: String = "", compressed: Long = gib, cardinality: Long? = null) =
        ColumnFacts(name, type, codec, compressed, 8 * compressed, kind, "", false, false, cardinality)

    private val facts = TableFacts(
        "analytics", "events", "MergeTree", "MergeTree ORDER BY (user_id, event_time)", "toYYYYMM(event_time)", "user_id, event_time", "user_id, event_time",
        rows = 4_000_000_000, compressedBytes = 70 * gib, uncompressedBytes = 560 * gib, bytesOnDisk = 71 * gib, activeParts = 40, partitions = 12,
        columns = listOf(
            column("user_id", "UInt64", "CODEC(ZSTD(1))"),
            column("event_type", "String", "CODEC(ZSTD(1))", cardinality = 40),
            column("payload", "String", compressed = 60 * gib),
            column("day", "Date", "CODEC(ZSTD(1))", kind = "ALIAS"),
        ),
        indexes = listOf(IndexFacts("idx_type", "event_type", "set(100)", 4, 1000, 2000)),
        queries = listOf(QueryFacts("q", "q", 5)),
        queryTotal = 5,
    )

    @Test
    fun `the plan starts from the table as scanned, with what the rules would change filled in`() {
        val plan = trialPlan(source, facts, scratchFreeBytes = 500 * gib)
        assertEquals(listOf("s1", "local", "analytics", "events", "scratch"), listOf(plan.sourceId, plan.sourceName, plan.database, plan.table, plan.scratchDatabase))
        assertTrue(plan.trialsEnabled)
        assertEquals(500 * gib, plan.scratchFreeBytes)
        assertEquals(40_000_000, plan.recommendedRows)
        assertEquals(1, plan.replayableQueries)
        assertEquals(listOf("idx_type"), plan.indexes)
        assertEquals(TrialPlanIndex("idx_type", "event_type", "set(100)", 1000), plan.indexDetails.single())
        // ALIAS columns hold no data: nothing to try on them.
        assertEquals(listOf("user_id", "event_type", "payload"), plan.columns.map { it.name })

        val type = plan.columns[1]
        assertEquals(listOf("String", "ZSTD(1)", "LowCardinality(String)", "ZSTD(1)"), listOf(type.baselineType, type.baselineCodec, type.suggestedType, type.suggestedCodec))
        assertEquals(listOf("String", "LowCardinality(String)"), type.typeOptions)

        val codec = plan.columns[2]
        assertEquals(listOf("LZ4 (default)", "String", "ZSTD(1)"), listOf(codec.baselineCodec, codec.suggestedType, codec.suggestedCodec))
        assertEquals(listOf("", "LZ4", "ZSTD(1)", "ZSTD(3)", "ZSTD(6)"), codec.codecOptions)
        assertEquals(listOf("type:event_type", "codec:payload"), plan.recommendations.map { it.id }.sorted().reversed())
    }

    @Test
    fun `a trial is recommended a hundredth of the table, within bounds and never more than there is`() {
        assertEquals(10_000_000, recommendedRows(50_000_000))
        assertEquals(40_000_000, recommendedRows(4_000_000_000))
        assertEquals(100_000_000, recommendedRows(50_000_000_000))
        assertEquals(300_000, recommendedRows(300_000))
        assertEquals(0, recommendedRows(0))
    }

    @Test
    fun `the form offers narrower types and the codecs made for a type`() {
        assertEquals(listOf("String", "LowCardinality(String)"), typeAlternatives("Nullable(String)"))
        assertEquals(listOf("Int32", "UInt32"), typeAlternatives("Int64"))
        assertEquals(listOf("DateTime"), typeAlternatives("DateTime64(3)"))
        assertEquals(emptyList(), typeAlternatives("UUID"))
        assertEquals(listOf("Delta, ZSTD(1)", "DoubleDelta, ZSTD(1)"), codecAlternatives("DateTime"))
        assertEquals(listOf("T64, ZSTD(1)", "Delta, ZSTD(1)"), codecAlternatives("Nullable(UInt32)"))
        assertEquals(listOf("Gorilla, ZSTD(1)"), codecAlternatives("Float64"))
        assertEquals(emptyList(), codecAlternatives("String"))
    }
}

class SamplingTest {
    private val request = TrialRequest(rows = 1000)
    private val none = { error("the partitions are not asked for") }

    @Test
    fun `an even sample takes every n-th row of each part`() {
        assertEquals(" WHERE _part_offset % 6 = 0", sampleFilter(request, tableRows = 6000, none))
        // Less than twice the wanted rows: the LIMIT alone does it.
        assertEquals("", sampleFilter(request, tableRows = 1999, none))
        assertEquals("", sampleFilter(request.copy(rows = 0), tableRows = 6000, none))
    }

    @Test
    fun `a trial on the whole table copies without a filter and without a limit`() {
        val all = request.copy(sampling = "all")
        // Whatever the row count says: it is from when the trial was set up, and the table may have grown.
        assertEquals("", sampleFilter(all.copy(rows = 10), tableRows = 6000) { error("partitions are not asked for") })
        assertEquals("", sampleLimit(all))
        assertEquals(" LIMIT ${request.rows}", sampleLimit(request))
    }

    @Test
    fun `a WHERE sample is the person's own condition, in parentheses`() {
        assertEquals(" WHERE (a = 1 OR b = 2)", sampleFilter(request.copy(sampling = "where", where = "a = 1 OR b = 2"), 6000, none))
    }

    @Test
    fun `a recent sample takes the newest partitions until they hold the wanted rows`() {
        val partitions = listOf(PartitionRows("202611", 400), PartitionRows("202610", 700), PartitionRows("202609", 900))
        val recent = request.copy(sampling = "recent")
        assertEquals(" WHERE _partition_id IN ('202611', '202610')", sampleFilter(recent, 6000) { partitions })
        assertEquals(" WHERE _partition_id IN ('202611')", sampleFilter(recent.copy(rows = 400), 6000) { partitions })
        assertEquals(" WHERE _partition_id IN ('202611', '202610', '202609')", sampleFilter(recent.copy(rows = 1_000_000), 6000) { partitions })
        // One partition, or none: nothing to choose between.
        assertEquals("", sampleFilter(recent, 6000) { partitions.take(1) })
        assertEquals("", sampleFilter(recent, 6000) { emptyList() })
    }
}

class CandidateColumnsTest {
    private fun column(name: String, type: String, codec: String = "") = ColumnFacts(name, type, codec, 0, 0, "", "", false, false, null)
    private val url = column("url", "String", "CODEC(ZSTD(1))")
    private val country = column("country", "Nullable(String)")

    @Test
    fun `without a request the columns are the production ones`() {
        val baseline = CandidateColumns(null)
        assertEquals(listOf("String", "ZSTD(1)", null), listOf(baseline.type(url), baseline.codec(url), baseline.diff(url)))
    }

    @Test
    fun `a request puts its types and codecs on top`() {
        val candidate = CandidateColumns(
            TrialRequest(columns = listOf(TrialColumn("url", "", " ZSTD(3) "), TrialColumn("country", "LowCardinality(Nullable(String))", ""))),
        )
        // An empty type keeps the production type.
        assertEquals(listOf("String", null, "ZSTD(3)"), listOf(candidate.type(url), candidate.requestedType(url), candidate.codec(url)))
        assertEquals(ColumnDiff(url, type = null, codec = "ZSTD(3)"), candidate.diff(url))
        assertEquals(ColumnDiff(country, type = "LowCardinality(Nullable(String))", codec = null), candidate.diff(country))
    }

    @Test
    fun `a request that only spells a column differently changes nothing`() {
        val candidate = CandidateColumns(TrialRequest(columns = listOf(TrialColumn("url", "String", "ZSTD( 1 )"), TrialColumn("country", " Nullable( String ) ", ""))))
        assertEquals(null, candidate.diff(url))
        assertEquals(null, candidate.diff(country))
    }

    @Test
    fun `a codec that goes back to the default is a change to no codec`() {
        assertEquals(ColumnDiff(url, null, ""), CandidateColumns(TrialRequest(columns = listOf(TrialColumn("url", "String", "")))).diff(url))
    }

    @Test
    fun `a copied column is cast where its type changes, and NULLs become the type's default where it stops being Nullable`() {
        assertEquals("`url`", copyExpression(url, null))
        assertEquals("`url`", copyExpression(url, " String "))
        assertEquals("CAST(`url` AS LowCardinality(String)) AS `url`", copyExpression(url, "LowCardinality(String)"))
        assertEquals("CAST(ifNull(`country`, defaultValueOfTypeName('String')) AS String) AS `country`", copyExpression(country, "String"))
    }
}
