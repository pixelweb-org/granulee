package io.granulee.explain

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.str
import io.granulee.sql.cleanQuery

/** One step of a measured run: what went in, what came out, and how much of the run it took. */
data class AnalyzeStep(
    val name: String, // "ReadFromMergeTree (scratch.events_base)"
    val depth: Int, // 0 is the step that hands out the result; the reads are deepest
    val rowsIn: Double?,
    val rowsOut: Double?,
    val timeMs: Double?, // summed over the step's stages
    val timeShare: Double?, // of the execution time, 0..1; steps run at the same time, so shares can add up to more than 1
    val threads: Double?, // average number of threads at work in its busiest stage
    val maxThreads: Int?, // how many it could have used
)

/** What EXPLAIN ANALYZE measured for one query on one table. The numbers are ClickHouse's own, rounded as it prints them. */
data class QueryAnalyze(
    val planningMs: Double? = null,
    val executionMs: Double? = null,
    val readRows: Double? = null, // rows read from tables, before any filter
    val peakMemoryBytes: Double? = null,
    val steps: List<AnalyzeStep> = emptyList(),
    val text: String? = null, // the report as ClickHouse printed it
    val error: String? = null,
)

/** Runs a query with EXPLAIN ANALYZE and reads the report. */
object ExplainAnalyze {
    private val MULTIPLIERS = mapOf("thousand" to 1e3, "million" to 1e6, "billion" to 1e9, "trillion" to 1e12, "quadrillion" to 1e15)
    private val TIME_UNITS = mapOf("ns" to 1e-6, "us" to 1e-3, "μs" to 1e-3, "ms" to 1.0, "s" to 1e3, "sec" to 1e3, "min" to 6e4)
    private val BYTE_UNITS = mapOf(
        "B" to 1.0, "KB" to 1e3, "MB" to 1e6, "GB" to 1e9, "TB" to 1e12,
        "KiB" to 1024.0, "MiB" to 1024.0 * 1024, "GiB" to 1024.0 * 1024 * 1024, "TiB" to 1024.0 * 1024 * 1024 * 1024,
    )
    private const val COUNT = "([\\d.]+)(?: (thousand|million|billion|trillion|quadrillion))?"
    private val STEP = Regex("^([│ ]*)(?:[├└]──)?([A-Z][A-Za-z]+(?: \\(.*\\))?)$")
    private val IO = Regex("I/O: rows $COUNT → $COUNT")
    private val STAGE = Regex("\\btime ([\\d.]+) (\\S+) \\(([\\d.]+)%\\) · parallelism ([\\d.]+)/(\\d+)")
    private val TIME = Regex("Time:\\s+\\S+ \\S+ \\(planning ([\\d.]+) (\\S+) · execution ([\\d.]+) (\\S+)\\)")
    private val READ = Regex("Read:\\s+$COUNT rows")
    private val MEMORY = Regex("Peak memory:\\s+([\\d.]+) (\\S+)")

    /** Whether the server has EXPLAIN ANALYZE at all; older ones take ANALYZE for the start of a query. */
    fun supported(ch: ChConnection): Boolean = try {
        ch.query("EXPLAIN ANALYZE SELECT 1").any { it.str("explain").startsWith("Query summary") }
    } catch (e: Exception) {
        false
    }

    /** Runs the query once more and measures every step. Index details are left out: the plan has them. */
    fun of(ch: ChConnection, query: String, settings: Map<String, String> = emptyMap(), database: String? = null): QueryAnalyze = try {
        parse(ch.query("EXPLAIN ANALYZE indexes = 0 ${cleanQuery(query)}", settings, database = database).joinToString("\n") { it.str("explain") })
    } catch (e: Exception) {
        QueryAnalyze(error = e.message ?: e.javaClass.simpleName)
    }

    internal fun parse(text: String): QueryAnalyze {
        var planning: Double? = null
        var execution: Double? = null
        var read: Double? = null
        var memory: Double? = null
        val steps = mutableListOf<AnalyzeStep>()
        var summary = true
        text.lineSequence().forEach { line ->
            if (summary) {
                TIME.find(line)?.let { planning = ms(it.groupValues[1], it.groupValues[2]); execution = ms(it.groupValues[3], it.groupValues[4]) }
                READ.find(line)?.let { read = count(it.groupValues[1], it.groupValues[2]) }
                MEMORY.find(line)?.let { m -> memory = BYTE_UNITS[m.groupValues[2]]?.let { m.groupValues[1].toDoubleOrNull()?.times(it) } }
            }
            val step = STEP.find(line)
            if (step != null && (line.contains("──") || step.groupValues[1].isEmpty())) {
                summary = false
                val indent = line.indexOf(step.groupValues[2])
                steps.add(AnalyzeStep(step.groupValues[2], indent / 3, null, null, null, null, null, null))
                return@forEach
            }
            val current = steps.lastOrNull() ?: return@forEach
            IO.find(line)?.let {
                steps[steps.lastIndex] = current.copy(rowsIn = count(it.groupValues[1], it.groupValues[2]), rowsOut = count(it.groupValues[3], it.groupValues[4]))
                return@forEach
            }
            STAGE.find(line)?.let {
                val time = ms(it.groupValues[1], it.groupValues[2])
                val share = it.groupValues[3].toDoubleOrNull()?.div(100)
                val threads = it.groupValues[4].toDoubleOrNull()
                val busiest = current.threads == null || (threads != null && threads > current.threads)
                steps[steps.lastIndex] = current.copy(
                    timeMs = sum(current.timeMs, time),
                    timeShare = sum(current.timeShare, share),
                    threads = if (busiest) threads else current.threads,
                    maxThreads = if (busiest) it.groupValues[5].toIntOrNull() else current.maxThreads,
                )
            }
        }
        return QueryAnalyze(planning, execution, read, memory, steps, text.take(12_000))
    }

    private fun sum(a: Double?, b: Double?): Double? = if (a == null) b else if (b == null) a else a + b
    private fun count(number: String, word: String): Double? = number.toDoubleOrNull()?.times(MULTIPLIERS[word] ?: 1.0)
    private fun ms(number: String, unit: String): Double? = TIME_UNITS[unit]?.let { number.toDoubleOrNull()?.times(it) }
}
