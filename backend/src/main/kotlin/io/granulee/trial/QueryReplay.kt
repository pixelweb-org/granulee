package io.granulee.trial

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.ExecStats
import io.granulee.explain.Explain
import io.granulee.explain.ExplainAnalyze
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.sql.rewriteBuffers
import io.granulee.sql.rewriteQuery

/**
 * Replays the table's most frequent logged SELECTs on the trial's tables: timed, planned with EXPLAIN and, where
 * the server can, measured step by step with EXPLAIN ANALYZE.
 */
internal class QueryReplay(
    private val trial: Trial,
    private val ch: ChConnection,
    private val statements: TrialStatements,
    private val tables: ScratchTables,
) {
    private val request = trial.request

    /** The runs of one query on one table. */
    private class Runs(all: List<ExecStats>, failed: Boolean) {
        private val kept = if (failed) emptyList() else counted(all)
        val ms: List<Double> = kept.map { it.elapsedMs }
        val median: Double? = median(ms)
        val readRows: Long? = kept.lastOrNull()?.readRows
        val queryIds: List<String> = kept.mapNotNull { it.queryId }
    }

    fun run(facts: TableFacts?, progress: (done: Int, of: Int) -> Unit): List<TrialQueryResult> {
        val queries = facts?.queries.orEmpty().take(MAX_QUERIES)
        val total = (facts?.queryTotal ?: 0).coerceAtLeast(1)
        val settings = Explain.uncached(ch) + mapOf("max_execution_time" to "120")
        val planning = statements.limits + Explain.planned(ch)
        val measured = queries.isNotEmpty() && ExplainAnalyze.supported(ch)
        val runIds = mutableListOf<Both<List<String>>>() // per query: the counted runs on each table

        val results = queries.mapIndexed { i, q ->
            // A logged query may name a Buffer table in front of the production table instead.
            fun pointedAt(target: String) = rewriteBuffers(rewriteQuery(q.query, trial.database, trial.table, target), facts?.buffers.orEmpty(), target)
            // The tables take turns run by run, so that whatever else the server does meanwhile hits both alike.
            // The other tables of a logged query are looked up where the session that ran it had them.
            val baseAll = mutableListOf<ExecStats>()
            val candAll = mutableListOf<ExecStats>()
            val plainAll = mutableListOf<ExecStats>()
            val error: String? = try {
                repeat(request.runsPerQuery) {
                    baseAll.add(statements.exec(pointedAt(tables.base), settings, q.database))
                    candAll.add(statements.exec(pointedAt(tables.cand), settings, q.database))
                    if (request.plainDefaults) plainAll.add(statements.exec(pointedAt(tables.plain), settings, q.database))
                }
                null
            } catch (e: ChException) {
                e.message
            }
            val failed = error != null
            val base = Runs(baseAll, failed)
            val cand = Runs(candAll, failed)
            val plain = Runs(plainAll, failed)
            runIds.add(Both(base.queryIds, cand.queryIds))
            // How ClickHouse plans the same query on each table: which indexes narrow it, and how far.
            val baseExplain = if (failed) null else explain(q, pointedAt(tables.base), tables.baseName, planning)
            val candExplain = if (failed) null else explain(q, pointedAt(tables.cand), tables.candName, planning)
            // One more run of each with EXPLAIN ANALYZE: which step took the time, and what it let through.
            val baseAnalyze = if (measured && !failed) ExplainAnalyze.of(ch, pointedAt(tables.base), statements.limits + settings, q.database) else null
            val candAnalyze = if (measured && !failed) ExplainAnalyze.of(ch, pointedAt(tables.cand), statements.limits + settings, q.database) else null
            val result = TrialQueryResult(
                label = "Q${i + 1}",
                query = q.normalized,
                share = q.count.toDouble() / total,
                baseMs = base.median,
                candMs = cand.median,
                baseReadRows = base.readRows,
                candReadRows = cand.readRows,
                error = error,
                baseExplain = baseExplain,
                candExplain = candExplain,
                baseAnalyze = baseAnalyze,
                candAnalyze = candAnalyze,
                baseRuns = base.ms.takeIf { it.isNotEmpty() },
                candRuns = cand.ms.takeIf { it.isNotEmpty() },
                plainMs = plain.median,
                plainRuns = plain.ms.takeIf { it.isNotEmpty() },
                plainReadRows = plain.readRows,
                plainExplain = if (request.plainDefaults && !failed) explain(q, pointedAt(tables.plain), tables.plainName, planning, pipeline = false) else null,
            )
            progress(i + 1, queries.size)
            result
        }
        if (queries.isEmpty()) return results

        // What the runs cost in CPU time is in query_log, once it is flushed.
        val cpu = TrialTables.cpuTimes(ch, trial.id)
        fun medianCpu(ids: List<String>) = ids.mapNotNull { cpu[it] }.takeIf { it.size == ids.size && it.isNotEmpty() }?.let { median(it) }
        return results.mapIndexed { at, result -> result.copy(baseCpuMs = medianCpu(runIds[at].base), candCpuMs = medianCpu(runIds[at].cand)) }
    }

    private fun explain(q: QueryFacts, sql: String, table: String, planning: Map<String, String>, pipeline: Boolean = true) =
        Explain.of(ch, sql, tables.described(table), pipeline = pipeline, settings = planning, database = q.database)

    private companion object {
        const val MAX_QUERIES = 20
    }
}
