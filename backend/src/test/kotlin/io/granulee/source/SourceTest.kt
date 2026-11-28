package io.granulee.source

import io.granulee.BadRequestException
import io.granulee.InMemorySourceStorage
import io.granulee.NotSignedInException
import io.granulee.SignedIn
import io.granulee.NotFoundException
import io.granulee.clickhouse.ChConnections
import io.granulee.scan.QueryLogStatus
import io.granulee.scan.Snapshot
import io.granulee.state.GranuleeProperties
import io.granulee.state.DataDir
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRecordTest {
    private val now = Instant.parse("2026-11-21T12:00:00Z")

    private fun source(schedule: String, lastScanAt: String? = null) = SourceRecord(
        "s1", "u1", "local", "http", "localhost", 8123, "granulee", "c2VjcmV0", "", "tmp_*", schedule, 30, true, "scratch",
        lastScan = lastScanAt?.let { LastScan(it, 120, null) },
    )

    @Test
    fun `the next scan follows the schedule from the last one`() {
        assertEquals(Instant.parse("2026-11-21T16:00:00Z"), source("every6h", "2026-11-21T10:00:00Z").nextScanAt(now))
        assertEquals(Instant.parse("2026-11-22T10:00:00Z"), source("daily", "2026-11-21T10:00:00Z").nextScanAt(now))
        assertEquals(Instant.parse("2026-11-28T10:00:00Z"), source("weekly", "2026-11-21T10:00:00Z").nextScanAt(now))
    }

    @Test
    fun `a source that was never scanned is due now, one scanned by hand never`() {
        assertEquals(now, source("every6h").nextScanAt(now))
        assertNull(source("manual", "2026-11-21T10:00:00Z").nextScanAt(now))
        assertNull(source("manual").nextScanAt(now))
    }

    @Test
    fun `the API never shows the password, only whether there is one`() {
        val dto = source("daily", "2026-11-21T10:00:00Z").toDto(null, now)
        assertTrue(dto.hasPassword)
        assertTrue(!source("daily").copy(passwordEnc = "").toDto(null, now).hasPassword)
        assertEquals("2026-11-22T10:00:00Z", dto.nextScanAt)
        assertNull(dto.tables)
        assertNull(dto.queryLog)
    }

    @Test
    fun `a snapshot from before the query-log status existed still says whether the log was readable`() {
        val source = source("daily")
        assertEquals(QueryLogStatus("ok"), source.toDto(Snapshot(queryLogReadable = true), now).queryLog)
        assertEquals(QueryLogStatus("error"), source.toDto(Snapshot(queryLogReadable = false), now).queryLog)
        assertEquals(QueryLogStatus("denied", "no grant"), source.toDto(Snapshot(queryLog = QueryLogStatus("denied", "no grant")), now).queryLog)
        assertEquals(0, source.toDto(Snapshot(), now).tables)
    }

    @Test
    fun `a scan covers what the source is set up for`() {
        val target = source("daily").scanTarget()
        assertEquals(listOf("s1", "local", "", "tmp_*", "scratch"), listOf(target.sourceId, target.sourceName, target.includeDatabases, target.excludeDatabases, target.scratchDatabase))
        assertEquals(30, target.queryLogWindowDays)
    }
}

class SourceServiceTest {
    @TempDir
    lateinit var dir: Path

    private val storage = InMemorySourceStorage()

    private fun service(): SourceService {
        val props = GranuleeProperties(dataDir = dir.toString())
        return SourceService(SourceRepository(storage), PasswordCipher(props, DataDir(props)), ChConnections())
    }

    @BeforeTest
    fun signIn() = SignedIn.be("u1")

    @AfterTest
    fun signOut() = SignedIn.nobody()

    private val input = SourceInput(name = " prod ", protocol = "https", host = " ch.example.com ", port = 8443, username = " reader ", password = "s3cret", scratchDatabase = "scratch")

    @Test
    fun `a source is stored trimmed, with its password encrypted, and survives a restart`() {
        val created = service().create(input)
        assertEquals(listOf("prod", "ch.example.com", "reader"), listOf(created.name, created.host, created.username))
        assertTrue(created.passwordEnc.isNotEmpty() && "s3cret" !in storage.all().toString())
        assertEquals("u1", created.ownerId)
        assertEquals(listOf(created), service().all())
    }

