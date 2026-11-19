package io.granulee

// The JSON shapes of /api/**. They mirror frontend/src/api/types.ts field for field.

data class SourceStatus(
    val state: String = "unknown", // healthy | unreachable | unknown
    val message: String? = null,
    val version: String? = null,
    val replicas: Int? = null,
    val checkedAt: String? = null,
    val lastSeenAt: String? = null,
)

data class LastScan(val at: String, val durationMs: Long, val error: String?)

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
)

data class DatabaseDetail(
    val sourceId: String,
    val sourceName: String,
    val database: DatabaseSummary,
    val tables: List<TableSummary>,
    val scannedAt: String?,
)

data class ColumnInfo(
    val name: String,
    val type: String,
    val codec: String,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val finding: String?,
)

data class SkipIndex(
    val name: String,
    val expression: String,
    val type: String,
    val granularity: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class Setting(val name: String, val value: String)
data class PartitionStat(val partition: String, val compressedBytes: Long, val parts: Long)

data class TableDetail(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val name: String,
    val engine: String,
    val engineFull: String,
    val partitionKey: String,
    val sortingKey: String,
    val primaryKey: String,
    val ttl: String?,
    val storagePolicy: String,
    val comment: String,
    val rows: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val bytesOnDisk: Long,
    val activeParts: Long,
    val partitions: Long,
    val primaryKeyBytesInMemory: Long,
    val primaryKeyBytesInMemoryAllocated: Long,
    val marks: Long,
    val marksBytes: Long,
    val skipIndexBytes: Long,
    val settings: List<Setting>,
    val columns: List<ColumnInfo>,
    val partitionStats: List<PartitionStat>,
    val indexes: List<SkipIndex>,
    val createQuery: String,
    val findings: Int,
)

data class QueryEvidence(val query: String, val count: Long, val share: Double)

data class Recommendation(
    val id: String,
    val severity: String, // high | medium | low | review
    val category: String, // Partitioning | Sorting key | Codec | Data type | Skip index
    val changeKind: String, // online | mutation | rebuild
    val title: String,
    val body: String,
    val effect: String,
    val sql: String,
    val savingsBytes: Long,
    val column: String?,
    val queries: List<QueryEvidence> = emptyList(),
)

data class ChangeCounts(val online: Int, val mutation: Int, val rebuild: Int)

data class TableRecommendations(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val compressedBytes: Long,
    val items: List<Recommendation>,
    val dismissed: Int,
    val savingsBytes: Long,
    val counts: ChangeCounts,
    val queryLogWindowDays: Int,
    val queryLogQueries: Long?,
    val latestTrial: Trial?,
)

data class DismissRequest(val id: String = "")

data class TrialPlanColumn(
    val name: String,
    val baselineType: String,
    val baselineCodec: String,
    val suggestedType: String,
    val suggestedCodec: String,
    val typeOptions: List<String>,
    val codecOptions: List<String>,
)

data class ColumnChange(val name: String, val type: String?, val codec: String?)

data class TrialPlanRecommendation(
    val id: String,
    val label: String,
    val partitionKey: String? = null,
    val sortingKey: String? = null,
    val column: ColumnChange? = null,
    val dropIndex: String? = null,
)

data class TrialPlan(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val trialsEnabled: Boolean,
    val scratchDatabase: String,
    val scratchFreeBytes: Long?,
    val totalRows: Long,
    val compressedBytes: Long,
    val recommendedRows: Long,
    val partitionKey: String,
    val sortingKey: String,
    val columns: List<TrialPlanColumn>,
    val indexes: List<String>,
    val recommendations: List<TrialPlanRecommendation>,
    val replayableQueries: Int,
)

data class TrialColumn(val name: String = "", val type: String = "", val codec: String = "")

data class TrialRequest(
    val sourceId: String = "",
    val database: String = "",
    val table: String = "",
    val name: String = "",
    val partitionKey: String = "",
    val sortingKey: String = "",
    val columns: List<TrialColumn> = emptyList(),
    val dropIndexes: List<String> = emptyList(),
    val rows: Long = 0,
    val sampling: String = "even", // even | recent | where
    val where: String? = null,
    val replayQueries: Boolean = true,
    val measureInserts: Boolean = true,
    val optimizeFinal: Boolean = true,
    val columnScans: Boolean = true,
    val runsPerQuery: Int = 5,
)

data class TrialStep(val label: String, val state: String) // done | running | pending | failed

data class TrialMetric(
    val key: String,
    val label: String,
    val baseline: Double,
    val candidate: Double,
    val unit: String, // bytes | ratio | ms | rows | count | rowsPerSec
    val lowerIsBetter: Boolean,
)

data class TrialColumnResult(
    val name: String,
    val baseline: String,
    val candidate: String?,
    val baseBytes: Long,
    val candBytes: Long,
    val baseUncompressedBytes: Long,
    val candUncompressedBytes: Long,
    val baseScanMs: Double?,
    val candScanMs: Double?,
)

data class TrialQueryResult(
    val label: String,
    val query: String,
    val share: Double,
    val baseMs: Double?,
    val candMs: Double?,
    val baseReadRows: Long?,
    val candReadRows: Long?,
    val error: String?,
)

data class TrialResult(
    val rows: Long,
    val totalRows: Long,
    val metrics: List<TrialMetric>,
    val columns: List<TrialColumnResult>,
    val queries: List<TrialQueryResult>,
    val baseDdl: String,
    val candDdl: String,
    val projectedBaseBytes: Long,
    val projectedCandBytes: Long,
    val verdict: String,
)

data class LogLine(val at: String, val message: String)

data class Trial(
    val id: String,
    val name: String,
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val status: String, // running | completed | failed | cancelled
    val error: String?,
    val createdAt: String,
    val finishedAt: String?,
    val progress: Int,
    val steps: List<TrialStep>,
    val request: TrialRequest,
    val scratchDatabase: String,
    val baseTable: String,
    val candTable: String,
    val scratchBytes: Long,
    val changedKeys: Int,
    val changedColumns: Int,
    val result: TrialResult?,
    val log: List<LogLine>,
)

data class TrialList(val trials: List<Trial>, val scratchUsedBytes: Long, val scratchFreeBytes: Long?)

data class ApiError(val message: String)

class NotFoundException(message: String) : RuntimeException(message)
class BadRequestException(message: String) : RuntimeException(message)
