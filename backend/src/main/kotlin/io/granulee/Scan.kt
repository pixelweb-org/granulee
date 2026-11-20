package io.granulee

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

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

/** File estimates per (database, table). Empty when the server's system.parts_columns has no substreams. */
internal fun fileFacts(ch: ChConnection, where: String): Map<Pair<String, String>, FileFacts> = try {
    ch.query(
        """
        SELECT database, table, toInt64(2 * sum(streams) + $FILES_PER_PART * count()) AS files,
               toInt64(2 * max(streams) + $FILES_PER_PART) AS largest_part_files,
               argMax(widest, widest_streams) AS widest_column, toInt64(2 * max(widest_streams)) AS widest_column_files
        FROM (
            SELECT database, table, name, sumIf(length(substreams), part_type = 'Wide') AS streams,
                   argMax(column, length(substreams)) AS widest, maxIf(length(substreams), part_type = 'Wide') AS widest_streams
            FROM system.parts_columns
            WHERE active AND $where
            GROUP BY database, table, name
        )
        GROUP BY database, table
        """.trimIndent(),
    ).associate {
        (it.str("database") to it.str("table")) to
            FileFacts(it.long("files"), it.long("largest_part_files"), it.str("widest_column"), it.long("widest_column_files"))
    }
} catch (e: Exception) {
    LoggerFactory.getLogger("io.granulee.Scan").info("No file counts: {}", e.message)
    emptyMap()
}

/** "database.table" a Buffer table flushes into, from its engine_full: Buffer('db', 'table', …). */
internal fun bufferTarget(database: String, engineFull: String): String? {
    val m = Regex("^Buffer\\(\\s*('(?:[^'\\\\]|\\\\.)*'|[^,]*?)\\s*,\\s*'?((?:[^'\\\\,)]|\\\\.)+)'?").find(engineFull.trim()) ?: return null
    val db = m.groupValues[1].takeIf { it.startsWith("'") }?.trim('\'')?.ifEmpty { null } ?: database
    return "$db.${m.groupValues[2].trim()}"
}

/**
 * Buffer tables by the table they flush into. A query on the Buffer table reads and writes the table behind
 * it, but query_log files it under the Buffer table's name.
 */
internal fun bufferTables(ch: ChConnection): Map<String, List<String>> = try {
    ch.query("SELECT database, name, engine_full FROM system.tables WHERE engine = 'Buffer'")
        .mapNotNull { row -> bufferTarget(row.str("database"), row.str("engine_full"))?.let { it to "${row.str("database")}.${row.str("name")}" } }
        .groupBy({ it.first }, { it.second })
} catch (e: Exception) {
    emptyMap()
}

