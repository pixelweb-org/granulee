package io.granulee.sql

/** SQL identifier and literal quoting. */
object Sql {
    fun id(name: String): String = "`" + name.replace("\\", "\\\\").replace("`", "\\`") + "`"
    fun lit(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"
    fun table(database: String, table: String): String = id(database) + "." + id(table)
}
