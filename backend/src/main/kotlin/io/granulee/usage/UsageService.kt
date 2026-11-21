package io.granulee.usage

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.NOT_GRANULEE
import io.granulee.clickhouse.Row
import io.granulee.clickhouse.double
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.scan.SnapshotStore
import io.granulee.scan.SystemTables
import io.granulee.source.SourceService
import io.granulee.sql.Sql
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/** How a table was used in the source's query-log window, read from system.query_log. */
@Component
class UsageService(private val sources: SourceService, private val snapshots: SnapshotStore) {
    private val log = LoggerFactory.getLogger(UsageService::class.java)

    fun usage(sourceId: String, database: String, table: String): TableUsage {
        val source = sources.find(sourceId)
        val ch = sources.connection(source)
        val days = source.queryLogWindowDays
        // Queries sent to a Buffer table in front reach this table, but the log files them under the Buffer's name.
        val via = SystemTables.bufferTables(ch)["$database.$table"].orEmpty()
        val queryLog = TableLog(ch, days, listOf("$database.$table") + via)

        val tableRows = ch.first("SELECT toInt64(sum(rows)) AS rows FROM system.parts WHERE active AND ${SystemTables.ofTable(database, table)}")?.long("rows") ?: 0
        val facts = snapshots.of(sourceId)?.table(database, table)

        fun empty(available: Boolean, message: String?, since: String? = null) = TableUsage(
            sourceId, source.name, database, table, days, available, message, since, 0, 0, 0, 0, 0, 0, 0, 0.0, 0.0, tableRows, 0,
            emptyList(), emptyList(), emptyList(), emptyList(), 0, emptyList(), via,
        )

        val totals = try {
            queryLog.totals(tableRows)
        } catch (e: ChException) {
            log.info("query_log is not readable on {}: {}", source.name, e.message)
            return empty(false, "system.query_log cannot be read on ${source.name}: ${e.message}")
        }
        val since = totals.long("log_since").takeIf { it > 0 }?.let { Instant.ofEpochSecond(it).toString() }
        if (totals.long("total") == 0L) {
            return empty(true, if (since == null) "system.query_log has no entries in the last $days days." else null, since)
        }
        val selects = totals.long("selects")

        val perDay = queryLog.perDay()
        val shapes = queryLog.shapes(selects)
        val columns = ColumnUsage.of(facts?.columns.orEmpty(), facts?.queries.orEmpty().associateBy { it.normalized }, shapes, queryLog.readBy(), selects)
        val unread = ColumnUsage.unread(columns, selects)
        val users = queryLog.users()

        return TableUsage(
            sourceId = sourceId, sourceName = source.name, database = database, table = table, windowDays = days, available = true, message = null, logSince = since,
            selects = selects, inserts = totals.long("inserts"), otherQueries = totals.long("total") - selects - totals.long("inserts"),
            readRows = totals.long("sel_rows"), readBytes = totals.long("sel_bytes"),
            writtenRows = totals.long("ins_rows"), writtenBytes = totals.long("ins_bytes"),
            selectMedianMs = totals.double("median_ms"), selectP95Ms = totals.double("p95_ms"),
            tableRows = tableRows, wideScans = totals.long("wide_scans"),
            days = perDay, queries = shapes, columns = columns,
            unreadColumns = unread.map { it.name }, unreadBytes = unread.sumOf { it.compressedBytes }, users = users, via = via,
        )
    }

    /** system.query_log for one table, under each of the names queries reach it by ("database.table"). */
    private class TableLog(private val ch: ChConnection, private val days: Int, private val names: List<String>) {
        private val scope = "event_date >= today() - $days AND type = 'QueryFinish' AND is_initial_query AND " +
            "hasAny(tables, [${names.joinToString(", ") { Sql.lit(it) }}]) AND $NOT_GRANULEE"

        // An asynchronous insert is logged twice: the client's INSERT, and the flush that carries the rows.
        private val isInsert = "query_kind = 'Insert'"
        private val writes = "query_kind IN ('Insert', 'AsyncInsertFlush')"

        /** Counts, volumes and timings over the window. Throws when the log cannot be read. */
        fun totals(tableRows: Long): Row = ch.first(
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

        fun perDay(): List<UsageDay> = ch.query(
            """
            SELECT toString(event_date) AS day, toInt64(countIf(query_kind = 'Select')) AS selects, toInt64(countIf($isInsert)) AS inserts
            FROM system.query_log WHERE $scope GROUP BY event_date ORDER BY event_date
            """.trimIndent(),
        ).map { UsageDay(it.str("day"), it.long("selects"), it.long("inserts")) }

        /** The most frequent SELECT shapes, with each one's share of the table's `selects`. */
        fun shapes(selects: Long): List<UsageQuery> = ch.query(
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

        /** Per column, the SELECTs that read it. */
        fun readBy(): Map<String, Long> {
            // query_log names a column as database.table.column, with the Buffer table's name where the query used it.
            val strip = names.joinToString(", ") { "startsWith(x, ${Sql.lit("$it.")}), substring(x, ${"$it.".length + 1})" }
            return ch.query(
                """
                SELECT col, toInt64(count()) AS c
                FROM (
                    SELECT arrayJoin(arrayDistinct(arrayFilter(c -> c != '', arrayMap(x -> multiIf($strip, ''), columns)))) AS col
                    FROM system.query_log WHERE $scope AND query_kind = 'Select'
                )
                GROUP BY col
                """.trimIndent(),
            ).associate { it.str("col") to it.long("c") }
        }

        fun users(): List<UsageUser> = ch.query(
            """
            SELECT user, toInt64(countIf(query_kind = 'Select')) AS selects, toInt64(countIf($isInsert)) AS inserts
            FROM system.query_log WHERE $scope GROUP BY user ORDER BY selects + inserts DESC LIMIT 20
            """.trimIndent(),
        ).map { UsageUser(it.str("user"), it.long("selects"), it.long("inserts")) }
    }
}
