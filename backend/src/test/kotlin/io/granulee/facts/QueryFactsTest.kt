package io.granulee.facts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryFactsTest {
    @Test
    fun `the analysed filters replace the look at the query text`() {
        // By its text this query filters on country (it comes after WHERE in the subquery) and not on event_type.
        val text = "SELECT count() FROM (SELECT * FROM analytics.events WHERE country = ?) AS t WHERE t.kind = ?"
        val byText = QueryFacts("q", text, 10)
        assertTrue(byText.filtersOn("country"))
        val analysed = byText.copy(filters = mapOf("event_type" to setOf("eq"), "revenue.null" to setOf("other")))
        assertTrue(analysed.filtersOn("event_type"))
        assertEquals(setOf("eq"), analysed.predicatesOn("event_type"))
        assertTrue(analysed.filtersOn("revenue"))
        assertTrue(!analysed.filtersOn("country"))
    }
}
