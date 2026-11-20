package io.granulee

import tools.jackson.databind.ObjectMapper

/** One index ClickHouse applied while planning a read, and how far it narrowed it. */
data class IndexStep(
    val kind: String, // minmax | partition | primary | skip
    val name: String, // the skip index's name, otherwise the key expressions
    val condition: String?,
    val partsBefore: Long,
    val partsAfter: Long,
    val granulesBefore: Long,
    val granulesAfter: Long,
)

/** What EXPLAIN says about one query on one table: the index steps, and how wide it reads. */
data class QueryExplain(
    val steps: List<IndexStep> = emptyList(),
    val granulesTotal: Long? = null, // before any index
    val granulesRead: Long? = null, // after the last one
    val partsRead: Long? = null,
    val streams: Int? = null, // parallel read streams, from EXPLAIN PIPELINE
    val pipeline: String? = null,
    val error: String? = null,
)

/** Asks ClickHouse how it would run a query, without running it. */
object Explain {
    private val mapper = ObjectMapper()

    // Caches that would let a repeated run look faster than the table is. Newer servers have more of them.
    private val UNCACHED = mapOf("use_query_cache" to "0", "enable_filesystem_cache" to "0", "use_query_condition_cache" to "0")

    // From 25.9 on skip indexes can be applied while reading, which hides them from EXPLAIN indexes = 1.
    private val PLANNED_INDEXES = mapOf("use_skip_indexes_on_data_read" to "0")

    /** The settings of `wanted` this server knows; an unknown setting would fail the query. */
    private fun known(ch: ChConnection, wanted: Map<String, String>): Map<String, String> = try {
        val names = ch.query("SELECT name FROM system.settings WHERE name IN (${wanted.keys.joinToString(", ") { Sql.lit(it) }})").map { it.str("name") }.toSet()
        wanted.filterKeys { it in names }
    } catch (e: Exception) {
        emptyMap()
    }

    /** Settings for timing a query without result, filesystem or condition caches. */
    fun uncached(ch: ChConnection): Map<String, String> = known(ch, UNCACHED)

    /** Settings under which EXPLAIN shows every skip index it would use. */
    fun planned(ch: ChConnection): Map<String, String> = known(ch, PLANNED_INDEXES)

    /** A logged or replayed query as EXPLAIN takes it: no trailing semicolon or FORMAT clause. */
    fun clean(query: String): String =
        query.trim().trimEnd(';').replace(Regex("\\s+FORMAT\\s+\\w+\\s*$", RegexOption.IGNORE_CASE), "")

    /**
     * EXPLAIN indexes = 1 for the index steps and EXPLAIN PIPELINE for the read streams.
     * `table` ("database.table") limits the steps to reads of that table; null takes every read.
     */
    fun of(ch: ChConnection, query: String, table: String? = null, pipeline: Boolean = true, settings: Map<String, String> = emptyMap()): QueryExplain = try {
        val sql = clean(query)
        val plan = ch.query("EXPLAIN json = 1, indexes = 1 $sql", settings).joinToString("\n") { it.str("explain") }
        val reads = readNodes(mapper.readValue(plan, Any::class.java)).filter { table == null || it["Description"] == table }
        val steps = reads.flatMap { node ->
            (node["Indexes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().map { index ->
                val kind = when (index["Type"]?.toString()?.replace("-", "")?.lowercase()) {
                    "minmax" -> "minmax"
                    "partition" -> "partition"
                    "primarykey" -> "primary"
                    else -> "skip"
                }
                IndexStep(
                    kind = kind,
                    name = index["Name"]?.toString() ?: (index["Keys"] as? List<*>).orEmpty().joinToString(", "),
                    condition = index["Condition"]?.toString(),
                    partsBefore = number(index["Initial Parts"]),
                    partsAfter = number(index["Selected Parts"]),
                    granulesBefore = number(index["Initial Granules"]),
                    granulesAfter = number(index["Selected Granules"]),
                )
            }
        }
        // Per read node the first step starts from everything and the last one is what gets read.
        val perRead = reads.mapNotNull { node ->
            (node["Indexes"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>().takeIf { it.isNotEmpty() }
        }
        var text: String? = null
        var streams: Int? = null
        if (pipeline) {
            text = runCatching { ch.query("EXPLAIN PIPELINE $sql", settings).joinToString("\n") { it.str("explain") } }.getOrNull()
            streams = text?.let { Regex("MergeTree\\w*(?:\\([^)]*\\))?\\s*×\\s*(\\d+)").findAll(it).maxOfOrNull { m -> m.groupValues[1].toInt() } }
                ?: text?.takeIf { it.contains("MergeTree") }?.let { 1 }
        }
        QueryExplain(
            steps = steps,
            granulesTotal = perRead.takeIf { it.isNotEmpty() }?.sumOf { number(it.first()["Initial Granules"]) },
            granulesRead = perRead.takeIf { it.isNotEmpty() }?.sumOf { number(it.last()["Selected Granules"]) },
            partsRead = perRead.takeIf { it.isNotEmpty() }?.sumOf { number(it.last()["Selected Parts"]) },
            streams = streams,
            pipeline = text?.take(4000),
        )
    } catch (e: Exception) {
        QueryExplain(error = e.message ?: e.javaClass.simpleName)
    }

    private fun number(value: Any?): Long = (value as? Number)?.toLong() ?: value?.toString()?.toLongOrNull() ?: 0

    /** Every ReadFromMergeTree node of a plan, however deep. */
    private fun readNodes(node: Any?): List<Map<*, *>> = when (node) {
        is List<*> -> node.flatMap { readNodes(it) }
        is Map<*, *> -> (if (node["Node Type"] == "ReadFromMergeTree") listOf(node) else emptyList()) +
            readNodes(node["Plan"]) + readNodes(node["Plans"])
        else -> emptyList()
    }
}
