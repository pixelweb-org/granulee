package io.granulee.recommendation

import io.granulee.Fmt
import io.granulee.facts.ColumnFacts
import io.granulee.facts.IndexFacts
import io.granulee.facts.TableFacts
import io.granulee.facts.filtersOn
import io.granulee.facts.predicatesOn
import io.granulee.sql.Sql
import io.granulee.sql.TrialIndex
import io.granulee.sql.Types
import io.granulee.sql.engineClause
import io.granulee.sql.indexSql
import io.granulee.sql.mentions
import io.granulee.sql.splitTopLevel
import kotlin.math.ceil
import kotlin.math.roundToLong


/**
 * The rules. Each one looks at sizes, types, codecs and keys from system tables, plus the most
 * frequent SELECTs from query_log. Savings are estimates until a trial measures them.
 */
object Analyzer {
    private const val MIB = 1L shl 20
    private const val GIB = 1L shl 30
    private const val MIN_COLUMN_BYTES = 8 * MIB
    private val SEVERITY_ORDER = listOf("high", "medium", "low", "review")

    fun analyze(t: TableFacts, queryLogWindowDays: Int): List<Finding> {
        val out = mutableListOf<Finding>()
        partitioning(t)?.let(out::add)
        val keyFinding = sortingKey(t, queryLogWindowDays)
        keyFinding?.let(out::add)
        out.addAll(newSkipIndexes(t, queryLogWindowDays, keyFinding?.rec?.column))
        t.columns.forEach { column(t, it)?.let(out::add) }
        nullable(t)?.let(out::add)
        t.indexes.forEach { skipIndex(t, it)?.let(out::add) }
        return out.sortedWith(compareBy<Finding> { SEVERITY_ORDER.indexOf(it.rec.severity) }.thenByDescending { it.rec.savingsBytes })
    }

    private fun rebuildSql(t: TableFacts, partitionKey: String, sortingKey: String, note: String): String {
        val name = Sql.table(t.database, t.name)
        val fresh = Sql.table(t.database, t.name + "_new")
        val lines = mutableListOf("-- $note", "CREATE TABLE $fresh AS $name", "ENGINE = ${engineClause(t.engineFull.ifBlank { t.engine })}")
        if (t.engine.startsWith("Replicated")) lines.add(1, "-- Replicated table: give the new table its own ZooKeeper path.")
        if (partitionKey.isNotBlank()) lines.add("PARTITION BY $partitionKey")
        lines.add("ORDER BY (${sortingKey.ifBlank { "tuple()" }});")
        lines.add("")
        lines.add("INSERT INTO $fresh SELECT * FROM $name;")
        lines.add("EXCHANGE TABLES $name AND $fresh;")
        return lines.joinToString("\n")
    }

    private fun partitioning(t: TableFacts): Finding? {
        if (t.partitions < 100 || t.partitionKey.isBlank()) return null
        val average = t.compressedBytes / t.partitions
        if (average >= GIB) return null
        val daily = Regex("^(?:toYYYYMMDD|toDate|toStartOfDay)\\((.+)\\)$").find(t.partitionKey.trim())?.groupValues?.get(1)
            ?: t.columns.firstOrNull { it.name == t.partitionKey.trim() && Types.base(it.type).startsWith("Date") }?.name
        val intro = "${Fmt.count(t.partitions)} partitions average ${Fmt.bytes(average)} and hold ${Fmt.count(t.activeParts)} active parts. " +
            "Partitions this small stop merges from building large parts and multiply the parts every query has to open."
        if (daily != null) {
            val monthly = "toYYYYMM($daily)"
            val expected = ceil(t.partitions / 30.0).toLong().coerceAtLeast(1)
            return Finding(
                Recommendation(
                    id = "partitioning",
                    severity = if (t.partitions >= 300) "high" else "medium",
                    category = "Partitioning",
                    changeKind = "rebuild",
                    title = "Partition by month instead of by day",
                    body = "$intro Monthly partitions keep TTL drops and backfills simple.",
                    effect = "${Fmt.count(t.partitions)} → about ${Fmt.count(expected)} partitions",
                    sql = rebuildSql(t, monthly, t.sortingKey, "The partition key cannot be changed in place: rebuild the table."),
                    savingsBytes = 0,
                    column = null,
                ),
                TrialPlanRecommendation("partitioning", "Partition by month", partitionKey = monthly),
            )
        }
        if (t.partitions < 1000) return null
        return Finding(
            Recommendation(
                id = "partitioning",
                severity = "review",
                category = "Partitioning",
                changeKind = "rebuild",
                title = "Use a coarser partition key",
                body = "$intro ClickHouse works best with at most a few hundred partitions per table.",
                effect = "${Fmt.count(t.partitions)} partitions by ${t.partitionKey}",
                sql = rebuildSql(t, "/* coarser key than ${t.partitionKey} */", t.sortingKey, "Pick a key that yields far fewer partitions, then rebuild."),
                savingsBytes = 0,
                column = null,
            ),
            null,
        )
    }