    @Test
    fun `what cannot work is refused with the reason`() {
        val service = service()
        fun refused(input: SourceInput) = assertFailsWith<BadRequestException> { service.create(input) }.message
        assertEquals("Give the source a name", refused(input.copy(name = " ")))
        assertEquals("Protocol must be HTTPS or HTTP", refused(input.copy(protocol = "native")))
        assertEquals("Enter the server's host name", refused(input.copy(host = "")))
        assertEquals("Port must be between 1 and 65535", refused(input.copy(port = 0)))
        assertEquals("Enter the ClickHouse user", refused(input.copy(username = "")))
        assertEquals("Unknown scan schedule", refused(input.copy(schedule = "hourly")))
        assertEquals("Query log window must be 1 to 365 days", refused(input.copy(queryLogWindowDays = 0)))
        assertEquals("Scratch database must be a plain name: letters, digits and underscores", refused(input.copy(scratchDatabase = "my scratch")))
        service.create(input)
        assertEquals("A source named PROD already exists", refused(input.copy(name = "PROD")))
    }

    @Test
    fun `an update without a password keeps the stored one, and may keep its own name`() {
        val service = service()
        val created = service.create(input)
        val renamed = service.update(created.id, input.copy(name = "prod", password = "", queryLogWindowDays = 7))
        assertEquals(created.passwordEnc, renamed.passwordEnc)
        assertEquals(7, service.find(created.id).queryLogWindowDays)
        val changed = service.update(created.id, input.copy(password = "other"))
        assertTrue(changed.passwordEnc != created.passwordEnc)
    }

    @Test
    fun `a source that is gone is not found`() {
        val service = service()
        val created = service.create(input)
        service.delete(created.id)
        assertEquals("Data source ${created.id} does not exist", assertFailsWith<NotFoundException> { service.find(created.id) }.message)
        assertNull(service.patch(created.id) { it })
        assertNull(service.checkHealth(created.id))
    }

    @Test
    fun `a request reaches its own user's sources only`() {
        val service = service()
        val mine = service.create(input)
        val theirs = SignedIn.`as`("u2") { service.create(input) } // the same name is free for another user
        assertEquals(listOf(mine), service.all())
        assertEquals(mine, service.find(mine.id))
        // Somebody else's source reads exactly like one that does not exist, whatever is tried with it.
        val gone = "Data source ${theirs.id} does not exist"
        assertEquals(gone, assertFailsWith<NotFoundException> { service.find(theirs.id) }.message)
        assertEquals(gone, assertFailsWith<NotFoundException> { service.update(theirs.id, input.copy(name = "taken")) }.message)
        assertEquals(gone, assertFailsWith<NotFoundException> { service.delete(theirs.id) }.message)
        assertEquals(gone, assertFailsWith<NotFoundException> { service.test(input.copy(id = theirs.id, password = null)) }.message)
        assertEquals(listOf(theirs), SignedIn.`as`("u2") { service.all() })
    }

    @Test
    fun `without a signed-in user nothing is found, and work that runs by itself names the source`() {
        val service = service()
        val created = service.create(input)
        SignedIn.nobody()
        assertFailsWith<NotSignedInException> { service.all() }
        assertFailsWith<NotSignedInException> { service.find(created.id) }
        assertFailsWith<NotSignedInException> { service.create(input.copy(name = "other")) }
        assertEquals(created, service.get(created.id))
        assertEquals(listOf(created), service.everyones())
    }

    @Test
    fun `a connection test without a host does not try to connect`() {
        assertEquals(ConnectionTest(false, null, null, "Enter a host first"), service().test(input.copy(host = " ")))
    }

    @Test
    fun `passwords come back out as they went in, and differ on disk each time`() {
        val props = GranuleeProperties(dataDir = dir.toString())
        val cipher = PasswordCipher(props, DataDir(props))
        val stored = cipher.encrypt("s3cret")
        assertEquals("s3cret", cipher.decrypt(stored))
        assertTrue(stored != cipher.encrypt("s3cret"))
        assertEquals("", cipher.decrypt(""))
        // The key file written on first use opens the passwords after a restart.
        assertEquals("s3cret", PasswordCipher(props, DataDir(props)).decrypt(stored))
    }
}
