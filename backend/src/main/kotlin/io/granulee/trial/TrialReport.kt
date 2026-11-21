package io.granulee.trial

import io.granulee.Fmt
import io.granulee.facts.isStored
import io.granulee.sql.Types
import io.granulee.sql.localEngine
import java.util.Locale
import kotlin.math.abs

/** Everything a run measured, before it is worded as a result. Null where the request left a measure out. */
internal data class TrialMeasurements(
    val live: LiveTable,
    val sizes: Both<TableSizes>,
    val plainSizes: TableSizes?,
    /** Per column: the median time of a full read on each table. */
    val scanMs: Map<String, Both<Double?>>,
    val queries: List<TrialQueryResult>,
    val baseDdl: String,
    val candDdl: String,
    val alterDdl: String,
    val plainDdl: String,
    val baseInsertMs: Double?,
    val candInsertMs: Double,
    val mergeMs: Both<Double?>,
    val plainMergeMs: Double?,
    val codecs: List<CodecColumn>,
    /** Rows the sample copied into the baseline, before any merge. */
    val copiedRows: Long,
)

/** Turns what a run measured into the result a person reads: metrics, columns side by side, and a verdict. */
internal object TrialReport {

    fun result(request: TrialRequest, measured: TrialMeasurements): TrialResult {
        val (base, cand) = measured.sizes
        val live = measured.live
        val timed = measured.queries.mapNotNull(::timed)
        val factor = if (base.compressed > 0) cand.compressed.toDouble() / base.compressed else 1.0
        return TrialResult(
            rows = base.rows,
            totalRows = live.rows,
            metrics = metrics(request, measured, timed),
            columns = columns(request, measured),
            queries = measured.queries,
            baseDdl = measured.baseDdl,
            candDdl = measured.candDdl,
            alterDdl = measured.alterDdl,
            projectedBaseBytes = live.compressedBytes,
            projectedCandBytes = (live.compressedBytes * factor).toLong(),
            verdict = verdict(factor, timed),
            codecs = measured.codecs,
            plainDdl = measured.plainDdl,
            engine = localEngine(live.engineFull),
            copiedRows = measured.copiedRows,
            candRows = cand.rows,
        )
    }

    /** A replayed query that ran on both tables. */
    internal class Timed(val query: TrialQueryResult, val baseMs: Double, val candMs: Double)

    private fun timed(query: TrialQueryResult): Timed? {
        val baseMs = query.baseMs ?: return null
        val candMs = query.candMs ?: return null
        return Timed(query, baseMs, candMs)
    }

    private fun columns(request: TrialRequest, measured: TrialMeasurements): List<TrialColumnResult> {
        fun label(type: String, codec: String) = "$type · ${codec.ifEmpty { "LZ4" }}"
        val candidate = CandidateColumns(request)
        val none = ColumnSize(0, 0)
        return measured.live.columns.filter { it.isStored() }.map { c ->
            val b = measured.sizes.base.columns[c.name] ?: none
            val k = measured.sizes.cand.columns[c.name] ?: none
            TrialColumnResult(
                name = c.name,
                baseline = label(c.type, Types.codecBody(c.codec)),
                candidate = if (candidate.diff(c) != null) label(candidate.type(c), candidate.codec(c)) else null,
                baseBytes = b.compressed,
                candBytes = k.compressed,
                baseUncompressedBytes = b.uncompressed,
                candUncompressedBytes = k.uncompressed,
                baseScanMs = measured.scanMs[c.name]?.base,
                candScanMs = measured.scanMs[c.name]?.cand,
                plainBytes = measured.plainSizes?.let { it.columns[c.name]?.compressed ?: 0 },
            )
        }.sortedByDescending { it.baseBytes }
    }

    private fun ratio(sizes: TableSizes): Double = if (sizes.compressed > 0) sizes.uncompressed.toDouble() / sizes.compressed else 0.0

