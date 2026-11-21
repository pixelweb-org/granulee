package io.granulee

import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

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

@RestController
@RequestMapping("/api")
class UsageController(private val sources: SourceService, private val scans: ScanService) {
    private val log = LoggerFactory.getLogger(UsageController::class.java)

    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/usage")
    fun usage(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableUsage {
        val source = sources.find(id)
        val ch = sources.connection(source)
        val days = source.queryLogWindowDays
        // Queries sent to a Buffer table in front reach this table, but the log files them under the Buffer's name.
        val via = bufferTables(ch)["$db.$table"].orEmpty()
        val names = listOf("$db.$table") + via
        val scope = "event_date >= today() - $days AND type = 'QueryFinish' AND is_initial_query AND " +
            "hasAny(tables, [${names.joinToString(", ") { Sql.lit(it) }}]) AND $NOT_GRANULEE"
        // An asynchronous insert is logged twice: the client's INSERT, and the flush that carries the rows.
        val isInsert = "query_kind = 'Insert'"
        val writes = "query_kind IN ('Insert', 'AsyncInsertFlush')"

        val tableRows = ch.first(
            "SELECT toInt64(sum(rows)) AS rows FROM system.parts WHERE active AND database = ${Sql.lit(db)} AND table = ${Sql.lit(table)}",
        )?.long("rows") ?: 0
        val facts = scans.snapshot(id)?.table(db, table)

        fun empty(available: Boolean, message: String?, since: String? = null) = TableUsage(
            id, source.name, db, table, days, available, message, since, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, tableRows, 0,
            emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList(), via,
        )

        val totals = try {
            ch.first(
                """
                SELECT toInt64(countIf(query_kind = 'Select')) AS selects, toInt64(countIf($isInsert)) AS inserts, toInt64(countIf(query_kind != 'AsyncInsertFlush')) AS total,
                       toInt64(sumIf(read_rows, query_kind = 'Select')) AS sel_rows, toInt64(sumIf(read_bytes, query_kind = 'Select')) AS sel_bytes,
                       toInt64(sumIf(written_rows, $writes)) AS ins_rows, toInt64(sumIf(written_bytes, $writes)) AS ins_bytes,
                       toFloat64(quantileExactIf(0.5)(query_duration_ms, query_kind = 'Select')) AS median_ms,
                       toFloat64(quantileExactIf(0.95)(query_duration_ms, query_kind = 'Select')) AS p95_ms,
                       toInt64(countIf(query_kind = 'Select' AND read_rows >= ${(tableRows / 2).coerceAtLeast(1)})) AS wide_scans,
                       (SELECT toInt64(toUnixTimestamp(min(event_time))) FROM system.query_log WHERE event_date >= today() - $days) AS log_since
                FROM system.query_log
                WHERE $scope
                """.trimIndent(),
            ) ?: emptyMap()
        } catch (e: ChException) {
            log.info("query_log is not readable on {}: {}", source.name, e.message)
            return empty(false, "system.query_log cannot be read on ${source.name}: ${e.message}")
        }
        val since = totals.long("log_since").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
        if (totals.long("total") == 0L) {
            return empty(true, if (since == null) "system.query_log has no entries in the last $days days." else null, since)
        }
        val selects = totals.long("selects")

        val perDay = ch.query(
            """
            SELECT toString(event_date) AS day, toInt64(countIf(query_kind = 'Select')) AS selects, toInt64(countIf($isInsert)) AS inserts
            FROM system.query_log WHERE $scope GROUP BY event_date ORDER BY event_date
            """.trimIndent(),
        ).map { UsageDay(it.str("day"), it.long("selects"), it.long("inserts")) }

        val shapes = ch.query(
            """
            SELECT substring(normalizeQuery(any(query)), 1, 4000) AS q, toInt64(count()) AS c,
                   toFloat64(quantileExact(0.5)(query_duration_ms)) AS median_ms, toFloat64(quantileExact(0.95)(query_duration_ms)) AS p95_ms,
                   toInt64(sum(query_duration_ms)) AS total_ms, toInt64(avg(read_rows)) AS avg_rows, toInt64(avg(read_bytes)) AS avg_bytes,
                   toInt64(avg(result_rows)) AS avg_result, toInt64(toUnixTimestamp(max(event_time))) AS last_seen
            FROM system.query_log
            WHERE $scope AND query_kind = 'Select'
            GROUP BY normalized_query_hash
            ORDER BY c DESC
            LIMIT 40
            """.trimIndent(),
        ).map {
            UsageQuery(
                it.str("q"), it.long("c"), if (selects > 0) it.long("c").toDouble() / selects else 0.0, it.double("median_ms"), it.double("p95_ms"),
                it.long("total_ms"), it.long("avg_rows"), it.long("avg_bytes"), it.long("avg_result"), Instant.ofEpochSecond(it.long("last_seen")).toString(),
            )
        }

        // query_log names a column as database.table.column, with the Buffer table's name where the query used it.
        val strip = names.joinToString(", ") { "startsWith(x, ${Sql.lit("$it.")}), substring(x, ${"$it.".length + 1})" }
        val readBy = ch.query(
            """
            SELECT col, toInt64(count()) AS c
            FROM (
                SELECT arrayJoin(arrayDistinct(arrayFilter(c -> c != '', arrayMap(x -> multiIf($strip, ''), columns)))) AS col
                FROM system.query_log WHERE $scope AND query_kind = 'Select'
            )
            GROUP BY col
            """.trimIndent(),
        ).associate { it.str("col") to it.long("c") }

        val listed = shapes.sumOf { it.count }.coerceAtLeast(1)
        // The scan has ClickHouse's analysis of its shapes; a shape it did not see is read by its text.
        val analysed = facts?.queries.orEmpty().associateBy { it.normalized }
        val filters = shapes.map { (analysed[it.query] ?: QueryFacts(it.query, it.query, it.count)) to it.count }
        val known = facts?.columns.orEmpty().filter { it.defaultKind != "ALIAS" && it.defaultKind != "EPHEMERAL" }
        // Without a scan of the table only the names from the log are known.
        val columnFacts = known.ifEmpty { readBy.keys.sorted().map { ColumnFacts(it, "", "", 0, 0, "", "", false, false, null) } }
        val columns = columnFacts.map { c ->
            val reads = readBy[c.name] ?: 0
            UsageColumn(
                c.name, c.type, c.compressedBytes, c.inSortingKey, reads, if (selects > 0) reads.toDouble() / selects else 0.0,
                filters.filter { it.first.filtersOn(c.name) }.sumOf { it.second }.toDouble() / listed,
            )
        }.sortedWith(compareByDescending<UsageColumn> { it.readBy }.thenByDescending { it.compressedBytes })
        val unread = if (selects > 0) columns.filter { it.readBy == 0L && !it.inSortingKey } else emptyList()

        val users = ch.query(
            """
            SELECT user, toInt64(countIf(query_kind = 'Select')) AS selects, toInt64(countIf($isInsert)) AS inserts
            FROM system.query_log WHERE $scope GROUP BY user ORDER BY selects + inserts DESC LIMIT 20
            """.trimIndent(),
        ).map { UsageUser(it.str("user"), it.long("selects"), it.long("inserts")) }

        return TableUsage(
            sourceId = id, sourceName = source.name, database = db, table = table, windowDays = days, available = true, message = null, logSince = since,
            selects = selects, inserts = totals.long("inserts"), otherQueries = totals.long("total") - selects - totals.long("inserts"),
            readRows = totals.long("sel_rows"), readBytes = totals.long("sel_bytes"),
            writtenRows = totals.long("ins_rows"), writtenBytes = totals.long("ins_bytes"),
            selectMedianMs = totals.double("median_ms"), selectP95Ms = totals.double("p95_ms"),
            tableRows = tableRows, wideScans = totals.long("wide_scans"),
            days = perDay, queries = shapes, columns = columns,
            unreadColumns = unread.map { it.name }, unreadBytes = unread.sumOf { it.compressedBytes }, users = users, via = via,
        )
    }
}
