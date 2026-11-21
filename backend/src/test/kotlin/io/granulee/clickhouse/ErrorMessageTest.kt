package io.granulee.clickhouse

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ErrorMessageTest {
    @Test
    fun `a server error is cut down to the sentence a person needs`() {
        assertEquals(
            "Unknown table expression identifier 'analytics.nope' in scope SELECT 1 FROM analytics.nope. (UNKNOWN_TABLE)",
            cleanMessage("Code: 60. DB::Exception: Unknown table expression identifier 'analytics.nope' in scope SELECT 1 FROM analytics.nope. (UNKNOWN_TABLE) (version 25.8.33.6 (official build))\nat stack"),
        )
        assertEquals("Connection refused", cleanMessage("\n\nConnection refused\nmore"))
    }

    @Test
    fun `a long message is shortened`() {
        val cleaned = cleanMessage("DB::Exception: " + "x".repeat(900))
        assertEquals(401, cleaned.length)
        assertTrue(cleaned.endsWith("…"))
    }

    @Test
    fun `rows read numbers and flags however the server sends them`() {
        val row: Row = mapOf("n" to 7, "s" to "12", "f" to 1.5, "nan" to Double.NaN, "b" to 1, "t" to true, "none" to null)
        assertEquals(7, row.long("n"))
        assertEquals(12, row.long("s"))
        assertEquals(0, row.long("none"))
        assertEquals(null, row.longOrNull("none"))
        assertEquals(1.5, row.double("f"))
        assertEquals(0.0, row.double("nan"))
        assertTrue(row.bool("b") && row.bool("t") && !row.bool("none"))
        assertEquals("", row.str("none"))
    }
}