    private fun sortingKey(t: TableFacts, windowDays: Int): Finding? {
        val total = t.queryTotal ?: return null
        if (total < 20 || t.queries.isEmpty()) return null
        val keyParts = splitTopLevel(t.sortingKey)
        val first = keyParts.firstOrNull() ?: return null
        fun share(column: String): Double =
            t.queries.filter { it.filtersOn(column) }.sumOf { it.count }.toDouble() / total
        val firstColumns = t.columns.filter { mentions(first, it.name) }.map { it.name }
        val firstShare = firstColumns.maxOfOrNull(::share) ?: 0.0
        if (firstShare >= 0.3) return null
        val best = t.columns
            .filter { it.name !in firstColumns && it.defaultKind != "ALIAS" }
            .map { it to share(it.name) }
            .filter { it.second >= 0.5 }
            // Most filtered first; among equals, the column with the fewest distinct values leads best,
            // and a timestamp (usually a range filter) leads worst.
            .sortedWith(
                compareByDescending<Pair<ColumnFacts, Double>> { it.second }
                    .thenBy { Types.isTime(it.first.type) }
                    .thenByDescending { Types.isLowCardinality(it.first.type) }
                    .thenBy { it.first.cardinality ?: Long.MAX_VALUE },
            )
            .firstOrNull()
            ?: return null
        val (column, columnShare) = best
        val rest = keyParts.filter { it != column.name }
        val newKey = (listOf(column.name) + rest).joinToString(", ")
        val evidence = t.queries.filter { it.filtersOn(column.name) }
            .map { QueryEvidence(it.normalized, it.count, it.count.toDouble() / total) }
        return Finding(
            Recommendation(
                id = "sorting-key",
                severity = if (columnShare >= 0.7) "high" else "medium",
                category = "Sorting key",
                changeKind = "rebuild",
                title = "Lead the sorting key with ${column.name}",
                body = "${Fmt.percent(columnShare)} of ${Fmt.count(total)} SELECTs in the last $windowDays days filter on ${column.name}; " +
                    "${Fmt.percent(firstShare)} filter on $first. With $first first, those queries cannot use the primary index " +
                    "to skip granules. Columns with few distinct values first also compress the columns after them better.",
                effect = "(${t.sortingKey}) → ($newKey)",
                sql = rebuildSql(t, t.partitionKey, newKey, "The sorting key cannot be reordered in place: rebuild the table."),
                savingsBytes = 0,
                column = column.name,
                queries = evidence,
            ),
            TrialPlanRecommendation("sorting-key", "Lead sorting key with ${column.name}", sortingKey = newKey),
        )
    }

    /** The rules that look at one column: its type first, and where that is fine, its codec. */
    private fun column(t: TableFacts, c: ColumnFacts): Finding? {
        if (c.compressedBytes < MIN_COLUMN_BYTES || c.defaultKind == "ALIAS") return null
        return lowCardinality(t, c) ?: codec(t, c)
    }

