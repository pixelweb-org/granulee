package io.granulee.sql

/**
 * A skip index that does not exist yet: proposed by a rule, estimated with EXPLAIN WHATIF, or added to a
 * trial's candidate on top of the production ones.
 */
data class TrialIndex(val name: String = "", val expression: String = "", val type: String = "", val granularity: Int = 1)

private val INDEX_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** What keeps the definition from going to the server, worded for the person who entered it; null when nothing does. */
fun TrialIndex.problem(): String? = when {
    !INDEX_NAME.matches(name.trim()) -> "Index name \"$name\" must be letters, digits and underscores"
    expression.isBlank() || type.isBlank() -> "Index $name needs an expression and a type"
    (expression + type).contains(';') -> "Index $name must be a single expression and type"
    else -> null
}

/** INDEX … as it stands in CREATE TABLE and after ADD in ALTER TABLE. */
fun indexSql(index: TrialIndex): String =
    "INDEX ${Sql.id(index.name.trim())} ${indexExpression(index.expression.trim())} TYPE ${index.type.trim()} GRANULARITY ${index.granularity.coerceAtLeast(1)}"

/** system.data_skipping_indices lists an index over several expressions as "a, b"; DDL wants "(a, b)". */
fun indexExpression(expression: String): String = if (splitTopLevel(expression).size > 1) "($expression)" else expression
