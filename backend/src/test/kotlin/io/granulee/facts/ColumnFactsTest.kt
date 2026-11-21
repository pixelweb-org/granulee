package io.granulee.facts

import kotlin.test.Test
import kotlin.test.assertEquals

class ColumnFactsTest {
    private fun column(defaultKind: String) = ColumnFacts("c", "String", "", 0, 0, defaultKind, "", false, false, null)

    @Test
    fun `only plain and DEFAULT columns are named in a copy, and ALIAS and EPHEMERAL hold no data`() {
        val kinds = listOf("", "DEFAULT", "MATERIALIZED", "ALIAS", "EPHEMERAL")
        assertEquals(listOf(true, true, false, false, false), kinds.map { column(it).isCopied() })
        assertEquals(listOf(true, true, true, false, false), kinds.map { column(it).isStored() })
    }
}
