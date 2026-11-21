package io.granulee.trial

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.double
import io.granulee.clickhouse.long
import io.granulee.clickhouse.longOrNull
import io.granulee.clickhouse.str
import io.granulee.scan.SystemTables
import io.granulee.sql.Sql
import io.granulee.sql.engineIndexGranularity
import org.slf4j.LoggerFactory

/** What a column weighs in one table. */
internal data class ColumnSize(val compressed: Long, val uncompressed: Long)

/** What one scratch table weighs, from system.parts and system.columns. */
internal data class TableSizes(
    val rows: Long,
    val compressed: Long,
    val uncompressed: Long,
    val onDisk: Long,
    val parts: Long,
    val primaryKeyBytes: Long,
    val columns: Map<String, ColumnSize>,
    val indexBytes: Long = 0,
)

/** The rows of one partition. */
internal data class PartitionRows(val partitionId: String, val rows: Long)

/** The scratch tables of one trial, by what they are for. */
internal class ScratchTables(trial: Trial) {
    private val database = trial.scratchDatabase
    private val stem = trial.baseTable.removeSuffix("_base")

    val baseName: String = trial.baseTable
    val candName: String = trial.candTable

    /** The same rows with no tuning at all. */
    val plainName = stem + "_plain"

    /** A second baseline, only to time an insert into it. */
    private val insertTwinName = trial.baseTable + "_ins"

    /** The largest columns once per codec. */
    val codecsName = stem + "_codecs"

    /** One column, to ask the server whether it takes a codec for a type. */
    private val probeName = stem + "_probe"

    val base = quoted(baseName)
    val cand = quoted(candName)
    val plain = quoted(plainName)
    val insertTwin = quoted(insertTwinName)
    val codecs = quoted(codecsName)
    val probe = quoted(probeName)

    /** Every table a run can leave behind. */
    val all: List<String> = listOf(base, cand, insertTwin, plain, codecs, probe)

    /** "database.table" as EXPLAIN describes a read of it. */
    fun described(name: String): String = "$database.$name"

    private fun quoted(name: String) = Sql.table(database, name)
}

/** What trials read from the server's system tables: the production table as it is, and what the scratch tables came to. */
internal object TrialTables {
    private val log = LoggerFactory.getLogger(TrialTables::class.java)

    fun live(ch: ChConnection, database: String, table: String): LiveTable {
        val where = SystemTables.ofTable(database, table)
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
        ).map { SystemTables.column(it) }
        val indexes = ch.query(
            "SELECT name, expr, type_full, toInt64(granularity) AS granularity FROM system.data_skipping_indices WHERE $where ORDER BY name",
        ).map(SystemTables::index)
        return LiveTable(
            engineFull = meta.str("engine_full"),
            partitionKey = meta.str("partition_key"),
            sortingKey = meta.str("sorting_key"),
            primaryKey = meta.str("primary_key"),
            indexGranularity = engineIndexGranularity(meta.str("engine_full")),
            rows = size.long("rows"),
            compressedBytes = size.long("compressed"),
            columns = columns,
            indexes = indexes,
            projections = projections(ch, where),
        )
    }

    /** The table's projections with their definitions; only the names where the server has no system.projections. */
    private fun projections(ch: ChConnection, where: String): List<ProjectionFacts> = try {
        // SELECT *: the settings column exists only on newer servers.
        ch.query("SELECT * FROM system.projections WHERE $where ORDER BY name").map { row ->
            val settings = (row["settings"] as? Map<*, *>).orEmpty().entries.associate { it.key.toString() to it.value.toString() }
            ProjectionFacts(row.str("name"), row.str("query"), settings)
        }
    } catch (e: Exception) {
        log.info("system.projections is not readable: {}", e.message)
        try {
            ch.query("SELECT DISTINCT name FROM system.projection_parts WHERE active AND $where ORDER BY name").map { ProjectionFacts(it.str("name"), "") }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** The table's partitions with their rows, newest first. */
    fun partitions(ch: ChConnection, database: String, table: String): List<PartitionRows> = ch.query(
        """
        SELECT partition_id, toInt64(sum(rows)) AS rows FROM system.parts
        WHERE active AND database = ${Sql.lit(database)} AND table = ${Sql.lit(table)}
        GROUP BY partition_id ORDER BY partition_id DESC
        """.trimIndent(),
    ).map { PartitionRows(it.str("partition_id"), it.long("rows")) }

    fun rowCount(ch: ChConnection, table: String): Long = ch.first("SELECT toInt64(count()) AS c FROM $table")?.long("c") ?: 0

    fun sizes(ch: ChConnection, database: String, table: String): TableSizes {
        val where = SystemTables.ofTable(database, table)
        val row = ch.first(
            """
            SELECT toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed,
                   toInt64(sum(data_uncompressed_bytes)) AS uncompressed, toInt64(sum(bytes_on_disk)) AS on_disk,
                   toInt64(count()) AS parts, toInt64(sum(primary_key_bytes_in_memory)) AS pk,
                   toInt64(sum(secondary_indices_compressed_bytes)) AS index_bytes
            FROM system.parts WHERE active AND $where
            """.trimIndent(),
        ) ?: emptyMap()
        val columns = ch.query(
            "SELECT name, toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed FROM system.columns WHERE $where",
        ).associate { it.str("name") to ColumnSize(it.long("compressed"), it.long("uncompressed")) }
        return TableSizes(row.long("rows"), row.long("compressed"), row.long("uncompressed"), row.long("on_disk"), row.long("parts"), row.long("pk"), columns, row.long("index_bytes"))
    }

    /** Free space on the server's default disk, where the scratch tables go. */
    fun freeBytes(ch: ChConnection): Long? =
        ch.first("SELECT toInt64(free_space) AS free FROM system.disks ORDER BY name = 'default' DESC LIMIT 1")?.longOrNull("free")

    /**
     * CPU time per statement of a trial, by query id, in ms over all threads. Empty where query_log is off or
     * unreadable. Without the right to flush the logs, the server writes them on its own within a few seconds.
     */
    fun cpuTimes(ch: ChConnection, trialId: String): Map<String, Double> = try {
        if (runCatching { ch.exec("SYSTEM FLUSH LOGS") }.isFailure) Thread.sleep(8_000)
        ch.query(
            """
            SELECT query_id, toFloat64(ProfileEvents['OSCPUVirtualTimeMicroseconds']) / 1000 AS cpu_ms
            FROM system.query_log
            WHERE event_date >= today() - 1 AND type = 'QueryFinish' AND startsWith(query_id, ${Sql.lit("granulee-$trialId-")})
            """.trimIndent(),
        ).associate { it.str("query_id") to it.double("cpu_ms") }
    } catch (e: Exception) {
        log.info("No CPU times for trial {}: {}", trialId, e.message)
        emptyMap()
    }

    fun drop(ch: ChConnection, tables: ScratchTables) {
        tables.all.forEach { ch.exec("DROP TABLE IF EXISTS $it SYNC") }
    }
}
