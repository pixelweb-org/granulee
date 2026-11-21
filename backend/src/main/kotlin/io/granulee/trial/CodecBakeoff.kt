package io.granulee.trial

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.Row
import io.granulee.clickhouse.long
import io.granulee.clickhouse.str
import io.granulee.explain.Explain
import io.granulee.facts.ColumnFacts
import io.granulee.sql.Sql
import io.granulee.sql.Types
import io.granulee.sql.mentions
import io.granulee.sql.normalizeKey

/**
 * Every codec worth trying for the largest columns, side by side: one more scratch table, filled from the
 * candidate, that holds each of those columns once per codec. The rows lie as in the candidate, because that
 * order is what the codecs see.
 */
internal class CodecBakeoff(
    private val trial: Trial,
    private val ch: ChConnection,
    private val statements: TrialStatements,
    private val tables: ScratchTables,
    private val journal: TrialJournal,
) {
    private val request = trial.request
    private val candidate = CandidateColumns(request)

    /** One column of the comparison table: a production column under one codec. */
    private class Variant(val column: ColumnFacts, val type: String, val codec: String, val name: String)

    /** `scan` times a full read of one column of a table, with the settings given. */
    fun run(live: LiveTable, copied: List<ColumnFacts>, candSizes: TableSizes, scan: (table: String, column: String, settings: Map<String, String>) -> Double?): List<CodecColumn> {
        val tested = copied
            .mapNotNull { c -> candSizes.columns[c.name]?.compressed?.takeIf { it > 0 && !Types.hasDynamicSubcolumns(candidate.type(c)) }?.let { c to it } }
            .sortedByDescending { it.second }
            .take(Codecs.MAX_COLUMNS)
            .map { it.first }
        if (tested.isEmpty()) {
            journal.say("No per-column sizes on the candidate, so there is nothing to compare codecs by")
            return emptyList()
        }
        return try {
            val variants = variants(tested, candSizes)
            fill(live, variants)
            val measured = ch.query(
                """
                SELECT name, compression_codec, toInt64(data_compressed_bytes) AS compressed, toInt64(data_uncompressed_bytes) AS uncompressed
                FROM system.columns
                WHERE database = ${Sql.lit(trial.scratchDatabase)} AND table = ${Sql.lit(tables.codecsName)}
                """.trimIndent(),
            ).associateBy { it.str("name") }
            val noCache = Explain.uncached(ch)
            val codecs = tested.map { c -> compare(c, variants.filter { it.column === c }, measured) { column -> scan(tables.codecs, column, noCache) } }
            val better = codecs.count { it.best != it.production }
            journal.say("Compared ${variants.size} codec variants for the ${codecs.size} largest columns in the candidate's row order: a better one for $better")
            codecs
        } catch (e: ChException) {
            journal.say("Comparing codecs failed and is left out: ${e.message}")
            emptyList()
        } finally {
            listOf(tables.codecs, tables.probe).forEach { table -> runCatching { ch.exec("DROP TABLE IF EXISTS $table SYNC") } }
        }
    }

    /** Per column the codec in production, the candidate's, and the ones worth trying for its type that the server takes. */
    private fun variants(tested: List<ColumnFacts>, candSizes: TableSizes): List<Variant> {
        // The server decides which codec it takes for a type; ask it once per pair.
        val accepted = HashMap<Pair<String, String>, Boolean>()
        fun usable(type: String, codec: String): Boolean = codec.isBlank() || accepted.getOrPut(type to codec) {
            try {
                statements.exec("CREATE TABLE ${tables.probe} (v $type${Codecs.clause(codec)}) ENGINE = MergeTree ORDER BY tuple()")
                statements.exec("DROP TABLE IF EXISTS ${tables.probe} SYNC")
                true
            } catch (e: ChException) {
                false
            }
        }
        return tested.flatMapIndexed { i, c ->
            val type = candidate.type(c)
            val average = if (candSizes.rows > 0) (candSizes.columns[c.name]?.uncompressed ?: 0).toDouble() / candSizes.rows else 0.0
            (listOf(Types.codecBody(c.codec), candidate.codec(c)) + Codecs.candidates(type, average)).map { it.trim() }.distinct()
                .filter { usable(type, it) }
                .mapIndexed { j, codec -> Variant(c, type, codec, "c${i}_v$j") }
        }
    }

    /** Builds the comparison table and copies the candidate's rows into it. */
    private fun fill(live: LiveTable, variants: List<Variant>) {
        // The keys have to be there for the rows to lie as in the candidate.
        val keys = live.columns.filter { mentions(request.sortingKey + " " + request.partitionKey, it.name) }
        statements.exec("DROP TABLE IF EXISTS ${tables.codecs} SYNC")
        statements.exec(
            buildString {
                append("CREATE TABLE ${tables.codecs}\n(\n")
                append((keys.map { "    ${Sql.id(it.name)} ${candidate.type(it)}" } + variants.map { "    ${Sql.id(it.name)} ${it.type}${Codecs.clause(it.codec)}" }).joinToString(",\n"))
                append("\n)\nENGINE = MergeTree\n")
                if (request.partitionKey.isNotBlank()) append("PARTITION BY ${request.partitionKey}\n")
                append("ORDER BY (${normalizeKey(request.sortingKey).ifBlank { "tuple()" }})\n")
                append(trialSettings(live))
            },
        )
        val into = (keys.map { Sql.id(it.name) } + variants.map { Sql.id(it.name) }).joinToString(", ")
        val from = (keys.map { Sql.id(it.name) } + variants.map { Sql.id(it.column.name) }).joinToString(", ")
        statements.exec("INSERT INTO ${tables.codecs} ($into) SELECT $from FROM ${tables.cand}", INSERT_SETTINGS)
        if (request.optimizeFinal) statements.exec("OPTIMIZE TABLE ${tables.codecs} FINAL")
    }

    /** What the variants of one column came to, and which of them to use. */
    private fun compare(c: ColumnFacts, mine: List<Variant>, measured: Map<String, Row>, scan: (column: String) -> Double?): CodecColumn {
        fun actual(variant: Variant) = Types.codecBody(measured[variant.name]?.str("compression_codec").orEmpty())
        fun bytes(variant: Variant) = measured[variant.name]?.long("compressed") ?: 0
        val smallest = mine.map(::bytes).filter { it > 0 }.minOrNull() ?: 0
        val inProduction = mine.firstOrNull { it.codec == Types.codecBody(c.codec).trim() }
        val inCandidate = mine.firstOrNull { it.codec == candidate.codec(c).trim() }
        // Reading is timed for the ones in the running: close to the smallest, or in use today.
        val (variants, defaultIs) = mine.distinctBy(::actual).map { variant ->
            val near = bytes(variant) <= smallest * 1.1 || variant === inProduction || variant === inCandidate
            CodecVariant(actual(variant), bytes(variant), measured[variant.name]?.long("uncompressed") ?: 0, if (request.columnScans && near) scan(variant.name) else null)
        }.sortedBy { it.compressedBytes }.let(Codecs::withoutDefaultTwin)
        val production = inProduction?.let(::actual) ?: Types.codecBody(c.codec)
        return CodecColumn(
            name = c.name,
            type = candidate.type(c),
            production = production,
            candidate = inCandidate?.let(::actual) ?: production,
            best = Codecs.best(variants, production.takeIf { inProduction != null }),
            variants = variants,
            defaultIs = defaultIs,
        )
    }
}