    private fun metrics(request: TrialRequest, measured: TrialMeasurements, timed: List<Timed>): List<TrialMetric> {
        val (base, cand) = measured.sizes
        val plain = measured.plainSizes
        val metrics = mutableListOf(
            TrialMetric("size", "Compressed size", base.compressed.toDouble(), cand.compressed.toDouble(), "bytes", true, plain?.compressed?.toDouble()),
            TrialMetric("ratio", "Compression ratio", ratio(base), ratio(cand), "ratio", false, plain?.let(::ratio)),
        )
        if (timed.isNotEmpty()) {
            val onPlain = timed.map { it.query }.filter { it.plainMs != null }
            metrics.add(
                TrialMetric(
                    "query", "Median query, ${timed.size} replayed", median(timed.map { it.baseMs }) ?: 0.0, median(timed.map { it.candMs }) ?: 0.0, "ms", true,
                    median(onPlain.mapNotNull { it.plainMs }),
                ),
            )
            metrics.add(
                TrialMetric(
                    "read", "Rows read per query, median",
                    median(timed.map { (it.query.baseReadRows ?: 0).toDouble() }) ?: 0.0, median(timed.map { (it.query.candReadRows ?: 0).toDouble() }) ?: 0.0, "rows", true,
                    median(onPlain.map { (it.plainReadRows ?: 0).toDouble() }),
                ),
            )
        }
        metrics.add(
            TrialMetric("parts", if (request.optimizeFinal) "Parts after full merge" else "Active parts", base.parts.toDouble(), cand.parts.toDouble(), "count", true, plain?.parts?.toDouble()),
        )
        val baseInsertMs = measured.baseInsertMs
        if (baseInsertMs != null && baseInsertMs > 0 && measured.candInsertMs > 0) {
            metrics.add(TrialMetric("insert", "Insert throughput", measured.copiedRows / (baseInsertMs / 1000), measured.copiedRows / (measured.candInsertMs / 1000), "rowsPerSec", false))
        }
        val (baseMergeMs, candMergeMs) = measured.mergeMs
        if (baseMergeMs != null && candMergeMs != null) {
            metrics.add(TrialMetric("merge", "Full merge (OPTIMIZE FINAL)", baseMergeMs, candMergeMs, "ms", true, measured.plainMergeMs))
        }
        if (base.indexBytes > 0 || cand.indexBytes > 0) {
            metrics.add(TrialMetric("indexes", "Skip indexes on disk", base.indexBytes.toDouble(), cand.indexBytes.toDouble(), "bytes", true, plain?.indexBytes?.toDouble()))
        }
        metrics.add(TrialMetric("pk", "Primary key in memory", base.primaryKeyBytes.toDouble(), cand.primaryKeyBytes.toDouble(), "bytes", true, plain?.primaryKeyBytes?.toDouble()))
        return metrics
    }

    /** One sentence on size and speed, and which query patterns got slower beyond what the runs vary by. */
    internal fun verdict(sizeFactor: Double, timed: List<Timed>): String {
        val change = (1 - sizeFactor) * 100
        val size = when {
            abs(change) < 0.5 -> "The candidate is the same size as the baseline"
            change > 0 -> String.format(Locale.US, "The candidate is %.1f%% smaller", change)
            else -> String.format(Locale.US, "The candidate is %.1f%% larger", -change)
        }
        val baseMedian = median(timed.map { it.baseMs }) ?: return "$size."
        val candMedian = median(timed.map { it.candMs }) ?: return "$size."
        val speed = when {
            candMedian <= 0 || baseMedian <= 0 || abs(baseMedian / candMedian - 1) < 0.05 -> "answers the median query about as fast"
            baseMedian > candMedian -> String.format(Locale.US, "answers the median query %.1f× faster", baseMedian / candMedian)
            else -> String.format(Locale.US, "answers the median query %.1f× slower", candMedian / baseMedian)
        }
        val slower = timed.filter { it.candMs > it.baseMs * 1.1 && it.candMs - it.baseMs > 1 && it.query.clearDifference() }.sortedByDescending { it.query.share }
        val tail = when (slower.size) {
            0 -> ""
            1 -> slower[0].let {
                String.format(Locale.US, " One query pattern gets slower: %s, %.0f ms → %.0f ms (%s of the workload).", it.query.label, it.baseMs, it.candMs, Fmt.percent(it.query.share))
            }
            else -> " ${slower.size} query patterns get slower: ${slower.joinToString(", ") { it.query.label }}."
        }
        return "$size and $speed.$tail"
    }
}
