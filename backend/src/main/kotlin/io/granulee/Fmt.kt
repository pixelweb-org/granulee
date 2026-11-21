package io.granulee

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Numbers as the texts of recommendations and trial logs word them. */
object Fmt {
    private val UNITS = listOf("B", "KiB", "MiB", "GiB", "TiB", "PiB")

    fun bytes(value: Long): String {
        var v = value.toDouble()
        var unit = 0
        while (abs(v) >= 1024 && unit < UNITS.size - 1) {
            v /= 1024
            unit++
        }
        val digits = if (unit == 0 || abs(v) >= 100) 0 else if (abs(v) >= 10) 1 else 2
        return String.format(Locale.US, "%.${digits}f %s", v, UNITS[unit])
    }

    fun count(value: Long): String = String.format(Locale.US, "%,d", value)

    fun rows(value: Long): String = when {
        value >= 1_000_000_000 -> String.format(Locale.US, "%.2f B", value / 1e9)
        value >= 1_000_000 -> String.format(Locale.US, "%.1f M", value / 1e6)
        value >= 1_000 -> String.format(Locale.US, "%.1f K", value / 1e3)
        else -> value.toString()
    }

    fun ratio(uncompressed: Long, compressed: Long): String =
        if (compressed <= 0) "—" else String.format(Locale.US, "%.1f×", uncompressed.toDouble() / compressed)

    fun percent(share: Double): String = "${(share * 100).roundToLong()}%"
}
