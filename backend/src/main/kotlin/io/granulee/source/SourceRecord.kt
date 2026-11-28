package io.granulee.source

import io.granulee.scan.ScanTarget
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

/** A data source as it is stored. It belongs to the user who added it; the password is AES-GCM encrypted. */
data class SourceRecord(
    val id: String,
    val ownerId: String,
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

/** Where the data sources are kept. */
interface SourceStorage {
    fun all(): List<SourceRecord>

    /** Stores the record, in place of the one with its id if there is one. */
    fun save(record: SourceRecord)

    fun remove(id: String)
}

/** The registered sources of every user, in memory and in the storage. */
@Component
class SourceRepository(private val storage: SourceStorage) {
    private val lock = Any()
    private var records: List<SourceRecord> = storage.all()

    fun all(): List<SourceRecord> = synchronized(lock) { records }

    fun add(record: SourceRecord) = synchronized(lock) {
        storage.save(record)
        records = records + record
    }

    fun replace(record: SourceRecord) = synchronized(lock) {
        storage.save(record)
        records = records.map { if (it.id == record.id) record else it }
    }

    fun remove(id: String) = synchronized(lock) {
        storage.remove(id)
        records = records.filter { it.id != id }
    }

    /** Applies a change to the stored record, e.g. a new status or scan result. Null when the source is gone. */
    fun patch(id: String, change: (SourceRecord) -> SourceRecord): SourceRecord? = synchronized(lock) {
        val current = records.firstOrNull { it.id == id } ?: return null
        val next = change(current)
        storage.save(next)
        records = records.map { if (it.id == id) next else it }
        next
    }
}
