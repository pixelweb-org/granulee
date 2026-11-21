package io.granulee.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SkipIndexesTest {
    @Test
    fun `an index reads the same in CREATE TABLE and after ADD`() {
        assertEquals("INDEX `idx_country` country TYPE set(100) GRANULARITY 4", indexSql(TrialIndex(" idx_country ", " country ", " set(100) ", 4)))
        assertEquals("INDEX `idx_pos` (lat, lon) TYPE minmax GRANULARITY 1", indexSql(TrialIndex("idx_pos", "lat, lon", "minmax", 0)))
        assertEquals("lower(url)", indexExpression("lower(url)"))
        assertEquals("(a, f(b, c))", indexExpression("a, f(b, c)"))
    }

    @Test
    fun `a definition that cannot go to the server says why`() {
        assertNull(TrialIndex("idx_a", "a", "minmax", 1).problem())
        assertNull(TrialIndex(" idx_a ", "a", "minmax", 1).problem())
        assertEquals("Index name \"1x\" must be letters, digits and underscores", TrialIndex("1x", "a", "minmax").problem())
        assertEquals("Index name \"\" must be letters, digits and underscores", TrialIndex("", "a", "minmax").problem())
        assertEquals("Index x needs an expression and a type", TrialIndex("x", " ", "minmax").problem())
        assertEquals("Index x needs an expression and a type", TrialIndex("x", "a", "").problem())
        assertEquals("Index x must be a single expression and type", TrialIndex("x", "a", "set(1); DROP TABLE t").problem())
    }

    @Test
    fun `names and values are quoted for SQL`() {
        assertEquals("`a\\`b`", Sql.id("a`b"))
        assertEquals("'it\\'s'", Sql.lit("it's"))
        assertEquals("'a\\\\b'", Sql.lit("a\\b"))
        assertEquals("`db`.`t`", Sql.table("db", "t"))
    }
}
