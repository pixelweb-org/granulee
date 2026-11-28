package io.granulee.facts

import io.granulee.sql.filterText
import io.granulee.sql.mentions
import io.granulee.sql.predicates

// What a scan knows about one table. Everything here comes from system tables; table data is never read.
// These classes are the format a scan is stored in.

data class ColumnFacts(
    val name: String,
    val type: String,
    val codec: String, // as in system.columns: "CODEC(ZSTD(1))", or "" for the server default
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val defaultKind: String,
    val defaultExpression: String,
    val inSortingKey: Boolean,
    val inPartitionKey: Boolean,
    val cardinality: Long?, // highest per-part estimate; only newer servers report it
)

// Extensions, not members: a member named is…() would become a field of the snapshot files.

/** Whether the column holds data of its own: an ALIAS is computed on read, an EPHEMERAL one only exists during an insert. */
fun ColumnFacts.isStored(): Boolean = defaultKind != "ALIAS" && defaultKind != "EPHEMERAL"

/** Whether an INSERT … SELECT has to name it: MATERIALIZED and ALIAS columns are computed by the table they are copied into. */
fun ColumnFacts.isCopied(): Boolean = defaultKind.isEmpty() || defaultKind == "DEFAULT"

data class IndexFacts(
    val name: String,
    val expression: String,
    val type: String,
    val granularity: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
)

data class QueryFacts(
    val query: String,
    val normalized: String,
    val count: Long,
    // Column → how the query filters on it ("eq", "range", "has", "like", "other"), from ClickHouse's own analysis
    // of the query. Null where the scan could not get one; then the query text has to do.
    val filters: Map<String, Set<String>>? = null,
    // The default database of the session that ran it: where its unqualified table names are looked up.
    val database: String? = null,
)

/** The analysed filters on a column, with its subcolumns: a test for NULL reads `column.null`. */
private fun Map<String, Set<String>>.of(column: String): List<Set<String>> = filterKeys { it == column || it.startsWith("$column.") }.values.toList()

/** Whether the query filters on the column: by ClickHouse's analysis where the scan has it, by the text otherwise. */
fun QueryFacts.filtersOn(column: String): Boolean = filters?.of(column)?.isNotEmpty() ?: mentions(filterText(normalized), column)

/** How the query filters on the column: "eq", "range", "has". */
fun QueryFacts.predicatesOn(column: String): Set<String> = filters?.of(column)?.flatten()?.toSet() ?: predicates(filterText(normalized), column)

/**
 * How many files a table is on disk, estimated from system.parts_columns: a wide part keeps two files per
 * substream of a column, a compact part a handful whatever its columns are.
 */
data class FileFacts(
    val files: Long = 0,
    val largestPartFiles: Long = 0,
    val widestColumn: String = "",
    val widestColumnFiles: Long = 0, // per wide part
)

data class TableFacts(
    val database: String,
    val name: String,
    val engine: String,
    val engineFull: String,
    val partitionKey: String,
    val sortingKey: String,
    val primaryKey: String,
    val rows: Long,
    val compressedBytes: Long,
    val uncompressedBytes: Long,
    val bytesOnDisk: Long,
    val activeParts: Long,
    val partitions: Long,
    val columns: List<ColumnFacts>,
    val indexes: List<IndexFacts>,
    val queries: List<QueryFacts> = emptyList(), // most frequent SELECTs from query_log
    val queryTotal: Long? = null, // all SELECTs on the table in the window; null = query_log not readable
    val findings: Int = 0,
    val savingsBytes: Long = 0,
    val findingsByCategory: Map<String, Int> = emptyMap(),
    val files: FileFacts? = null, // null = the server does not report substreams
    val buffers: List<String> = emptyList(), // Buffer tables in front of this one, as database.table
)
