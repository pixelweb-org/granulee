package io.granulee.clickhouse

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.query.QuerySettings
import com.clickhouse.data.ClickHouseFormat
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutionException

/** A server reached over HTTP(S) with client-v2. Results are read as JSONEachRow. */
internal class HttpChConnection(private val client: Client, private val mapper: ObjectMapper) : ChConnection {

    override fun query(sql: String, settings: Map<String, String>, session: String?, database: String?): List<Row> {
        val qs = QuerySettings()
            .setFormat(ClickHouseFormat.JSONEachRow)
            .serverSetting("output_format_json_quote_64bit_integers", "0")
        settings.forEach { (k, v) -> qs.serverSetting(k, v) }
        if (session != null) qs.setSessionId(session)
        if (!database.isNullOrBlank()) qs.setDatabase(database)
        return run {
            client.query(sql, qs).get().use { response ->
                BufferedReader(InputStreamReader(response.inputStream, Charsets.UTF_8)).useLines { lines ->
                    lines.filter { it.isNotBlank() }
                        .map { @Suppress("UNCHECKED_CAST") (mapper.readValue(it, Map::class.java) as Row) }
                        .toList()
                }
            }
        }
    }

    override fun exec(sql: String, settings: Map<String, String>, queryId: String?, session: String?, database: String?): ExecStats {
        val qs = QuerySettings().setFormat(ClickHouseFormat.Null).waitEndOfQuery(true)
        settings.forEach { (k, v) -> qs.serverSetting(k, v) }
        if (queryId != null) qs.setQueryId(queryId)
        if (session != null) qs.setSessionId(session)
        if (!database.isNullOrBlank()) qs.setDatabase(database)
        return run {
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

    private fun <T> run(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        // A server error carries its text on the way out; a network error has the real reason at the bottom.
        val chain = generateSequence<Throwable>(if (e is ExecutionException) e.cause ?: e else e) { it.cause }
            .mapNotNull { cause -> cause.message?.takeIf { it.isNotBlank() }?.let { cause to it } }.toList()
        val best = chain.firstOrNull { (_, message) -> message.contains("DB::Exception") || message.contains("Code: ") } ?: chain.lastOrNull()
        throw ChException(cleanMessage(best?.second ?: e.message ?: e.javaClass.simpleName), e)
    }

    override fun close() {
        runCatching { client.close() }
    }
}

/** ClickHouse errors carry the query and a stack hint; keep the sentence a person needs. */
internal fun cleanMessage(raw: String): String {
    val line = raw.lineSequence().firstOrNull { it.isNotBlank() } ?: raw
    val text = line.substringAfter("DB::Exception: ", line).substringBefore(" (version ").trim()
    return if (text.length > 400) text.take(400) + "…" else text
}
