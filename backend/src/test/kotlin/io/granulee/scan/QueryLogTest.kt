package io.granulee.scan

import kotlin.test.Test
import kotlin.test.assertEquals

class QueryLogTest {
    @Test
    fun `the reason query_log cannot be read is told apart`() {
        assertEquals("missing", QueryLog.problem("Unknown table expression identifier 'system.query_log'. In scope SELECT t").state)
        assertEquals("missing", QueryLog.problem("Table system.query_log does not exist").state)
        assertEquals("denied", QueryLog.problem("granulee_ro: Not enough privileges. To execute this query, it's necessary to have the grant SELECT(tables) ON system.query_log").state)
        assertEquals("error", QueryLog.problem("Timeout exceeded").state)
    }
}
