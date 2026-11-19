package io.granulee

import org.slf4j.LoggerFactory
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
import java.security.SecureRandom
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

data class TrialFile(val trials: List<Trial> = emptyList())

private class TrialCancelled : RuntimeException("Cancelled")

/** What a trial reads about the production table right before it starts. */
internal data class LiveTable(
    val engineFull: String,
    val partitionKey: String,
    val sortingKey: String,
    val primaryKey: String,
    val indexGranularity: String?,
    val rows: Long,
    val compressedBytes: Long,
    val columns: List<ColumnFacts>,
    val indexes: List<IndexFacts>,
)

private data class Sizes(
    val rows: Long,
    val compressed: Long,
    val uncompressed: Long,
    val onDisk: Long,
    val parts: Long,
    val primaryKeyBytes: Long,
    val columns: Map<String, Pair<Long, Long>>,
)

/** "(a, b)" and "a, b" mean the same sorting key. */
fun normalizeKey(key: String): String {
    val k = key.trim()
    if (!k.startsWith("(") || !k.endsWith(")")) return k
    var depth = 0
    for ((i, ch) in k.withIndex()) {
        if (ch == '(') depth++
        if (ch == ')') depth--
        if (depth == 0 && i < k.length - 1) return k
    }
    return k.substring(1, k.length - 1).trim()
}

private fun sameText(a: String, b: String) = a.replace(Regex("\\s+"), "") == b.replace(Regex("\\s+"), "")

/**
 * A JSON, Dynamic or Variant column is stored as one set of files per path, easily thousands per wide part, and
 * every reading or writing thread opens its own. With the server's default of one thread per core that runs into
 * "Too many open files", so trials on such tables read and write with few threads.
 */
internal fun hasDynamicSubcolumns(type: String): Boolean =
    Regex("\\b(JSON|Dynamic|Variant)\\b").containsMatchIn(type.replace(Regex("'(?:[^'\\\\]|\\\\.)*'"), ""))

private val FEW_FILES = mapOf("max_threads" to "2", "max_insert_threads" to "1")

/** CREATE TABLE with the production columns, and the request's changes if given. */
internal fun createSql(target: String, live: LiveTable, request: TrialRequest?, engine: String, tail: String): String {
    val overrides = request?.columns?.associateBy { it.name }.orEmpty()
    val columns = live.columns.map { c ->
        val o = overrides[c.name]
        val type = o?.type?.ifBlank { null } ?: c.type
        val codec = if (o != null) o.codec.trim() else Types.codecBody(c.codec)
        buildString {
            append("    ${Sql.id(c.name)} $type")
            if (c.defaultKind.isNotEmpty()) append(" ${c.defaultKind} ${c.defaultExpression}")
            if (codec.isNotEmpty() && c.defaultKind != "ALIAS") append(" CODEC($codec)")
        }
    }
    val indexes = live.indexes.filter { request == null || it.name !in request.dropIndexes }
        .map {
            // system.data_skipping_indices lists an index over several expressions as "a, b"; DDL wants "(a, b)".
            val expression = if (splitTopLevel(it.expression).size > 1) "(${it.expression})" else it.expression
            "    INDEX ${Sql.id(it.name)} $expression TYPE ${it.type} GRANULARITY ${it.granularity}"
        }
    val partitionKey = request?.partitionKey ?: live.partitionKey
    val sortingKey = normalizeKey(request?.sortingKey ?: live.sortingKey)
    return buildString {
        append("CREATE TABLE $target\n(\n")
        append((columns + indexes).joinToString(",\n"))
        append("\n)\nENGINE = $engine\n")
        if (partitionKey.isNotBlank()) append("PARTITION BY $partitionKey\n")
        if (live.primaryKey.isNotBlank() && !sameText(live.primaryKey, live.sortingKey) && sameText(sortingKey, normalizeKey(live.sortingKey))) {
            append("PRIMARY KEY (${live.primaryKey})\n")
        }
        append("ORDER BY (${sortingKey.ifBlank { "tuple()" }})")
        if (tail.isNotBlank()) append("\n$tail")
    }
}

/** A trial table: plain MergeTree. Wide parts only, because compact parts keep all columns in one file and per-column sizes would read as zero. */
private fun trialSql(target: String, live: LiveTable, request: TrialRequest?): String = createSql(
    target, live, request, "MergeTree",
    "SETTINGS min_bytes_for_wide_part = 0, min_rows_for_wide_part = 0" + (live.indexGranularity?.let { ", index_granularity = $it" } ?: ""),
)

/** How a copied column is read when the candidate gives it another type. */
private fun copyExpression(c: ColumnFacts, newType: String?): String = when {
    newType == null || sameText(newType, c.type) -> Sql.id(c.name)
    Types.isNullable(c.type) && !Types.isNullable(newType) ->
        "CAST(ifNull(${Sql.id(c.name)}, defaultValueOfTypeName(${Sql.lit(Types.base(c.type))})) AS $newType) AS ${Sql.id(c.name)}"
    else -> "CAST(${Sql.id(c.name)} AS $newType) AS ${Sql.id(c.name)}"
}

