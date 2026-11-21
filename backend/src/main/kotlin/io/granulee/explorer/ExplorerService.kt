package io.granulee.explorer

import io.granulee.NotFoundException
import io.granulee.facts.TableFacts
import io.granulee.recommendation.Finding
import io.granulee.recommendation.OpenFindings
import io.granulee.scan.HistoryPoint
import io.granulee.scan.SizeHistory
import io.granulee.scan.Snapshot
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceDto
import io.granulee.source.SourceRecord
import io.granulee.source.SourceService
import io.granulee.source.nextScanAt
import io.granulee.source.toDto
import org.springframework.stereotype.Component
import java.time.Instant

data class Series(val name: String, val values: List<Double>)
data class ChartData(val categories: List<String>, val series: List<Series>)

data class Opportunity(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val findings: Int,
    val savingsBytes: Long,
)

data class Totals(
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val tables: Int,
    val findings: Int,
    val savingsBytes: Long,
)

data class Overview(
    val totals: Totals,
    val sources: List<SourceDto>,
    val growth: ChartData,
    val findingsByCategory: ChartData,
    val opportunities: List<Opportunity>,
    val lastScanAt: String?,
    val nextScanAt: String?,
)

data class DatabaseSummary(val name: String, val tables: Int, val compressedBytes: Long, val uncompressedBytes: Long)

data class DatabaseList(
    val sourceId: String,
    val sourceName: String,
    val databases: List<DatabaseSummary>,
    val scannedAt: String?,
)

data class TableSummary(
    val database: String,
    val name: String,
    val engine: String,
    val rows: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val activeParts: Long,
    val findings: Int,
    val files: Long? = null,
)

data class DatabaseDetail(
    val sourceId: String,
    val sourceName: String,
    val database: DatabaseSummary,
    val tables: List<TableSummary>,
    val scannedAt: String?,
)

/** How the sources grew over the last twelve days that have a scan: one series per source, a day without a scan carrying the size before it. */
internal fun growthChart(sources: List<SourceRecord>, history: List<HistoryPoint>): ChartData {
    val days = history.map { it.day }.distinct().sorted().takeLast(12)
    return ChartData(
        categories = days.map { it.substring(5) },
        series = sources.filter { s -> history.any { it.sourceId == s.id } }.map { s ->
            val points = history.filter { it.sourceId == s.id }
            Series(s.name, days.map { day -> (points.lastOrNull { it.day <= day }?.compressedBytes ?: 0).toDouble() })
        },
    )
}

internal fun databaseSummaries(snapshot: Snapshot?): List<DatabaseSummary> = snapshot?.tables.orEmpty().groupBy { it.database }
    .map { (name, tables) -> DatabaseSummary(name, tables.size, tables.sumOf { it.compressedBytes }, tables.sumOf { it.uncompressedBytes }) }
    .sortedByDescending { it.compressedBytes }

/** What the last scans found, to browse: the overview across sources, and each source's databases and tables. Reads no server. */
@Component
class ExplorerService(
    private val sources: SourceService,
    private val snapshots: SnapshotStore,
    private val history: SizeHistory,
    private val openFindings: OpenFindings,
) {
    private fun inScope(sourceId: String?): List<SourceRecord> =
        if (sourceId.isNullOrBlank()) sources.all() else listOf(sources.find(sourceId))

    /** A table with the findings a person has not dismissed. */
    private class Open(val source: SourceRecord, val table: TableFacts, val findings: List<Finding>)

    private fun open(source: SourceRecord, table: TableFacts): List<Finding> = openFindings.of(source.id, table, source.queryLogWindowDays)

    /** The tables of these sources the scan had findings for, with what is left of them. */
    private fun open(scope: List<SourceRecord>): List<Open> = scope.flatMap { source ->
        snapshots.of(source.id)?.tables.orEmpty().filter { it.findings > 0 }.map { Open(source, it, open(source, it)) }
    }

    private fun opportunities(open: List<Open>): List<Opportunity> = open.filter { it.findings.isNotEmpty() }.map {
        Opportunity(
            sourceId = it.source.id,
            sourceName = it.source.name,
            database = it.table.database,
            table = it.table.name,
            compressedBytes = it.table.compressedBytes,
            uncompressedBytes = it.table.uncompressedBytes,
            findings = it.findings.size,
            savingsBytes = it.findings.sumOf { f -> f.rec.savingsBytes }.coerceAtMost(it.table.compressedBytes),
        )
    }.sortedWith(compareByDescending<Opportunity> { it.savingsBytes }.thenByDescending { it.findings })

    fun opportunities(sourceId: String?): List<Opportunity> = opportunities(open(inScope(sourceId)))

    fun overview(sourceId: String?): Overview {
        val scope = inScope(sourceId)
        val tables = scope.flatMap { snapshots.of(it.id)?.tables.orEmpty() }
        val open = open(scope)
        val all = opportunities(open)
        val byCategory = open.flatMap { it.findings }.groupingBy { it.rec.category }.eachCount().entries.sortedByDescending { it.value }
        val scanTimes = scope.mapNotNull { it.lastScan?.at?.let(Instant::parse) }
        val now = Instant.now()
        return Overview(
            totals = Totals(
                compressedBytes = tables.sumOf { it.compressedBytes },
                uncompressedBytes = tables.sumOf { it.uncompressedBytes },
                tables = tables.size,
                findings = all.sumOf { it.findings },
                savingsBytes = all.sumOf { it.savingsBytes },
            ),
            sources = scope.map { it.toDto(snapshots.of(it.id), now) },
            growth = growthChart(scope, history.all().filter { point -> scope.any { it.id == point.sourceId } }),
            findingsByCategory = ChartData(byCategory.map { it.key }, listOf(Series("Findings", byCategory.map { it.value.toDouble() }))),
            opportunities = all.take(5),
            lastScanAt = scanTimes.maxOrNull()?.toString(),
            nextScanAt = scope.mapNotNull { it.nextScanAt(now) }.minOrNull()?.toString(),
        )
    }

    fun databases(sourceId: String): DatabaseList {
        val source = sources.find(sourceId)
        val snapshot = snapshots.of(sourceId)
        return DatabaseList(sourceId, source.name, databaseSummaries(snapshot), snapshot?.scannedAt)
    }

    fun database(sourceId: String, database: String): DatabaseDetail {
        val source = sources.find(sourceId)
        val snapshot = snapshots.of(sourceId)
        val summary = databaseSummaries(snapshot).firstOrNull { it.name == database }
            ?: throw NotFoundException("Database $database was not in the last scan of ${source.name}")
        val tables = snapshot?.tables.orEmpty().filter { it.database == database }.map {
            TableSummary(it.database, it.name, it.engine, it.rows, it.compressedBytes, it.uncompressedBytes, it.activeParts, open(source, it).size, it.files?.files)
        }
        return DatabaseDetail(sourceId, source.name, summary, tables, snapshot?.scannedAt)
    }

    /** A table as the server has it right now, with the findings of the last scan. */
    fun table(sourceId: String, database: String, table: String): TableDetail {
        val source = sources.find(sourceId)
        val findings = snapshots.of(sourceId)?.table(database, table)?.let { open(source, it) }.orEmpty()
        return TableDetailReader.read(sources.connection(source), source, database, table, findings)
    }
}
