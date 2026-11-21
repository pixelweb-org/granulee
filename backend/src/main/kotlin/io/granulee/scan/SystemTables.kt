package io.granulee.scan

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.Row
import io.granulee.clickhouse.bool
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.facts.ColumnFacts
import io.granulee.facts.FileFacts
import io.granulee.facts.IndexFacts
import io.granulee.sql.Sql
import io.granulee.sql.bufferTarget
import org.slf4j.LoggerFactory

/** A table as system tables key it. */
typealias TableKey = Pair<String, String>

/**
 * What a scan reads from the server's system tables, for all tables at once. Nothing here reads table data.
 * A part of the catalog an older server does not have comes back empty instead of failing the scan.
 */
object SystemTables {
    private val log = LoggerFactory.getLogger(SystemTables::class.java)

    private const val SYSTEM_DATABASES = "'system', 'INFORMATION_SCHEMA', 'information_schema'"

    /** The condition that leaves the server's own databases out. */
    const val NOT_SYSTEM = "database NOT IN ($SYSTEM_DATABASES)"

    /** The condition that picks one table in the system tables that name it by `database` and `table`. */
    fun ofTable(database: String, table: String): String = "database = ${Sql.lit(database)} AND table = ${Sql.lit(table)}"

    // checksums, columns, count, primary index, partition and the like
    private const val FILES_PER_PART = 10

    /** Every MergeTree table with its engine and keys. */
    fun mergeTreeTables(ch: ChConnection): List<Row> = ch.query(
        """
        SELECT database, name, engine, engine_full, partition_key, sorting_key, primary_key
        FROM system.tables
        WHERE engine LIKE '%MergeTree' AND NOT is_temporary AND $NOT_SYSTEM
        """.trimIndent(),
    )

    /** Rows, sizes, parts and partitions per table, over its active parts. */
    fun partTotals(ch: ChConnection): Map<TableKey, Row> = ch.query(
        """
        SELECT database, table, toInt64(sum(rows)) AS rows, toInt64(sum(data_compressed_bytes)) AS compressed,
               toInt64(sum(data_uncompressed_bytes)) AS uncompressed, toInt64(sum(bytes_on_disk)) AS on_disk,
               toInt64(count()) AS parts, toInt64(uniqExact(partition_id)) AS partitions
        FROM system.parts
        WHERE active AND $NOT_SYSTEM
        GROUP BY database, table
        """.trimIndent(),
    ).associateBy { it.str("database") to it.str("table") }

    fun columns(ch: ChConnection, cardinality: Map<Triple<String, String, String>, Long>): Map<TableKey, List<ColumnFacts>> = ch.query(
        """
        SELECT database, table, name, type, compression_codec, default_kind, default_expression,
               toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed,
               is_in_sorting_key, is_in_partition_key
        FROM system.columns
        WHERE $NOT_SYSTEM
        ORDER BY database, table, position
        """.trimIndent(),
    ).groupBy({ it.str("database") to it.str("table") }) {
        column(it, cardinality[Triple(it.str("database"), it.str("table"), it.str("name"))])
    }

    fun indexes(ch: ChConnection): Map<TableKey, List<IndexFacts>> = ch.query(
        """
        SELECT database, table, name, type_full, expr, toInt64(granularity) AS granularity,
               toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed
        FROM system.data_skipping_indices
        WHERE $NOT_SYSTEM
        """.trimIndent(),
    ).groupBy({ it.str("database") to it.str("table") }, ::index)

    /** A row of system.columns. */
    fun column(row: Row, cardinality: Long? = null) = ColumnFacts(
        name = row.str("name"),
        type = row.str("type"),
        codec = row.str("compression_codec"),
        compressedBytes = row.long("compressed"),
        uncompressedBytes = row.long("uncompressed"),
        defaultKind = row.str("default_kind"),
        defaultExpression = row.str("default_expression"),
        inSortingKey = row.bool("is_in_sorting_key"),
        inPartitionKey = row.bool("is_in_partition_key"),
        cardinality = cardinality,
    )

    /** A row of system.data_skipping_indices; the sizes are 0 where the query did not ask for them. */
    fun index(row: Row) =
        IndexFacts(row.str("name"), row.str("expr"), row.str("type_full"), row.long("granularity"), row.long("compressed"), row.long("uncompressed"))

    /** Per-part distinct-value estimates, where the server keeps them (recent versions only). */
    fun cardinalities(ch: ChConnection): Map<Triple<String, String, String>, Long> = try {
        val supported = ch.first(
            "SELECT count() AS c FROM system.columns WHERE database = 'system' AND table = 'parts_columns' AND name = 'estimates.cardinality'",
        )?.long("c") ?: 0
        if (supported == 0L) emptyMap() else ch.query(
            """
            SELECT database, table, column, toInt64(max(ifNull(`estimates.cardinality`, 0))) AS cardinality
            FROM system.parts_columns
            WHERE active AND $NOT_SYSTEM
            GROUP BY database, table, column
            HAVING cardinality > 0
            """.trimIndent(),
        ).associate { Triple(it.str("database"), it.str("table"), it.str("column")) to it.long("cardinality") }
    } catch (e: Exception) {
        log.info("No cardinality estimates: {}", e.message)
        emptyMap()
    }

    /** File estimates per table, for the tables `where` selects. Empty when the server's system.parts_columns has no substreams. */
    fun fileFacts(ch: ChConnection, where: String): Map<TableKey, FileFacts> = try {
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
        log.info("No file counts: {}", e.message)
        emptyMap()
    }

    /**
     * Buffer tables by the table they flush into, both as "database.table". A query on the Buffer table reads and
     * writes the table behind it, but query_log files it under the Buffer table's name.
     */
    fun bufferTables(ch: ChConnection): Map<String, List<String>> = try {
        ch.query("SELECT database, name, engine_full FROM system.tables WHERE engine = 'Buffer'")
            .mapNotNull { row -> bufferTarget(row.str("database"), row.str("engine_full"))?.let { it to "${row.str("database")}.${row.str("name")}" } }
            .groupBy({ it.first }, { it.second })
    } catch (e: Exception) {
        emptyMap()
    }
}
