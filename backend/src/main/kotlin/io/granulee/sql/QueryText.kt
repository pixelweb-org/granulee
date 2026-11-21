package io.granulee.sql

// Logged queries as text: what can be said about one, and done to one, without parsing it.

/** A logged query as EXPLAIN or a replay takes it: no trailing semicolon or FORMAT clause. */
fun cleanQuery(query: String): String =
    query.trim().trimEnd(';').replace(Regex("\\s+FORMAT\\s+\\w+\\s*$", RegexOption.IGNORE_CASE), "")

/** Points a logged query at another table: `target` stands wherever the query names database.table or table. */
fun rewriteQuery(query: String, database: String, table: String, target: String): String {
    val name = Regex.escape(table)
    val db = Regex.escape(database)
    val pattern = Regex("(?<![\\w.`])(?:`?$db`?\\.)?`?$name`?(?![\\w`.(])")
    return pattern.replace(cleanQuery(query)) { target }
}

/** The same for every Buffer table ("database.table") in front of the table: a logged query may name one of them instead. */
fun rewriteBuffers(query: String, buffers: List<String>, target: String): String =
    buffers.fold(query) { text, buffer -> rewriteQuery(text, buffer.substringBefore('.'), buffer.substringAfter('.'), target) }

/** A query from its first WHERE or PREWHERE on: where a column has to appear to count as filtered on. */
fun filterText(query: String): String {
    val at = Regex("\\b(PRE)?WHERE\\b", RegexOption.IGNORE_CASE).find(query)?.range?.first
    return if (at == null) "" else query.substring(at)
}

/** How a query filters on a column, read off the text after WHERE: "eq", "range", "has". */
fun predicates(filter: String, column: String): Set<String> {
    val c = columnPattern(column)
    val found = mutableSetOf<String>()
    if (Regex("$c\\s*(==?(?!=)|IN\\b)", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("eq")
    if (Regex("$c\\s*(>=?|<=?(?!>)|BETWEEN\\b)", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("range")
    if (Regex("\\bhas(Any|All)?\\(\\s*$c", RegexOption.IGNORE_CASE).containsMatchIn(filter)) found.add("has")
    return found
}
