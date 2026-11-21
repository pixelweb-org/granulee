package io.granulee.recommendation

import io.granulee.facts.TableFacts
import org.springframework.stereotype.Component

/** A table's findings as a person sees them: what the rules say, less what was dismissed. */
@Component
class OpenFindings(private val dismissed: DismissedStore) {
    fun of(sourceId: String, table: TableFacts, queryLogWindowDays: Int): List<Finding> {
        val hidden = dismissed.of(sourceId, table.database, table.name)
        return Analyzer.analyze(table, queryLogWindowDays).filter { it.rec.id !in hidden }
    }
}
