package io.granulee.usage

import io.granulee.facts.ColumnFacts
import io.granulee.facts.QueryFacts
import io.granulee.facts.filtersOn
import io.granulee.facts.isStored

data class UsageDay(val day: String, val selects: Long, val inserts: Long)

data class UsageQuery(
    val query: String, // normalized
    val count: Long,
    val share: Double, // of the table's SELECTs
    val medianMs: Double,
    val p95Ms: Double,
    val totalMs: Long,
    val avgReadRows: Long,
    val avgReadBytes: Long,
    val avgResultRows: Long,
    val lastSeen: String,
)

data class UsageColumn(
    val name: String,
    val type: String,
    val compressedBytes: Long,
    val inSortingKey: Boolean,
    val readBy: Long, // SELECTs that read the column
    val readShare: Double,
    val filterShare: Double, // SELECTs with the column after WHERE, among the listed query shapes
)

data class UsageUser(val user: String, val selects: Long, val inserts: Long)

/** How a table was used in the window, read from system.query_log. */
data class TableUsage(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val windowDays: Int,
    val available: Boolean,
    val message: String?, // why there is nothing to show
    val logSince: String?, // the oldest entry of the log inside the window
    val selects: Long,
    val inserts: Long,
    val otherQueries: Long,
    val readRows: Long,
    val readBytes: Long,
    val writtenRows: Long,
    val writtenBytes: Long,
    val selectMedianMs: Double,
    val selectP95Ms: Double,
    val tableRows: Long,
    val wideScans: Long, // SELECTs that read at least half of the table's rows
    val days: List<UsageDay>,
    val queries: List<UsageQuery>,
    val columns: List<UsageColumn>,
    val unreadColumns: List<String>,
    val unreadBytes: Long,
    val users: List<UsageUser>,
    val via: List<String> = emptyList(), // Buffer tables in front whose queries are counted in
)

/** How the columns of a table were used: read by how many SELECTs, filtered on in which share of the listed shapes. */
internal object ColumnUsage {

    /**
     * `known` are the table's columns from the last scan and `analysed` its query shapes from there, by normalized
     * text: the scan has ClickHouse's analysis of them, and a shape it did not see is read by its text.
     * `readBy` counts, per column name, the SELECTs the log says read it.
     */
    fun of(known: List<ColumnFacts>, analysed: Map<String, QueryFacts>, shapes: List<UsageQuery>, readBy: Map<String, Long>, selects: Long): List<UsageColumn> {
        val listed = shapes.sumOf { it.count }.coerceAtLeast(1)
        val filters = shapes.map { (analysed[it.query] ?: QueryFacts(it.query, it.query, it.count)) to it.count }
        // Without a scan of the table only the names from the log are known.
        val columns = known.filter { it.isStored() }.ifEmpty { readBy.keys.sorted().map { ColumnFacts(it, "", "", 0, 0, "", "", false, false, null) } }
        return columns.map { c ->
            val reads = readBy[c.name] ?: 0
            UsageColumn(
                c.name, c.type, c.compressedBytes, c.inSortingKey, reads, if (selects > 0) reads.toDouble() / selects else 0.0,
                filters.filter { it.first.filtersOn(c.name) }.sumOf { it.second }.toDouble() / listed,
            )
        }.sortedWith(compareByDescending<UsageColumn> { it.readBy }.thenByDescending { it.compressedBytes })
    }

    /** Columns no logged SELECT read, the sorting key aside: nothing to say without any SELECT. */
    fun unread(columns: List<UsageColumn>, selects: Long): List<UsageColumn> =
        if (selects > 0) columns.filter { it.readBy == 0L && !it.inSortingKey } else emptyList()
}
