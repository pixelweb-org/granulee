package io.granulee.sql

// Key and index expressions as system tables print them, read without a SQL parser.

/** Splits "a, f(b, c), d" at top-level commas. */
fun splitTopLevel(expression: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    val current = StringBuilder()
    for (ch in expression) {
        when {
            ch == '(' -> { depth++; current.append(ch) }
            ch == ')' -> { depth--; current.append(ch) }
            ch == ',' && depth == 0 -> { parts.add(current.toString().trim()); current.clear() }
            else -> current.append(ch)
        }
    }
    if (current.isNotBlank()) parts.add(current.toString().trim())
    return parts
}

/** "(a, b)" and "a, b" mean the same sorting key. */
fun normalizeKey(key: String): String {
    val k = key.trim()
    if (!k.startsWith("(") || !k.endsWith(")")) return k
    var depth = 0
    for ((i, ch) in k.withIndex()) {
        if (ch == '(') depth++
        if (ch == ')') depth--
        if (depth == 0 && i < k.length - 1) return k
    }
    return k.substring(1, k.length - 1).trim()
}

/** Whether two expressions are the same but for their whitespace. */
fun sameText(a: String, b: String) = a.replace(Regex("\\s+"), "") == b.replace(Regex("\\s+"), "")

/** A column name as it stands in an expression or a query: not part of a longer name, not a function call. */
internal fun columnPattern(column: String): String = "(?<![\\w.`])`?" + Regex.escape(column) + "`?(?![\\w(`])"

fun mentions(text: String, column: String): Boolean = Regex(columnPattern(column)).containsMatchIn(text)
