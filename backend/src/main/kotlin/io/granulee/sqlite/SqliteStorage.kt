package io.granulee.sqlite

import io.granulee.recommendation.Dismissed
import io.granulee.recommendation.DismissedStorage
import io.granulee.scan.HistoryPoint
import io.granulee.scan.HistoryStorage
import io.granulee.scan.Snapshot
import io.granulee.scan.SnapshotStorage
import io.granulee.source.SourceRecord
import io.granulee.source.SourceStorage
import io.granulee.trial.Trial
import io.granulee.trial.TrialStorage
import io.granulee.user.Session
import io.granulee.user.SessionStorage
import io.granulee.user.User
import io.granulee.user.UserStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.sql.ResultSet

// The stored documents' format belongs to this package alone; the mapper of the HTTP API can change without touching them.
private val mapper: ObjectMapper = jacksonObjectMapper()

/** The stored documents that still read, each with the key it is stored under; one that does not is skipped and named in the log. */
private fun <T> SqliteDatabase.documents(table: String, key: String, type: Class<T>): List<T> =
    jdbc.sql("SELECT $key AS k, body FROM $table ORDER BY rowid").query { rs, _ -> rs.getString("k") to rs.getString("body") }.list().mapNotNull { (k, body) ->
        runCatching { mapper.readValue(body, type) }
            .onFailure { LoggerFactory.getLogger(SqliteDatabase::class.java).warn("Could not read {} {}: {}", table, k, it.message) }
            .getOrNull()
    }

@Component
class SqliteUserStorage(private val db: SqliteDatabase) : UserStorage {
    private fun user(rs: ResultSet) = User(
        id = rs.getString("id"),
        username = rs.getString("username"),
        passwordHash = rs.getString("password_hash"),
        role = rs.getString("role"),
        active = rs.getBoolean("active"),
        mustChangePassword = rs.getBoolean("must_change_password"),
        createdAt = rs.getString("created_at"),
        lastLoginAt = rs.getString("last_login_at"),
    )

    override fun all(): List<User> = db.jdbc.sql("SELECT * FROM users ORDER BY rowid").query { rs, _ -> user(rs) }.list()

    override fun find(id: String): User? = db.jdbc.sql("SELECT * FROM users WHERE id = ?").param(id).query { rs, _ -> user(rs) }.list().firstOrNull()

    override fun findByName(username: String): User? =
        db.jdbc.sql("SELECT * FROM users WHERE username = ? COLLATE NOCASE").param(username).query { rs, _ -> user(rs) }.list().firstOrNull()

    override fun count(): Int = db.jdbc.sql("SELECT count(*) FROM users").query(Int::class.java).single()

    override fun add(user: User) {
        db.jdbc.sql("INSERT INTO users (id, username, password_hash, role, active, must_change_password, created_at, last_login_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
            .params(listOf(user.id, user.username, user.passwordHash, user.role, user.active, user.mustChangePassword, user.createdAt, user.lastLoginAt)).update()
    }

    override fun update(user: User) {
        db.jdbc.sql("UPDATE users SET username = ?, password_hash = ?, role = ?, active = ?, must_change_password = ?, last_login_at = ? WHERE id = ?")
            .params(listOf(user.username, user.passwordHash, user.role, user.active, user.mustChangePassword, user.lastLoginAt, user.id)).update()
    }
}

@Component
class SqliteSessionStorage(private val db: SqliteDatabase) : SessionStorage {
    override fun find(tokenHash: String): Session? = db.jdbc.sql("SELECT * FROM sessions WHERE token_hash = ?").param(tokenHash)
        .query { rs, _ -> Session(rs.getString("token_hash"), rs.getString("user_id"), rs.getLong("created_at"), rs.getLong("last_seen_at")) }.list().firstOrNull()

    override fun add(session: Session) {
        db.jdbc.sql("INSERT INTO sessions (token_hash, user_id, created_at, last_seen_at) VALUES (?, ?, ?, ?)")
            .params(session.tokenHash, session.userId, session.createdAt, session.lastSeenAt).update()
    }

    override fun touch(tokenHash: String, lastSeenAt: Long) {
        db.jdbc.sql("UPDATE sessions SET last_seen_at = ? WHERE token_hash = ?").params(lastSeenAt, tokenHash).update()
    }

    override fun remove(tokenHash: String) {
        db.jdbc.sql("DELETE FROM sessions WHERE token_hash = ?").param(tokenHash).update()
    }

