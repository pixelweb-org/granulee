package io.granulee.trial

import io.granulee.facts.TableFacts
import io.granulee.facts.isStored
import io.granulee.recommendation.Analyzer
import io.granulee.recommendation.TrialPlanRecommendation
import io.granulee.source.SourceRecord
import io.granulee.sql.Types

data class TrialPlanColumn(
    val name: String,
    val baselineType: String,
    val baselineCodec: String,
    val suggestedType: String,
    val suggestedCodec: String,
    val typeOptions: List<String>,
    val codecOptions: List<String>,
    val compressedBytes: Long = 0,
)

data class TrialPlanIndex(val name: String, val expression: String, val type: String, val compressedBytes: Long)

data class TrialPlan(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val trialsEnabled: Boolean,
    val scratchDatabase: String,
    val scratchFreeBytes: Long?,
    val totalRows: Long,
    val compressedBytes: Long,
    val recommendedRows: Long,
    val partitionKey: String,
    val sortingKey: String,
    val columns: List<TrialPlanColumn>,
    val indexes: List<String>,
    val indexDetails: List<TrialPlanIndex> = emptyList(),
    val recommendations: List<TrialPlanRecommendation>,
    val replayableQueries: Int,
)

/** What the "New trial" form starts from: the table as the last scan saw it, and what the rules would change. */
internal fun trialPlan(source: SourceRecord, facts: TableFacts, scratchFreeBytes: Long?): TrialPlan {
    val findings = Analyzer.analyze(facts, source.queryLogWindowDays).mapNotNull { it.plan }
    val changes = findings.mapNotNull { it.column }.associateBy { it.name }
    val columns = facts.columns.filter { it.isStored() }.map { c ->
        val codec = Types.codecBody(c.codec)
        val change = changes[c.name]
        val suggestedType = change?.type ?: c.type
        val suggestedCodec = change?.codec ?: codec
        TrialPlanColumn(
            name = c.name,
            baselineType = c.type,
            baselineCodec = Types.codecLabel(c.codec),
            suggestedType = suggestedType,
            suggestedCodec = suggestedCodec,
            typeOptions = (listOf(c.type, suggestedType) + typeAlternatives(c.type)).distinct(),
            codecOptions = (listOf("", "LZ4", "ZSTD(1)", "ZSTD(3)", "ZSTD(6)") + codecAlternatives(c.type) + listOf(codec, suggestedCodec)).distinct(),
            compressedBytes = c.compressedBytes,
        )
    }
    return TrialPlan(
        sourceId = source.id,
        sourceName = source.name,
        database = facts.database,
        table = facts.name,
        trialsEnabled = source.trialsEnabled,
        scratchDatabase = source.scratchDatabase,
        scratchFreeBytes = scratchFreeBytes,
        totalRows = facts.rows,
        compressedBytes = facts.compressedBytes,
        recommendedRows = recommendedRows(facts.rows),
        partitionKey = facts.partitionKey,
        sortingKey = facts.sortingKey,
        columns = columns,
        indexes = facts.indexes.map { it.name },
        indexDetails = facts.indexes.map { TrialPlanIndex(it.name, it.expression, it.type, it.compressedBytes) },
        recommendations = findings,
        replayableQueries = facts.queries.size,
    )
}

/** A hundredth of the table, but enough rows to compress like the whole and no more than a trial should copy. */
internal fun recommendedRows(tableRows: Long): Long = (tableRows / 100).coerceIn(10_000_000, 100_000_000).coerceAtMost(tableRows)

/** The types a column could reasonably be narrowed to. */
internal fun typeAlternatives(type: String): List<String> {
    val base = Types.base(type)
    val out = mutableListOf<String>()
    if (Types.isNullable(type) || Types.isLowCardinality(type)) out.add(base)
    when {
        base == "String" -> out.add("LowCardinality(String)")
        base == "Int64" -> out.addAll(listOf("Int32", "UInt32"))
        base == "UInt64" -> out.add("UInt32")
        base == "Int32" -> out.add("Int16")
        base == "UInt32" -> out.add("UInt16")
        base == "Float64" -> out.add("Float32")
        base.startsWith("DateTime64") -> out.add("DateTime")
    }
    return out
}

/** The specialised codecs the form offers for a type, next to the general ones. */
internal fun codecAlternatives(type: String): List<String> = when {
    Types.isTime(type) -> listOf("Delta, ZSTD(1)", "DoubleDelta, ZSTD(1)")
    Types.isInteger(type) -> listOf("T64, ZSTD(1)", "Delta, ZSTD(1)")
    Types.isFloat(type) -> listOf("Gorilla, ZSTD(1)")
    else -> emptyList()
}
