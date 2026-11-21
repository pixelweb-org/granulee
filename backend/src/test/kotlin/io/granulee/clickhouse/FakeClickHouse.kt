package io.granulee.clickhouse

/**
 * A stand-in server for tests. It answers a statement by the first rule whose text the statement contains,
 * with no rows where there is none, and remembers everything it was sent in order.
 */
class FakeClickHouse : ChConnection {
    data class Sent(val sql: String, val settings: Map<String, String>, val queryId: String?, val session: String?, val database: String?, val executed: Boolean)

    private val rules = mutableListOf<Pair<String, (String) -> List<Row>>>()
    val sent = mutableListOf<Sent>()

    /** The statements run with exec, as text. */
    val executed: List<String> get() = sent.filter { it.executed }.map { it.sql }

    fun answer(containing: String, vararg rows: Row) = apply { rules.add(containing to { rows.toList() }) }

    fun answer(containing: String, rows: (String) -> List<Row>) = apply { rules.add(containing to rows) }

    /** Statements with this text fail as the server would refuse them. */
    fun refuse(containing: String, message: String = "Code: 62. DB::Exception: refused") = apply { rules.add(containing to { throw ChException(message) }) }

    private fun rows(sql: String): List<Row> = rules.firstOrNull { sql.contains(it.first) }?.second?.invoke(sql).orEmpty()

    override fun query(sql: String, settings: Map<String, String>, session: String?, database: String?): List<Row> {
        sent.add(Sent(sql, settings, null, session, database, executed = false))
        return rows(sql)
    }

    override fun exec(sql: String, settings: Map<String, String>, queryId: String?, session: String?, database: String?): ExecStats {
        sent.add(Sent(sql, settings, queryId, session, database, executed = true))
        rows(sql)
        return ExecStats(readRows = 100, writtenRows = 0, elapsedMs = 250.0, queryId = queryId)
    }
}
