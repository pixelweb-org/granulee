package io.granulee.index

import io.granulee.explain.QueryExplain
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.sql.TrialIndex

/** One logged query shape and how ClickHouse plans it on the production table now. */
data class PlannedQuery(
    val query: String,
    val count: Long, // runs in the log window
    val share: Double, // of the table's SELECTs
    val plan: QueryExplain,
)

/** What the primary key or the partition key did for the logged queries that read the table itself. */
data class KeyUsage(
    val kind: String, // primary | partition
    val expression: String,
    val shapes: Int, // shapes the key narrows
    val share: Double, // of the table's SELECTs, by run count
    val binaryShare: Double?, // primary key only: narrowed by binary search
    val genericShare: Double?, // primary key only: narrowed by generic exclusion search, the slower way
    val granulesBefore: Long,
    val granulesAfter: Long,
    val queries: List<Int>,
)

/** The query shapes that touch one projection: planned onto it now, or logged as answered from it. */
data class ProjectionUsage(val name: String, val queries: List<Int>)

/** What one skip index did for the logged queries, measured with EXPLAIN on the production table. */
data class IndexUsage(
    val name: String,
    val expression: String,
    val type: String,
    val compressedBytes: Long,
    val shapes: Int, // query shapes whose plan uses the index
    val share: Double, // of the table's SELECTs, by run count
    val granulesBefore: Long, // handed to the index, over those shapes
    val granulesAfter: Long, // left after it
    val queries: List<Int> = emptyList(), // the shapes that use it, as positions in TableIndexUsage.queries
)

data class TableIndexUsage(
    val checkedShapes: Int,
    val failedShapes: Int,
    val selects: Long, // SELECTs in the log window the shapes stand for
    val message: String?,
    val indexes: List<IndexUsage>,
    val projections: List<ProjectionUsage> = emptyList(),
    val queries: List<PlannedQuery> = emptyList(), // every planned shape, most frequent first
    val keys: List<KeyUsage> = emptyList(),
    val proposals: List<TrialIndex> = emptyList(), // skip indexes the scan proposes for this table
    val whatIf: Boolean = false, // the server can estimate an index that does not exist yet
)

/** A logged query shape with the plan ClickHouse made for it. */
internal class PlannedShape(val query: QueryFacts, val plan: QueryExplain)

/** Adds up what the plans of a table's logged queries say about its skip indexes, its projections and its keys. */
internal object IndexUsageReport {

    /** The answer when there are no logged queries to plan: the indexes with nothing counted, and why. */
    fun none(facts: TableFacts, message: String) =
        TableIndexUsage(0, 0, 0, message, facts.indexes.map { IndexUsage(it.name, it.expression, it.type, it.compressedBytes, 0, 0.0, 0, 0) })

    /**
     * The shapes to plan, each once by its normalized text and most frequent first: the table's most frequent
     * ones, then those the log says read a projection.
     */
    fun shapesToPlan(queries: List<QueryFacts>, projectionReads: Map<String, List<QueryFacts>>): List<QueryFacts> {
        val shapes = LinkedHashMap<String, QueryFacts>()
        queries.forEach { shapes.putIfAbsent(it.normalized, it) }
        projectionReads.values.flatten().forEach { shapes.putIfAbsent(it.normalized, it) }
        return shapes.values.sortedByDescending { it.count }
    }

    /**
     * `planned` are the shapes in the order they are listed; `projections` the names of the table's projections
     * and `projectionReads` the shapes the log says were answered from each.
     */
    fun of(
        facts: TableFacts, planned: List<PlannedShape>, projections: Set<String>, projectionReads: Map<String, List<QueryFacts>>,
        proposals: List<TrialIndex>, whatIf: Boolean,
    ): TableIndexUsage {
        val total = (facts.queryTotal ?: 0).coerceAtLeast(1)
        val indexes = facts.indexes.map { index ->
            val used = planned.withIndex().mapNotNull { (at, shape) ->
                shape.plan.steps.firstOrNull { it.kind == "skip" && it.name == index.name }?.let { Triple(at, shape.query, it) }
            }
            IndexUsage(
                index.name, index.expression, index.type, index.compressedBytes,
                shapes = used.size,
                share = used.sumOf { it.second.count }.toDouble() / total,
                granulesBefore = used.sumOf { it.third.granulesBefore },
                granulesAfter = used.sumOf { it.third.granulesAfter },
                queries = used.map { it.first },
            )
        }
        val projectionUse = projections.sorted().map { name ->
            val read = projectionReads[name].orEmpty().map { it.normalized }.toSet()
            ProjectionUsage(name, planned.withIndex().filter { (_, shape) -> shape.plan.projection == name || shape.query.normalized in read }.map { it.index })
        }
        val keys = listOfNotNull(
            keyUsage(planned, total, "primary", facts.primaryKey.ifBlank { facts.sortingKey }, setOf("primary")),
            keyUsage(planned, total, "partition", facts.partitionKey, setOf("minmax", "partition")),
        )
        return TableIndexUsage(
            checkedShapes = planned.size,
            failedShapes = planned.count { it.plan.error != null },
            selects = planned.sumOf { it.query.count },
            message = null,
            indexes = indexes,
            projections = projectionUse,
            queries = planned.map { PlannedQuery(it.query.query, it.query.count, it.query.count.toDouble() / total, it.plan) },
            keys = keys,
            proposals = proposals,
            whatIf = whatIf,
        )
    }

    /** What a key did, over the shapes whose plan it narrows. Null for a table without that key. */
    private fun keyUsage(planned: List<PlannedShape>, total: Long, kind: String, expression: String, stepKinds: Set<String>): KeyUsage? {
        if (expression.isBlank()) return null
        // The keys only count for reads of the table itself: a projection has a primary key of its own.
        val own = planned.withIndex().filter { it.value.plan.projection == null }
        val used = own.mapNotNull { (at, shape) ->
            shape.plan.steps.filter { it.kind in stepKinds && it.condition != null && it.condition != "true" }.takeIf { it.isNotEmpty() }?.let { Triple(at, shape.query, it) }
        }
        fun shareOf(algorithm: String) = used.filter { (_, _, steps) -> steps.any { it.searchAlgorithm.orEmpty().startsWith(algorithm) } }
            .sumOf { it.second.count }.toDouble() / total
        return KeyUsage(
            kind, expression,
            shapes = used.size,
            share = used.sumOf { it.second.count }.toDouble() / total,
            binaryShare = if (kind == "primary") shareOf("binary") else null,
            genericShare = if (kind == "primary") shareOf("generic") else null,
            granulesBefore = used.sumOf { it.third.first().granulesBefore },
            granulesAfter = used.sumOf { it.third.last().granulesAfter },
            queries = used.map { it.first },
        )
    }
}
