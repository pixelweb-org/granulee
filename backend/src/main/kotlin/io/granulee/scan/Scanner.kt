package io.granulee.scan

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.explain.QueryTree
import io.granulee.facts.QueryFacts
import io.granulee.facts.TableFacts
import io.granulee.recommendation.Analyzer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/** What of a server one scan covers. */
data class ScanTarget(
    val sourceId: String,
    val sourceName: String,
    val includeDatabases: String,
    val excludeDatabases: String,
    val scratchDatabase: String,
    val queryLogWindowDays: Int,
)

/** include/exclude lists with * wildcards. The scratch database is never scanned. */
internal fun databaseFilter(include: String, exclude: String, scratchDatabase: String): (String) -> Boolean {
    fun patterns(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .map { Regex(it.split('*').joinToString(".*") { part -> Regex.escape(part) }) }
    val included = patterns(include)
    val excluded = patterns(exclude)
    return { database ->
        database != scratchDatabase &&
            (included.isEmpty() || included.any { it.matches(database) }) &&
            excluded.none { it.matches(database) }
    }
}

/** Reads one server into a snapshot: every MergeTree table with its columns, indexes, logged queries and findings. */
@Component
class Scanner {
    private val log = LoggerFactory.getLogger(Scanner::class.java)

    fun scan(ch: ChConnection, target: ScanTarget, started: Instant): Snapshot {
        val wanted = databaseFilter(target.includeDatabases, target.excludeDatabases, target.scratchDatabase)
        val tables = SystemTables.mergeTreeTables(ch).filter { wanted(it.str("database")) }
        val parts = SystemTables.partTotals(ch)
        val columns = SystemTables.columns(ch, SystemTables.cardinalities(ch))
        val indexes = SystemTables.indexes(ch)
        val buffers = SystemTables.bufferTables(ch)

        val logged = readQueryLog(ch, target, buffers)
        val selects = logged.selects
        val files = SystemTables.fileFacts(ch, SystemTables.NOT_SYSTEM)
        val analysis = FilterAnalysis(ch, buffers)

        val facts = tables.map { row ->
            val key = row.str("database") to row.str("name")
            val name = "${key.first}.${key.second}"
            val size = parts[key]
            val tableSelects = selects?.get(name)
            val table = TableFacts(
                database = key.first,
                name = key.second,
                engine = row.str("engine"),
                engineFull = row.str("engine_full"),
                partitionKey = row.str("partition_key"),
                sortingKey = row.str("sorting_key"),
                primaryKey = row.str("primary_key"),
                rows = size?.long("rows") ?: 0,
                compressedBytes = size?.long("compressed") ?: 0,
                uncompressedBytes = size?.long("uncompressed") ?: 0,
                bytesOnDisk = size?.long("on_disk") ?: 0,
                activeParts = size?.long("parts") ?: 0,
                partitions = size?.long("partitions") ?: 0,
                columns = columns[key].orEmpty(),
                indexes = indexes[key].orEmpty(),
                queries = tableSelects?.let { analysis.withFilters(name, it.shapes) }.orEmpty(),
                queryTotal = if (selects == null) null else tableSelects?.total ?: 0,
                files = files[key],
                buffers = buffers[name].orEmpty(),
            )
            val findings = Analyzer.analyze(table, target.queryLogWindowDays)
            table.copy(
                findings = findings.size,
                savingsBytes = findings.sumOf { it.rec.savingsBytes }.coerceAtMost(table.compressedBytes),
                findingsByCategory = findings.groupingBy { it.rec.category }.eachCount(),
            )
        }.sortedByDescending { it.compressedBytes }

        if (analysis.asked > 0) {
            log.info("{}: ClickHouse analysed {} of {} logged query shapes", target.sourceName, analysis.answered, analysis.asked)
        }
        return Snapshot(
            sourceId = target.sourceId,
            scannedAt = started.toString(),
            durationMs = Duration.between(started, Instant.now()).toMillis(),
            queryLogReadable = selects != null,
            queryLog = logged.status,
            tables = facts,
        )
    }

    /** What the query log has on the tables; no selects when it cannot be read, and the status says why. */
    private class LoggedQueries(val selects: Map<String, LoggedSelects>?, val status: QueryLogStatus)

    private fun readQueryLog(ch: ChConnection, target: ScanTarget, buffers: Map<String, List<String>>): LoggedQueries = try {
        val found = QueryLog.selects(ch, target.queryLogWindowDays, buffers)
        // Readable but nothing in it for any table: tell an idle server from a log that records nothing.
        val nothingLogged = found.isEmpty() && QueryLog.isEmpty(ch, target.queryLogWindowDays)
        LoggedQueries(found, QueryLogStatus(if (nothingLogged) "empty" else "ok"))
    } catch (e: Exception) {
        log.info("query_log is not readable on {}: {}", target.sourceName, e.message)
        LoggedQueries(null, QueryLog.problem(e.message))
    }

    /**
     * Asks the server how it reads each logged shape: which columns of which table the filters are on.
     * A query that touches several tables is analysed once. The whole pass has a time budget; what it does not
     * get to is read by its text.
     */
    private class FilterAnalysis(private val ch: ChConnection, private val buffers: Map<String, List<String>>) {
        private val analysed = HashMap<String, Map<String, Map<String, Set<String>>>?>()
        private val until = System.nanoTime() + BUDGET_NS

        val asked: Int get() = analysed.size
        val answered: Int get() = analysed.values.count { it != null }

        /** The shapes of one table ("database.table") with what each filters on in that table. */
        fun withFilters(table: String, shapes: List<QueryFacts>): List<QueryFacts> = shapes.map { q ->
            val key = q.database.orEmpty() + "\u0000" + q.query
            val all = analysed[key]
                ?: if (key in analysed || System.nanoTime() > until) null else QueryTree.analyse(ch, q.query, q.database).also { analysed[key] = it }
            q.copy(filters = QueryTree.on(all, listOf(table) + buffers[table].orEmpty()))
        }

        private companion object {
            const val BUDGET_NS = 60_000_000_000L
        }
    }
}
