package io.granulee.trial

import io.granulee.sql.Sql

/**
 * The WHERE clause that picks a trial's sample from the production table, with its leading space; empty when
 * the first rows will do. `partitions` are the table's partitions, newest first; they are only asked for when
 * the sample is the most recent ones.
 */
internal fun sampleFilter(request: TrialRequest, tableRows: Long, partitions: () -> List<PartitionRows>): String = when (request.sampling) {
    "where" -> " WHERE (${request.where})"
    "recent" -> recentPartitions(partitions(), request.rows)
    else -> {
        val every = if (request.rows > 0) tableRows / request.rows else 0
        // Every n-th row of each part: evenly spread and repeatable. Not rand(): on ClickHouse 26.9 a table
        // with skip indexes returns no rows for a rand() filter.
        if (every >= 2) " WHERE _part_offset % $every = 0" else ""
    }
}

/** The newest partitions that together hold the wanted rows. A table of one partition is read from its start. */
private fun recentPartitions(newestFirst: List<PartitionRows>, rows: Long): String {
    if (newestFirst.size <= 1) return ""
    var sum = 0L
    val picked = newestFirst.takeWhile { p -> (sum < rows).also { sum += p.rows } }
    return " WHERE _partition_id IN (${picked.joinToString(", ") { Sql.lit(it.partitionId) }})"
}