    override fun removeOf(userId: String, exceptTokenHash: String?) {
        db.jdbc.sql("DELETE FROM sessions WHERE user_id = ? AND token_hash <> ?").params(userId, exceptTokenHash.orEmpty()).update()
    }

    override fun removeExpired(idleBefore: Long, createdBefore: Long) {
        db.jdbc.sql("DELETE FROM sessions WHERE last_seen_at < ? OR created_at < ?").params(idleBefore, createdBefore).update()
    }
}

@Component
class SqliteSourceStorage(private val db: SqliteDatabase) : SourceStorage {
    override fun all(): List<SourceRecord> = db.documents("sources", "id", SourceRecord::class.java)

    override fun save(record: SourceRecord) {
        db.jdbc.sql(
            "INSERT INTO sources (id, owner_id, name, body) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET owner_id = excluded.owner_id, name = excluded.name, body = excluded.body",
        ).params(record.id, record.ownerId, record.name, mapper.writeValueAsString(record)).update()
    }

    override fun remove(id: String) {
        db.jdbc.sql("DELETE FROM sources WHERE id = ?").param(id).update()
    }
}

@Component
class SqliteSnapshotStorage(private val db: SqliteDatabase) : SnapshotStorage {
    override fun all(): List<Snapshot> = db.documents("snapshots", "source_id", Snapshot::class.java)

    override fun save(snapshot: Snapshot) {
        db.jdbc.sql("INSERT INTO snapshots (source_id, body) VALUES (?, ?) ON CONFLICT (source_id) DO UPDATE SET body = excluded.body")
            .params(snapshot.sourceId, mapper.writeValueAsString(snapshot)).update()
    }

    override fun forget(sourceId: String) {
        db.jdbc.sql("DELETE FROM snapshots WHERE source_id = ?").param(sourceId).update()
    }
}

@Component
class SqliteHistoryStorage(private val db: SqliteDatabase) : HistoryStorage {
    override fun all(): List<HistoryPoint> = db.jdbc.sql("SELECT day, source_id, compressed_bytes FROM size_history ORDER BY day, rowid")
        .query { rs, _ -> HistoryPoint(rs.getString("day"), rs.getString("source_id"), rs.getLong("compressed_bytes")) }.list()

    override fun put(point: HistoryPoint) {
        db.jdbc.sql(
            "INSERT INTO size_history (source_id, day, compressed_bytes) VALUES (?, ?, ?) " +
                "ON CONFLICT (source_id, day) DO UPDATE SET compressed_bytes = excluded.compressed_bytes",
        ).params(point.sourceId, point.day, point.compressedBytes).update()
    }

    override fun removeBefore(day: String) {
        db.jdbc.sql("DELETE FROM size_history WHERE day < ?").param(day).update()
    }

    override fun forget(sourceId: String) {
        db.jdbc.sql("DELETE FROM size_history WHERE source_id = ?").param(sourceId).update()
    }
}

@Component
class SqliteDismissedStorage(private val db: SqliteDatabase) : DismissedStorage {
    override fun all(): List<Dismissed> = db.jdbc.sql("SELECT source_id, database_name, table_name, recommendation_id FROM dismissed ORDER BY rowid")
        .query { rs, _ -> Dismissed(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) }.list()

    override fun add(dismissed: Dismissed) {
        db.jdbc.sql("INSERT OR IGNORE INTO dismissed (source_id, database_name, table_name, recommendation_id) VALUES (?, ?, ?, ?)")
            .params(dismissed.sourceId, dismissed.database, dismissed.table, dismissed.recommendationId).update()
    }

    override fun clear(sourceId: String, database: String, table: String) {
        db.jdbc.sql("DELETE FROM dismissed WHERE source_id = ? AND database_name = ? AND table_name = ?").params(sourceId, database, table).update()
    }

    override fun forget(sourceId: String) {
        db.jdbc.sql("DELETE FROM dismissed WHERE source_id = ?").param(sourceId).update()
    }
}

@Component
class SqliteTrialStorage(private val db: SqliteDatabase) : TrialStorage {
    override fun all(): List<Trial> = db.documents("trials", "id", Trial::class.java)

    override fun save(trial: Trial) {
        db.jdbc.sql(
            "INSERT INTO trials (id, owner_id, source_id, created_at, body) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET owner_id = excluded.owner_id, source_id = excluded.source_id, body = excluded.body",
        ).params(trial.id, trial.ownerId, trial.sourceId, trial.createdAt, mapper.writeValueAsString(trial)).update()
    }

    override fun remove(id: String) {
        db.jdbc.sql("DELETE FROM trials WHERE id = ?").param(id).update()
    }
}
