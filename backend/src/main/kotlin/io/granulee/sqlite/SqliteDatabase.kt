package io.granulee.sqlite

import io.granulee.state.DataDir
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Granulee's state as one SQLite file in the data directory. This package is the only one that knows SQLite:
 * every feature names what it keeps as a `…Storage` interface, and the classes next to this one implement them.
 * Another database would be another package like this one.
 *
 * What is looked up by is a column; the rest of a source, a scan or a trial is one JSON document, in the shape
 * its class has.
 */
@Component
class SqliteDatabase(dataDir: DataDir) {
    val file: Path = dataDir.path.resolve("granulee.db")

    private val dataSource = SQLiteDataSource(
        SQLiteConfig().apply {
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
            // Writers take turns; one that finds the file busy waits instead of failing.
            busyTimeout = 10_000
        },
    ).apply { url = "jdbc:sqlite:$file" }

    val jdbc: JdbcClient = JdbcClient.create(dataSource)

    init {
        migrate()
        // Password hashes and encrypted ClickHouse passwords live here: for the owner's eyes only.
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    /** Brings the schema up to date: each entry of SCHEMA runs once, and `user_version` counts how many have. */
    private fun migrate() {
        dataSource.connection.use { connection ->
            val version = connection.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { it.getInt(1) } }
            check(version <= SCHEMA.size) { "$file was written by a newer Granulee (schema $version, this release knows ${SCHEMA.size})" }
            SCHEMA.drop(version).forEachIndexed { i, script ->
                connection.autoCommit = false
                connection.createStatement().use { s ->
                    script.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach(s::execute)
                    s.execute("PRAGMA user_version = ${version + i + 1}")
                }
                connection.commit()
                connection.autoCommit = true
            }
        }
    }

    private companion object {
        /** Never change an entry that was released; add one. */
        val SCHEMA = listOf(
            """
            CREATE TABLE users (
                id TEXT PRIMARY KEY,
                username TEXT NOT NULL UNIQUE COLLATE NOCASE,
                password_hash TEXT NOT NULL,
                role TEXT NOT NULL,
                active INTEGER NOT NULL,
                must_change_password INTEGER NOT NULL,
                created_at TEXT NOT NULL,
                last_login_at TEXT
            );
            CREATE TABLE sessions (
                token_hash TEXT PRIMARY KEY,
                user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                created_at INTEGER NOT NULL,
                last_seen_at INTEGER NOT NULL
            );
            CREATE INDEX sessions_user ON sessions(user_id);
            CREATE TABLE sources (
                id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL REFERENCES users(id),
                name TEXT NOT NULL,
                body TEXT NOT NULL
            );
            CREATE TABLE snapshots (
                source_id TEXT PRIMARY KEY REFERENCES sources(id) ON DELETE CASCADE,
                body TEXT NOT NULL
            );
            CREATE TABLE size_history (
                source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
                day TEXT NOT NULL,
                compressed_bytes INTEGER NOT NULL,
                PRIMARY KEY (source_id, day)
            );
            CREATE TABLE dismissed (
                source_id TEXT NOT NULL REFERENCES sources(id) ON DELETE CASCADE,
                database_name TEXT NOT NULL,
                table_name TEXT NOT NULL,
                recommendation_id TEXT NOT NULL,
                PRIMARY KEY (source_id, database_name, table_name, recommendation_id)
            );
            CREATE TABLE trials (
                id TEXT PRIMARY KEY,
                owner_id TEXT NOT NULL REFERENCES users(id),
                source_id TEXT NOT NULL,
                created_at TEXT NOT NULL,
                body TEXT NOT NULL
            )
            """,
        )
    }
}
