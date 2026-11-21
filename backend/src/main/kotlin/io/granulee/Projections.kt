package io.granulee

import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("io.granulee.Projections")

/**
 * A table's projections: what each one is, what it costs on disk and how often logged queries read it.
 * Definitions come from system.projections, sizes from system.projection_parts, use from system.query_log.
 */
internal fun projections(ch: ChConnection, database: String, table: String, windowDays: Int): List<Projection> {
    val where = "database = ${Sql.lit(database)} AND table = ${Sql.lit(table)}"

    val sizes = try {
        ch.query(
            """
            SELECT name, toInt64(count()) AS parts, toInt64(sum(rows)) AS rows, toInt64(sum(bytes_on_disk)) AS on_disk,
                   toInt64(sum(data_compressed_bytes)) AS compressed, toInt64(sum(data_uncompressed_bytes)) AS uncompressed
            FROM system.projection_parts WHERE active AND $where
            GROUP BY name
            """.trimIndent(),
        ).associateBy { it.str("name") }
    } catch (e: Exception) {
        log.info("system.projection_parts is not readable: {}", e.message)
        emptyMap()
    }

    // Servers before 24.9 have no system.projections; the parts still tell which projections exist.
    val definitions = try {
        ch.query(
            """
            SELECT name, toString(type) AS type, arrayStringConcat(sorting_key, ', ') AS sorting_key, query
            FROM system.projections WHERE $where
            """.trimIndent(),
        ).associateBy { it.str("name") }
    } catch (e: Exception) {
        log.info("system.projections is not readable: {}", e.message)
        emptyMap()
    }
    if (sizes.isEmpty() && definitions.isEmpty()) return emptyList()

    val prefix = "$database.$table."
    val used = try {
        ch.query(
            """
            SELECT p, toInt64(count()) AS selects, toInt64(toUnixTimestamp(max(t))) AS last
            FROM (
                SELECT arrayJoin(projections) AS p, event_time AS t
                FROM system.query_log
                WHERE event_date >= today() - $windowDays AND type = 'QueryFinish' AND query_kind = 'Select'
                  AND notEmpty(projections) AND $NOT_GRANULEE
            )
            WHERE startsWith(p, ${Sql.lit(prefix)})
            GROUP BY p
            """.trimIndent(),
        ).associateBy { it.str("p").removePrefix(prefix) }
    } catch (e: Exception) {
        log.info("Projection use is not readable from query_log: {}", e.message)
        null
    }

    return (definitions.keys + sizes.keys).sorted().map { name ->
        val definition = definitions[name]
        val size = sizes[name]
        val use = used?.get(name)
        Projection(
            name = name,
            type = definition?.str("type").orEmpty(),
            sortingKey = definition?.str("sorting_key").orEmpty(),
            query = definition?.str("query").orEmpty(),
            parts = size?.long("parts") ?: 0,
            rows = size?.long("rows") ?: 0,
            bytesOnDisk = size?.long("on_disk") ?: 0,
            compressedBytes = size?.long("compressed") ?: 0,
            uncompressedBytes = size?.long("uncompressed") ?: 0,
            selects = if (used == null) null else use?.long("selects") ?: 0,
            lastUsed = use?.long("last")?.let { Instant.ofEpochSecond(it).toString() },
        )
    }.sortedByDescending { it.bytesOnDisk }
}
