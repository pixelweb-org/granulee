package io.granulee.recommendation

import io.granulee.state.JsonStore
import org.springframework.stereotype.Component

data class DismissedFile(val dismissed: Map<String, Set<String>> = emptyMap())

/** Recommendations a person dismissed, per table. */
@Component
class DismissedStore(private val store: JsonStore) {
    private var dismissed: Map<String, Set<String>> = store.read("dismissed.json", DismissedFile::class.java)?.dismissed ?: emptyMap()

    @Synchronized
    fun of(sourceId: String, database: String, table: String): Set<String> = dismissed[key(sourceId, database, table)].orEmpty()

    @Synchronized
    fun add(sourceId: String, database: String, table: String, id: String) {
        val key = key(sourceId, database, table)
        dismissed = dismissed + (key to (dismissed[key].orEmpty() + id))
        store.write("dismissed.json", DismissedFile(dismissed))
    }

    @Synchronized
    fun clear(sourceId: String, database: String, table: String) {
        dismissed = dismissed - key(sourceId, database, table)
        store.write("dismissed.json", DismissedFile(dismissed))
    }

    private fun key(sourceId: String, database: String, table: String) = "$sourceId/$database/$table"
}
