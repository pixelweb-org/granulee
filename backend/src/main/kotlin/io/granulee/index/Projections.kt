package io.granulee.index

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.NOT_GRANULEE
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.facts.QueryFacts
import io.granulee.scan.SystemTables
import io.granulee.sql.Sql
import org.slf4j.LoggerFactory
import java.time.Instant

/** One projection of a table. Sizes are sums over the active parts that carry it. */
data class Projection(
    val name: String,
    val type: String, // Normal | Aggregate; "" where the server has no system.projections
    val sortingKey: String,
    val query: String,
    val parts: Long, // active parts in which it is built
    val rows: Long,
    val bytesOnDisk: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val selects: Long?, // logged SELECTs that read it; null = query_log not readable
    val lastUsed: String?,
)

/** What the server's system tables and its query log say about the projections of one table. */
object Projections {
    private val log = LoggerFactory.getLogger(Projections::class.java)

    /**
     * A table's projections: what each one is, what it costs on disk and how often logged queries read it.
     * Definitions come from system.projections, sizes from system.projection_parts, use from system.query_log.
     */
    fun of(ch: ChConnection, database: String, table: String, windowDays: Int): List<Projection> {
        val where = SystemTables.ofTable(database, table)

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

    /** Per projection name, the five most frequent SELECT shapes the log says were answered from it. */
    fun loggedReads(ch: ChConnection, database: String, table: String, days: Int): Map<String, List<QueryFacts>> = try {
        val prefix = "$database.$table."
        ch.query(
            """
            SELECT p, substring(q, 1, 20000) AS q, substring(normalizeQuery(q), 1, 4000) AS nq, d, toInt64(c) AS c
            FROM (
                SELECT p, any((query, current_database)) AS qd, qd.1 AS q, qd.2 AS d, count() AS c
                FROM system.query_log ARRAY JOIN projections AS p
                WHERE event_date >= today() - $days AND type = 'QueryFinish' AND query_kind = 'Select' AND is_initial_query
                  AND $NOT_GRANULEE AND startsWith(p, ${Sql.lit(prefix)})
                GROUP BY p, normalized_query_hash
            )
            ORDER BY c DESC
            LIMIT 5 BY p
            """.trimIndent(),
        ).groupBy({ it.str("p").removePrefix(prefix) }) { QueryFacts(it.str("q"), it.str("nq"), it.long("c"), database = it.str("d").ifBlank { null }) }
    } catch (e: Exception) {
        emptyMap()
    }
}
