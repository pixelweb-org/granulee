package io.granulee.sqlite

import io.granulee.recommendation.Dismissed
import io.granulee.scan.HistoryPoint
import io.granulee.scan.QueryLogStatus
import io.granulee.scan.Snapshot
import io.granulee.source.LastScan
import io.granulee.source.SourceRecord
import io.granulee.source.SourceStatus
import io.granulee.state.DataDir
import io.granulee.state.GranuleeProperties
import io.granulee.trial.Trial
import io.granulee.trial.TrialApply
import io.granulee.trial.TrialRequest
import io.granulee.user.Session
import io.granulee.user.User
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The SQLite implementation of every Storage interface, against a real file. A second SqliteDatabase on the
 * same directory is a restart: what was stored has to come back as it went in.
 */
class SqliteStorageTest {
    @TempDir
    lateinit var dir: Path

    private fun database() = SqliteDatabase(DataDir(GranuleeProperties(dataDir = dir.toString())))

    private val ann = User("u1", "Ann", "\$argon2id\$hash", "admin", active = true, mustChangePassword = true, createdAt = "2026-11-21T10:00:00Z")
    private val ben = User("u2", "ben", "\$argon2id\$other", "user", active = false, mustChangePassword = false, createdAt = "2026-11-22T10:00:00Z", lastLoginAt = "2026-11-23T10:00:00Z")

    private fun source(id: String, owner: String) = SourceRecord(
        id, owner, "prod", "https", "clickhouse.test", 8443, "granulee", "ZW5jcnlwdGVk", "analytics", "tmp_*", "daily", 30, true, "scratch",
        SourceStatus("healthy", null, "25.8.1.1", 3, "2026-11-21T10:00:00Z", "2026-11-21T10:00:00Z"), LastScan("2026-11-21T10:00:00Z", 64, null),
    )

    private fun trial(id: String, owner: String) = Trial(
        id = id, ownerId = owner, name = "t", sourceId = "s1", sourceName = "prod", database = "analytics", table = "events", status = "completed", error = null,
        createdAt = "2026-11-21T10:00:00Z", finishedAt = null, progress = 100, steps = emptyList(), request = TrialRequest(sourceId = "s1", rows = 1000),
        scratchDatabase = "scratch", baseTable = "events_trial_${id}_base", candTable = "events_trial_${id}_cand", scratchBytes = 12, changedKeys = 1, changedColumns = 2,
        result = null, log = emptyList(), apply = TrialApply("completed", "2026-11-21T11:00:00Z", "2026-11-21T11:00:05Z", null, "ALTER TABLE …", 2, 2),
    )

    /** Two users and a source of the first: most of what is stored hangs on them. */
    private fun SqliteDatabase.seeded() = apply {
        SqliteUserStorage(this).apply { add(ann); add(ben) }
        SqliteSourceStorage(this).save(source("s1", "u1"))
    }

    @Test
    fun `users are stored as they are, found by name whatever its case, and a name is taken once`() {
        SqliteUserStorage(database()).apply {
            assertEquals(0, count())
            add(ann)
            add(ben)
            update(ben.copy(active = true, passwordHash = "\$argon2id\$new"))
        }
        val users = SqliteUserStorage(database())
        assertEquals(listOf(ann, ben.copy(active = true, passwordHash = "\$argon2id\$new")), users.all())
        assertEquals(2, users.count())
        assertEquals(ann, users.findByName("ANN"))
        assertEquals("u2", users.find("u2")?.id)
        assertNull(users.find("nobody"))
        assertNull(users.findByName("nobody"))
        assertFailsWith<Exception> { users.add(ann.copy(id = "u3", username = "ann")) }
    }

    @Test
    fun `sessions are kept, ended one by one or for a user, and swept when they have run out`() {
        val db = database().seeded()
        SqliteSessionStorage(db).apply {
            add(Session("h1", "u1", 1000, 1000))
            add(Session("h2", "u1", 2000, 2000))
            add(Session("h3", "u2", 3000, 3000))
            touch("h1", 5000)
        }
        val sessions = SqliteSessionStorage(database())
        assertEquals(Session("h1", "u1", 1000, 5000), sessions.find("h1"))
        assertNull(sessions.find("nothing"))
        sessions.removeOf("u1", exceptTokenHash = "h2")
        assertEquals(listOf(false, true, true), listOf("h1", "h2", "h3").map { sessions.find(it) != null })
        sessions.removeExpired(idleBefore = 2500, createdBefore = 0)
        assertEquals(listOf(false, true), listOf("h2", "h3").map { sessions.find(it) != null })
        sessions.removeExpired(idleBefore = 0, createdBefore = 3001)
        sessions.add(Session("h4", "u2", 1, 1))
        sessions.remove("h4")
        sessions.removeOf("u2")
        assertEquals(listOf(null, null), listOf("h3", "h4").map { sessions.find(it) })
    }

