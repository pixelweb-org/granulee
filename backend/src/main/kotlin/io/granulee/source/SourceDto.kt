package io.granulee.source

import io.granulee.scan.QueryLogStatus
import io.granulee.scan.Snapshot
import java.time.Instant

/** A source as the API shows it: the record without its password, with what the last scan found. */
data class SourceDto(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val hasPassword: Boolean,
    val includeDatabases: String,
    val excludeDatabases: String,
    val schedule: String,
    val queryLogWindowDays: Int,
    val trialsEnabled: Boolean,
    val scratchDatabase: String,
    val status: SourceStatus,
    val lastScan: LastScan?,
    val nextScanAt: String?,
    val tables: Int?,
    val compressedBytes: Long?,
    /** Whether the last scan could use system.query_log; null before the first scan. */
    val queryLog: QueryLogStatus? = null,
)

data class SourceInput(
    val id: String? = null, // only read by /api/sources/test
    val name: String = "",
    val protocol: String = "https",
    val host: String = "",
    val port: Int = 8443,
    val username: String = "",
    val password: String? = null,
    val includeDatabases: String = "",
    val excludeDatabases: String = "",
    val schedule: String = "every6h",
    val queryLogWindowDays: Int = 30,
    val trialsEnabled: Boolean = false,
    val scratchDatabase: String = "scratch",
)

data class ConnectionTest(val ok: Boolean, val version: String?, val latencyMs: Long?, val error: String?)

fun SourceRecord.toDto(snapshot: Snapshot?, now: Instant) = SourceDto(
    id = id,
    name = name,
    protocol = protocol,
    host = host,
    port = port,
    username = username,
    hasPassword = passwordEnc.isNotEmpty(),
    includeDatabases = includeDatabases,
    excludeDatabases = excludeDatabases,
    schedule = schedule,
    queryLogWindowDays = queryLogWindowDays,
    trialsEnabled = trialsEnabled,
    scratchDatabase = scratchDatabase,
    status = status,
    lastScan = lastScan,
    nextScanAt = nextScanAt(now)?.toString(),
    tables = snapshot?.tables?.size,
    compressedBytes = snapshot?.tables?.sumOf { it.compressedBytes },
    // Snapshots written before the status existed only know whether the log could be read.
    queryLog = snapshot?.let { it.queryLog ?: QueryLogStatus(if (it.queryLogReadable) "ok" else "error") },
)
