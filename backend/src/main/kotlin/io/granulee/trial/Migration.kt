package io.granulee.trial

import io.granulee.facts.isCopied
import io.granulee.sql.Sql
import io.granulee.sql.Types
import io.granulee.sql.engineClause
import io.granulee.sql.engineTail
import io.granulee.sql.indexSql
import io.granulee.sql.normalizeKey
import io.granulee.sql.sameText

/** What brings the production table to a candidate's schema. */
internal sealed interface Migration {
    /** none | alter | rebuild, as the API names it. */
    val kind: String

    /** The statements as a person reviews them, comments included. */
    val sql: String

    /** What Granulee may run itself: only ALTERs, never a rebuild. */
    val statements: List<String>

    /** The table already is what the candidate describes. */
    data object None : Migration {
        override val kind = "none"
        override val sql = ""
        override val statements = emptyList<String>()
    }

    /** ClickHouse can change the table in place. */
    data class Alter(override val sql: String, override val statements: List<String>) : Migration {
        override val kind get() = "alter"
    }

    /** A key changes, so the table has to be built anew. Shown as a script, never run. */
    data class Rebuild(override val sql: String) : Migration {
        override val kind get() = "rebuild"
        override val statements get() = emptyList<String>()
    }
}

/**
 * The statements that bring the production table to the candidate's schema: ALTER TABLE where ClickHouse
 * can change the table in place, a rebuild script where it cannot. Empty when the candidate changes nothing.
 */
internal fun migrationSql(database: String, table: String, live: LiveTable, request: TrialRequest): String =
    migration(database, table, live, request).sql

internal fun migration(database: String, table: String, live: LiveTable, request: TrialRequest): Migration {
    val candidate = CandidateColumns(request)
    val changes = live.columns.mapNotNull(candidate::diff)
    val rebuild = buildList {
        if (!sameText(request.partitionKey, live.partitionKey)) add("the partition key changes")
        if (!sameText(normalizeKey(request.sortingKey), normalizeKey(live.sortingKey))) add("the sorting key changes")
        changes.filter { it.type != null && (it.column.inSortingKey || it.column.inPartitionKey) }
            .forEach { add("${it.column.name} is a key column and changes its type") }
    }
    if (rebuild.isNotEmpty()) return Migration.Rebuild(rebuildScript(database, table, live, request, candidate, rebuild))

    val blocks = alterBlocks(Sql.table(database, table), live, request, changes)
    return if (blocks.isEmpty()) Migration.None else Migration.Alter(blocks.joinToString("\n\n"), blocks.map { it.removeSuffix(";") })
}

private fun rebuildScript(
    database: String, table: String, live: LiveTable, request: TrialRequest, candidate: CandidateColumns, reasons: List<String>,
): String {
    val name = Sql.table(database, table)
    val fresh = Sql.table(database, table + "_new")
    val copied = live.columns.filter { it.isCopied() }
    val engine = engineClause(live.engineFull).ifBlank { "MergeTree" }
    return buildString {
        append("-- ALTER TABLE cannot do this in place: ${reasons.joinToString(", ")}.\n")
        append("-- The table is rebuilt instead. Pause writes while the rows are copied, or backfill them afterwards.\n")
        if (engine.startsWith("Replicated")) append("-- Replicated table: give the new table its own ZooKeeper path.\n")
        val (known, unknown) = live.projections.partition { it.query.isNotBlank() }
        if (known.isNotEmpty()) {
            append("-- The new table gets the ${if (known.size == 1) "projection" else "${known.size} projections"} of the old one; ")
            append("${if (known.size == 1) "it is" else "they are"} built as the rows are copied.\n")
        }
        unknown.forEach {
            append("-- Projection ${it.name} is missing below: this server does not tell its definition. Add it to the CREATE TABLE by hand.\n")
        }
        append(createSql(fresh, live, request, engine, engineTail(live.engineFull), withProjections = true))
        append(";\n\nINSERT INTO $fresh (${copied.joinToString(", ") { Sql.id(it.name) }})\nSELECT ")
        append(copied.joinToString(", ") { copyExpression(it, candidate.requestedType(it)) })
        append("\nFROM $name;\n\nEXCHANGE TABLES $name AND $fresh;\n\n")
        append("-- $fresh now holds the old data. Drop it once the new table is verified:\n")
        append("-- DROP TABLE $fresh;")
    }
}

/** One commented ALTER TABLE per kind of change, in the order they should run. */
private fun alterBlocks(name: String, live: LiveTable, request: TrialRequest, changes: List<ColumnDiff>): List<String> {
    fun alter(comment: String, actions: List<String>) =
        "-- $comment\nALTER TABLE $name\n" + actions.joinToString(",\n") { "    $it" } + ";"

    val blocks = mutableListOf<String>()
    val types = changes.mapNotNull { change -> change.type?.let { change to it } }
    if (types.isNotEmpty()) {
        val notes = types.filter { (change, type) -> Types.isNullable(change.column.type) && !Types.isNullable(type) }
            .joinToString("") { (change, _) -> "\n-- ${change.column.name} loses Nullable: the ALTER fails while the column still holds NULLs." }
        blocks.add(
            alter(
                "Type changes run as a mutation that rewrites the column in every part. Progress is in system.mutations.$notes",
                types.map { (change, type) -> "MODIFY COLUMN ${Sql.id(change.column.name)} $type" + if (change.codec.isNullOrEmpty()) "" else " CODEC(${change.codec})" },
            ),
        )
    }
    // A codec that only changes, and a codec that is removed next to a type change.
    val codecs = changes.mapNotNull { change -> change.codec?.takeIf { change.type == null || it.isEmpty() }?.let { change to it } }
    if (codecs.isNotEmpty()) {
        blocks.add(
            alter(
                "Codec changes only touch metadata. Existing parts keep the old codec until they are merged;\n" +
                    "-- OPTIMIZE TABLE $name FINAL rewrites them right away.",
                codecs.map { (change, codec) -> "MODIFY COLUMN ${Sql.id(change.column.name)} " + if (codec.isEmpty()) "REMOVE CODEC" else "CODEC($codec)" },
            ),
        )
    }
    val dropped = live.indexes.filter { it.name in request.dropIndexes }
    if (dropped.isNotEmpty()) {
        blocks.add(alter("Skip indexes the candidate leaves out.", dropped.map { "DROP INDEX ${Sql.id(it.name)}" }))
    }
    val added = request.addIndexes.filter { new -> live.indexes.none { it.name == new.name.trim() } }
    if (added.isNotEmpty()) {
        blocks.add(alter("New skip indexes. ADD INDEX only covers parts written from now on.", added.map { "ADD ${indexSql(it)}" }))
        blocks.add(
            alter(
                "Builds the new indexes for the existing parts, as a mutation. Progress is in system.mutations.",
                added.map { "MATERIALIZE INDEX ${Sql.id(it.name.trim())}" },
            ),
        )
    }
    return blocks
}
