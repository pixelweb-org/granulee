package io.granulee.explorer

import io.granulee.NotFoundException
import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.facts.FileFacts
import io.granulee.index.Projection
import io.granulee.index.Projections
import io.granulee.recommendation.Analyzer
import io.granulee.recommendation.Finding
import io.granulee.scan.SystemTables
import io.granulee.source.SourceRecord
import io.granulee.sql.Sql
import io.granulee.sql.Types
import io.granulee.sql.engineSettings
import io.granulee.sql.engineTtl

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
    val files: FileFacts? = null,
    val buffers: List<String> = emptyList(), // Buffer tables in front, as database.table
    val projections: List<Projection> = emptyList(),
)

/** Reads one table as the server has it right now: definition, sizes, columns, partitions, indexes, projections. */
internal object TableDetailReader {

    /** `findings` are the table's open findings from the last scan: their count, and a label on the columns they touch. */
    fun read(ch: ChConnection, source: SourceRecord, database: String, table: String, findings: List<Finding>): TableDetail {
        val where = SystemTables.ofTable(database, table)

        val meta = ch.first(
            """
            SELECT engine, engine_full, partition_key, sorting_key, primary_key, storage_policy, comment, create_table_query
            FROM system.tables WHERE database = ${Sql.lit(database)} AND name = ${Sql.lit(table)}
            """.trimIndent(),
        ) ?: throw NotFoundException("Table $database.$table does not exist on ${source.name}")

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
            sourceId = source.id,
            sourceName = source.name,
            database = database,
            name = table,
            engine = meta.str("engine"),
            engineFull = engineFull,
            partitionKey = meta.str("partition_key"),
            sortingKey = meta.str("sorting_key"),
            primaryKey = meta.str("primary_key"),
            ttl = engineTtl(engineFull),
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
            settings = engineSettings(engineFull).map { (name, value) -> Setting(name, value) },
            columns = columns,
            partitionStats = partitions,
            indexes = indexes,
            createQuery = meta.str("create_table_query"),
            findings = findings.size,
            files = SystemTables.fileFacts(ch, where)[database to table],
            buffers = SystemTables.bufferTables(ch)["$database.$table"].orEmpty(),
            projections = Projections.of(ch, database, table, source.queryLogWindowDays),
        )
    }
}
