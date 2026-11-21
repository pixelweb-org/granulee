package io.granulee

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

typealias Row = Map<String, Any?>

fun Row.str(key: String): String = this[key]?.toString() ?: ""
fun Row.long(key: String): Long = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull() ?: 0L
    else -> 0L
}
fun Row.longOrNull(key: String): Long? = when (val v = this[key]) {
    is Number -> v.toLong()
    is String -> v.toLongOrNull()
    else -> null
}
fun Row.double(key: String): Double = when (val v = this[key]) {
    is Number -> v.toDouble().takeIf { it.isFinite() } ?: 0.0
    is String -> v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0
    else -> 0.0
}
fun Row.bool(key: String): Boolean = when (val v = this[key]) {
    is Boolean -> v
    is Number -> v.toInt() != 0
    else -> false
}

class ChException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ExecStats(val readRows: Long, val writtenRows: Long, val elapsedMs: Double, val queryId: String? = null)

/** SQL identifier and literal quoting. */
object Sql {
    fun id(name: String): String = "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"
    fun lit(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    fun table(database: String, table: String): String = id(database) + "." + id(table)
}

/** One ClickHouse server, reached over HTTP(S) with client-v2. Results are read as JSONEachRow. */
class ChConnection(private val client: Client, private val mapper: ObjectMapper) : AutoCloseable {

    /**
     * `session` names an HTTP session: statements that share it see each other's session state, one at a time.
     * `database` is the default database for table names the statement does not qualify.
     */
    fun query(sql: String, settings: Map<String, String> = emptyMap(), session: String? = null, database: String? = null): List<Row> {
        val qs = QuerySettings()
            .setFormat(ClickHouseFormat.JSONEachRow)
            .serverSetting("output_format_json_quote_64bit_integers", "0")
        settings.forEach { (k, v) -> qs.serverSetting(k, v) }
        if (session != null) qs.setSessionId(session)
        if (!database.isNullOrBlank()) qs.setDatabase(database)
        return run(sql) {
            client.query(sql, qs).get().use { response ->
                BufferedReader(InputStreamReader(response.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.filter { it.isNotBlank() }
                        .map { @Suppress("UNCHECKED_CAST") (mapper.readValue(it, Map::class.java) as Row) }
                        .toList()
                }
            }
        }
    }

    fun first(sql: String): Row? = query(sql).firstOrNull()

    /** Runs a statement and throws its result away: DDL, INSERT … SELECT, or a SELECT that is only timed. */
    fun exec(sql: String, settings: Map<String, String> = emptyMap(), queryId: String? = null, session: String? = null, database: String? = null): ExecStats {
        val qs = QuerySettings().setFormat(ClickHouseFormat.Null).waitEndOfQuery(true)
        settings.forEach { (k, v) -> qs.serverSetting(k, v) }
        if (queryId != null) qs.setQueryId(queryId)
        if (session != null) qs.setSessionId(session)
        if (!database.isNullOrBlank()) qs.setDatabase(database)
        return run(sql) {
            val started = System.nanoTime()
            client.query(sql, qs).get().use { response ->
                response.inputStream.use { it.readAllBytes() }
                val wallMs = (System.nanoTime() - started) / 1e6
                ExecStats(response.readRows, response.writtenRows, serverElapsedMs(response.responseHeaders) ?: wallMs, queryId)
            }
        }
    }

    /** The server's own elapsed time from the X-ClickHouse-Summary header, when it sends one. */
    private fun serverElapsedMs(headers: Map<String, String>): Double? {
        val summary = headers.entries.firstOrNull { it.key.equals("X-ClickHouse-Summary", ignoreCase = true) }?.value
            ?: return null
        val ns = Regex("\"elapsed_ns\"\\s*:\\s*\"?(\\d+)").find(summary)?.groupValues?.get(1)?.toLongOrNull()
        return ns?.takeIf { it > 0 }?.let { it / 1e6 }
    }

    private fun <T> run(sql: String, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        // A server error carries its text on the way out; a network error has the real reason at the bottom.
        val chain = generateSequence<Throwable>(if (e is ExecutionException) e.cause ?: e else e) { it.cause }
            .filter { !it.message.isNullOrBlank() }.toList()
        val best = chain.firstOrNull { it.message!!.contains("DB::Exception") || it.message!!.contains("Code: ") }
            ?: chain.lastOrNull() ?: e
        throw ChException(cleanMessage(best.message ?: best.javaClass.simpleName), e)
    }

    override fun close() {
        runCatching { client.close() }
    }

    companion object {
        /** ClickHouse errors carry the query and a stack hint; keep the sentence a person needs. */
        fun cleanMessage(raw: String): String {
            val line = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
            val text = line.substringAfter("DB::Exception: ", line).substringBefore(" (version ").trim()
            return if (text.length > 400) text.take(400) + "…" else text
        }
    }
}

data class Endpoint(val protocol: String, val host: String, val port: Int, val username: String, val password: String) {
    val url: String get() = "$protocol://$host:$port"
}

/** Keeps one client per endpoint; a changed endpoint or password gets a fresh client. */
@Component
class ChConnections(private val store: JsonStore) {
    private val cache = ConcurrentHashMap<Endpoint, ChConnection>()

    fun get(endpoint: Endpoint): ChConnection = cache.computeIfAbsent(endpoint) { open(it, longRunning = true) }

    /** A short-lived connection with tight timeouts, for "Test connection". */
    fun probe(endpoint: Endpoint): ChConnection = open(endpoint, longRunning = false)

    fun evict(endpoint: Endpoint) {
        cache.remove(endpoint)?.close()
    }

    private fun open(endpoint: Endpoint, longRunning: Boolean): ChConnection {
        val client = Client.Builder()
            .addEndpoint(endpoint.url)
            .setUsername(endpoint.username)
            .setPassword(endpoint.password)
            .setClientName("granulee")
            .setConnectTimeout(5, ChronoUnit.SECONDS)
            .setSocketTimeout(if (longRunning) 3600L else 10L, ChronoUnit.SECONDS)
            .setMaxRetries(0)
            .build()
        return ChConnection(client, store.mapper)
    }
}
