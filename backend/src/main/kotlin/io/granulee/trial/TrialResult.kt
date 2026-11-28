package io.granulee.trial

import io.granulee.explain.QueryAnalyze
import io.granulee.explain.QueryExplain

// What a finished trial measured. Part of the trial in the API and in the storage.

data class TrialMetric(
    val key: String,
    val label: String,
    val baseline: Double,
    val candidate: Double,
    val unit: String, // bytes | ratio | ms | rows | count | rowsPerSec
    val lowerIsBetter: Boolean,
    /** The same measure on the table with plain defaults; null when the trial did not build one. */
    val plain: Double? = null,
)

data class TrialColumnResult(
    val name: String,
    val baseline: String,
    val candidate: String?,
    val baseBytes: Long,
    val candBytes: Long,
    val baseUncompressedBytes: Long,
    val candUncompressedBytes: Long,
    val baseScanMs: Double?,
    val candScanMs: Double?,
    /** The column's size with the server's default codec; null when the trial built no table with plain defaults. */
    val plainBytes: Long? = null,
)

data class TrialQueryResult(
    val label: String,
    val query: String,
    val share: Double,
    val baseMs: Double?,
    val candMs: Double?,
    val baseReadRows: Long?,
    val candReadRows: Long?,
    val error: String?,
    /** How the indexes narrowed the read on each table, from EXPLAIN. */
    val baseExplain: QueryExplain? = null,
    val candExplain: QueryExplain? = null,
    /** One measured run on each table, from EXPLAIN ANALYZE: where the time went. Null where the server has none. */
    val baseAnalyze: QueryAnalyze? = null,
    val candAnalyze: QueryAnalyze? = null,
    /** The runs baseMs and candMs are the medians of, in ms and in the order they ran. The warm-up run is not among them. */
    val baseRuns: List<Double>? = null,
    val candRuns: List<Double>? = null,
    /** Median CPU time of those runs, summed over all threads, from query_log. Null where the log does not have them. */
    val baseCpuMs: Double? = null,
    val candCpuMs: Double? = null,
    /** The same query on the table with plain defaults, where the trial built one. */
    val plainMs: Double? = null,
    val plainRuns: List<Double>? = null,
    val plainReadRows: Long? = null,
    val plainExplain: QueryExplain? = null,
) {
    /**
     * Whether the two medians tell the tables apart: no run of the faster table took as long as a run of the slower one.
     * Short queries vary from run to run by more than many a candidate changes them.
     */
    fun clearDifference(): Boolean {
        val b = baseRuns?.takeIf { it.isNotEmpty() } ?: return true
        val c = candRuns?.takeIf { it.isNotEmpty() } ?: return true
        return b.max() < c.min() || c.max() < b.min()
    }
}

data class TrialResult(
    val rows: Long,
    val totalRows: Long,
    val metrics: List<TrialMetric>,
    val columns: List<TrialColumnResult>,
    val queries: List<TrialQueryResult>,
    val baseDdl: String,
    val candDdl: String,
    val projectedBaseBytes: Long,
    val projectedCandBytes: Long,
    val verdict: String,
    /** Statements that apply the candidate to the production table; empty when nothing changes or for trials run before this existed. */
    val alterDdl: String = "",
    /** The codecs compared for the largest columns; empty when the trial did not compare any. */
    val codecs: List<CodecColumn> = emptyList(),
    /** CREATE TABLE of the table with plain defaults; empty when the trial did not build one. */
    val plainDdl: String = "",
    /** The engine of the trial tables: the production engine without replication. Null for trials run before 0.20, which built plain MergeTree. */
    val engine: String? = null,
    /** Rows the sample copied into each table. `rows` is what the baseline holds after its merge, which an engine like ReplacingMergeTree makes fewer. */
    val copiedRows: Long? = null,
    /** Rows the candidate holds after its merge; differs from `rows` where its sorting key makes other rows duplicates. */
    val candRows: Long? = null,
)

/** One codec tried on one column: what the column weighs with it and, where timed, how long a full read takes. */
data class CodecVariant(
    val codec: String, // as system.columns words it, without "CODEC(…)"; "" = the server's default
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val scanMs: Double? = null,
)

/**
 * The codecs a trial compared for one column, on the sample and in the candidate's row order. A codec is only as
 * good as its fit to the values and to the order they are stored in, so the answer is measured, not derived.
 */
data class CodecColumn(
    val name: String,
    val type: String, // on the candidate table
    val production: String, // the codec the production table uses today; "" = the server's default
    val candidate: String, // the codec this trial's candidate uses
    val best: String, // the pick: the cheapest codec within a few percent of the smallest
    val variants: List<CodecVariant>, // smallest first
    // What the server's default turned out to be: the named codec that came out byte for byte the same size.
    val defaultIs: String? = null,
)