/** Why reading system.query_log failed, from the server's message. */
internal fun queryLogProblem(message: String?): QueryLogStatus {
    val text = message.orEmpty()
    val state = when {
        Regex("unknown table|doesn'?t exist|does not exist|UNKNOWN_TABLE", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "missing"
        Regex("not enough privileges|ACCESS_DENIED", RegexOption.IGNORE_CASE).containsMatchIn(text) -> "denied"
        else -> "error"
    }
    return QueryLogStatus(state, text.take(300).ifBlank { null })
}

/** Granulee's own scans and trial copies are not how the table is used. */
internal const val NOT_GRANULEE = "NOT startsWith(http_user_agent, 'granulee ')"

// checksums, columns, count, primary index, partition and the like
private const val FILES_PER_PART = 10

data class HistoryPoint(val day: String = "", val sourceId: String = "", val compressedBytes: Long = 0)
data class HistoryFile(val points: List<HistoryPoint> = emptyList())

private const val SYSTEM_DATABASES = "'system', 'INFORMATION_SCHEMA', 'information_schema'"

@Component
class ScanService(
    private val sources: SourceService,
    private val store: JsonStore,
) {
    private val log = LoggerFactory.getLogger(ScanService::class.java)
    private val snapshots = ConcurrentHashMap<String, Snapshot>()
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "granulee-scan").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private var history: List<HistoryPoint> = store.read("history.json", HistoryFile::class.java)?.points ?: emptyList()
    private var ticks = 0

    init {
        sources.all().forEach { source ->
            runCatching { store.read("snapshots/${source.id}.json", Snapshot::class.java) }
                .onFailure { log.warn("Could not read the stored scan of {}: {}", source.name, it.message) }
                .getOrNull()?.let { snapshots[source.id] = it }
        }
    }

    fun snapshot(sourceId: String): Snapshot? = snapshots[sourceId]

    fun history(): List<HistoryPoint> = synchronized(this) { history }

    fun forget(sourceId: String) {
        snapshots.remove(sourceId)
        store.delete("snapshots/$sourceId.json")
        synchronized(this) {
            history = history.filter { it.sourceId != sourceId }
            store.write("history.json", HistoryFile(history))
        }
    }

    fun toDto(source: SourceRecord): SourceDto {
        val snapshot = snapshots[source.id]
        return SourceDto(
            id = source.id,
            name = source.name,
            protocol = source.protocol,
            host = source.host,
            port = source.port,
            username = source.username,
            hasPassword = source.passwordEnc.isNotEmpty(),
            includeDatabases = source.includeDatabases,
            excludeDatabases = source.excludeDatabases,
            schedule = source.schedule,
            queryLogWindowDays = source.queryLogWindowDays,
            trialsEnabled = source.trialsEnabled,
            scratchDatabase = source.scratchDatabase,
            status = source.status,
            lastScan = source.lastScan,
            nextScanAt = sources.nextScanAt(source)?.toString(),
            tables = snapshot?.tables?.size,
            compressedBytes = snapshot?.tables?.sumOf { it.compressedBytes },
            // Snapshots written before the status existed only know whether the log could be read.
            queryLog = snapshot?.let { it.queryLog ?: QueryLogStatus(if (it.queryLogReadable) "ok" else "error") },
        )
    }

    fun scanInBackground(sourceId: String) {
        if (!queued.add(sourceId)) return
        executor.execute {
            try {
                scan(sourceId)
            } catch (e: Exception) {
                log.warn("Background scan of {} failed: {}", sourceId, e.message)
            } finally {
                queued.remove(sourceId)
            }
        }
    }

    /** Scans one source now. A failed scan is recorded on the source; the previous snapshot stays. */
    fun scan(sourceId: String): SourceRecord {
        val lock = locks.computeIfAbsent(sourceId) { ReentrantLock() }
        lock.lock()
        try {
            val source = sources.find(sourceId)
            val started = Instant.now()
            return try {
                val snapshot = collect(source, started)
                snapshots[sourceId] = snapshot
                store.write("snapshots/$sourceId.json", snapshot)
                record(sourceId, snapshot)
                sources.checkHealth(sourceId)
                sources.patch(sourceId) { it.copy(lastScan = LastScan(started.toString(), snapshot.durationMs, null)) }!!
            } catch (e: Exception) {
                log.warn("Scan of {} failed: {}", source.name, e.message)
                sources.checkHealth(sourceId)
                val took = java.time.Duration.between(started, Instant.now()).toMillis()
                sources.patch(sourceId) { it.copy(lastScan = LastScan(started.toString(), took, e.message ?: "Scan failed")) }!!
            }
        } finally {
            lock.unlock()
        }
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    fun tick() {
        val now = Instant.now()
        val healthRound = ticks++ % 5 == 0
        sources.all().forEach { source ->
            val due = sources.nextScanAt(source)
            if (due != null && !due.isAfter(now)) scanInBackground(source.id)
            else if (healthRound) runCatching { sources.checkHealth(source.id) }
        }
    }

    private fun record(sourceId: String, snapshot: Snapshot) = synchronized(this) {
        val day = LocalDate.now(ZoneId.systemDefault()).toString()
        val cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(120).toString()
        history = (history.filter { !(it.day == day && it.sourceId == sourceId) && it.day >= cutoff } +
            HistoryPoint(day, sourceId, snapshot.tables.sumOf { it.compressedBytes })).sortedBy { it.day }
        store.write("history.json", HistoryFile(history))
    }

    private fun collect(source: SourceRecord, started: Instant): Snapshot {
        val ch = sources.connection(source)
        val wanted = databaseFilter(source)

        val tables = ch.query(
            """
            SELECT database, name, engine, engine_full, partition_key, sorting_key, primary_key
            FROM system.tables
            WHERE engine LIKE '%MergeTree' AND NOT is_temporary AND database NOT IN ($SYSTEM_DATABASES)
            """.trimIndent(),
        ).filter { wanted(it.str("database")) }

        val parts = ch.query(
            """
            SELECT database, table, toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed,
                   toInt64(sum(data_uncompressed_bytes)) AS uncompressed, toInt64(sum(bytes_on_disk)) AS on_disk,
                   toInt64(count()) AS parts, toInt64(uniqExact(partition_id)) AS partitions
            FROM system.parts
            WHERE active AND database NOT IN ($SYSTEM_DATABASES)
            GROUP BY database, table
            """.trimIndent(),
        ).associateBy { it.str("database") to it.str("table") }

        val cardinality = cardinalities(ch)
        val columns = ch.query(
            """
            SELECT database, table, name, type, compression_codec, default_kind, default_expression,
                   toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed,
                   is_in_sorting_key, is_in_partition_key
            FROM system.columns
            WHERE database NOT IN ($SYSTEM_DATABASES)
            ORDER BY database, table, position
            """.trimIndent(),
        ).groupBy({ it.str("database") to it.str("table") }) {
            ColumnFacts(
                name = it.str("name"),
                type = it.str("type"),
                codec = it.str("compression_codec"),
                compressedBytes = it.long("compressed"),
                uncompressedBytes = it.long("uncompressed"),
                defaultKind = it.str("default_kind"),
                defaultExpression = it.str("default_expression"),
                inSortingKey = it.bool("is_in_sorting_key"),
                inPartitionKey = it.bool("is_in_partition_key"),
                cardinality = cardinality[Triple(it.str("database"), it.str("table"), it.str("name"))],
            )
        }

        val indexes = ch.query(
            """
            SELECT database, table, name, type_full, expr, toInt64(granularity) AS granularity,
                   toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed
            FROM system.data_skipping_indices
            WHERE database NOT IN ($SYSTEM_DATABASES)
            """.trimIndent(),
        ).groupBy({ it.str("database") to it.str("table") }) {
            IndexFacts(it.str("name"), it.str("expr"), it.str("type_full"), it.long("granularity"), it.long("compressed"), it.long("uncompressed"))
        }

        val buffers = bufferTables(ch)
        var queryLogStatus = QueryLogStatus("ok")
        val queryLog = try {
            queryLog(ch, source.queryLogWindowDays, buffers).also { found ->
                // Readable but nothing in it for any table: tell an idle server from a log that records nothing.
                if (found.isEmpty()) {
                    val entries = ch.first("SELECT count() AS c FROM system.query_log WHERE event_date >= today() - ${source.queryLogWindowDays}")?.long("c") ?: 0
                    if (entries == 0L) queryLogStatus = QueryLogStatus("empty")
                }
            }
        } catch (e: Exception) {
            log.info("query_log is not readable on {}: {}", source.name, e.message)
            queryLogStatus = queryLogProblem(e.message)
            null
        }
        val files = fileFacts(ch, "database NOT IN ($SYSTEM_DATABASES)")

        val facts = tables.map { row ->
            val key = row.str("database") to row.str("name")
            val size = parts[key]
            val queries = queryLog?.get("${key.first}.${key.second}")
            val table = TableFacts(
                database = key.first,
                name = key.second,
                engine = row.str("engine"),
                engineFull = row.str("engine_full"),
                partitionKey = row.str("partition_key"),
                sortingKey = row.str("sorting_key"),
                primaryKey = row.str("primary_key"),
                rows = size?.long("rows") ?: 0,
                compressedBytes = size?.long("compressed") ?: 0,
                uncompressedBytes = size?.long("uncompressed") ?: 0,
                bytesOnDisk = size?.long("on_disk") ?: 0,
                activeParts = size?.long("parts") ?: 0,
                partitions = size?.long("partitions") ?: 0,
                columns = columns[key].orEmpty(),
                indexes = indexes[key].orEmpty(),
                queries = queries?.first.orEmpty(),
                queryTotal = if (queryLog == null) null else queries?.second ?: 0,
                files = files[key],
                buffers = buffers["${key.first}.${key.second}"].orEmpty(),
            )
            val findings = Analyzer.analyze(table, source.queryLogWindowDays)
            table.copy(
                findings = findings.size,
                savingsBytes = findings.sumOf { it.rec.savingsBytes }.coerceAtMost(table.compressedBytes),
                findingsByCategory = findings.groupingBy { it.rec.category }.eachCount(),
            )
        }.sortedByDescending { it.compressedBytes }

        return Snapshot(
            sourceId = source.id,
            scannedAt = started.toString(),
            durationMs = java.time.Duration.between(started, Instant.now()).toMillis(),
            queryLogReadable = queryLog != null,
            queryLog = queryLogStatus,
            tables = facts,
        )
    }

    /** include/exclude lists with * wildcards. The scratch database is never scanned. */
    private fun databaseFilter(source: SourceRecord): (String) -> Boolean {
        fun patterns(list: String) = list.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Regex(it.split('*').joinToString(".*") { part -> Regex.escape(part) }) }
        val include = patterns(source.includeDatabases)
        val exclude = patterns(source.excludeDatabases)
        return { database ->
            database != source.scratchDatabase &&
                (include.isEmpty() || include.any { it.matches(database) }) &&
                exclude.none { it.matches(database) }
        }
    }

    /** Per-part distinct-value estimates, where the server keeps them (recent versions only). */
    private fun cardinalities(ch: ChConnection): Map<Triple<String, String, String>, Long> = try {
        val supported = ch.first(
            "SELECT count() AS c FROM system.columns WHERE database = 'system' AND table = 'parts_columns' AND name = 'estimates.cardinality'",
        )?.long("c") ?: 0
        if (supported == 0L) emptyMap() else ch.query(
            """
            SELECT database, table, column, toInt64(max(ifNull(`estimates.cardinality`, 0))) AS cardinality
            FROM system.parts_columns
            WHERE active AND database NOT IN ($SYSTEM_DATABASES)
            GROUP BY database, table, column
            HAVING cardinality > 0
            """.trimIndent(),
        ).associate { Triple(it.str("database"), it.str("table"), it.str("column")) to it.long("cardinality") }
    } catch (e: Exception) {
        log.info("No cardinality estimates: {}", e.message)
        emptyMap()
    }

    /** The 20 most frequent SELECT shapes per table, and the total SELECT count. Throws when query_log cannot be read. */
    private fun queryLog(ch: ChConnection, days: Int, buffers: Map<String, List<String>>): Map<String, Pair<List<QueryFacts>, Long>> {
        // Queries sent to a Buffer table count for the table behind it.
        val behind = buffers.flatMap { (target, fronts) -> fronts.map { it to target } }.toMap()
        return ch.query(
            """
            SELECT t, substring(q, 1, 20000) AS q, substring(normalizeQuery(q), 1, 4000) AS nq, toInt64(c) AS c,
                   toInt64(sum(c) OVER (PARTITION BY t)) AS total
            FROM (
                SELECT arrayJoin(tables) AS t, normalized_query_hash AS h, any(query) AS q, count() AS c
                FROM system.query_log
                WHERE event_date >= today() - $days AND type = 'QueryFinish' AND query_kind = 'Select' AND is_initial_query AND $NOT_GRANULEE
                GROUP BY t, h
            )
            WHERE NOT startsWith(t, 'system.')
            ORDER BY c DESC
            LIMIT 20 BY t
            """.trimIndent(),
        ).groupBy { it.str("t") }.mapValues { (_, rows) ->
            rows.map { QueryFacts(it.str("q"), it.str("nq"), it.long("c")) } to rows.first().long("total")
        }.entries.groupBy({ behind[it.key] ?: it.key }, { it.value }).mapValues { (_, parts) ->
            parts.flatMap { it.first }.sortedByDescending { it.count }.take(20) to parts.sumOf { it.second }
        }
    }
}
