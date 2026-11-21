package io.granulee

/**
 * What a logged query filters on, read off ClickHouse's own analysis of it. EXPLAIN QUERY TREE prints the query
 * after name resolution: every column in WHERE and PREWHERE carries the table it comes from, through aliases,
 * subqueries, CTEs and joins. That is what a look at the query text can only guess.
 */
object QueryTree {
    // The query is analysed, not run. Analysis would still execute a scalar subquery, so no table rows may be read.
    private val ANALYSE_ONLY = mapOf("max_rows_to_read" to "1", "read_overflow_mode" to "throw", "max_execution_time" to "5")

    private val HEAD = Regex("^([A-Z_]+) id: (\\d+)")
    private val FILTERS = setOf("WHERE", "PREWHERE")
    private val EQ = setOf("equals", "in", "globalIn", "nullIn", "globalNullIn")
    private val RANGE = setOf("less", "greater", "lessOrEquals", "greaterOrEquals")
    private val HAS = setOf("has", "hasAny", "hasAll")
    private val LIKE = setOf("like", "ilike", "match", "startsWith", "endsWith", "hasToken", "hasTokenCaseInsensitive", "multiSearchAny", "position", "positionCaseInsensitive")
    private val LOGICAL = setOf("and", "or")

    private class Node(val indent: Int, val text: String) {
        val children = mutableListOf<Node>()
        var parent: Node? = null
        val type: String?
        val id: Int?

        init {
            val head = HEAD.find(text)
            type = head?.groupValues?.get(1)
            id = head?.groupValues?.get(2)?.toIntOrNull()
        }

        /** "name: value" of a node line; the value ends at the next ", key: " or at the end of the line. */
        fun attr(name: String): String? = Regex("(?:^|, )$name: (.*?)(?=, [a-z_]+: |$)").find(text)?.groupValues?.get(1)

        fun section(name: String): Node? = children.firstOrNull { it.type == null && it.text == name }
    }

    /**
     * Asks the server how it reads the query. Null when it cannot: the user may not read the table, the server has
     * no analyzer, the logged text was cut, or a scalar subquery would have had to read data.
     */
    fun analyse(ch: ChConnection, query: String): Map<String, Map<String, Set<String>>>? = try {
        filters(ch.query("EXPLAIN QUERY TREE ${Explain.clean(query)}", ANALYSE_ONLY).joinToString("\n") { it.str("explain") })
    } catch (e: Exception) {
        null
    }

    /**
     * The filters on one table out of `analyse`, counting what the query reads through the table's Buffer tables.
     * Null when the query does not name the table itself, as with a view over it: then the tree says nothing about it.
     */
    fun on(all: Map<String, Map<String, Set<String>>>?, tables: Collection<String>): Map<String, Set<String>>? {
        val found = tables.mapNotNull { all?.get(it) }
        if (found.isEmpty()) return null
        val merged = linkedMapOf<String, MutableSet<String>>()
        found.forEach { columns -> columns.forEach { (column, kinds) -> merged.getOrPut(column) { linkedSetOf() }.addAll(kinds) } }
        return merged
    }

    /**
     * Per table ("database.table") the columns the query filters on, each with how: "eq", "range", "has", "like" or
     * "other". A table the query reads without filtering on it is there with no columns.
     */
    fun filters(dump: String): Map<String, Map<String, Set<String>>> {
        val root = Node(-1, "")
        val stack = ArrayDeque(listOf(root))
        val all = mutableListOf<Node>()
        dump.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val node = Node(line.length - line.trimStart().length, line.trim())
            while (stack.last().indent >= node.indent) stack.removeLast()
            node.parent = stack.last()
            stack.last().children.add(node)
            stack.addLast(node)
            all.add(node)
        }

        val sources = HashMap<Int, Node>()
        all.forEach { node -> if (node.id != null && node.type in setOf("TABLE", "QUERY", "UNION")) sources.putIfAbsent(node.id, node) }
        val out = linkedMapOf<String, MutableMap<String, MutableSet<String>>>()
        all.filter { it.type == "TABLE" }.forEach { table -> table.attr("table_name")?.let { out.getOrPut(it) { linkedMapOf() } } }

