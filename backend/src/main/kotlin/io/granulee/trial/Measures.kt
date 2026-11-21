package io.granulee.trial

import java.util.Locale
import kotlin.math.roundToInt

internal fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** The runs that count: the first run of three or more only warms up. */
internal fun <T> counted(runs: List<T>): List<T> = if (runs.size >= 3) runs.drop(1) else runs

/** A duration for a log line: "0.4 s", "12 s". */
internal fun seconds(ms: Double): String =
    if (ms >= 10_000) "${(ms / 1000).roundToInt()} s" else String.format(Locale.US, "%.1f s", ms / 1000)

/** One measure on the baseline and on the candidate. */
internal data class Both<T>(val base: T, val cand: T)
