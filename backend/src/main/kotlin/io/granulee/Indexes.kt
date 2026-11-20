package io.granulee

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

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
)

data class TableIndexUsage(
    val checkedShapes: Int,
    val failedShapes: Int,
    val selects: Long, // SELECTs in the log window the shapes stand for
    val message: String?,
    val indexes: List<IndexUsage>,
)

@RestController
@RequestMapping("/api")
class IndexController(private val sources: SourceService, private val scans: ScanService) {

    /**
     * Runs EXPLAIN indexes = 1 for the table's most frequent logged SELECTs and adds up, per skip index,
     * how many granules it was given and how many it left. Nothing is executed; planning reads index files only.
     */
    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/index-usage")
    fun usage(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableIndexUsage {
        val source = sources.find(id)
        val facts = scans.snapshot(id)?.table(db, table)
            ?: throw NotFoundException("Table $db.$table was not in the last scan of ${source.name}. Scan the source first.")
        fun none(message: String) = TableIndexUsage(0, 0, 0, message, facts.indexes.map { IndexUsage(it.name, it.expression, it.type, it.compressedBytes, 0, 0.0, 0, 0) })
        if (facts.queryTotal == null) return none("system.query_log cannot be read on ${source.name}, so there are no queries to check the indexes against.")
        if (facts.queries.isEmpty()) return none("No SELECT on this table in the last ${source.queryLogWindowDays} days of query_log.")

        val ch = sources.connection(source)
        val target = Sql.table(db, table)
        val total = facts.queryTotal.coerceAtLeast(1)
        val settings = Explain.planned(ch) + mapOf("max_execution_time" to "30")
        val plans = facts.queries.map { q ->
            // A query on a Buffer table in front is planned against the table behind it.
            val sql = facts.buffers.fold(q.query) { text, buffer -> rewriteQuery(text, buffer.substringBefore('.'), buffer.substringAfter('.'), target) }
            q to Explain.of(ch, sql, "$db.$table", pipeline = false, settings = settings)
        }
        val indexes = facts.indexes.map { index ->
            val used = plans.mapNotNull { (q, plan) -> plan.steps.firstOrNull { it.kind == "skip" && it.name == index.name }?.let { q to it } }
            IndexUsage(
                index.name, index.expression, index.type, index.compressedBytes,
                shapes = used.size,
                share = used.sumOf { it.first.count }.toDouble() / total,
                granulesBefore = used.sumOf { it.second.granulesBefore },
                granulesAfter = used.sumOf { it.second.granulesAfter },
            )
        }
        return TableIndexUsage(plans.size, plans.count { it.second.error != null }, facts.queries.sumOf { it.count }, null, indexes)
    }
}