/**
 * The statements that bring the production table to the candidate's schema: ALTER TABLE where ClickHouse
 * can change the table in place, a rebuild script where it cannot. Empty when the candidate changes nothing.
 */
internal fun migrationSql(database: String, table: String, live: LiveTable, request: TrialRequest): String {
    val name = Sql.table(database, table)
    val overrides = request.columns.associateBy { it.name }
    // type / codec are null where the candidate keeps what production has
    val changes = live.columns.mapNotNull { c ->
        val o = overrides[c.name] ?: return@mapNotNull null
        val type = o.type.ifBlank { c.type }.takeIf { !sameText(it, c.type) }
        val codec = o.codec.trim().takeIf { !sameText(it, Types.codecBody(c.codec)) }
        if (type == null && codec == null) null else Triple(c, type, codec)
    }
    val dropped = live.indexes.filter { it.name in request.dropIndexes }

    val rebuild = buildList {
        if (!sameText(request.partitionKey, live.partitionKey)) add("the partition key changes")
        if (!sameText(normalizeKey(request.sortingKey), normalizeKey(live.sortingKey))) add("the sorting key changes")
        changes.filter { (c, type, _) -> type != null && (c.inSortingKey || c.inPartitionKey) }
            .forEach { (c, _, _) -> add("${c.name} is a key column and changes its type") }
    }
    if (rebuild.isNotEmpty()) {
        val fresh = Sql.table(database, table + "_new")
        val copied = live.columns.filter { it.defaultKind.isEmpty() || it.defaultKind == "DEFAULT" }
        val engine = Analyzer.engineClause(live.engineFull).ifBlank { "MergeTree" }
        return buildString {
            append("-- ALTER TABLE cannot do this in place: ${rebuild.joinToString(", ")}.\n")
            append("-- The table is rebuilt instead. Pause writes while the rows are copied, or backfill them afterwards.\n")
            if (engine.startsWith("Replicated")) append("-- Replicated table: give the new table its own ZooKeeper path.\n")
            append(createSql(fresh, live, request, engine, Analyzer.engineTail(live.engineFull)))
            append(";\n\nINSERT INTO $fresh (${copied.joinToString(", ") { Sql.id(it.name) }})\nSELECT ")
            append(copied.joinToString(", ") { copyExpression(it, overrides[it.name]?.type?.ifBlank { null }) })
            append("\nFROM $name;\n\nEXCHANGE TABLES $name AND $fresh;\n\n")
            append("-- $fresh now holds the old data. Drop it once the new table is verified:\n")
            append("-- DROP TABLE $fresh;")
        }
    }

    fun alter(comment: String, actions: List<String>) =
        "-- $comment\nALTER TABLE $name\n" + actions.joinToString(",\n") { "    $it" } + ";"

    val blocks = mutableListOf<String>()
    val types = changes.filter { it.second != null }
    if (types.isNotEmpty()) {
        val notes = types.filter { (c, type, _) -> Types.isNullable(c.type) && !Types.isNullable(type!!) }
            .joinToString("") { (c, _, _) -> "\n-- ${c.name} loses Nullable: the ALTER fails while the column still holds NULLs." }
        blocks.add(
            alter(
                "Type changes run as a mutation that rewrites the column in every part. Progress is in system.mutations.$notes",
                types.map { (c, type, codec) -> "MODIFY COLUMN ${Sql.id(c.name)} $type" + if (codec.isNullOrEmpty()) "" else " CODEC($codec)" },
            ),
        )
    }
    // A codec that only changes, and a codec that is removed next to a type change.
    val codecs = changes.filter { (_, type, codec) -> codec != null && (type == null || codec.isEmpty()) }
    if (codecs.isNotEmpty()) {
        blocks.add(
            alter(
                "Codec changes only touch metadata. Existing parts keep the old codec until they are merged;\n" +
                    "-- OPTIMIZE TABLE $name FINAL rewrites them right away.",
                codecs.map { (c, _, codec) -> "MODIFY COLUMN ${Sql.id(c.name)} " + if (codec!!.isEmpty()) "REMOVE CODEC" else "CODEC($codec)" },
            ),
        )
    }
    if (dropped.isNotEmpty()) {
        blocks.add(alter("Skip indexes the candidate leaves out.", dropped.map { "DROP INDEX ${Sql.id(it.name)}" }))
    }
    return blocks.joinToString("\n\n")
}

