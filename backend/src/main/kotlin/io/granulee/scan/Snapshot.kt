package io.granulee.scan

import io.granulee.NotFoundException
import io.granulee.facts.TableFacts
import io.granulee.state.JsonStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * ok: readable and has entries. missing: the server keeps no query log. denied: the source's user may not
 * read it. empty: it exists but holds nothing in the window. error: reading failed for another reason.
 */
data class QueryLogStatus(val state: String, val message: String? = null)

/** The result of one full scan of a source: every MergeTree table with its columns, indexes and findings. */
data class Snapshot(
    val sourceId: String = "",
    val scannedAt: String = "",
    val durationMs: Long = 0,
    val queryLogReadable: Boolean = false,
    val queryLog: QueryLogStatus? = null,
    val tables: List<TableFacts> = emptyList(),
) {
    fun table(database: String, name: String): TableFacts? = tables.firstOrNull { it.database == database && it.name == name }
}

/** The last scan of every source, in memory and as snapshots/<source>.json. */
@Component
class SnapshotStore(private val store: JsonStore) {
    private val log = LoggerFactory.getLogger(SnapshotStore::class.java)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    init {
        store.list(DIRECTORY).filter { it.endsWith(".json") }.forEach { file ->
            runCatching { store.read("$DIRECTORY/$file", Snapshot::class.java) }
                .onFailure { log.warn("Could not read the stored scan {}: {}", file, it.message) }
                .getOrNull()?.let { snapshots[file.removeSuffix(".json")] = it }
        }
    }

    fun of(sourceId: String): Snapshot? = snapshots[sourceId]

    /** A table as the source's last scan saw it; what works from the scan cannot go on without it. */
    fun requireTable(sourceId: String, sourceName: String, database: String, table: String): TableFacts =
        of(sourceId)?.table(database, table)
            ?: throw NotFoundException("Table $database.$table was not in the last scan of $sourceName. Scan the source first.")

    fun save(snapshot: Snapshot) {
        snapshots[snapshot.sourceId] = snapshot
        store.write(file(snapshot.sourceId), snapshot)
    }

    fun forget(sourceId: String) {
        snapshots.remove(sourceId)
        store.delete(file(sourceId))
    }

    private fun file(sourceId: String) = "$DIRECTORY/$sourceId.json"

    private companion object {
        const val DIRECTORY = "snapshots"
    }
}
