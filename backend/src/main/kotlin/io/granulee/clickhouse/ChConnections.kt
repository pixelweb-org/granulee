package io.granulee.clickhouse

import com.clickhouse.client.api.Client
import org.springframework.stereotype.Component
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

data class Endpoint(val protocol: String, val host: String, val port: Int, val username: String, val password: String) {
    val url: String get() = "$protocol://$host:$port"
}

/** Keeps one client per endpoint; a changed endpoint or password gets a fresh client. */
@Component
class ChConnections {
    private val cache = ConcurrentHashMap<Endpoint, ChConnection>()

    // Reads the JSONEachRow lines of every connection; the wire format is this package's own business.
    private val rows = jacksonObjectMapper()

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
            .setClientName(CLIENT_NAME)
            .setConnectTimeout(5, ChronoUnit.SECONDS)
            .setSocketTimeout(if (longRunning) 3600L else 10L, ChronoUnit.SECONDS)
            .setMaxRetries(0)
            .build()
        return HttpChConnection(client, rows)
    }
}
