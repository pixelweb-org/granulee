package io.granulee.clickhouse

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

/** The server refused a statement or could not be reached; the message is the sentence a person needs. */
class ChException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class ExecStats(val readRows: Long, val writtenRows: Long, val elapsedMs: Double, val queryId: String? = null)

/**
 * One ClickHouse server. Everything Granulee asks of a server goes through these three calls, so what reads
 * system tables or runs a trial can be tested against a stand-in.
 */
interface ChConnection : AutoCloseable {
    /**
     * `session` names an HTTP session: statements that share it see each other's session state, one at a time.
     * `database` is the default database for table names the statement does not qualify.
     */
    fun query(sql: String, settings: Map<String, String> = emptyMap(), session: String? = null, database: String? = null): List<Row>

    fun first(sql: String): Row? = query(sql).firstOrNull()

    /** Runs a statement and throws its result away: DDL, INSERT … SELECT, or a SELECT that is only timed. */
    fun exec(sql: String, settings: Map<String, String> = emptyMap(), queryId: String? = null, session: String? = null, database: String? = null): ExecStats

    override fun close() {}
}

/** The name the HTTP client sends as its user agent. */
internal const val CLIENT_NAME = "granulee"

/** Granulee's own scans and trial copies are not how a table is used: the condition that keeps them out of query_log reads. */
const val NOT_GRANULEE = "NOT startsWith(http_user_agent, '$CLIENT_NAME ')"
