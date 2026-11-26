package io.granulee.index

import io.granulee.NotFoundException
import io.granulee.explain.Explain
import io.granulee.explain.QueryExplain
import io.granulee.recommendation.Analyzer
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceService
import io.granulee.sql.Sql
import io.granulee.sql.rewriteBuffers
import io.granulee.usage.UsageService
import org.springframework.stereotype.Component

/**
 * What a table's skip indexes, projections and keys do for its logged queries. Runs EXPLAIN indexes = 1 for
 * the table's most frequent logged SELECTs, and for the ones logged as answered from a projection. Nothing is
 * executed; planning reads index files only.
 */
@Component
class IndexUsageService(private val sources: SourceService, private val snapshots: SnapshotStore, private val usage: UsageService) {

    /**
     * How ClickHouse would run one logged query shape right now: its most recent run, planned with
     * EXPLAIN indexes = 1 and EXPLAIN PIPELINE. Nothing is executed.
     */
    fun plan(sourceId: String, database: String, table: String, hash: String): QueryExplain {
        val source = sources.find(sourceId)
        val logged = usage.example(sourceId, database, table, hash)
            ?: throw NotFoundException("The last ${source.queryLogWindowDays} days of query_log on ${source.name} no longer hold a run of this query. Reload the page.")
        val ch = sources.connection(source)
        val projections = Projections.of(ch, database, table, source.queryLogWindowDays).map { it.name }.toSet()
        val settings = Explain.planned(ch) + mapOf("max_execution_time" to "30")
        // A query on a Buffer table in front is planned against the table behind it.
        val sql = rewriteBuffers(logged.query, logged.via, Sql.table(database, table))
        return Explain.of(ch, sql, "$database.$table", pipeline = true, settings = settings, projections = projections, database = logged.database)
    }

    fun usage(sourceId: String, database: String, table: String): TableIndexUsage {
        val source = sources.find(sourceId)
        val facts = snapshots.requireTable(sourceId, source.name, database, table)
        if (facts.queryTotal == null) {
            return IndexUsageReport.none(facts, "system.query_log cannot be read on ${source.name}, so there are no queries to check the indexes and projections against.")
        }
        if (facts.queries.isEmpty()) return IndexUsageReport.none(facts, "No SELECT on this table in the last ${source.queryLogWindowDays} days of query_log.")

        val ch = sources.connection(source)
        val target = Sql.table(database, table)
        val proposals = Analyzer.analyze(facts, source.queryLogWindowDays).mapNotNull { it.plan?.addIndex }
        val projections = Projections.of(ch, database, table, source.queryLogWindowDays).map { it.name }.toSet()
        val projectionReads = if (projections.isEmpty()) emptyMap() else Projections.loggedReads(ch, database, table, source.queryLogWindowDays)

        val settings = Explain.planned(ch) + mapOf("max_execution_time" to "30")
        val planned = IndexUsageReport.shapesToPlan(facts.queries, projectionReads).map { q ->
            // A query on a Buffer table in front is planned against the table behind it.
            val sql = rewriteBuffers(q.query, facts.buffers, target)
            PlannedShape(q, Explain.of(ch, sql, "$database.$table", pipeline = false, settings = settings, projections = projections, database = q.database))
        }
        return IndexUsageReport.of(facts, planned, projections, projectionReads, proposals, whatIf = WhatIf.supported(ch))
    }
}