    @Test
    fun `sources come back as they were stored, with their owner`() {
        val db = database().seeded()
        SqliteSourceStorage(db).apply {
            save(source("s2", "u2"))
            save(source("s1", "u1").copy(name = "renamed", lastScan = null))
            save(source("s3", "u2"))
            remove("s3")
        }
        assertEquals(listOf(source("s1", "u1").copy(name = "renamed", lastScan = null), source("s2", "u2")), SqliteSourceStorage(database()).all())
        // A source cannot belong to nobody.
        assertFailsWith<Exception> { SqliteSourceStorage(db).save(source("s9", "no-such-user")) }
    }

    @Test
    fun `a source's scan, history and dismissed recommendations are kept, replaced and forgotten`() {
        val db = database().seeded()
        SqliteSourceStorage(db).save(source("s2", "u2"))
        val scan = Snapshot("s1", "2026-11-21T10:00:00Z", 64, true, QueryLogStatus("ok"))
        SqliteSnapshotStorage(db).apply {
            save(Snapshot(sourceId = "s1"))
            save(scan)
            save(Snapshot(sourceId = "s2"))
        }
        SqliteHistoryStorage(db).apply {
            put(HistoryPoint("2026-07-01", "s1", 1))
            put(HistoryPoint("2026-11-21", "s1", 10))
            put(HistoryPoint("2026-11-21", "s1", 11))
            put(HistoryPoint("2026-11-21", "s2", 20))
            removeBefore("2026-07-24")
        }
        SqliteDismissedStorage(db).apply {
            add(Dismissed("s1", "analytics", "events", "codec:url"))
            add(Dismissed("s1", "analytics", "events", "codec:url"))
            add(Dismissed("s1", "analytics", "events", "sorting-key"))
            add(Dismissed("s1", "analytics", "users", "codec:name"))
            add(Dismissed("s2", "analytics", "events", "codec:url"))
            clear("s1", "analytics", "users")
        }

        val again = database()
        assertEquals(listOf(scan, Snapshot(sourceId = "s2")), SqliteSnapshotStorage(again).all())
        assertEquals(listOf(HistoryPoint("2026-11-21", "s1", 11), HistoryPoint("2026-11-21", "s2", 20)), SqliteHistoryStorage(again).all())
        assertEquals(
            listOf(Dismissed("s1", "analytics", "events", "codec:url"), Dismissed("s1", "analytics", "events", "sorting-key"), Dismissed("s2", "analytics", "events", "codec:url")),
            SqliteDismissedStorage(again).all(),
        )

        SqliteSnapshotStorage(again).forget("s1")
        SqliteHistoryStorage(again).forget("s1")
        SqliteDismissedStorage(again).forget("s1")
        assertEquals(listOf("s2"), SqliteSnapshotStorage(again).all().map { it.sourceId })
        assertEquals(listOf("s2"), SqliteHistoryStorage(again).all().map { it.sourceId })
        assertEquals(listOf("s2"), SqliteDismissedStorage(again).all().map { it.sourceId })
        // Removing a source takes along whatever still hangs on it.
        SqliteSourceStorage(again).remove("s2")
        assertTrue(SqliteSnapshotStorage(again).all().isEmpty() && SqliteHistoryStorage(again).all().isEmpty() && SqliteDismissedStorage(again).all().isEmpty())
    }

    @Test
    fun `trials come back as they were stored, and outlive their source`() {
        val db = database().seeded()
        SqliteTrialStorage(db).apply {
            save(trial("0001", "u1"))
            save(trial("0002", "u2"))
            save(trial("0001", "u1").copy(progress = 38, status = "running"))
            save(trial("0003", "u1"))
            remove("0003")
        }
        SqliteSourceStorage(db).remove("s1")
        assertEquals(listOf(trial("0001", "u1").copy(progress = 38, status = "running"), trial("0002", "u2")), SqliteTrialStorage(database()).all())
    }

    @Test
    fun `the database is one file only its owner can read, and a file from a newer release is refused`() {
        val db = database()
        assertEquals(dir.resolve("granulee.db"), db.file)
        runCatching { Files.getPosixFilePermissions(db.file) }.getOrNull()?.let { assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(it)) }
        db.jdbc.sql("PRAGMA user_version = 99").update()
        assertTrue("newer Granulee" in assertFailsWith<IllegalStateException> { database() }.message.orEmpty())
    }
}
