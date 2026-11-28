package io.granulee

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The backend is one package per feature, and a package only uses the ones before it in this list. That keeps
 * the rules, the SQL text and the reports free of the connection and of HTTP, and the features free of cycles.
 * The README's table of packages is in the same order.
 */
class PackageDependenciesTest {
    private val order = listOf("sql", "clickhouse", "state", "user", "facts", "explain", "recommendation", "scan", "source", "trial", "usage", "index", "explorer", "sqlite", "web")

    // What a package may hold without knowing Spring or a server: it is tested with plain objects.
    private val plain = setOf("sql", "facts")

    private val root: Path = Path.of("src/main/kotlin/io/granulee")

    private fun sources(): Map<String, List<Path>> = Files.walk(root).use { files ->
        files.filter { it.extension == "kt" && it.parent != root }.toList().groupBy { root.relativize(it).getName(0).name }
    }

    private fun imports(file: Path): List<String> = Regex("^import ([\\w.]+)", RegexOption.MULTILINE).findAll(file.readText()).map { it.groupValues[1] }.toList()

    @Test
    fun `every package is in the list`() {
        assertEquals(order.sorted(), sources().keys.sorted())
    }

    @Test
    fun `a package only uses the packages before it`() {
        val wrong = sources().flatMap { (pkg, files) ->
            files.flatMap { file ->
                imports(file).mapNotNull { Regex("^io\\.granulee\\.([a-z]+)\\.").find(it)?.groupValues?.get(1) }
                    .filter { it != pkg && order.indexOf(it) > order.indexOf(pkg) }
                    .map { "${root.relativize(file)} uses $it" }
            }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `the packages that only hold text and facts know neither Spring nor the ClickHouse client`() {
        val wrong = sources().filterKeys { it in plain }.values.flatten().flatMap { file ->
            imports(file).filter { it.startsWith("org.springframework") || it.startsWith("com.clickhouse") || it.startsWith("java.nio.file") }.map { "${root.relativize(file)} imports $it" }
        }
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }

    @Test
    fun `only the connection package knows the ClickHouse client, only the sqlite package the database, and only the data directory, the database and the key file touch the file system`() {
        val all = sources()
        fun users(prefix: String) = all.flatMap { (pkg, files) -> files.filter { file -> imports(file).any { it.startsWith(prefix) } }.map { "$pkg/${it.name}" } }.sorted()
        assertEquals(listOf("clickhouse/ChConnections.kt", "clickhouse/HttpChConnection.kt"), users("com.clickhouse"))
        assertEquals(listOf("source/PasswordCipher.kt", "sqlite/SqliteDatabase.kt", "state/DataDir.kt"), users("java.nio.file"))
        // Every feature keeps its state behind a Storage interface of its own; another database is another package.
        assertEquals(listOf("sqlite/SqliteDatabase.kt", "sqlite/SqliteStorage.kt"), (users("org.sqlite") + users("org.springframework.jdbc") + users("java.sql")).distinct().sorted())
    }
}
