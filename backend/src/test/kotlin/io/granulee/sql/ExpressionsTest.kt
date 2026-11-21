package io.granulee.sql

import kotlin.test.Test
import kotlin.test.assertEquals

class ExpressionsTest {
    @Test
    fun `key expressions are split and compared without their outer parentheses`() {
        assertEquals(listOf("event_type", "toDate(event_time, 'UTC')", "user_id"), splitTopLevel("event_type, toDate(event_time, 'UTC'), user_id"))
        assertEquals("a, b", normalizeKey("(a, b)"))
        assertEquals("(a), (b)", normalizeKey("(a), (b)"))
    }
}