    /** A finding that shrinks one column by an estimated share of its size. */
    private fun columnFinding(
        t: TableFacts, c: ColumnFacts, id: String, category: String, kind: String, title: String, body: String, saved: Double, sql: String,
        plan: TrialPlanRecommendation,
    ): Finding {
        val savings = (c.compressedBytes * saved).roundToLong()
        return Finding(
            Recommendation(
                id = id,
                severity = if (t.compressedBytes > 0 && savings.toDouble() / t.compressedBytes >= 0.02) "medium" else "low",
                category = category,
                changeKind = kind,
                title = title,
                body = body,
                effect = "${Fmt.bytes(c.compressedBytes)} → est. ${Fmt.bytes(c.compressedBytes - savings)} · −${Fmt.bytes(savings)}",
                sql = sql,
                savingsBytes = savings,
                column = c.name,
            ),
            plan,
        )
    }

    private fun lowCardinality(t: TableFacts, c: ColumnFacts): Finding? {
        val cardinality = c.cardinality
        val isKey = c.inSortingKey || c.inPartitionKey
        if (!Types.isString(c.type) || Types.isLowCardinality(c.type) || isKey || cardinality == null || cardinality !in 1..10_000 || t.rows < 100_000) return null
        val newType = if (Types.isNullable(c.type)) "LowCardinality(Nullable(String))" else "LowCardinality(String)"
        return columnFinding(
            t, c, "type:${c.name}", "Data type", "mutation", "Make ${c.name} LowCardinality",
            "About ${Fmt.count(cardinality)} distinct values per part across ${Fmt.rows(t.rows)} rows. LowCardinality stores a " +
                "dictionary plus small integer keys, which shrinks the column and speeds up filtering and GROUP BY on it. " +
                "Changing the type rewrites the column as a mutation.",
            0.6,
            "ALTER TABLE ${Sql.table(t.database, t.name)}\n  MODIFY COLUMN ${Sql.id(c.name)} $newType;",
            TrialPlanRecommendation("type:${c.name}", "${c.name} → LowCardinality", column = ColumnChange(c.name, newType, null)),
        )
    }

    /** A codec for a column on the server's default, with the share of the column it is estimated to save. */
    private class CodecAdvice(val codec: String, val saved: Double, val title: String, val why: String)

    private fun codecAdvice(t: TableFacts, c: ColumnFacts): CodecAdvice? {
        val share = if (t.compressedBytes > 0) c.compressedBytes.toDouble() / t.compressedBytes else 0.0
        return when {
            Types.isString(c.type) && !Types.isLowCardinality(c.type) -> {
                val wide = t.rows > 0 && c.uncompressedBytes / t.rows >= 200
                val codec = if (wide) "ZSTD(3)" else "ZSTD(1)"
                CodecAdvice(codec, if (wide) 0.35 else 0.25, "Compress ${c.name} with $codec", "ZSTD trades a little CPU on insert for a much better ratio on repetitive text.")
            }
            Types.isTime(c.type) && !Types.isLowCardinality(c.type) -> CodecAdvice(
                "Delta, ZSTD(1)", if (c.inSortingKey) 0.5 else 0.25, "Delta-encode ${c.name}",
                "Timestamps rise steadily inside each part, so storing differences before ZSTD shrinks them sharply.",
            )
            Types.isInteger(c.type) && !Types.isLowCardinality(c.type) && share >= 0.02 -> CodecAdvice(
                "T64, ZSTD(1)", 0.3, "Compress ${c.name} with T64 + ZSTD(1)", "T64 strips the unused high bits of integers before ZSTD compresses the rest.",
            )
            share >= 0.05 -> CodecAdvice("ZSTD(1)", 0.15, "Compress ${c.name} with ZSTD(1)", "ZSTD usually beats LZ4 on ratio for a small CPU cost on insert.")
            else -> null
        }
    }

