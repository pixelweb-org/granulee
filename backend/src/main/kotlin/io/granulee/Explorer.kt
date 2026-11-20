package io.granulee

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

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

@RestController
@RequestMapping("/api")
class ExplorerController(
    private val sources: SourceService,
    private val scans: ScanService,
    private val dismissedStore: DismissedStore,
    private val trials: TrialService,
) {
    private fun inScope(sourceId: String?): List<SourceRecord> =
        if (sourceId.isNullOrBlank()) sources.all() else listOf(sources.find(sourceId))

    /** Findings and savings of a table with dismissed recommendations left out. */
    private fun open(source: SourceRecord, table: TableFacts): List<Finding> {
        val dismissed = dismissedStore.of(source.id, table.database, table.name)
        return Analyzer.analyze(table, source.queryLogWindowDays).filter { it.rec.id !in dismissed }
    }

    private fun opportunities(scope: List<SourceRecord>): List<Opportunity> = scope.flatMap { source ->
        scans.snapshot(source.id)?.tables.orEmpty().filter { it.findings > 0 }.mapNotNull { table ->
            val findings = open(source, table)
            if (findings.isEmpty()) null else Opportunity(
                sourceId = source.id,
                sourceName = source.name,
                database = table.database,
                table = table.name,
                compressedBytes = table.compressedBytes,
                uncompressedBytes = table.uncompressedBytes,
                findings = findings.size,
                savingsBytes = findings.sumOf { it.rec.savingsBytes }.coerceAtMost(table.compressedBytes),
            )
        }
    }.sortedWith(compareByDescending<Opportunity> { it.savingsBytes }.thenByDescending { it.findings })

    @GetMapping("/overview")
    fun overview(@RequestParam(required = false) source: String?): Overview {
        val scope = inScope(source)
        val tables = scope.flatMap { scans.snapshot(it.id)?.tables.orEmpty() }
        val all = opportunities(scope)

        val byCategory = scope.flatMap { s -> scans.snapshot(s.id)?.tables.orEmpty().filter { it.findings > 0 }.flatMap { open(s, it) } }
            .groupingBy { it.rec.category }.eachCount().entries.sortedByDescending { it.value }

        val history = scans.history().filter { point -> scope.any { it.id == point.sourceId } }
        val days = history.map { it.day }.distinct().sorted().takeLast(12)
        val growth = ChartData(
            categories = days.map { it.substring(5) },
            series = scope.filter { s -> history.any { it.sourceId == s.id } }.map { s ->
                val points = history.filter { it.sourceId == s.id }
                Series(s.name, days.map { day -> (points.lastOrNull { it.day <= day }?.compressedBytes ?: 0).toDouble() })
            },
        )

        val scanTimes = scope.mapNotNull { it.lastScan?.at?.let(Instant::parse) }
        return Overview(
            totals = Totals(
                compressedBytes = tables.sumOf { it.compressedBytes },
                uncompressedBytes = tables.sumOf { it.uncompressedBytes },
                tables = tables.size,
                findings = all.sumOf { it.findings },
                savingsBytes = all.sumOf { it.savingsBytes },
            ),
            sources = scope.map(scans::toDto),
            growth = growth,
            findingsByCategory = ChartData(byCategory.map { it.key }, listOf(Series("Findings", byCategory.map { it.value.toDouble() }))),
            opportunities = all.take(5),
            lastScanAt = scanTimes.maxOrNull()?.toString(),
            nextScanAt = scope.mapNotNull { sources.nextScanAt(it) }.minOrNull()?.toString(),
        )
    }

    @GetMapping("/opportunities")
    fun allOpportunities(@RequestParam(required = false) source: String?): List<Opportunity> = opportunities(inScope(source))

    @GetMapping("/sources/{id}/databases")
    fun databases(@PathVariable id: String): DatabaseList {
        val source = sources.find(id)
        val snapshot = scans.snapshot(id)
        return DatabaseList(id, source.name, summaries(snapshot), snapshot?.scannedAt)
    }

    private fun summaries(snapshot: Snapshot?): List<DatabaseSummary> = snapshot?.tables.orEmpty().groupBy { it.database }
        .map { (name, tables) -> DatabaseSummary(name, tables.size, tables.sumOf { it.compressedBytes }, tables.sumOf { it.uncompressedBytes }) }
        .sortedByDescending { it.compressedBytes }

    @GetMapping("/sources/{id}/databases/{db}")
    fun database(@PathVariable id: String, @PathVariable db: String): DatabaseDetail {
        val source = sources.find(id)
        val snapshot = scans.snapshot(id)
        val summary = summaries(snapshot).firstOrNull { it.name == db }
            ?: throw NotFoundException("Database $db was not in the last scan of ${source.name}")
        val tables = snapshot!!.tables.filter { it.database == db }.map {
            TableSummary(it.database, it.name, it.engine, it.rows, it.compressedBytes, it.uncompressedBytes, it.activeParts, open(source, it).size, it.files?.files)
        }
        return DatabaseDetail(id, source.name, summary, tables, snapshot.scannedAt)
    }

    @GetMapping("/sources/{id}/databases/{db}/tables/{table}")
    fun table(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableDetail {
        val source = sources.find(id)
        val ch = sources.connection(source)
        val where = "database = ${Sql.lit(db)} AND table = ${Sql.lit(table)}"

        val meta = ch.first(
            """
            SELECT engine, engine_full, partition_key, sorting_key, primary_key, storage_policy, comment, create_table_query
            FROM system.tables WHERE database = ${Sql.lit(db)} AND name = ${Sql.lit(table)}
            """.trimIndent(),
        ) ?: throw NotFoundException("Table $db.$table does not exist on ${source.name}")

        val size = ch.first(
            """
            SELECT toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed,
                   toInt64(sum(data_uncompressed_bytes)) AS uncompressed, toInt64(sum(bytes_on_disk)) AS on_disk,
                   toInt64(count()) AS parts, toInt64(uniqExact(partition_id)) AS partitions,
                   toInt64(sum(primary_key_bytes_in_memory)) AS pk, toInt64(sum(primary_key_bytes_in_memory_allocated)) AS pk_allocated,
                   toInt64(sum(marks)) AS marks, toInt64(sum(marks_bytes)) AS marks_bytes,
                   toInt64(sum(secondary_indices_compressed_bytes)) AS index_bytes
            FROM system.parts WHERE active AND $where
            """.trimIndent(),
        ) ?: emptyMap()

        val facts = scans.snapshot(id)?.table(db, table)
        val findings = facts?.let { open(source, it) }.orEmpty()
        val labels = Analyzer.columnLabels(findings)

        val columns = ch.query(
            """
            SELECT name, type, compression_codec, toInt64(data_compressed_bytes) AS compressed,
                   toInt64(data_uncompressed_bytes) AS uncompressed
            FROM system.columns WHERE $where ORDER BY data_compressed_bytes DESC, position
            """.trimIndent(),
        ).map {
            ColumnInfo(it.str("name"), it.str("type"), Types.codecLabel(it.str("compression_codec")), it.long("compressed"), it.long("uncompressed"), labels[it.str("name")])
        }

        val partitions = ch.query(
            """
            SELECT partition, toInt64(sum(data_compressed_bytes)) AS compressed, toInt64(count()) AS parts
            FROM system.parts WHERE active AND $where
            GROUP BY partition, partition_id ORDER BY partition_id DESC LIMIT 14
            """.trimIndent(),
        ).map { PartitionStat(it.str("partition"), it.long("compressed"), it.long("parts")) }.reversed()

        val indexes = ch.query(
            """
            SELECT name, expr, type_full, toInt64(granularity) AS granularity,
                   toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed
            FROM system.data_skipping_indices WHERE $where ORDER BY name
            """.trimIndent(),
        ).map { SkipIndex(it.str("name"), it.str("expr"), it.str("type_full"), it.long("granularity"), it.long("compressed"), it.long("uncompressed")) }

        val engineFull = meta.str("engine_full")
        return TableDetail(
            sourceId = id,
            sourceName = source.name,
            database = db,
            name = table,
            engine = meta.str("engine"),
            engineFull = engineFull,
            partitionKey = meta.str("partition_key"),
            sortingKey = meta.str("sorting_key"),
            primaryKey = meta.str("primary_key"),
            ttl = clause(engineFull, "TTL"),
            storagePolicy = meta.str("storage_policy"),
            comment = meta.str("comment"),
            rows = size.long("rows"),
            compressedBytes = size.long("compressed"),
            uncompressedBytes = size.long("uncompressed"),
            bytesOnDisk = size.long("on_disk"),
            activeParts = size.long("parts"),
            partitions = size.long("partitions"),
            primaryKeyBytesInMemory = size.long("pk"),
            primaryKeyBytesInMemoryAllocated = size.long("pk_allocated"),
            marks = size.long("marks"),
            marksBytes = size.long("marks_bytes"),
            skipIndexBytes = size.long("index_bytes"),
            settings = clause(engineFull, "SETTINGS")?.let(::splitTopLevel).orEmpty().mapNotNull {
                val at = it.indexOf('=')
                if (at < 0) null else Setting(it.substring(0, at).trim(), it.substring(at + 1).trim())
            },
            columns = columns,
            partitionStats = partitions,
            indexes = indexes,
            createQuery = meta.str("create_table_query"),
            findings = findings.size,
            files = fileFacts(ch, where)[db to table],
            buffers = bufferTables(ch)["$db.$table"].orEmpty(),
        )
    }

    /** Pulls "TTL …" or "SETTINGS …" out of engine_full. */
    private fun clause(engineFull: String, keyword: String): String? {
        val start = engineFull.indexOf(" $keyword ")
        if (start < 0) return null
        val rest = engineFull.substring(start + keyword.length + 2)
        val end = if (keyword == "TTL") rest.indexOf(" SETTINGS ") else -1
        return (if (end < 0) rest else rest.substring(0, end)).trim().ifEmpty { null }
    }

    @GetMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations")
    fun recommendations(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String): TableRecommendations {
        val source = sources.find(id)
        val facts = scans.snapshot(id)?.table(db, table)
            ?: throw NotFoundException("Table $db.$table was not in the last scan of ${source.name}. Scan the source first.")
        val dismissed = dismissedStore.of(id, db, table)
        val all = Analyzer.analyze(facts, source.queryLogWindowDays).map { it.rec }
        val items = all.filter { it.id !in dismissed }
        return TableRecommendations(
            sourceId = id,
            sourceName = source.name,
            database = db,
            table = table,
            compressedBytes = facts.compressedBytes,
            items = items,
            dismissed = all.size - items.size,
            savingsBytes = items.sumOf { it.savingsBytes }.coerceAtMost(facts.compressedBytes),
            counts = ChangeCounts(
                online = items.count { it.changeKind == "online" },
                mutation = items.count { it.changeKind == "mutation" },
                rebuild = items.count { it.changeKind == "rebuild" },
            ),
            queryLogWindowDays = source.queryLogWindowDays,
            queryLogQueries = facts.queryTotal,
            latestTrial = trials.latestCompleted(id, db, table),
        )
    }

    @PostMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun dismiss(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String, @RequestBody body: DismissRequest) {
        sources.find(id)
        if (body.id.isBlank()) throw BadRequestException("Name the recommendation to dismiss")
        dismissedStore.add(id, db, table, body.id)
    }

    @DeleteMapping("/sources/{id}/databases/{db}/tables/{table}/recommendations/dismissed")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restore(@PathVariable id: String, @PathVariable db: String, @PathVariable table: String) {
        sources.find(id)
        dismissedStore.clear(id, db, table)
    }
}
