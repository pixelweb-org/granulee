package io.granulee.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueryTextTest {
    @Test
    fun `logged queries are pointed at the trial table`() {
        val target = "`scratch`.`events_trial_7f3a_base`"
        assertEquals(
            "SELECT count() FROM $target WHERE events_count > 1",
            rewriteQuery("SELECT count() FROM analytics.events WHERE events_count > 1;", "analytics", "events", target),
        )
        assertEquals(
            "SELECT e.url FROM $target AS e",
            rewriteQuery("SELECT e.url FROM `analytics`.`events` AS e FORMAT JSON", "analytics", "events", target),
        )
        assertEquals("SELECT x FROM $target", rewriteQuery("SELECT x FROM events", "analytics", "events", target))
    }

    @Test
    fun `a replayed query on the Buffer table is pointed at the trial table`() {
        val logged = "SELECT count() FROM flight_tracker.movement_data_buffer WHERE hex = 'abc'"
        val first = rewriteQuery(logged, "flight_tracker", "movement_data_optimized", "`scratch`.`t`")
        assertEquals(logged, first)
        assertEquals("SELECT count() FROM `scratch`.`t` WHERE hex = 'abc'", rewriteQuery(first, "flight_tracker", "movement_data_buffer", "`scratch`.`t`"))
    }

    @Test
    fun `predicates on a column are read off the filter text`() {
        val filter = "WHERE mil = ? AND timestamp_sort >= toDateTime(?) AND has(navModes, ?) AND hex IN (?) AND lat != ?"
        assertEquals(setOf("eq"), predicates(filter, "mil"))
        assertEquals(setOf("range"), predicates(filter, "timestamp_sort"))
        assertEquals(setOf("has"), predicates(filter, "navModes"))
        assertEquals(setOf("eq"), predicates(filter, "hex"))
        assertEquals(emptySet(), predicates(filter, "lat"))
    }

    @Test
    fun `a logged query loses its semicolon and FORMAT clause`() {
        assertEquals("SELECT 1", cleanQuery("  SELECT 1;  "))
        assertEquals("SELECT a FROM t", cleanQuery("SELECT a FROM t format JSONEachRow"))
        assertEquals("SELECT formatDateTime(d, 'x') FROM t", cleanQuery("SELECT formatDateTime(d, 'x') FROM t"))
    }

    @Test
    fun `every Buffer table in front is pointed at the target`() {
        val buffers = listOf("analytics.events_buf", "staging.events_in")
        assertEquals("SELECT 1 FROM `s`.`t` JOIN `s`.`t` USING id", rewriteBuffers("SELECT 1 FROM analytics.events_buf JOIN staging.events_in USING id", buffers, "`s`.`t`"))
        assertEquals("SELECT 1 FROM analytics.events", rewriteBuffers("SELECT 1 FROM analytics.events", emptyList(), "`s`.`t`"))
    }

    @Test
    fun `a table name inside a longer name or a function call is left alone`() {
        val target = "`s`.`t`"
        assertEquals("SELECT events_count FROM $target", rewriteQuery("SELECT events_count FROM events", "analytics", "events", target))
        assertEquals("SELECT events(1) FROM other.events", rewriteQuery("SELECT events(1) FROM other.events", "analytics", "events", target))
    }

    @Test
    fun `the filter text starts at the first WHERE or PREWHERE`() {
        assertEquals("PREWHERE a = 1 WHERE b = 2", filterText("SELECT x FROM t PREWHERE a = 1 WHERE b = 2"))
        assertEquals("", filterText("SELECT x FROM t"))
        assertTrue(mentions("WHERE `user_id` = 1", "user_id"))
        assertFalse(mentions("WHERE user_id_hash = 1", "user_id"))
        assertFalse(mentions("WHERE t.user_id = 1", "user_id"))
    }
}
