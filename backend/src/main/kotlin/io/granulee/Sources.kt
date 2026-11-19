package io.granulee

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A data source as stored in sources.json. The password is AES-GCM encrypted. */
data class SourceRecord(
    val id: String,
    val name: String,
    val protocol: String,
    val host: String,
    val port: Int,
    val username: String,
    val passwordEnc: String,
    val includeDatabases: String,
    val excludeDatabases: String,
    val schedule: String,
    val queryLogWindowDays: Int,
    val trialsEnabled: Boolean,
    val scratchDatabase: String,
    val status: SourceStatus = SourceStatus(),
    val lastScan: LastScan? = null,
)

data class SourceFile(val sources: List<SourceRecord> = emptyList())

val SCHEDULES: Map<String, Duration?> = mapOf(
    "every6h" to Duration.ofHours(6),
    "daily" to Duration.ofDays(1),
    "weekly" to Duration.ofDays(7),
    "manual" to null,
)

private val IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

@Component
class SourceService(
    private val store: JsonStore,
    private val crypto: Crypto,
    private val connections: ChConnections,
) {
    private val lock = Any()
    private var records: List<SourceRecord> = store.read("sources.json", SourceFile::class.java)?.sources ?: emptyList()

    fun all(): List<SourceRecord> = synchronized(lock) { records }

    fun find(id: String): SourceRecord =
        all().firstOrNull { it.id == id } ?: throw NotFoundException("Data source $id does not exist")

    fun endpoint(source: SourceRecord): Endpoint =
        Endpoint(source.protocol, source.host, source.port, source.username, crypto.decrypt(source.passwordEnc))

    fun connection(source: SourceRecord): ChConnection = connections.get(endpoint(source))

    fun create(input: SourceInput): SourceRecord {
        validate(input, null)
        val record = SourceRecord(
            id = UUID.randomUUID().toString().take(8),
            name = input.name.trim(),
            protocol = input.protocol,
            host = input.host.trim(),
            port = input.port,
            username = input.username.trim(),
            passwordEnc = crypto.encrypt(input.password.orEmpty()),
            includeDatabases = input.includeDatabases.trim(),
            excludeDatabases = input.excludeDatabases.trim(),
            schedule = input.schedule,
            queryLogWindowDays = input.queryLogWindowDays,
            trialsEnabled = input.trialsEnabled,
            scratchDatabase = input.scratchDatabase.trim(),
        )
        synchronized(lock) { save(records + record) }
        return record
    }

    fun update(id: String, input: SourceInput): SourceRecord {
        validate(input, id)
        val old = find(id)
        connections.evict(endpoint(old))
        val updated = old.copy(
            name = input.name.trim(),
            protocol = input.protocol,
            host = input.host.trim(),
            port = input.port,
            username = input.username.trim(),
            passwordEnc = if (input.password.isNullOrEmpty()) old.passwordEnc else crypto.encrypt(input.password),
            includeDatabases = input.includeDatabases.trim(),
            excludeDatabases = input.excludeDatabases.trim(),
            schedule = input.schedule,
            queryLogWindowDays = input.queryLogWindowDays,
            trialsEnabled = input.trialsEnabled,
            scratchDatabase = input.scratchDatabase.trim(),
        )
        synchronized(lock) { save(records.map { if (it.id == id) updated else it }) }
        return updated
    }

    fun delete(id: String) {
        val old = find(id)
        connections.evict(endpoint(old))
        synchronized(lock) { save(records.filter { it.id != id }) }
    }

    /** Applies a change to the stored record, e.g. a new status or scan result. */
    fun patch(id: String, change: (SourceRecord) -> SourceRecord): SourceRecord? = synchronized(lock) {
        val current = records.firstOrNull { it.id == id } ?: return null
        val next = change(current)
        save(records.map { if (it.id == id) next else it })
        next
    }

    fun test(input: SourceInput): ConnectionTest {
        val password = when {
            !input.password.isNullOrEmpty() -> input.password
            input.id != null -> crypto.decrypt(find(input.id).passwordEnc)
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

    /** Pings the server and stores what it found. */
    fun checkHealth(id: String): SourceRecord? {
        val source = all().firstOrNull { it.id == id } ?: return null
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

    fun nextScanAt(source: SourceRecord): Instant? {
        val interval = SCHEDULES[source.schedule] ?: return null
        val last = source.lastScan?.at?.let(Instant::parse) ?: return Instant.now()
        return last.plus(interval)
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

    private fun save(next: List<SourceRecord>) {
        records = next
        store.write("sources.json", SourceFile(next))
    }
}

@RestController
@RequestMapping("/api")
class SourceController(
    private val sources: SourceService,
    private val scans: ScanService,
) {
    @GetMapping("/sources")
    fun list(): List<SourceDto> = sources.all().map(scans::toDto)

    @PostMapping("/sources")
    fun create(@RequestBody input: SourceInput): SourceDto {
        val created = sources.create(input)
        scans.scanInBackground(created.id)
        return scans.toDto(created)
    }

    @PutMapping("/sources/{id}")
    fun update(@PathVariable id: String, @RequestBody input: SourceInput): SourceDto {
        sources.update(id, input)
        return scans.toDto(sources.checkHealth(id) ?: sources.find(id))
    }

    @DeleteMapping("/sources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: String) {
        sources.delete(id)
        scans.forget(id)
    }

    @PostMapping("/sources/test")
    fun test(@RequestBody input: SourceInput): ConnectionTest = sources.test(input)

    @PostMapping("/sources/{id}/scan")
    fun scan(@PathVariable id: String): SourceDto {
        sources.find(id)
        return scans.toDto(scans.scan(id))
    }

    @PostMapping("/scan")
    fun scanAll(): List<SourceDto> = sources.all().map { scans.toDto(scans.scan(it.id)) }
}
