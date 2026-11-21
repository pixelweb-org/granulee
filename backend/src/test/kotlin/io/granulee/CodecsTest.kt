package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodecsTest {
    private fun v(codec: String, bytes: Long) = CodecVariant(codec, bytes, 1_000_000)

    @Test
    fun `the candidates follow what each codec is built for`() {
        assertTrue("T64, ZSTD(1)" in Codecs.candidates("Int64"))
        assertTrue("DoubleDelta, ZSTD(1)" in Codecs.candidates("DateTime"))
        assertTrue("Delta, ZSTD(1)" in Codecs.candidates("Nullable(UInt32)"))
        assertTrue("GCD, ZSTD(1)" in Codecs.candidates("DateTime64(3)"))
        assertFalse("GCD, ZSTD(1)" in Codecs.candidates("DateTime"))
        // ClickHouse refuses Gorilla on integers, and T64 is no use on floats.
        assertFalse(Codecs.candidates("UInt64").any { it.startsWith("Gorilla") })
        assertTrue("Gorilla, ZSTD(1)" in Codecs.candidates("Float32"))
        assertFalse(Codecs.candidates("Float64").any { it.startsWith("T64") })
        // A LowCardinality column's codec only sees dictionary keys.
        assertEquals(listOf("LZ4", "ZSTD(1)", "ZSTD(3)"), Codecs.candidates("LowCardinality(String)"))
        assertFalse("ZSTD(6)" in Codecs.candidates("String", averageBytes = 40.0))
        assertTrue("ZSTD(6)" in Codecs.candidates("String", averageBytes = 400.0))
    }

    @Test
    fun `reading back costs least with LZ4 and more with every ZSTD level`() {
        assertEquals(0, Codecs.cost("LZ4"))
        assertEquals(0, Codecs.cost("T64, LZ4"))
        assertEquals(1, Codecs.cost("Delta(4), ZSTD(1)"))
        assertEquals(9, Codecs.cost("Gorilla(4), ZSTD(9)"))
        assertEquals(1, Codecs.cost(""))
    }

    @Test
    fun `the smallest wins, unless a cheaper codec or the one in place is as good`() {
        // Clearly smallest.
        assertEquals("DoubleDelta, ZSTD(1)", Codecs.best(listOf(v("DoubleDelta, ZSTD(1)", 198), v("Delta(8), ZSTD(1)", 258), v("", 1530)), ""))
        // T64 with LZ4 is within 3% of T64 with ZSTD: the cheaper one to read wins.
        assertEquals("T64, LZ4", Codecs.best(listOf(v("T64, ZSTD(1)", 5260), v("T64, LZ4", 5280), v("ZSTD(1)", 6700)), ""))
        // The codec in place stays when nothing beats it by more than 3%.
        assertEquals("ZSTD(3)", Codecs.best(listOf(v("ZSTD(6)", 1000), v("ZSTD(3)", 1020), v("LZ4", 1025)), "ZSTD(3)"))
        // It goes when something does.
        assertEquals("ZSTD(1)", Codecs.best(listOf(v("ZSTD(1)", 1000), v("Gorilla(4), ZSTD(9)", 1400)), "Gorilla(4), ZSTD(9)"))
        // Nothing measured: nothing to change.
        assertEquals("ZSTD(1)", Codecs.best(emptyList(), "ZSTD(1)"))
    }

    @Test
    fun `the plain codec that weighs exactly as much as the default is the default`() {
        val (left, name) = Codecs.withoutDefaultTwin(listOf(v("ZSTD(1)", 500), v("", 900), v("LZ4", 900), v("T64, LZ4", 900)))
        assertEquals("LZ4", name)
        assertEquals(listOf("ZSTD(1)", "", "T64, LZ4"), left.map { it.codec })
        // No default among the variants, or nothing that matches it: nothing to say.
        assertEquals(null, Codecs.withoutDefaultTwin(listOf(v("ZSTD(1)", 500), v("LZ4", 900))).second)
        assertEquals(null, Codecs.withoutDefaultTwin(listOf(v("", 800), v("LZ4", 900))).second)
    }

    @Test
    fun `a codec becomes a column clause, the server default none`() {
        assertEquals(" CODEC(Delta, ZSTD(1))", Codecs.clause("Delta, ZSTD(1)"))
        assertEquals("", Codecs.clause(""))
    }
}