    private fun codec(t: TableFacts, c: ColumnFacts): Finding? {
        if (c.codec.isNotEmpty()) return null
        // Data LZ4 can barely shrink (random ids, hashes) will not shrink much under another codec either.
        if (c.uncompressedBytes < c.compressedBytes * 1.3) return null
        val advice = codecAdvice(t, c) ?: return null
        return columnFinding(
            t, c, "codec:${c.name}", "Codec", "online", advice.title,
            "${Fmt.bytes(c.uncompressedBytes)} of ${c.type} on the default LZ4 codec, ratio ${Fmt.ratio(c.uncompressedBytes, c.compressedBytes)}. ${advice.why} " +
                "New parts use it at once; existing parts as they merge. The saving is an estimate: a trial measures it.",
            advice.saved,
            "ALTER TABLE ${Sql.table(t.database, t.name)}\n  MODIFY COLUMN ${Sql.id(c.name)} ${c.type} CODEC(${advice.codec});",
            TrialPlanRecommendation("codec:${c.name}", "${c.name} → ${advice.codec.replace(", ", " + ")}", column = ColumnChange(c.name, null, advice.codec)),
        )
    }

    private fun nullable(t: TableFacts): Finding? {
        if (t.rows < 1_000_000) return null
        val columns = t.columns.filter {
            it.type.startsWith("Nullable(") && !it.inSortingKey && !it.inPartitionKey && it.defaultKind != "ALIAS"
        }
        if (columns.isEmpty()) return null
        val table = Sql.table(t.database, t.name)
        val names = columns.joinToString(", ") { it.name }
        val checks = columns.joinToString(", ") { "countIf(${Sql.id(it.name)} IS NULL)" }
        val alters = columns.joinToString("\n") {
            "ALTER TABLE $table MODIFY COLUMN ${Sql.id(it.name)} ${Types.base(it.type)};"
        }
        return Finding(
            Recommendation(
                id = "type:nullable",
                severity = "review",
                category = "Data type",
                changeKind = "mutation",
                title = if (columns.size == 1) "Check whether ${columns[0].name} needs Nullable" else "Check ${columns.size} Nullable columns",
                body = "$names ${if (columns.size == 1) "is" else "are"} Nullable. Each Nullable column stores a separate null map " +
                    "per part and rules out some optimisations. Where a column never holds NULL, or an empty value can stand in " +
                    "for it, declare it without Nullable. System tables do not show NULL counts, so check first.",
                effect = "${columns.size} null ${if (columns.size == 1) "map" else "maps"} per part",
                sql = "-- Check first:\n-- SELECT $checks FROM $table;\n$alters",
                savingsBytes = 0,
                column = null,
            ),
            null,
        )
    }

    /** The skip index type that fits how a column is filtered, or null when none does. */
    internal fun skipIndexType(c: ColumnFacts, predicates: Set<String>): String? {
        val fewValues = Types.isLowCardinality(c.type) || Types.base(c.type) == "Bool" || (c.cardinality != null && c.cardinality <= 10_000)
        return when {
            // Dates and times follow the insert order closely enough that their range per granule says it all.
            Types.isTime(c.type) && ("eq" in predicates || "range" in predicates) -> "minmax"
            "has" in predicates && c.type.startsWith("Array(") -> "bloom_filter(0.01)"
            "eq" in predicates && fewValues -> "set(100)"
            "eq" in predicates -> "bloom_filter(0.01)"
            "range" in predicates && (Types.isInteger(c.type) || Types.isFloat(c.type) || Types.isTime(c.type)) -> "minmax"
            else -> null
        }
    }

