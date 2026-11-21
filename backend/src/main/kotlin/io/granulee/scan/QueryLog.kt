package io.granulee.scan

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.NOT_GRANULEE
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.facts.QueryFacts

/** The SELECTs query_log has for one table: its most frequent shapes, and how many ran in all. */
data class LoggedSelects(val shapes: List<QueryFacts>, val total: Long)

/** What a scan reads from system.query_log. */
object QueryLog {
    private const val SHAPES_PER_TABLE = 20

    /**
     * The most frequent SELECT shapes per table ("database.table"), and the total SELECT count. Queries sent to a
     * Buffer table count for the table behind it. Throws when query_log cannot be read.
     */
    fun selects(ch: ChConnection, days: Int, buffers: Map<String, List<String>>): Map<String, LoggedSelects> {
        val behind = buffers.flatMap { (target, fronts) -> fronts.map { it to target } }.toMap()
        return ch.query(
            """
            SELECT t, substring(q, 1, 20000) AS q, substring(normalizeQuery(q), 1, 4000) AS nq, d, toInt64(c) AS c,
                   toInt64(sum(c) OVER (PARTITION BY t)) AS total
            FROM (
                SELECT arrayJoin(tables) AS t, normalized_query_hash AS h, any((query, current_database)) AS qd, qd.1 AS q, qd.2 AS d, count() AS c
                FROM system.query_log
                WHERE event_date >= today() - $days AND type = 'QueryFinish' AND query_kind = 'Select' AND is_initial_query AND $NOT_GRANULEE
                GROUP BY t, h
            )
            WHERE NOT startsWith(t, 'system.')
            ORDER BY c DESC
            LIMIT $SHAPES_PER_TABLE BY t
            """.trimIndent(),
        ).groupBy { it.str("t") }.mapValues { (_, rows) ->
            LoggedSelects(rows.map { QueryFacts(it.str("q"), it.str("nq"), it.long("c"), database = it.str("d").ifBlank { null }) }, rows.first().long("total"))
        }.entries.groupBy({ behind[it.key] ?: it.key }, { it.value }).mapValues { (_, parts) ->
            LoggedSelects(parts.flatMap { it.shapes }.sortedByDescending { it.count }.take(SHAPES_PER_TABLE), parts.sumOf { it.total })
        }
    }

    /** Whether the log holds anything at all in the window: tells an idle server from a log that records nothing. */
    fun isEmpty(ch: ChConnection, days: Int): Boolean =
        (ch.first("SELECT count() AS c FROM system.query_log WHERE event_date >= today() - $days")?.long("c") ?: 0) == 0L

    /** Why reading system.query_log failed, from the server's message. */
    fun problem(message: String?): QueryLogStatus {
        val text = message.orEmpty()
        val state = when {
            Regex("unknown table|doesn'?t exist|does not exist|UNKNOWN_TABLE", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "missing"
            Regex("not enough privileges|ACCESS_DENIED", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "denied"
            else -> "error"
        }
        return QueryLogStatus(state, text.take(300).ifBlank { null })
    }
}
