package io.granulee

import kotlin.math.ceil
import kotlin.math.roundToLong

// What a scan knows about one table. Everything here comes from system tables; table data is never read.

data class ColumnFacts(
    val name: String,
    val type: String,
    val codec: String, // as in system.columns: "CODEC(ZSTD(1))", or "" for the server default
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val defaultKind: String,
    val defaultExpression: String,
    val inSortingKey: Boolean,
    val inPartitionKey: Boolean,
    val cardinality: Long?, // highest per-part estimate; only newer servers report it
)

data class IndexFacts(
    val name: String,
    val expression: String,
    val type: String,
    val granularity: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class QueryFacts(
    val query: String,
    val normalized: String,
    val count: Long,
    // Column → how the query filters on it ("eq", "range", "has", "like", "other"), from ClickHouse's own analysis
    // of the query. Null where the scan could not get one; then the query text has to do.
    val filters: Map<String, Set<String>>? = null,
    // The default database of the session that ran it: where its unqualified table names are looked up.
    val database: String? = null,
)

/**
 * How many files a table is on disk, estimated from system.parts_columns: a wide part keeps two files per
 * substream of a column, a compact part a handful whatever its columns are.
 */
data class FileFacts(
    val files: Long = 0,
    val largestPartFiles: Long = 0,
    val widestColumn: String = "",
    val widestColumnFiles: Long = 0, // per wide part
)

data class TableFacts(
    val database: String,
    val name: String,
    val engine: String,
    val engineFull: String,
    val partitionKey: String,
    val sortingKey: String,
    val primaryKey: String,
    val rows: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val bytesOnDisk: Long,
    val activeParts: Long,
    val partitions: Long,
    val columns: List<ColumnFacts>,
    val indexes: List<IndexFacts>,
    val queries: List<QueryFacts> = emptyList(), // most frequent SELECTs from query_log
    val queryTotal: Long? = null, // all SELECTs on the table in the window; null = query_log not readable
    val findings: Int = 0,
    val savingsBytes: Long = 0,
    val findingsByCategory: Map<String, Int> = emptyMap(),
    val files: FileFacts? = null, // null = the server does not report substreams
    val buffers: List<String> = emptyList(), // Buffer tables in front of this one, as database.table
)

data class Finding(val rec: Recommendation, val plan: TrialPlanRecommendation?)

object Fmt {
    private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    fun bytes(value: Long): String {
        var v = value.toDouble()
        var unit = 0
        while (kotlin.math.abs(v) >= 1024 && unit < UNITS.size - 1) {
            v /= 1024
            unit++
        }
        val digits = if (unit == 0 || kotlin.math.abs(v) >= 100) 0 else if (kotlin.math.abs(v) >= 10) 1 else 2
        return String.format(java.util.Locale.US, "%.${digits}f %s", v, UNITS[unit])
    }

    fun count(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

    fun rows(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(java.util.Locale.US, "%.2f B", value / 1e9)
        value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f M", value / 1e6)
        value >= 1_000 -> String.format(java.util.Locale.US, "%.1f K", value / 1e3)
        else -> value.toString()
    }

    fun ratio(uncompressed: Long, compressed: Long): String =
        if (compressed <= 0) "—" else String.format(java.util.Locale.US, "%.1f×", uncompressed.toDouble() / compressed)

    fun percent(share: Double): String = "${(share * 100).roundToLong()}%"
}

object Types {
    fun isNullable(type: String) = type.startsWith("Nullable(") || type.startsWith("LowCardinality(Nullable(")
    fun isLowCardinality(type: String) = type.startsWith("LowCardinality(")

    /** The type without Nullable / LowCardinality wrappers. */
    fun base(type: String): String {
        var t = type
        while (true) {
            t = when {
                t.startsWith("Nullable(") && t.endsWith(")") -> t.substring(9, t.length - 1)
                t.startsWith("LowCardinality(") && t.endsWith(")") -> t.substring(15, t.length - 1)
                else -> return t
            }
        }
    }

    fun isString(type: String) = base(type) == "String"
    fun isTime(type: String) = base(type).let { it == "Date" || it == "Date32" || it.startsWith("DateTime") }
    fun isInteger(type: String) = Regex("^U?Int(8|16|32|64)$").matches(base(type))
    fun isFloat(type: String) = base(type).let { it == "Float32" || it == "Float64" }

    /** "CODEC(ZSTD(1))" -> "ZSTD(1)"; "" stays "". */
    fun codecBody(codec: String): String =
        if (codec.startsWith("CODEC(") && codec.endsWith(")")) codec.substring(6, codec.length - 1) else codec

    fun codecLabel(codec: String): String = codecBody(codec).ifEmpty { "LZ4 (default)" }
}

/** Splits "a, f(b, c), d" at top-level commas. */
fun splitTopLevel(expression: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    val current = StringBuilder()
    for (ch in expression) {
        when {
            ch == '(' -> { depth++; current.append(ch) }
            ch == ')' -> { depth--; current.append(ch) }
            ch == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.clear() }
            else -> current.append(ch)
        }
    }
    if (current.isNotBlank()) parts.add(current.toString().trim())
    return parts
}

fun mentions(text: String, column: String): Boolean =
    Regex("(?<![\\w.`])`?" + Regex.escape(column) + "`?(?![\\w(`])").containsMatchIn(text)

/** A query from its first WHERE or PREWHERE on: where a column has to appear to count as filtered on. */
fun filterText(query: String): String {
    val at = Regex("\\b(PRE)?WHERE\\b", RegexOption.IGNORE_CASE).find(query)?.range?.first
    return if (at == null) "" else query.substring(at)
}

/** The analysed filters on a column, with its subcolumns: a test for NULL reads `column.null`. */
private fun Map<String, Set<String>>.of(column: String): List<Set<String>> = filterKeys { it == column || it.startsWith("$column.") }.values.toList()

/** Whether the query filters on the column: by ClickHouse's analysis where the scan has it, by the text otherwise. */
fun QueryFacts.filtersOn(column: String): Boolean = filters?.of(column)?.isNotEmpty() ?: mentions(filterText(normalized), column)

/** How the query filters on the column: "eq", "range", "has". */
fun QueryFacts.predicatesOn(column: String): Set<String> = filters?.of(column)?.flatten()?.toSet() ?: Analyzer.predicates(filterText(normalized), column)

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

    /** The engine clause of engine_full: everything before the first key or settings clause. */
    fun engineClause(t: TableFacts): String = engineClause(t.engineFull.ifBlank { t.engine })

    fun engineClause(full: String): String = clauseStart(full, CLAUSES)?.let { full.substring(0, it) } ?: full

    /** What follows ORDER BY in engine_full: SAMPLE BY, TTL and SETTINGS, one clause per line. */
    fun engineTail(full: String): String {
        val parts = mutableListOf<String>()
        var rest = clauseStart(full, TAIL_CLAUSES)?.let { full.substring(it + 1) } ?: return ""
        while (true) {
            val next = clauseStart(rest, TAIL_CLAUSES) ?: break
            parts.add(rest.substring(0, next))
            rest = rest.substring(next + 1)
        }
        return (parts + rest).joinToString("\n")
    }

    /** Index of the space in front of the first of these clauses that is not inside parentheses. */
    private fun clauseStart(full: String, clauses: List<String>): Int? {
        var depth = 0
        for (i in full.indices) {
            when (full[i]) {
                '(' -> depth++
                ')' -> depth--
                ' ' -> if (depth == 0 && i > 0 && clauses.any { full.startsWith(it, i + 1) }) return i
            }
        }
        return null
    }

    private val CLAUSES = listOf("PARTITION BY ", "PRIMARY KEY ", "ORDER BY ", "SAMPLE BY ", "TTL ", "SETTINGS ")
    private val TAIL_CLAUSES = listOf("SAMPLE BY ", "TTL ", "SETTINGS ")

    private fun rebuildSql(t: TableFacts, partitionKey: String, sortingKey: String, note: String): String {
        val name = Sql.table(t.database, t.name)
        val fresh = Sql.table(t.database, t.name + "_new")
        val lines = mutableListOf("-- $note", "CREATE TABLE $fresh AS $name", "ENGINE = ${engineClause(t)}")
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

    private fun column(t: TableFacts, c: ColumnFacts): Finding? {
        if (c.compressedBytes < MIN_COLUMN_BYTES || c.defaultKind == "ALIAS") return null
        val share = if (t.compressedBytes > 0) c.compressedBytes.toDouble() / t.compressedBytes else 0.0
        val table = Sql.table(t.database, t.name)
        val ratio = Fmt.ratio(c.uncompressedBytes, c.compressedBytes)
        val isKey = c.inSortingKey || c.inPartitionKey

        fun build(
            id: String, category: String, kind: String, title: String, body: String, saved: Double, sql: String,
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

        val cardinality = c.cardinality
        if (Types.isString(c.type) && !Types.isLowCardinality(c.type) && !isKey &&
            cardinality != null && cardinality in 1..10_000 && t.rows >= 100_000
        ) {
            val newType = if (Types.isNullable(c.type)) "LowCardinality(Nullable(String))" else "LowCardinality(String)"
            return build(
                "type:${c.name}", "Data type", "mutation", "Make ${c.name} LowCardinality",
                "About ${Fmt.count(cardinality)} distinct values per part across ${Fmt.rows(t.rows)} rows. LowCardinality stores a " +
                    "dictionary plus small integer keys, which shrinks the column and speeds up filtering and GROUP BY on it. " +
                    "Changing the type rewrites the column as a mutation.",
                0.6,
                "ALTER TABLE $table\n  MODIFY COLUMN ${Sql.id(c.name)} $newType;",
                TrialPlanRecommendation("type:${c.name}", "${c.name} → LowCardinality", column = ColumnChange(c.name, newType, null)),
            )
        }

        if (c.codec.isNotEmpty()) return null
        // Data LZ4 can barely shrink (random ids, hashes) will not shrink much under another codec either.
        if (c.uncompressedBytes < c.compressedBytes * 1.3) return null
        val codec: String
        val saved: Double
        val title: String
        val why: String
        when {
            Types.isString(c.type) && !Types.isLowCardinality(c.type) -> {
                val wide = t.rows > 0 && c.uncompressedBytes / t.rows >= 200
                codec = if (wide) "ZSTD(3)" else "ZSTD(1)"
                saved = if (wide) 0.35 else 0.25
                title = "Compress ${c.name} with $codec"
                why = "ZSTD trades a little CPU on insert for a much better ratio on repetitive text."
            }
            Types.isTime(c.type) && !Types.isLowCardinality(c.type) -> {
                codec = "Delta, ZSTD(1)"
                saved = if (c.inSortingKey) 0.5 else 0.25
                title = "Delta-encode ${c.name}"
                why = "Timestamps rise steadily inside each part, so storing differences before ZSTD shrinks them sharply."
            }
            Types.isInteger(c.type) && !Types.isLowCardinality(c.type) && share >= 0.02 -> {
                codec = "T64, ZSTD(1)"
                saved = 0.3
                title = "Compress ${c.name} with T64 + ZSTD(1)"
                why = "T64 strips the unused high bits of integers before ZSTD compresses the rest."
            }
            share >= 0.05 -> {
                codec = "ZSTD(1)"
                saved = 0.15
                title = "Compress ${c.name} with ZSTD(1)"
                why = "ZSTD usually beats LZ4 on ratio for a small CPU cost on insert."
            }
            else -> return null
        }
        return build(
            "codec:${c.name}", "Codec", "online", title,
            "${Fmt.bytes(c.uncompressedBytes)} of ${c.type} on the default LZ4 codec, ratio $ratio. $why " +
                "New parts use it at once; existing parts as they merge. The saving is an estimate: a trial measures it.",
            saved,
            "ALTER TABLE $table\n  MODIFY COLUMN ${Sql.id(c.name)} ${c.type} CODEC($codec);",
            TrialPlanRecommendation("codec:${c.name}", "${c.name} → ${codec.replace(", ", " + ")}", column = ColumnChange(c.name, null, codec)),
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

    /** How a query filters on a column, read off the text after WHERE: "eq", "range", "has". */
    internal fun predicates(filter: String, column: String): Set<String> {
        val c = "(?<![\\w.`])`?" + Regex.escape(column) + "`?(?![\\w(`])"
        val found = mutableSetOf<String>()
        if (Regex("$c\\s*(==?(?!=)|IN\\b)", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("eq")
        if (Regex("$c\\s*(>=?|<=?(?!>)|BETWEEN\\b)", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("range")
        if (Regex("\\bhas(Any|All)?\\(\\s*$c", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("has")
        return found
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
