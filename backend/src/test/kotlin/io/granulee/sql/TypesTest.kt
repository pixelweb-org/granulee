package io.granulee.sql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TypesTest {
    @Test
    fun `types lose their wrappers and codecs their CODEC()`() {
        assertEquals("String", Types.base("LowCardinality(Nullable(String))"))
        assertEquals("LZ4 (default)", Types.codecLabel(""))
        assertEquals("Delta(4), ZSTD(1)", Types.codecLabel("CODEC(Delta(4), ZSTD(1))"))
    }

    @Test
    fun `JSON, Dynamic and Variant types are recognised wherever they are nested`() {
        listOf("JSON", "JSON(max_dynamic_paths = 100)", "Array(JSON)", "Nullable(Dynamic)", "Variant(String, UInt64)", "Map(String, Dynamic)")
            .forEach { assertTrue(Types.hasDynamicSubcolumns(it), it) }
        listOf("String", "LowCardinality(String)", "Array(Nullable(Int64))", "Enum8('JSON' = 1)").forEach { assertFalse(Types.hasDynamicSubcolumns(it), it) }
    }
}
