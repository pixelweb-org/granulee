package io.granulee

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The dumps under resources/querytree are what ClickHouse 26.9 (and 25.8 where named) printed for the queries quoted here. */
class QueryTreeTest {
    private fun dump(name: String): String = checkNotNull(javaClass.getResource("/querytree/$name.txt")) { "missing fixture $name" }.readText()
    private fun filters(name: String) = QueryTree.filters(dump(name))
    private val events = "analytics.events"

    @Test
    fun `comparisons, IN and BETWEEN are read off the tree`() {
        // WHERE toDate(event_time) = today() AND event_type IN ('a','b') AND duration_ms BETWEEN 1 AND 5
        assertEquals(
            mapOf("event_time" to setOf("range"), "event_type" to setOf("eq"), "duration_ms" to setOf("range")),
            filters("simple")[events],
        )
    }

    @Test
    fun `aliases, PREWHERE and wrapped columns lead back to the table's columns`() {
        // SELECT user_id AS u, lower(url) AS lu … PREWHERE country = 'DE' WHERE u = 5 AND lu LIKE '%x%' AND isNotNull(revenue) AND user_id != 7
        val found = filters("alias-prewhere").getValue(events)
        assertEquals(setOf("eq"), found["country"])
        // ClickHouse drops "user_id != 7": it follows from "u = 5".
        assertEquals(setOf("eq"), found["user_id"])
        assertEquals(setOf("like"), found["url"])
        assertEquals(setOf("other"), found["revenue.null"])
    }

    @Test
    fun `has with the column second is a lookup of the column, with the column first a search in an array`() {
        // 25.8: WHERE has(['a'], event_type) AND toDate(event_time) = today() AND hasAny(splitByChar('/', url), ['p'])
        val found = filters("has-25-8").getValue(events)
        assertEquals(setOf("eq"), found["event_type"])
        assertEquals(setOf("eq"), found["event_time"])
        assertEquals(setOf("has"), found["url"])
    }

    @Test
    fun `a filter on a subquery's output is a filter on the columns behind it`() {
        // FROM (SELECT user_id, duration_ms AS d, lower(country) AS lc FROM analytics.events WHERE country = 'DE') WHERE d > 10 AND lc = 'de'
        assertEquals(mapOf("country" to setOf("eq"), "duration_ms" to setOf("range")), filters("subquery")[events])
    }

    @Test
    fun `each table of a join gets its own filters and the join keys are none`() {
        // events AS e INNER JOIN users AS u ON e.user_id = u.user_id WHERE u.plan = 'pro' AND e.event_type = 'x'
        val found = filters("join")
        assertEquals(mapOf("event_type" to setOf("eq")), found[events])
        assertEquals(mapOf("plan" to setOf("eq")), found["analytics.users"])
    }

    @Test
    fun `an IN subquery filters the outer column, and its own filter stays with its table`() {
        // WHERE user_id IN (SELECT user_id FROM analytics.users WHERE plan = 'pro') GROUP BY country HAVING count() > 1 ORDER BY country
        val found = filters("in-subquery")
        assertEquals(mapOf("user_id" to setOf("eq")), found[events])
        assertEquals(mapOf("plan" to setOf("eq")), found["analytics.users"])
    }

    @Test
    fun `a CTE and the branches of a UNION all count`() {
        // WITH recent AS (SELECT * FROM events WHERE event_time > now() - 3600) SELECT … FROM recent WHERE country = 'DE' UNION ALL SELECT … FROM events WHERE revenue > 5
        assertEquals(
            mapOf("event_time" to setOf("range"), "country" to setOf("eq"), "revenue" to setOf("range")),
            filters("cte-union")[events],
        )
    }

    @Test
    fun `a query on the Buffer table counts for the table behind it, one on a view does not`() {
        val buffered = QueryTree.filters(dump("buffer"))
        assertNull(QueryTree.on(buffered, listOf(events)))
        assertEquals(mapOf("event_type" to setOf("eq"), "duration_ms" to setOf("range")), QueryTree.on(buffered, listOf(events, "analytics.events_buf")))
        assertNull(QueryTree.on(QueryTree.filters(dump("view")), listOf(events)))
        assertNull(QueryTree.on(null, listOf(events)))
    }

    @Test
    fun `a query without a filter names the table with no columns`() {
        assertEquals(emptyMap(), filters("no-filter")[events])
    }

    @Test
    fun `the type is cut off an output column`() {
        assertEquals("c", QueryTree.outputName("c UInt64"))
        assertEquals("count()", QueryTree.outputName("count() UInt64"))
        assertEquals("lc", QueryTree.outputName("lc Nullable(String)"))
        assertEquals("pair", QueryTree.outputName("pair Tuple(String, UInt8)"))
        assertEquals("plus(a, 1)", QueryTree.outputName("plus(a, 1) DateTime64(3, 'UTC')"))
    }

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
