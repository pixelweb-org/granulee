package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals

class FmtTest {
    private val gib = 1L shl 30

    @Test
    fun `sizes are worded in binary units`() {
        assertEquals("300 GiB", Fmt.bytes(300 * gib))
        assertEquals("2.34 TiB", Fmt.bytes((2.34 * 1024 * gib).toLong()))
    }
}
