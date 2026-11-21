package io.granulee.sql

// engine_full of system.tables, taken apart: "Engine(args) PARTITION BY … ORDER BY … TTL … SETTINGS …".

private val CLAUSES = listOf("PARTITION BY ", "PRIMARY KEY ", "ORDER BY ", "SAMPLE BY ", "TTL ", "SETTINGS ")
private val TAIL_CLAUSES = listOf("SAMPLE BY ", "TTL ", "SETTINGS ")

/** The engine clause of engine_full: everything before the first key or settings clause. */
fun engineClause(full: String): String = clauseStart(full, CLAUSES)?.let { full.substring(0, it) } ?: full

/** What follows ORDER BY in engine_full: SAMPLE BY, TTL and SETTINGS, one clause per line. */
fun engineTail(full: String): String {
    val parts = mutableListOf<String>()
    var rest = clauseStart(full, TAIL_CLAUSES)?.let { full.substring(it + 1) } ?: return ""
    while (true) {
        val next = clauseStart(rest, TAIL_CLAUSES) ?: break
        parts.add(rest.substring(0, next))
        rest = rest.substring(next + 1)
    }
    return (parts + rest).joinToString("\n")
}

/** Index of the space in front of the first of these clauses that is not inside parentheses. */
private fun clauseStart(full: String, clauses: List<String>): Int? {
    var depth = 0
    for (i in full.indices) {
        when (full[i]) {
            '(' -> depth++
            ')' -> depth--
            ' ' -> if (depth == 0 && i > 0 && clauses.any { full.startsWith(it, i + 1) }) return i
        }
    }
    return null
}

/** The TTL expression of engine_full, or null when the table has none. */
fun engineTtl(full: String): String? = keywordClause(full, "TTL")

/** The table's own settings from engine_full, as name and value. */
fun engineSettings(full: String): List<Pair<String, String>> = keywordClause(full, "SETTINGS")?.let(::splitTopLevel).orEmpty().mapNotNull {
    val at = it.indexOf('=')
    if (at < 0) null else it.substring(0, at).trim() to it.substring(at + 1).trim()
}

/** index_granularity as engine_full states it; null where the table leaves it to the server. */
fun engineIndexGranularity(full: String): String? = Regex("index_granularity = (\\d+)").find(full)?.groupValues?.get(1)

/** Pulls "TTL …" or "SETTINGS …" out of engine_full. */
private fun keywordClause(full: String, keyword: String): String? {
    val start = full.indexOf(" $keyword ")
    if (start < 0) return null
    val rest = full.substring(start + keyword.length + 2)
    val end = if (keyword == "TTL") rest.indexOf(" SETTINGS ") else -1
    return (if (end < 0) rest else rest.substring(0, end)).trim().ifEmpty { null }
}

/** "database.table" a Buffer table flushes into, from its engine_full: Buffer('db', 'table', …). */
fun bufferTarget(database: String, engineFull: String): String? {
    val m = Regex("^Buffer\\(\\s*('(?:[^'\\\\]|\\\\.)*'|[^,]*?)\\s*,\\s*'?((?:[^'\\\\,)]|\\\\.)+)'?").find(engineFull.trim()) ?: return null
    val db = m.groupValues[1].takeIf { it.startsWith("'") }?.trim('\'')?.ifEmpty { null } ?: database
    return "$db.${m.groupValues[2].trim()}"
}
