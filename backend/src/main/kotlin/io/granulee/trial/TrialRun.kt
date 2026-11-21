package io.granulee.trial

import io.granulee.Fmt
import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.explain.Explain
import io.granulee.facts.ColumnFacts
import io.granulee.facts.TableFacts
import io.granulee.facts.isCopied
import io.granulee.sql.Sql
import io.granulee.sql.Types
import io.granulee.sql.sameText

/**
 * A JSON, Dynamic or Variant column is stored as one set of files per path, easily thousands per wide part, and
 * every reading or writing thread opens its own. With the server's default of one thread per core that runs into
 * "Too many open files", so trials on such tables read and write with few threads.
 */
private val FEW_FILES = mapOf("max_threads" to "2", "max_insert_threads" to "1")

/** A sample may span more partitions than the server lets one insert block touch by default. */
internal val INSERT_SETTINGS = mapOf("max_partitions_per_insert_block" to "0")

/**
 * One run of a trial: a baseline and a candidate table in the scratch database, filled with the same sample of
 * production rows, merged, measured and queried side by side. Throws when a step fails or the trial is cancelled;
 * what becomes of the trial then is the caller's business.
 */
internal class TrialRun(
    private val trial: Trial,
    private val ch: ChConnection,
    private val statements: TrialStatements,
    private val journal: TrialJournal,
    /** The table as the last scan saw it, with its logged queries; asked for when the replay starts. */
    private val facts: () -> TableFacts?,
) {
    private val request = trial.request
    private val tables = ScratchTables(trial)
    private val candidate = CandidateColumns(request)

    /** The columns an INSERT … SELECT carries over, and how the statements name them. */
    private class Copied(val columns: List<ColumnFacts>) {
        val list: String = columns.joinToString(", ") { Sql.id(it.name) }
    }

    fun execute(): TrialResult {
        journal.enter(TrialPhase.CREATE_TABLES)
        val live = TrialTables.live(ch, trial.database, trial.table)
        // Tables with JSON columns are copied into compact parts first; see FEW_FILES.
        val compactFirst = (live.columns.map { it.type } + request.columns.map { it.type }).any(Types::hasDynamicSubcolumns)
        if (compactFirst) {
            statements.limits = FEW_FILES
            journal.say("The table has JSON or Dynamic columns, which are thousands of files per wide part: copying into compact parts and with few threads")
        }
        val changedColumns = live.columns.count { candidate.diff(it) != null }
        val changedKeys = listOf(
            !sameText(request.partitionKey, live.partitionKey),
            !sameText(request.sortingKey, live.sortingKey),
        ).count { it }
        journal.update { it.copy(changedKeys = changedKeys, changedColumns = changedColumns) }

        val baseDdl = trialSql(tables.base, live, null, compactFirst)
        val candDdl = trialSql(tables.cand, live, request, compactFirst)
        val alterDdl = migrationSql(trial.database, trial.table, live, request)
        runCatching { ch.exec("CREATE DATABASE IF NOT EXISTS ${Sql.id(trial.scratchDatabase)}") }
        statements.exec(baseDdl)
        statements.exec(candDdl)
        journal.say("Created both tables in ${trial.scratchDatabase}")

        val copied = Copied(live.columns.filter { it.isCopied() })
        copyBaseline(live, copied)
        val candInsertMs = copyCandidate(copied)
        val plainDdl = if (request.plainDefaults) copyPlain(live, copied, compactFirst) else ""
        val baseInsertMs = if (request.measureInserts) timeInsert(live, copied, compactFirst) else null

        journal.enter(TrialPhase.MEASURE_SIZES)
        if (compactFirst) {
            // From here on parts are written wide, so the merge below leaves per-column files to measure.
            measuredTables().forEach { statements.exec("ALTER TABLE $it MODIFY SETTING $WIDE_PARTS") }
            if (!request.optimizeFinal) journal.say("Without the full merge the copied parts stay compact, so per-column sizes are not available")
        }
        val mergeMs: Both<Double?> = if (request.optimizeFinal) Both(merge(tables.base), merge(tables.cand)) else Both(null, null)
        val plainMergeMs = if (request.optimizeFinal && request.plainDefaults) merge(tables.plain) else null
        val sizes = Both(
            TrialTables.sizes(ch, trial.scratchDatabase, tables.baseName),
            TrialTables.sizes(ch, trial.scratchDatabase, tables.candName),
        )
        val plainSizes = if (request.plainDefaults) TrialTables.sizes(ch, trial.scratchDatabase, tables.plainName) else null
        journal.update { it.copy(scratchBytes = sizes.base.onDisk + sizes.cand.onDisk + (plainSizes?.onDisk ?: 0)) }
        journal.say((if (request.optimizeFinal) "Fully merged both tables and read" else "Read") + " sizes from system.parts and system.columns")

        val scanMs = if (request.columnScans) scanColumns(copied) else emptyMap()
        val queries = if (request.replayQueries) replayQueries() else emptyList()
        val codecs = if (request.codecBakeoff) compareCodecs(live, copied, sizes.cand) else emptyList()

        return TrialReport.result(
            request,
            TrialMeasurements(
                live = live,
                sizes = sizes,
                plainSizes = plainSizes,
                scanMs = scanMs,
                queries = queries,
                baseDdl = baseDdl,
                candDdl = candDdl,
                alterDdl = alterDdl,
                plainDdl = plainDdl,
                baseInsertMs = baseInsertMs,
                candInsertMs = candInsertMs,
                mergeMs = mergeMs,
                plainMergeMs = plainMergeMs,
                codecs = codecs,
            ),
        )
    }

    private fun copyBaseline(live: LiveTable, copied: Copied) {
        journal.enter(TrialPhase.COPY_BASELINE)
        val production = Sql.table(trial.database, trial.table)
        val filter = sampleFilter(request, live.rows) { TrialTables.partitions(ch, trial.database, trial.table) }
        val copy = statements.exec("INSERT INTO ${tables.base} (${copied.list}) SELECT ${copied.list} FROM $production$filter LIMIT ${request.rows}", INSERT_SETTINGS)
        val rows = TrialTables.rowCount(ch, tables.base)
        if (rows == 0L) throw ChException("The sample selected no rows from ${trial.database}.${trial.table}")
        journal.say("Copied ${Fmt.count(rows)} rows from ${trial.database}.${trial.table} into the baseline (${seconds(copy.elapsedMs)})")
    }

    /** Fills the candidate from the baseline, so both hold the same rows. Returns how long the insert took. */
    private fun copyCandidate(copied: Copied): Double {
        journal.enter(TrialPhase.COPY_CANDIDATE)
        val expressions = copied.columns.joinToString(", ") { copyExpression(it, candidate.requestedType(it)) }
        val copy = statements.exec("INSERT INTO ${tables.cand} (${copied.list}) SELECT $expressions FROM ${tables.base}", INSERT_SETTINGS)
        journal.say("Copied the same rows from the baseline into the candidate (${seconds(copy.elapsedMs)}), so both hold identical data")
        return copy.elapsedMs
    }

    /** The same rows with no tuning at all: no skip indexes, no projections, the server's default codec. Returns its CREATE TABLE. */
    private fun copyPlain(live: LiveTable, copied: Copied, compactFirst: Boolean): String {
        journal.enter(TrialPhase.COPY_PLAIN)
        val untuned = live.copy(columns = live.columns.map { it.copy(codec = "") }, indexes = emptyList(), projections = emptyList())
        val ddl = trialSql(tables.plain, untuned, null, compactFirst)
        statements.exec(ddl)
        val copy = statements.exec("INSERT INTO ${tables.plain} (${copied.list}) SELECT ${copied.list} FROM ${tables.base}", INSERT_SETTINGS)
        journal.say("Copied the same rows into a table with plain defaults: no skip indexes, the server's default codec (${seconds(copy.elapsedMs)})")
        return ddl
    }

    /** What the same insert takes into a second baseline table: the measure the candidate's insert is compared with. */
    private fun timeInsert(live: LiveTable, copied: Copied, compactFirst: Boolean): Double {
        journal.progress(52)
        statements.exec(trialSql(tables.insertTwin, live, null, compactFirst))
        val ms = statements.exec("INSERT INTO ${tables.insertTwin} (${copied.list}) SELECT ${copied.list} FROM ${tables.base}", INSERT_SETTINGS).elapsedMs
        statements.exec("DROP TABLE IF EXISTS ${tables.insertTwin} SYNC")
        journal.say("Timed the same insert into a second baseline table (${seconds(ms)}) to compare insert throughput")
        return ms
    }

    private fun measuredTables(): List<String> = listOf(tables.base, tables.cand) + if (request.plainDefaults) listOf(tables.plain) else emptyList()

    private fun merge(table: String): Double = statements.exec("OPTIMIZE TABLE $table FINAL").elapsedMs

    /** The median time of a full read of one column, without caches. */
    private fun scan(table: String, column: String, noCache: Map<String, String>): Double? = median(
        counted((1..request.runsPerQuery).map { statements.exec("SELECT count() FROM $table WHERE NOT ignore(${Sql.id(column)})", noCache).elapsedMs }),
    )

    private fun scanColumns(copied: Copied): Map<String, Both<Double?>> {
        journal.enter(TrialPhase.SCAN_COLUMNS)
        val noCache = Explain.uncached(ch)
        val times = mutableMapOf<String, Both<Double?>>()
        copied.columns.forEachIndexed { i, c ->
            times[c.name] = Both(scan(tables.base, c.name, noCache), scan(tables.cand, c.name, noCache))
            journal.progress(TrialPhase.SCAN_COLUMNS.percent + (i + 1) * 14 / copied.columns.size)
        }
        journal.say("Scanned every column ${request.runsPerQuery} times per table")
        return times
    }

    private fun replayQueries(): List<TrialQueryResult> {
        journal.enter(TrialPhase.REPLAY_QUERIES)
        val results = QueryReplay(trial, ch, statements, tables).run(facts()) { done, of -> journal.progress(TrialPhase.REPLAY_QUERIES.percent + done * 13 / of) }
        journal.say(
            if (results.isEmpty()) "No SELECTs for this table in query_log, so no queries were replayed"
            else "Replayed ${results.size} queries × ${request.runsPerQuery} runs per table, taking turns, and read their plans with EXPLAIN",
        )
        return results
    }

    private fun compareCodecs(live: LiveTable, copied: Copied, candSizes: TableSizes): List<CodecColumn> {
        journal.enter(TrialPhase.COMPARE_CODECS)
        return CodecBakeoff(trial, ch, statements, tables, journal).run(live, copied.columns, candSizes) { table, column, noCache -> scan(table, column, noCache) }
    }
}
