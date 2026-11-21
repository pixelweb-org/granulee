package io.granulee.trial

import io.granulee.sql.Types

/** Which codec to give a column: what is worth trying for its type, and which of the measured ones to take. */
object Codecs {
    /** How many columns a trial compares codecs for: the largest ones, where a better codec pays. */
    const val MAX_COLUMNS = 16

    /** Two codecs this close in size count as equal; then the cheaper one to decompress wins, or the one in place stays. */
    const val CLOSE = 1.03

    private val GENERAL = listOf("LZ4", "ZSTD(1)", "ZSTD(3)")

    /**
     * The codecs worth trying for a type, by what each one is built for. The server has the last word on which of
     * them it accepts for the type; these are the ones that can make sense at all.
     */
    fun candidates(type: String, averageBytes: Double = 0.0): List<String> {
        val base = Types.base(type)
        return when {
            // The codec of a LowCardinality column only sees dictionary keys: nothing a specialised codec knows about.
            Types.isLowCardinality(type) -> GENERAL
            // Gorilla and FPC are made for floats that change little from row to row.
            Types.isFloat(type) -> GENERAL + listOf("Gorilla, ZSTD(1)", "Gorilla, LZ4", "FPC, ZSTD(1)")
            Types.isInteger(type) || Types.isTime(type) || base.startsWith("Decimal") || base.startsWith("Enum") ->
                // T64 for small or sparse values, Delta for values that follow the row order, DoubleDelta for steady steps.
                GENERAL + listOf("T64, ZSTD(1)", "T64, LZ4", "Delta, ZSTD(1)", "DoubleDelta, ZSTD(1)") +
                    // GCD for values that are all multiples of something, as whole seconds in a DateTime64(3).
                    (if (base.startsWith("DateTime64") || base.startsWith("Decimal")) listOf("GCD, ZSTD(1)") else emptyList())
            // Long text has more for a higher level to find.
            Types.isString(type) -> GENERAL + (if (averageBytes >= 200) listOf("ZSTD(6)") else emptyList())
            else -> GENERAL
        }
    }

    /** How much a codec costs to read back, roughly: LZ4 least, then ZSTD by level. Encodings in front do not count. */
    fun cost(codec: String): Int {
        val last = codec.substringAfterLast(',').trim()
        val level = Regex("\\((\\d+)\\)").find(last)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            last.isEmpty() -> 1 // the server's default: LZ4 on most, ZSTD(1) on ClickHouse Cloud
            last.startsWith("LZ4HC") -> 0 // decompresses like LZ4
            last.startsWith("LZ4") -> 0
            last.startsWith("ZSTD") -> level ?: 1
            else -> 1
        }
    }

    /**
     * The codec to use. Sizes within CLOSE of the smallest count as equal: among those the codec already in place
     * stays, otherwise the cheapest to read back wins.
     */
    fun best(variants: List<CodecVariant>, inPlace: String?): String {
        val measured = variants.filter { it.compressedBytes > 0 }
        val smallest = measured.minOfOrNull { it.compressedBytes } ?: return inPlace.orEmpty()
        val equal = measured.filter { it.compressedBytes <= smallest * CLOSE }
        if (inPlace != null && equal.any { it.codec == inPlace }) return inPlace
        return equal.minWith(compareBy<CodecVariant> { cost(it.codec) }.thenBy { it.compressedBytes }).codec
    }

    /**
     * The server's default codec has no name in system.columns, but it is one of the plain ones. The variant that
     * weighs exactly as much as the default is the same codec: it is taken out and its name returned.
     */
    fun withoutDefaultTwin(variants: List<CodecVariant>): Pair<List<CodecVariant>, String?> {
        val default = variants.firstOrNull { it.codec.isEmpty() } ?: return variants to null
        val twin = variants.firstOrNull { it.codec.isNotEmpty() && !it.codec.contains(',') && it.compressedBytes == default.compressedBytes && default.compressedBytes > 0 }
            ?: return variants to null
        return variants.filter { it !== twin } to twin.codec
    }

    /** "CODEC(Delta, ZSTD(1))" as a column clause, or nothing for the server's default. */
    fun clause(codec: String): String = if (codec.isBlank()) "" else " CODEC(${codec.trim()})"
}