        all.filter { it.type == "QUERY" }.forEach { query ->
            query.children.filter { it.type == null && it.text in FILTERS }.forEach { section ->
                columnsIn(section).forEach { column ->
                    val kind = kindOf(column, section)
                    origins(column, sources, 0).forEach { (table, name) ->
                        out.getOrPut(table) { linkedMapOf() }.getOrPut(name) { linkedSetOf() }.add(kind)
                    }
                }
            }
        }
        return out
    }

    /** The COLUMN nodes under a node. A nested query is a query of its own and is not entered. */
    private fun columnsIn(node: Node): List<Node> = node.children.flatMap { child ->
        when (child.type) {
            "QUERY", "UNION" -> emptyList()
            "COLUMN" -> listOf(child) + columnsIn(child)
            else -> columnsIn(child)
        }
    }

    /** How the column is compared: the nearest comparison around it decides. A column on its own is a boolean test. */
    private fun kindOf(column: Node, section: Node): String {
        var bare = true
        var at = column.parent
        while (at != null && at !== section) {
            if (at.type == "FUNCTION") {
                val function = at.attr("function_name").orEmpty()
                when {
                    function in EQ -> return "eq"
                    function in RANGE -> return "range"
                    // has(column, value) searches an array column; has([values], column) is a list the column is looked up in.
                    function in HAS -> return if (isFirstArgument(at, column)) "has" else "eq"
                    function in LIKE -> return "like"
                    function in LOGICAL && bare -> return "eq"
                }
                bare = false
            }
            at = at.parent
        }
        return if (bare) "eq" else "other"
    }

    private fun isFirstArgument(function: Node, column: Node): Boolean {
        val first = function.section("ARGUMENTS")?.children?.firstOrNull { it.type == "LIST" }?.children?.firstOrNull() ?: return false
        var at: Node? = column
        while (at != null && at !== function) {
            if (at === first) return true
            at = at.parent
        }
        return false
    }

    /** The table columns a COLUMN node stands for: itself, or what a subquery's output column is made of. */
    private fun origins(column: Node, sources: Map<Int, Node>, depth: Int): List<Pair<String, String>> {
        val name = column.attr("column_name") ?: return emptyList()
        val source = column.attr("source_id")?.toIntOrNull()?.let(sources::get) ?: return emptyList()
        return when (source.type) {
            "TABLE" -> source.attr("table_name")?.let { listOf(it to name) }.orEmpty()
            "QUERY" -> if (depth > 8) emptyList() else projected(source, outputIndex(source, name)).flatMap { origins(it, sources, depth + 1) }
            "UNION" -> {
                val branches = branchesOf(source)
                val index = branches.firstOrNull()?.let { outputIndex(it, name) } ?: -1
                if (depth > 8) emptyList() else branches.flatMap { projected(it, index) }.flatMap { origins(it, sources, depth + 1) }
            }
            else -> emptyList()
        }
    }

    private fun outputIndex(query: Node, name: String): Int =
        query.section("PROJECTION COLUMNS")?.children.orEmpty().indexOfFirst { outputName(it.text) == name }

    /** The columns behind the query's output column at a position. */
    private fun projected(query: Node, index: Int): List<Node> {
        val item = query.section("PROJECTION")?.children?.firstOrNull { it.type == "LIST" }?.children?.getOrNull(index) ?: return emptyList()
        return (if (item.type == "COLUMN") listOf(item) else emptyList()) + columnsIn(item)
    }

    private fun branchesOf(union: Node): List<Node> = union.children.flatMap { child ->
        if (child.type == "QUERY") listOf(child) else branchesOf(child)
    }

    /** "name Type" of a PROJECTION COLUMNS line without the type: the type starts at the last space outside parentheses. */
    internal fun outputName(line: String): String {
        var depth = 0
        for (i in line.indices.reversed()) {
            when (line[i]) {
                ')' -> depth++
                '(' -> depth--
                ' ' -> if (depth == 0) return line.substring(0, i)
            }
        }
        return line
    }
}
