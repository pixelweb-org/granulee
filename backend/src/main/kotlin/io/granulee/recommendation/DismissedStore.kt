package io.granulee.recommendation

import org.springframework.stereotype.Component

/** One recommendation a person dismissed for a table. */
data class Dismissed(val sourceId: String, val database: String, val table: String, val recommendationId: String)

/** Where the dismissed recommendations are kept. */
interface DismissedStorage {
    fun all(): List<Dismissed>

    fun add(dismissed: Dismissed)

    /** Brings back everything dismissed for a table. */
    fun clear(sourceId: String, database: String, table: String)

    /** Drops what was dismissed on a source that was removed. */
    fun forget(sourceId: String)
}

/** Recommendations a person dismissed, per table. */
@Component
class DismissedStore(private val storage: DismissedStorage) {
    private var dismissed: Map<String, Set<String>> =
        storage.all().groupBy({ key(it.sourceId, it.database, it.table) }, { it.recommendationId }).mapValues { it.value.toSet() }

    @Synchronized
    fun of(sourceId: String, database: String, table: String): Set<String> = dismissed[key(sourceId, database, table)].orEmpty()

    @Synchronized
    fun add(sourceId: String, database: String, table: String, id: String) {
        val key = key(sourceId, database, table)
        if (id in dismissed[key].orEmpty()) return
        storage.add(Dismissed(sourceId, database, table, id))
        dismissed = dismissed + (key to (dismissed[key].orEmpty() + id))
    }

    @Synchronized
    fun clear(sourceId: String, database: String, table: String) {
        storage.clear(sourceId, database, table)
        dismissed = dismissed - key(sourceId, database, table)
    }

    @Synchronized
    fun forget(sourceId: String) {
        storage.forget(sourceId)
        dismissed = dismissed.filterKeys { !it.startsWith("$sourceId/") }
    }

    private fun key(sourceId: String, database: String, table: String) = "$sourceId/$database/$table"
}