    /**
     * Columns outside the sorting key that many logged SELECTs filter on: candidates for a skip index.
     * Whether one prunes anything depends on how the values lie along the sort order, so these are
     * proposals for a trial, never savings.
     */
    private fun newSkipIndexes(t: TableFacts, windowDays: Int, keyColumn: String?): List<Finding> {
        val total = t.queryTotal ?: return emptyList()
        if (total < 20 || t.queries.isEmpty()) return emptyList()
        return t.columns
            .filter { !it.inSortingKey && it.defaultKind != "ALIAS" && it.name != keyColumn && t.indexes.none { index -> mentions(index.expression, it.name) } }
            .mapNotNull { c ->
                val using = t.queries.filter { it.filtersOn(c.name) }
                val share = using.sumOf { it.count }.toDouble() / total
                if (share < 0.1) return@mapNotNull null
                val type = skipIndexType(c, using.flatMap { it.predicatesOn(c.name) }.toSet()) ?: return@mapNotNull null
                Triple(c, share, type) to using
            }
            .sortedByDescending { it.first.second }
            .take(3)
            .map { (found, using) ->
                val (c, share, type) = found
                val index = TrialIndex("idx_" + c.name.replace(Regex("[^A-Za-z0-9_]"), "_"), Sql.id(c.name), type, 4)
                val table = Sql.table(t.database, t.name)
                Finding(
                    Recommendation(
                        id = "add-index:${c.name}",
                        severity = "review",
                        category = "Skip index",
                        changeKind = "mutation",
                        title = "Try a ${type.substringBefore('(')} skip index on ${c.name}",
                        body = "${Fmt.percent(share)} of ${Fmt.count(total)} SELECTs in the last $windowDays days filter on ${c.name}, which is not in " +
                            "the sorting key, so the primary index cannot narrow them by it. A skip index lets ClickHouse pass over granules " +
                            "that cannot match, but only if the values are clustered along the sort order. That depends on the data: " +
                            "a trial replays these queries and shows the granules read with and without the index.",
                        effect = "fewer granules read for ${Fmt.percent(share)} of SELECTs, if it prunes · measure in a trial",
                        sql = "ALTER TABLE $table\n  ADD ${indexSql(index)};\nALTER TABLE $table\n  MATERIALIZE INDEX ${Sql.id(index.name)};",
                        savingsBytes = 0,
                        column = c.name,
                        queries = using.map { QueryEvidence(it.normalized, it.count, it.count.toDouble() / total) },
                    ),
                    TrialPlanRecommendation("add-index:${c.name}", "Add ${type.substringBefore('(')} index on ${c.name}", addIndex = index),
                )
            }
    }

    private fun skipIndex(t: TableFacts, index: IndexFacts): Finding? {
        if (index.compressedBytes < MIB || index.compressedBytes < t.compressedBytes / 100) return null
        return Finding(
            Recommendation(
                id = "index:${index.name}",
                severity = "review",
                category = "Skip index",
                changeKind = "online",
                title = "Check whether ${index.name} earns its size",
                body = "The ${index.type} index on ${index.expression} takes ${Fmt.bytes(index.compressedBytes)} and is written on every " +
                    "insert. System tables do not record how often an index prunes granules. The table's Structure page can check it " +
                    "against the logged queries with EXPLAIN; drop it if it rarely helps.",
                effect = "−${Fmt.bytes(index.compressedBytes)} on disk · faster inserts",
                sql = "ALTER TABLE ${Sql.table(t.database, t.name)} DROP INDEX ${Sql.id(index.name)};",
                savingsBytes = index.compressedBytes,
                column = null,
            ),
            TrialPlanRecommendation("index:${index.name}", "Drop ${index.name}", dropIndex = index.name),
        )
    }

    /** Short label per column for the columns table: which kind of finding touches it. */
    fun columnLabels(findings: List<Finding>): Map<String, String> = findings.mapNotNull { f ->
        val column = f.rec.column ?: return@mapNotNull null
        column to when (f.rec.category) {
            "Data type" -> "Type"
            else -> f.rec.category
        }
    }.toMap()
}
