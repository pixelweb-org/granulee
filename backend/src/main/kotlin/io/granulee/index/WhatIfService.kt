package io.granulee.index

import io.granulee.BadRequestException
import io.granulee.clickhouse.str
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceService
import io.granulee.sql.Sql
import io.granulee.sql.TrialIndex
import io.granulee.sql.cleanQuery
import io.granulee.sql.problem
import io.granulee.sql.rewriteBuffers
import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * Estimates skip indexes that do not exist yet. Each candidate becomes a hypothetical index in a session of its
 * own, then EXPLAIN WHATIF runs for the table's most frequent logged SELECTs. ClickHouse builds a candidate in
 * memory over the granules a query reads today, so this reads table data; nothing is written.
 */
@Component
class WhatIfService(private val sources: SourceService, private val snapshots: SnapshotStore) {
    private val random = SecureRandom()

    fun estimate(sourceId: String, database: String, table: String, candidates: List<TrialIndex>): TableWhatIf {
        val source = sources.find(sourceId)
        val facts = snapshots.requireTable(sourceId, source.name, database, table)
        val wanted = candidates.map { it.copy(name = it.name.trim(), expression = it.expression.trim(), type = it.type.trim(), granularity = it.granularity.coerceAtLeast(1)) }
        if (wanted.isEmpty()) throw BadRequestException("Add a candidate index first")
        if (wanted.size > MAX_CANDIDATES) throw BadRequestException("At most $MAX_CANDIDATES candidates at a time")
        wanted.firstNotNullOfOrNull { it.problem() }?.let { throw BadRequestException(it) }
        if (wanted.map { it.name }.distinct().size != wanted.size) throw BadRequestException("Two candidates share a name")

        val queryTotal = facts.queryTotal
            ?: return WhatIfSummary.none(wanted, "system.query_log cannot be read on ${source.name}, so there are no queries to estimate the candidates against.")
        if (facts.queries.isEmpty()) return WhatIfSummary.none(wanted, "No SELECT on this table in the last ${source.queryLogWindowDays} days of query_log.")
        val ch = sources.connection(source)
        if (!WhatIf.supported(ch)) return WhatIfSummary.none(wanted, "${source.name} has no EXPLAIN WHATIF. A trial measures a new index instead.")

        val target = Sql.table(database, table)
        val session = "granulee-whatif-" + String.format("%016x", random.nextLong())
        try {
            val refused = wanted.associate { index ->
                index.name to try {
                    ch.exec("CREATE HYPOTHETICAL INDEX ${Sql.id(index.name)} ON $target ${WhatIf.parenthesised(index.expression)} TYPE ${index.type} GRANULARITY ${index.granularity}", session = session)
                    null
                } catch (e: Exception) {
                    e.message ?: "The server refused this index"
                }
            }
            if (refused.values.all { it != null }) return WhatIfSummary.none(wanted, null, refused)

            val settings = mapOf("max_execution_time" to "60")
            val answers = facts.queries.map { q ->
                // A query on a Buffer table in front is estimated against the table behind it.
                val sql = rewriteBuffers(q.query, facts.buffers, target)
                WhatIfAnswer(
                    q,
                    try {
                        Result.success(WhatIf.parse(ch.query("EXPLAIN WHATIF ${cleanQuery(sql)}", settings, session, q.database).joinToString("\n") { it.str("explain") }))
                    } catch (e: Exception) {
                        Result.failure(e)
                    },
                )
            }
            return WhatIfSummary.of(wanted, refused, answers, queryTotal)
        } finally {
            runCatching { ch.exec("DROP ALL HYPOTHETICAL INDEXES", session = session) }
        }
    }

    private companion object {
        const val MAX_CANDIDATES = 8
    }
}
