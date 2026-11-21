package io.granulee.source

import io.granulee.scan.ScanTarget
import io.granulee.state.JsonStore
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class SourceStatus(
    val state: String = "unknown", // healthy | unreachable | unknown
    val message: String? = null,
    val version: String? = null,
    val replicas: Int? = null,
    val checkedAt: String? = null,
    val lastSeenAt: String? = null,
)

data class LastScan(val at: String, val durationMs: Long, val error: String?)

/** A data source as stored in sources.json. The password is AES-GCM encrypted. */
data class SourceRecord(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val passwordEnc: String,
    val includeDatabases: String,
    val excludeDatabases: String,
    val schedule: String,
    val queryLogWindowDays: Int,
    val trialsEnabled: Boolean,
    val scratchDatabase: String,
    val status: SourceStatus = SourceStatus(),
    val lastScan: LastScan? = null,
)

/** How often a source is scanned on its own; null = only when asked. */
val SCHEDULES: Map<String, Duration?> = mapOf(
    "every6h" to Duration.ofHours(6),
    "daily" to Duration.ofDays(1),
    "weekly" to Duration.ofDays(7),
    "manual" to null,
)

/** When the source's schedule wants the next scan: right away if it was never scanned, never if it is scanned by hand. */
fun SourceRecord.nextScanAt(now: Instant): Instant? {
    val interval = SCHEDULES[schedule] ?: return null
    val last = lastScan?.at?.let(Instant::parse) ?: return now
    return last.plus(interval)
}

/** What of the source's server a scan covers. */
fun SourceRecord.scanTarget() = ScanTarget(id, name, includeDatabases, excludeDatabases, scratchDatabase, queryLogWindowDays)

data class SourceFile(val sources: List<SourceRecord> = emptyList())

/** The registered sources, in memory and as sources.json. */
@Component
class SourceRepository(private val store: JsonStore) {
    private val lock = Any()
    private var records: List<SourceRecord> = store.read(FILE, SourceFile::class.java)?.sources ?: emptyList()

    fun all(): List<SourceRecord> = synchronized(lock) { records }

    fun add(record: SourceRecord) = synchronized(lock) { save(records + record) }

    fun replace(record: SourceRecord) = synchronized(lock) { save(records.map { if (it.id == record.id) record else it }) }

    fun remove(id: String) = synchronized(lock) { save(records.filter { it.id != id }) }

    /** Applies a change to the stored record, e.g. a new status or scan result. Null when the source is gone. */
    fun patch(id: String, change: (SourceRecord) -> SourceRecord): SourceRecord? = synchronized(lock) {
        val current = records.firstOrNull { it.id == id } ?: return null
        val next = change(current)
        save(records.map { if (it.id == id) next else it })
        next
    }

    private fun save(next: List<SourceRecord>) {
        records = next
        store.write(FILE, SourceFile(next))
    }

    private companion object {
        const val FILE = "sources.json"
    }
}
