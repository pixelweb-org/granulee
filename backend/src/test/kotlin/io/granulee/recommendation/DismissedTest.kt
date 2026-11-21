package io.granulee.recommendation

import io.granulee.facts.ColumnFacts
import io.granulee.facts.TableFacts
import io.granulee.state.GranuleeProperties
import io.granulee.state.JsonStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DismissedTest {
    @TempDir
    lateinit var dir: Path

    private fun store() = DismissedStore(JsonStore(GranuleeProperties(dataDir = dir.toString())))

    private val gib = 1L shl 30
    private val table = TableFacts(
        "analytics", "events", "MergeTree", "MergeTree ORDER BY id", "", "id", "id", 4_000_000_000, 70 * gib, 560 * gib, 70 * gib, 10, 1,
        columns = listOf(
            ColumnFacts("payload", "String", "", 60 * gib, 480 * gib, "", "", false, false, null),
            ColumnFacts("kind", "String", "CODEC(ZSTD(1))", 10 * gib, 80 * gib, "", "", false, false, 12),
        ),
        indexes = emptyList(),
    )

    @Test
    fun `a dismissed recommendation stays dismissed across restarts, per table`() {
        store().apply {
            add("s1", "analytics", "events", "codec:payload")
            add("s1", "analytics", "events", "type:kind")
            add("s1", "analytics", "users", "codec:name")
        }
        assertEquals(setOf("codec:payload", "type:kind"), store().of("s1", "analytics", "events"))
        assertEquals(emptySet(), store().of("s2", "analytics", "events"))
        store().clear("s1", "analytics", "events")
        assertEquals(emptySet(), store().of("s1", "analytics", "events"))
        assertEquals(setOf("codec:name"), store().of("s1", "analytics", "users"))
    }

    @Test
    fun `a table's open findings are what the rules say less what was dismissed`() {
        val dismissed = store()
        val open = OpenFindings(dismissed)
        assertEquals(setOf("codec:payload", "type:kind"), open.of("s1", table, 30).map { it.rec.id }.toSet())
        dismissed.add("s1", "analytics", "events", "type:kind")
        assertEquals(listOf("codec:payload"), open.of("s1", table, 30).map { it.rec.id })
        // Dismissed for one source, not for another that has a table of the same name.
        assertEquals(2, open.of("s2", table, 30).size)
    }

    @Test
    fun `a column is labelled with the kind of finding that touches it`() {
        assertEquals(mapOf("payload" to "Codec", "kind" to "Type"), Analyzer.columnLabels(Analyzer.analyze(table, 30)))
    }
}