private fun median(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

/** Points a logged production query at a trial table. */
fun rewriteQuery(query: String, database: String, table: String, target: String): String {
    val body = query.trim().trimEnd(';').replace(Regex("\\s+FORMAT\\s+\\w+\\s*$", RegexOption.IGNORE_CASE), "")
    val name = Regex.escape(table)
    val db = Regex.escape(database)
    val pattern = Regex("(?<![\\w.`])(?:`?$db`?\\.)?`?$name`?(?![\\w`.(])")
    return pattern.replace(body) { target }
}

/**
 * Trials: a baseline and a candidate table in the scratch database, filled with the same sample of
 * production rows, merged, measured and queried side by side.
 */
@Component
class TrialService(
    private val sources: SourceService,
    private val scans: ScanService,
    private val store: JsonStore,
) {
    private val log = LoggerFactory.getLogger(TrialService::class.java)
    private val lock = Any()
    private var trials: List<Trial> = store.read("trials.json", TrialFile::class.java)?.trials ?: emptyList()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "granulee-trial").apply { isDaemon = true } }
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val runningQuery = ConcurrentHashMap<String, String>()
    private val random = SecureRandom()

    init {
        val interrupted = trials.filter { it.status == "running" }
        if (interrupted.isNotEmpty()) {
            val now = Instant.now().toString()
            save(trials.map {
                if (it.status != "running") it else it.copy(
                    status = "failed",
                    error = "Granulee restarted while this trial was running. Its scratch tables may still exist; remove the trial to drop them.",
                    finishedAt = now,
                    steps = it.steps.map { s -> if (s.state == "running") s.copy(state = "failed") else s },
                )
            })
        }
    }

    fun all(): List<Trial> = synchronized(lock) { trials }

    fun find(id: String): Trial = all().firstOrNull { it.id == id } ?: throw NotFoundException("Trial #$id does not exist")

    fun latestCompleted(sourceId: String, database: String, table: String): Trial? = all()
        .filter { it.sourceId == sourceId && it.database == database && it.table == table && it.status == "completed" }
        .maxByOrNull { it.createdAt }

    fun list(sourceId: String?): TrialList {
        val listed = all().filter { sourceId.isNullOrBlank() || it.sourceId == sourceId }.sortedByDescending { it.createdAt }
        val free = if (sourceId.isNullOrBlank()) null else runCatching { freeBytes(sources.connection(sources.find(sourceId))) }.getOrNull()
        return TrialList(listed, listed.sumOf { it.scratchBytes }, free)
    }

    private fun freeBytes(ch: ChConnection): Long? =
        ch.first("SELECT toInt64(free_space) AS free FROM system.disks ORDER BY name = 'default' DESC LIMIT 1")?.longOrNull("free")

    // ---- plan ----

    fun plan(sourceId: String, database: String, table: String): TrialPlan {
        val source = sources.find(sourceId)
        val facts = scans.snapshot(sourceId)?.table(database, table)
            ?: throw NotFoundException("Table $database.$table was not in the last scan of ${source.name}. Scan the source first.")
        val findings = Analyzer.analyze(facts, source.queryLogWindowDays).mapNotNull { it.plan }
        val changes = findings.mapNotNull { it.column }.associateBy { it.name }
        val columns = facts.columns.filter { it.defaultKind != "ALIAS" && it.defaultKind != "EPHEMERAL" }.map { c ->
            val codec = Types.codecBody(c.codec)
            val change = changes[c.name]
            val suggestedType = change?.type ?: c.type
            val suggestedCodec = change?.codec ?: codec
            TrialPlanColumn(
                name = c.name,
                baselineType = c.type,
                baselineCodec = Types.codecLabel(c.codec),
                suggestedType = suggestedType,
                suggestedCodec = suggestedCodec,
                typeOptions = (listOf(c.type, suggestedType) + typeAlternatives(c.type)).distinct(),
                codecOptions = (listOf("", "LZ4", "ZSTD(1)", "ZSTD(3)", "ZSTD(6)") + codecAlternatives(c.type) + listOf(codec, suggestedCodec)).distinct(),
            )
        }
        val recommended = (facts.rows / 100).coerceIn(10_000_000, 100_000_000).coerceAtMost(facts.rows)
        return TrialPlan(
            sourceId = sourceId,
            sourceName = source.name,
            database = database,
            table = table,
            trialsEnabled = source.trialsEnabled,
            scratchDatabase = source.scratchDatabase,
            scratchFreeBytes = runCatching { freeBytes(sources.connection(source)) }.getOrNull(),
            totalRows = facts.rows,
            compressedBytes = facts.compressedBytes,
            recommendedRows = recommended,
            partitionKey = facts.partitionKey,
            sortingKey = facts.sortingKey,
            columns = columns,
            indexes = facts.indexes.map { it.name },
            recommendations = findings,
            replayableQueries = facts.queries.size,
        )
    }

    private fun typeAlternatives(type: String): List<String> {
        val base = Types.base(type)
        val out = mutableListOf<String>()
        if (Types.isNullable(type) || Types.isLowCardinality(type)) out.add(base)
        when {
            base == "String" -> out.add("LowCardinality(String)")
            base == "Int64" -> out.addAll(listOf("Int32", "UInt32"))
            base == "UInt64" -> out.add("UInt32")
            base == "Int32" -> out.add("Int16")
            base == "UInt32" -> out.add("UInt16")
            base == "Float64" -> out.add("Float32")
            base.startsWith("DateTime64") -> out.add("DateTime")
        }
        return out
    }

    private fun codecAlternatives(type: String): List<String> = when {
        Types.isTime(type) -> listOf("Delta, ZSTD(1)", "DoubleDelta, ZSTD(1)")
        Types.isInteger(type) -> listOf("T64, ZSTD(1)", "Delta, ZSTD(1)")
        Types.isFloat(type) -> listOf("Gorilla, ZSTD(1)")
        else -> emptyList()
    }

    // ---- lifecycle ----

    fun start(request: TrialRequest): Trial {
        val source = sources.find(request.sourceId)
        if (!source.trialsEnabled) throw BadRequestException("Trials are switched off for ${source.name}. Allow them under Data sources.")
        if (request.database.isBlank() || request.table.isBlank()) throw BadRequestException("Pick a table for the trial")
        if (request.rows < 1) throw BadRequestException("Rows to copy must be at least 1")
        if (request.runsPerQuery !in 1..20) throw BadRequestException("Runs per query must be 1 to 20")
        if (request.sampling !in setOf("even", "recent", "where")) throw BadRequestException("Unknown sampling method")
        if (request.sampling == "where" && request.where.isNullOrBlank()) throw BadRequestException("Enter the WHERE clause for the sample")
        if (request.where.orEmpty().contains(';')) throw BadRequestException("The WHERE clause must be a single expression")
        if (request.columns.isEmpty()) throw BadRequestException("The candidate schema has no columns")

        val id = synchronized(lock) {
            generateSequence { String.format("%04x", random.nextInt(0x10000)) }.first { candidate -> trials.none { it.id == candidate } }
        }
        val steps = buildList {
            add("Create baseline and candidate tables")
            add("Copy ${Fmt.rows(request.rows)} rows to baseline")
            add("Copy the same rows to candidate")
            add(if (request.optimizeFinal) "Full merge, measure sizes" else "Measure sizes")
            if (request.columnScans) add("Scan every column")
            if (request.replayQueries) add("Replay queries")
        }
        val trial = Trial(
            id = id,
            name = request.name.trim().ifEmpty { "Trial of ${request.table}" },
            sourceId = source.id,
            sourceName = source.name,
            database = request.database,
            table = request.table,
            status = "running",
            error = null,
            createdAt = Instant.now().toString(),
            finishedAt = null,
            progress = 0,
            steps = steps.map { TrialStep(it, "pending") },
            request = request.copy(sortingKey = normalizeKey(request.sortingKey), partitionKey = request.partitionKey.trim()),
            scratchDatabase = source.scratchDatabase,
            baseTable = "${request.table}_trial_${id}_base",
            candTable = "${request.table}_trial_${id}_cand",
            scratchBytes = 0,
            changedKeys = 0,
            changedColumns = 0,
            result = null,
            log = emptyList(),
        )
        synchronized(lock) { save(trials + trial) }
        pool.execute { run(id) }
        return trial
    }

    fun cancel(id: String): Trial {
        val trial = find(id)
        if (trial.status != "running") return trial
        cancelled.add(id)
        runningQuery[id]?.let { queryId ->
            runCatching {
                sources.connection(sources.find(trial.sourceId)).exec("KILL QUERY WHERE query_id = ${Sql.lit(queryId)}")
            }
        }
        return find(id)
    }

    fun delete(id: String) {
        val trial = find(id)
        if (trial.status == "running") throw BadRequestException("Trial #$id is still running. Cancel it first.")
        val source = sources.all().firstOrNull { it.id == trial.sourceId }
        if (source != null) dropTables(sources.connection(source), trial)
        synchronized(lock) { save(trials.filter { it.id != id }) }
    }

    private fun dropTables(ch: ChConnection, trial: Trial) {
        listOf(trial.baseTable, trial.candTable, trial.baseTable + "_ins").forEach {
            ch.exec("DROP TABLE IF EXISTS ${Sql.table(trial.scratchDatabase, it)} SYNC")
        }
    }

    private fun update(id: String, change: (Trial) -> Trial): Trial = synchronized(lock) {
        val current = trials.first { it.id == id }
        val next = change(current)
        save(trials.map { if (it.id == id) next else it })
        next
    }

    private fun save(next: List<Trial>) {
        trials = next
        store.write("trials.json", TrialFile(next))
    }

    // ---- the run ----

    private fun run(id: String) {
        val trial = find(id)
        val request = trial.request
        var ch: ChConnection? = null
        var statement = 0

        fun say(message: String) = update(id) { it.copy(log = it.log + LogLine(Instant.now().toString(), message)) }
        fun progress(value: Int) = update(id) { it.copy(progress = value.coerceIn(0, 100)) }
        fun enter(step: Int, percent: Int) = update(id) {
            it.copy(
                progress = percent,
                steps = it.steps.mapIndexed { i, s -> s.copy(state = if (i < step) "done" else if (i == step) "running" else "pending") },
            )
        }
        // Set once the live table is known; applied to every statement of the run.
        var limits = emptyMap<String, String>()
        fun exec(sql: String, settings: Map<String, String> = emptyMap()): ExecStats {
            if (id in cancelled) throw TrialCancelled()
            val queryId = "granulee-$id-${++statement}"
            runningQuery[id] = queryId
            try {
                return ch!!.exec(sql, limits + settings, queryId)
            } catch (e: ChException) {
                if (id in cancelled) throw TrialCancelled()
                throw e
            } finally {
                runningQuery.remove(id)
            }
        }

        try {
            val source = sources.find(trial.sourceId)
            ch = sources.connection(source)
            val conn = ch
            val production = Sql.table(trial.database, trial.table)
            val base = Sql.table(trial.scratchDatabase, trial.baseTable)
            val cand = Sql.table(trial.scratchDatabase, trial.candTable)

            // 1. tables
            var step = 0
            enter(step, 2)
            val live = liveTable(conn, trial.database, trial.table)
            val overrides = request.columns.associateBy { it.name }
            if ((live.columns.map { it.type } + request.columns.map { it.type }).any(::hasDynamicSubcolumns)) {
                limits = FEW_FILES
                say("The table has JSON or Dynamic columns, which are thousands of files per part: copying and scanning with few threads")
            }
            val changedColumns = live.columns.filter { c ->
                val o = overrides[c.name] ?: return@filter false
                !sameText(o.type.ifBlank { c.type }, c.type) || !sameText(o.codec, Types.codecBody(c.codec))
            }.map { it.name }.toSet()
            val changedKeys = listOf(
                !sameText(request.partitionKey, live.partitionKey),
                !sameText(request.sortingKey, live.sortingKey),
            ).count { it }
            update(id) { it.copy(changedKeys = changedKeys, changedColumns = changedColumns.size) }

            val baseDdl = trialSql(base, live, null)
            val candDdl = trialSql(cand, live, request)
            val alterDdl = migrationSql(trial.database, trial.table, live, request)
            runCatching { conn.exec("CREATE DATABASE IF NOT EXISTS ${Sql.id(trial.scratchDatabase)}") }
            exec(baseDdl)
            exec(candDdl)
            say("Created both tables in ${trial.scratchDatabase}")

            // 2. baseline copy
            enter(++step, 6)
            val copied = live.columns.filter { it.defaultKind.isEmpty() || it.defaultKind == "DEFAULT" }
            val columnList = copied.joinToString(", ") { Sql.id(it.name) }
            val insertSettings = mapOf("max_partitions_per_insert_block" to "0")
            val filter = sampleFilter(conn, trial, live)
            val baseCopy = exec("INSERT INTO $base ($columnList) SELECT $columnList FROM $production$filter LIMIT ${request.rows}", insertSettings)
            val rows = conn.first("SELECT toInt64(count()) AS c FROM $base")?.long("c") ?: 0
            if (rows == 0L) throw ChException("The sample selected no rows from ${trial.database}.${trial.table}")
            say("Copied ${Fmt.count(rows)} rows from ${trial.database}.${trial.table} into the baseline (${seconds(baseCopy.elapsedMs)})")

            // 3. candidate copy
            enter(++step, 38)
            val expressions = copied.joinToString(", ") { copyExpression(it, overrides[it.name]?.type?.ifBlank { null }) }
            val candCopy = exec("INSERT INTO $cand ($columnList) SELECT $expressions FROM $base", insertSettings)
            say("Copied the same rows from the baseline into the candidate (${seconds(candCopy.elapsedMs)}), so both hold identical data")

            var baseInsertMs: Double? = null
            if (request.measureInserts) {
                progress(52)
                val twin = Sql.table(trial.scratchDatabase, trial.baseTable + "_ins")
                exec(trialSql(twin, live, null))
                baseInsertMs = exec("INSERT INTO $twin ($columnList) SELECT $columnList FROM $base", insertSettings).elapsedMs
                exec("DROP TABLE IF EXISTS $twin SYNC")
                say("Timed the same insert into a second baseline table (${seconds(baseInsertMs)}) to compare insert throughput")
            }

            // 4. merge and sizes
            enter(++step, 60)
            var baseMergeMs: Double? = null
            var candMergeMs: Double? = null
            if (request.optimizeFinal) {
                baseMergeMs = exec("OPTIMIZE TABLE $base FINAL").elapsedMs
                candMergeMs = exec("OPTIMIZE TABLE $cand FINAL").elapsedMs
            }
            val baseSizes = sizes(conn, trial.scratchDatabase, trial.baseTable)
            val candSizes = sizes(conn, trial.scratchDatabase, trial.candTable)
            update(id) { it.copy(scratchBytes = baseSizes.onDisk + candSizes.onDisk) }
            say(
                (if (request.optimizeFinal) "Fully merged both tables and read" else "Read") +
                    " sizes from system.parts and system.columns",
            )

            // 5. column scans
            val scanTimes = mutableMapOf<String, Pair<Double?, Double?>>()
            if (request.columnScans) {
                enter(++step, 70)
                val noCache = mapOf("use_query_cache" to "0")
                copied.forEachIndexed { i, c ->
                    fun scan(target: String): Double? = median(
                        (1..request.runsPerQuery).map {
                            exec("SELECT count() FROM $target WHERE NOT ignore(${Sql.id(c.name)})", noCache).elapsedMs
                        }.let { if (it.size >= 3) it.drop(1) else it },
                    )
                    scanTimes[c.name] = scan(base) to scan(cand)
                    progress(70 + (i + 1) * 14 / copied.size)
                }
                say("Scanned every column ${request.runsPerQuery} times per table")
            }

            // 6. query replay
            val queryResults = mutableListOf<TrialQueryResult>()
            if (request.replayQueries) {
                enter(++step, 85)
                val facts = scans.snapshot(trial.sourceId)?.table(trial.database, trial.table)
                val queries = facts?.queries.orEmpty().take(20)
                val total = (facts?.queryTotal ?: 0).coerceAtLeast(1)
                val settings = mapOf("use_query_cache" to "0", "max_execution_time" to "120")
                queries.forEachIndexed { i, q ->
                    var error: String? = null
                    fun replay(target: String): Pair<Double?, Long?> = try {
                        val sql = rewriteQuery(q.query, trial.database, trial.table, target)
                        val runs = (1..request.runsPerQuery).map { exec(sql, settings) }
                        median(runs.map { it.elapsedMs }.let { if (it.size >= 3) it.drop(1) else it }) to runs.last().readRows
                    } catch (e: ChException) {
                        error = e.message
                        null to null
                    }
                    val (baseMs, baseRead) = replay(base)
                    val (candMs, candRead) = replay(cand)
                    queryResults.add(TrialQueryResult("Q${i + 1}", q.normalized, q.count.toDouble() / total, baseMs, candMs, baseRead, candRead, error))
                    progress(85 + (i + 1) * 13 / queries.size)
                }
                say(
                    if (queries.isEmpty()) "No SELECTs for this table in query_log, so no queries were replayed"
                    else "Replayed ${queries.size} queries × ${request.runsPerQuery} runs per table",
                )
            }

            // result
            val result = buildResult(
                request, live, changedColumns, overrides, baseSizes, candSizes, scanTimes, queryResults, baseDdl, candDdl, alterDdl,
                baseInsertMs, candCopy.elapsedMs, baseMergeMs, candMergeMs,
            )
            update(id) {
                it.copy(
                    status = "completed",
                    progress = 100,
                    finishedAt = Instant.now().toString(),
                    steps = it.steps.map { s -> s.copy(state = "done") },
                    result = result,
                )
            }
        } catch (e: TrialCancelled) {
            runCatching { ch?.let { dropTables(it, trial) } }
            update(id) {
                it.copy(
                    status = "cancelled",
                    finishedAt = Instant.now().toString(),
                    scratchBytes = 0,
                    steps = it.steps.map { s -> if (s.state == "running") s.copy(state = "failed") else s },
                    log = it.log + LogLine(Instant.now().toString(), "Cancelled; scratch tables dropped"),
                )
            }
        } catch (e: Exception) {
            log.warn("Trial {} failed: {}", id, e.message)
            val used = runCatching {
                ch?.let { sizes(it, trial.scratchDatabase, trial.baseTable).onDisk + sizes(it, trial.scratchDatabase, trial.candTable).onDisk }
            }.getOrNull() ?: 0
            update(id) {
                it.copy(
                    status = "failed",
                    error = e.message ?: e.javaClass.simpleName,
                    finishedAt = Instant.now().toString(),
                    scratchBytes = used,
                    steps = it.steps.map { s -> if (s.state == "running") s.copy(state = "failed") else s },
                )
            }
        } finally {
            cancelled.remove(id)
        }
    }

    private fun seconds(ms: Double): String =
        if (ms >= 10_000) "${(ms / 1000).roundToInt()} s" else String.format(Locale.US, "%.1f s", ms / 1000)

    private fun liveTable(ch: ChConnection, database: String, table: String): LiveTable {
        val where = "database = ${Sql.lit(database)} AND table = ${Sql.lit(table)}"
        val meta = ch.first(
            "SELECT engine_full, partition_key, sorting_key, primary_key FROM system.tables WHERE database = ${Sql.lit(database)} AND name = ${Sql.lit(table)}",
        ) ?: throw ChException("Table $database.$table does not exist")
        val size = ch.first(
            "SELECT toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed FROM system.parts WHERE active AND $where",
        ) ?: emptyMap()
        val columns = ch.query(
            """
            SELECT name, type, compression_codec, default_kind, default_expression,
                   toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed,
                   is_in_sorting_key, is_in_partition_key
            FROM system.columns WHERE $where ORDER BY position
            """.trimIndent(),
        ).map {
            ColumnFacts(
                it.str("name"), it.str("type"), it.str("compression_codec"), it.long("compressed"), it.long("uncompressed"),
                it.str("default_kind"), it.str("default_expression"), it.bool("is_in_sorting_key"), it.bool("is_in_partition_key"), null,
            )
        }
        val indexes = ch.query(
            "SELECT name, expr, type_full, toInt64(granularity) AS granularity FROM system.data_skipping_indices WHERE $where ORDER BY name",
        ).map { IndexFacts(it.str("name"), it.str("expr"), it.str("type_full"), it.long("granularity"), 0, 0) }
        return LiveTable(
            engineFull = meta.str("engine_full"),
            partitionKey = meta.str("partition_key"),
            sortingKey = meta.str("sorting_key"),
            primaryKey = meta.str("primary_key"),
            indexGranularity = Regex("index_granularity = (\\d+)").find(meta.str("engine_full"))?.groupValues?.get(1),
            rows = size.long("rows"),
            compressedBytes = size.long("compressed"),
            columns = columns,
            indexes = indexes,
        )
    }

    private fun sampleFilter(ch: ChConnection, trial: Trial, live: LiveTable): String {
        val request = trial.request
        return when (request.sampling) {
            "where" -> " WHERE (${request.where})"
            "recent" -> {
                val partitions = ch.query(
                    """
                    SELECT partition_id, toInt64(sum(rows)) AS rows FROM system.parts
                    WHERE active AND database = ${Sql.lit(trial.database)} AND table = ${Sql.lit(trial.table)}
                    GROUP BY partition_id ORDER BY partition_id DESC
                    """.trimIndent(),
                )
                if (partitions.size <= 1) "" else {
                    var sum = 0L
                    val picked = partitions.takeWhile { p -> (sum < request.rows).also { sum += p.long("rows") } }
                    " WHERE _partition_id IN (${picked.joinToString(", ") { Sql.lit(it.str("partition_id")) }})"
                }
            }
            else -> {
                val every = if (request.rows > 0) live.rows / request.rows else 0
                // Every n-th row of each part: evenly spread and repeatable. Not rand(): on ClickHouse 26.9 a table
                // with skip indexes returns no rows for a rand() filter.
                if (every >= 2) " WHERE _part_offset % $every = 0" else ""
            }
        }
    }

    private fun sizes(ch: ChConnection, database: String, table: String): Sizes {
        val where = "database = ${Sql.lit(database)} AND table = ${Sql.lit(table)}"
        val row = ch.first(
            """
            SELECT toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed,
                   toInt64(sum(data_uncompressed_bytes)) AS uncompressed, toInt64(sum(bytes_on_disk)) AS on_disk,
                   toInt64(count()) AS parts, toInt64(sum(primary_key_bytes_in_memory)) AS pk
            FROM system.parts WHERE active AND $where
            """.trimIndent(),
        ) ?: emptyMap()
        val columns = ch.query(
            "SELECT name, toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed FROM system.columns WHERE $where",
        ).associate { it.str("name") to (it.long("compressed") to it.long("uncompressed")) }
        return Sizes(row.long("rows"), row.long("compressed"), row.long("uncompressed"), row.long("on_disk"), row.long("parts"), row.long("pk"), columns)
    }

    private fun buildResult(
        request: TrialRequest,
        live: LiveTable,
        changedColumns: Set<String>,
        overrides: Map<String, TrialColumn>,
        base: Sizes,
        cand: Sizes,
        scanTimes: Map<String, Pair<Double?, Double?>>,
        queries: List<TrialQueryResult>,
        baseDdl: String,
        candDdl: String,
        alterDdl: String,
        baseInsertMs: Double?,
        candInsertMs: Double,
        baseMergeMs: Double?,
        candMergeMs: Double?,
    ): TrialResult {
        fun label(type: String, codec: String) = "$type · ${codec.ifEmpty { "LZ4" }}"
        val columns = live.columns.filter { it.defaultKind != "ALIAS" && it.defaultKind != "EPHEMERAL" }.map { c ->
            val o = overrides[c.name]
            val b = base.columns[c.name] ?: (0L to 0L)
            val k = cand.columns[c.name] ?: (0L to 0L)
            TrialColumnResult(
                name = c.name,
                baseline = label(c.type, Types.codecBody(c.codec)),
                candidate = if (c.name in changedColumns && o != null) label(o.type.ifBlank { c.type }, o.codec.trim()) else null,
                baseBytes = b.first,
                candBytes = k.first,
                baseUncompressedBytes = b.second,
                candUncompressedBytes = k.second,
                baseScanMs = scanTimes[c.name]?.first,
                candScanMs = scanTimes[c.name]?.second,
            )
        }.sortedByDescending { it.baseBytes }

        val metrics = mutableListOf(
            TrialMetric("size", "Compressed size", base.compressed.toDouble(), cand.compressed.toDouble(), "bytes", true),
            TrialMetric(
                "ratio", "Compression ratio",
                if (base.compressed > 0) base.uncompressed.toDouble() / base.compressed else 0.0,
                if (cand.compressed > 0) cand.uncompressed.toDouble() / cand.compressed else 0.0,
                "ratio", false,
            ),
        )
        val timed = queries.filter { it.baseMs != null && it.candMs != null }
        if (timed.isNotEmpty()) {
            metrics.add(TrialMetric("query", "Median query, ${timed.size} replayed", median(timed.map { it.baseMs!! })!!, median(timed.map { it.candMs!! })!!, "ms", true))
            metrics.add(
                TrialMetric(
                    "read", "Rows read per query, median",
                    median(timed.map { (it.baseReadRows ?: 0).toDouble() })!!, median(timed.map { (it.candReadRows ?: 0).toDouble() })!!, "rows", true,
                ),
            )
        }
        metrics.add(TrialMetric("parts", if (request.optimizeFinal) "Parts after full merge" else "Active parts", base.parts.toDouble(), cand.parts.toDouble(), "count", true))
        if (baseInsertMs != null && baseInsertMs > 0 && candInsertMs > 0) {
            metrics.add(TrialMetric("insert", "Insert throughput", base.rows / (baseInsertMs / 1000), base.rows / (candInsertMs / 1000), "rowsPerSec", false))
        }
        if (baseMergeMs != null && candMergeMs != null) {
            metrics.add(TrialMetric("merge", "Full merge (OPTIMIZE FINAL)", baseMergeMs, candMergeMs, "ms", true))
        }
        metrics.add(TrialMetric("pk", "Primary key in memory", base.primaryKeyBytes.toDouble(), cand.primaryKeyBytes.toDouble(), "bytes", true))

        val factor = if (base.compressed > 0) cand.compressed.toDouble() / base.compressed else 1.0
        return TrialResult(
            rows = base.rows,
            totalRows = live.rows,
            metrics = metrics,
            columns = columns,
            queries = queries,
            baseDdl = baseDdl,
            candDdl = candDdl,
            alterDdl = alterDdl,
            projectedBaseBytes = live.compressedBytes,
            projectedCandBytes = (live.compressedBytes * factor).toLong(),
            verdict = verdict(factor, timed),
        )
    }

    private fun verdict(sizeFactor: Double, timed: List<TrialQueryResult>): String {
        val change = (1 - sizeFactor) * 100
        val size = when {
            abs(change) < 0.5 -> "The candidate is the same size as the baseline"
            change > 0 -> String.format(Locale.US, "The candidate is %.1f%% smaller", change)
            else -> String.format(Locale.US, "The candidate is %.1f%% larger", -change)
        }
        if (timed.isEmpty()) return "$size."
        val baseMedian = median(timed.map { it.baseMs!! })!!
        val candMedian = median(timed.map { it.candMs!! })!!
        val speed = when {
            candMedian <= 0 || baseMedian <= 0 || abs(baseMedian / candMedian - 1) < 0.05 -> "answers the median query about as fast"
            baseMedian > candMedian -> String.format(Locale.US, "answers the median query %.1f× faster", baseMedian / candMedian)
            else -> String.format(Locale.US, "answers the median query %.1f× slower", candMedian / baseMedian)
        }
        val slower = timed.filter { it.candMs!! > it.baseMs!! * 1.1 && it.candMs - it.baseMs > 1 }.sortedByDescending { it.share }
        val tail = when (slower.size) {
            0 -> ""
            1 -> slower[0].let { String.format(Locale.US, " One query pattern gets slower: %s, %.0f ms → %.0f ms (%s of the workload).", it.label, it.baseMs, it.candMs, Fmt.percent(it.share)) }
            else -> " ${slower.size} query patterns get slower: ${slower.joinToString(", ") { it.label }}."
        }
        return "$size and $speed.$tail"
    }
}

@RestController
@RequestMapping("/api/trials")
class TrialController(private val trials: TrialService) {
    @GetMapping
    fun list(@RequestParam(required = false) source: String?): TrialList = trials.list(source)

    @GetMapping("/plan")
    fun plan(@RequestParam source: String, @RequestParam database: String, @RequestParam table: String): TrialPlan =
        trials.plan(source, database, table)

    @GetMapping("/{id}")
    fun get(@PathVariable id: String): Trial = trials.find(id)

    @PostMapping
    fun start(@RequestBody request: TrialRequest): Trial = trials.start(request)

    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: String): Trial = trials.cancel(id)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) = trials.delete(id)
}
