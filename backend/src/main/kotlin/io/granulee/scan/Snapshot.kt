package io.granulee.scan

import io.granulee.NotFoundException
import io.granulee.facts.TableFacts
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

/** Where the last scan of every source is kept. */
interface SnapshotStorage {
    fun all(): List<Snapshot>

    /** Stores the snapshot in place of the source's earlier one. */
    fun save(snapshot: Snapshot)

    fun forget(sourceId: String)
}

/** The last scan of every source, in memory and in the storage. */
@Component
class SnapshotStore(private val storage: SnapshotStorage) {
    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    init {
        storage.all().forEach { snapshots[it.sourceId] = it }
    }

    fun of(sourceId: String): Snapshot? = snapshots[sourceId]

    /** A table as the source's last scan saw it; what works from the scan cannot go on without it. */
    fun requireTable(sourceId: String, sourceName: String, database: String, table: String): TableFacts =
        of(sourceId)?.table(database, table)
            ?: throw NotFoundException("Table $database.$table was not in the last scan of $sourceName. Scan the source first.")

    fun save(snapshot: Snapshot) {
        storage.save(snapshot)
        snapshots[snapshot.sourceId] = snapshot
    }

    fun forget(sourceId: String) {
        storage.forget(sourceId)
        snapshots.remove(sourceId)
    }
}
