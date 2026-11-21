package io.granulee.trial

import io.granulee.facts.ColumnFacts
import io.granulee.facts.IndexFacts
import io.granulee.sql.Sql
import io.granulee.sql.Types
import io.granulee.sql.indexExpression
import io.granulee.sql.indexSql
import io.granulee.sql.normalizeKey
import io.granulee.sql.sameText

/** What a trial reads about the production table right before it starts. */
internal data class LiveTable(
    val engineFull: String,
    val partitionKey: String,
    val sortingKey: String,
    val primaryKey: String,
    val indexGranularity: String?,
    val rows: Long,
    val compressedBytes: Long,
    val columns: List<ColumnFacts>,
    val indexes: List<IndexFacts>,
    val projections: List<ProjectionFacts> = emptyList(),
)

/** A projection as a rebuild has to recreate it. `query` is empty where the server does not tell its definition. */
internal data class ProjectionFacts(val name: String, val query: String, val settings: Map<String, String> = emptyMap())

/** What the candidate does to one column. `type` and `codec` are null where it keeps what production has. */
internal data class ColumnDiff(val column: ColumnFacts, val type: String?, val codec: String?)

/**
 * The columns of a candidate: the production columns with the types and codecs of a trial's request on top.
 * Without a request they are the production columns as they are, which is the baseline.
 */
internal class CandidateColumns(request: TrialRequest?) {
    private val overrides = request?.columns?.associateBy { it.name }.orEmpty()

    /** The type the request names for the column, if it names one. */
    fun requestedType(c: ColumnFacts): String? = overrides[c.name]?.type?.ifBlank { null }

    fun type(c: ColumnFacts): String = requestedType(c) ?: c.type

    /** The codec without "CODEC(…)"; "" is the server's default. */
    fun codec(c: ColumnFacts): String = overrides[c.name]?.codec?.trim() ?: Types.codecBody(c.codec)

    /** Null when the column stays as it is. */
    fun diff(c: ColumnFacts): ColumnDiff? {
        val type = type(c).takeIf { !sameText(it, c.type) }
        val codec = codec(c).takeIf { !sameText(it, Types.codecBody(c.codec)) }
        return if (type == null && codec == null) null else ColumnDiff(c, type, codec)
    }
}

/** CREATE TABLE with the production columns, and the request's changes if given. */
internal fun createSql(
    target: String, live: LiveTable, request: TrialRequest?, engine: String, tail: String, withProjections: Boolean = false,
): String {
    val candidate = CandidateColumns(request)
    val columns = live.columns.map { c ->
        val codec = candidate.codec(c)
        buildString {
            append("    ${Sql.id(c.name)} ${candidate.type(c)}")
            if (c.defaultKind.isNotEmpty()) append(" ${c.defaultKind} ${c.defaultExpression}")
            if (codec.isNotEmpty() && c.defaultKind != "ALIAS") append(" CODEC($codec)")
        }
    }
    val added = request?.addIndexes.orEmpty().map { indexSql(it) }.map { "    $it" }
    val indexes = live.indexes.filter { request == null || it.name !in request.dropIndexes }
        .map { "    INDEX ${Sql.id(it.name)} ${indexExpression(it.expression)} TYPE ${it.type} GRANULARITY ${it.granularity}" }
    val projections = if (withProjections) live.projections.filter { it.query.isNotBlank() }.map { "    ${projectionSql(it)}" } else emptyList()
    val partitionKey = request?.partitionKey ?: live.partitionKey
    val sortingKey = normalizeKey(request?.sortingKey ?: live.sortingKey)
    return buildString {
        append("CREATE TABLE $target\n(\n")
        append((columns + indexes + added + projections).joinToString(",\n"))
        append("\n)\nENGINE = $engine\n")
        if (partitionKey.isNotBlank()) append("PARTITION BY $partitionKey\n")
        if (live.primaryKey.isNotBlank() && !sameText(live.primaryKey, live.sortingKey) && sameText(sortingKey, normalizeKey(live.sortingKey))) {
            append("PRIMARY KEY (${live.primaryKey})\n")
        }
        append("ORDER BY (${sortingKey.ifBlank { "tuple()" }})")
        if (tail.isNotBlank()) append("\n$tail")
    }
}

/** PROJECTION … as it stands in CREATE TABLE. */
internal fun projectionSql(projection: ProjectionFacts): String {
    val settings = projection.settings.entries.joinToString(", ") { "${it.key} = ${it.value}" }
    return "PROJECTION ${Sql.id(projection.name)} (${projection.query.trim()})" + if (settings.isEmpty()) "" else " WITH SETTINGS ($settings)"
}

/** Wide parts only, because compact parts keep all columns in one file and per-column sizes would read as zero. */
internal const val WIDE_PARTS = "min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0"

// A compact part is a handful of files whatever its columns are. Tables with JSON columns are copied into compact
// parts, which are cheap to write and to merge in the background, and become one wide part in the final merge.
private const val COMPACT_PARTS = "min_bytes_for_wide_part = 1099511627776, min_rows_for_wide_part = 0"

/** The SETTINGS of every table a trial builds: the part layout, and the production table's index granularity. */
internal fun trialSettings(live: LiveTable, compactFirst: Boolean = false): String =
    "SETTINGS " + (if (compactFirst) COMPACT_PARTS else WIDE_PARTS) + (live.indexGranularity?.let { ", index_granularity = $it" } ?: "")

/** A trial table: plain MergeTree, whatever the production engine is. */
internal fun trialSql(target: String, live: LiveTable, request: TrialRequest?, compactFirst: Boolean = false): String =
    createSql(target, live, request, "MergeTree", trialSettings(live, compactFirst))

/** How a copied column is read when the candidate gives it another type. */
internal fun copyExpression(c: ColumnFacts, newType: String?): String = when {
    newType == null || sameText(newType, c.type) -> Sql.id(c.name)
    Types.isNullable(c.type) && !Types.isNullable(newType) ->
        "CAST(ifNull(${Sql.id(c.name)}, defaultValueOfTypeName(${Sql.lit(Types.base(c.type))})) AS $newType) AS ${Sql.id(c.name)}"
    else -> "CAST(${Sql.id(c.name)} AS $newType) AS ${Sql.id(c.name)}"
}
