package io.granulee.source

import io.granulee.BadRequestException
import io.granulee.NotFoundException
import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChConnections
import io.granulee.clickhouse.Endpoint
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.user.CurrentUser
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

/**
 * The registered ClickHouse servers: adding and changing them, reaching them, and whether they answer.
 * A source belongs to the user who added it. `all` and `find` are what a request uses, and know only the
 * signed-in user's sources: somebody else's is as absent as one that does not exist. Work that runs by itself
 * for a source's owner (a scheduled scan, a running trial) uses `everyones` and `get`.
 */
@Component
class SourceService(
    private val repository: SourceRepository,
    private val cipher: PasswordCipher,
    private val connections: ChConnections,
) {
    /** The signed-in user's sources. */
    fun all(): List<SourceRecord> = CurrentUser.id().let { owner -> repository.all().filter { it.ownerId == owner } }

    /** One of the signed-in user's sources. */
    fun find(id: String): SourceRecord =
        all().firstOrNull { it.id == id } ?: throw NotFoundException("Data source $id does not exist")

    /** Every user's sources, for work that serves no request. Never the answer to one. */
    fun everyones(): List<SourceRecord> = repository.all()

    /** A source by its id alone, for work its owner has already started or scheduled. Never the answer to a request. */
    fun get(id: String): SourceRecord =
        repository.all().firstOrNull { it.id == id } ?: throw NotFoundException("Data source $id does not exist")

    fun connection(source: SourceRecord): ChConnection = connections.get(endpoint(source))

    private fun endpoint(source: SourceRecord): Endpoint =
        Endpoint(source.protocol, source.host, source.port, source.username, cipher.decrypt(source.passwordEnc))

    fun create(input: SourceInput): SourceRecord {
        validate(input, null)
        val record = SourceRecord(
            id = generateSequence { UUID.randomUUID().toString().take(8) }.first { id -> repository.all().none { it.id == id } },
            ownerId = CurrentUser.id(),
            name = input.name.trim(),
            protocol = input.protocol,
            host = input.host.trim(),
            port = input.port,
            username = input.username.trim(),
            passwordEnc = cipher.encrypt(input.password.orEmpty()),
            includeDatabases = input.includeDatabases.trim(),
            excludeDatabases = input.excludeDatabases.trim(),
            schedule = input.schedule,
            queryLogWindowDays = input.queryLogWindowDays,
            trialsEnabled = input.trialsEnabled,
            scratchDatabase = input.scratchDatabase.trim(),
        )
        repository.add(record)
        return record
    }

    fun update(id: String, input: SourceInput): SourceRecord {
        val old = find(id)
        validate(input, id)
        connections.evict(endpoint(old))
        val updated = old.copy(
            name = input.name.trim(),
            protocol = input.protocol,
            host = input.host.trim(),
            port = input.port,
            username = input.username.trim(),
            passwordEnc = if (input.password.isNullOrEmpty()) old.passwordEnc else cipher.encrypt(input.password),
            includeDatabases = input.includeDatabases.trim(),
            excludeDatabases = input.excludeDatabases.trim(),
            schedule = input.schedule,
            queryLogWindowDays = input.queryLogWindowDays,
            trialsEnabled = input.trialsEnabled,
            scratchDatabase = input.scratchDatabase.trim(),
        )
        repository.replace(updated)
        return updated
    }

    fun delete(id: String) {
        val old = find(id)
        connections.evict(endpoint(old))
        repository.remove(id)
    }

    /** Applies a change to the stored record, e.g. a scan result. Null when the source is gone. */
    fun patch(id: String, change: (SourceRecord) -> SourceRecord): SourceRecord? = repository.patch(id, change)

    fun test(input: SourceInput): ConnectionTest {
        val password = when {
            !input.password.isNullOrEmpty() -> input.password
            input.id != null -> cipher.decrypt(find(input.id).passwordEnc)
            else -> ""
        }
        if (input.host.isBlank()) return ConnectionTest(false, null, null, "Enter a host first")
        val endpoint = Endpoint(input.protocol, input.host.trim(), input.port, input.username.trim(), password)
        return try {
            connections.probe(endpoint).use { ch ->
                val started = System.nanoTime()
                val version = ch.first("SELECT version() AS v")?.str("v")
                ConnectionTest(true, version, (System.nanoTime() - started) / 1_000_000, null)
            }
        } catch (e: Exception) {
            ConnectionTest(false, null, null, e.message ?: "Connection failed")
        }
    }

    /** Pings the server and stores what it found. Runs by itself too, so it takes any user's source. */
    fun checkHealth(id: String): SourceRecord? {
        val source = repository.all().firstOrNull { it.id == id } ?: return null
        val now = Instant.now().toString()
        val status = try {
            val ch = connection(source)
            val version = ch.first("SELECT version() AS v")?.str("v")
            val replicas = runCatching {
                ch.first("SELECT toInt64(max(total_replicas)) AS r FROM system.replicas")?.long("r")?.toInt()
            }.getOrNull()
            SourceStatus("healthy", null, version, replicas?.takeIf { it > 1 }, now, now)
        } catch (e: Exception) {
            SourceStatus("unreachable", e.message ?: "Connection failed", source.status.version, null, now, source.status.lastSeenAt)
        }
        return patch(id) { it.copy(status = status) }
    }

    private fun validate(input: SourceInput, selfId: String?) {
        if (input.name.isBlank()) throw BadRequestException("Give the source a name")
        if (all().any { it.name.equals(input.name.trim(), ignoreCase = true) && it.id != selfId }) {
            throw BadRequestException("A source named ${input.name.trim()} already exists")
        }
        if (input.protocol != "https" && input.protocol != "http") throw BadRequestException("Protocol must be HTTPS or HTTP")
        if (input.host.isBlank()) throw BadRequestException("Enter the server's host name")
        if (input.port !in 1..65535) throw BadRequestException("Port must be between 1 and 65535")
        if (input.username.isBlank()) throw BadRequestException("Enter the ClickHouse user")
        if (input.schedule !in SCHEDULES) throw BadRequestException("Unknown scan schedule")
        if (input.queryLogWindowDays !in 1..365) throw BadRequestException("Query log window must be 1 to 365 days")
        if (!IDENTIFIER.matches(input.scratchDatabase.trim())) {
            throw BadRequestException("Scratch database must be a plain name: letters, digits and underscores")
        }
    }
}
