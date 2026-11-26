package io.granulee.trial

import io.granulee.sql.TrialIndex
import io.granulee.sql.problem

// A trial as the API shows it and trials.json keeps it. The field names are the contract with both.

/**
 * How far a trial, one of its steps, or something started from it has got, as the API and trials.json spell it.
 * Kept as text in the classes below: a stored file from any earlier release has to load as it is.
 */
internal object Status {
    const val RUNNING = "running"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled" // trials only
    const val DONE = "done" // steps only
    const val PENDING = "pending" // steps only
}

data class TrialColumn(val name: String = "", val type: String = "", val codec: String = "")

data class TrialRequest(
    val sourceId: String = "",
    val database: String = "",
    val table: String = "",
    val name: String = "",
    val partitionKey: String = "",
    val sortingKey: String = "",
    val columns: List<TrialColumn> = emptyList(),
    val dropIndexes: List<String> = emptyList(),
    val addIndexes: List<TrialIndex> = emptyList(),
    val rows: Long = 0,
    val sampling: String = "even", // even | recent | where | all (the whole table; `rows` is then only what it held when the trial was set up)
    val where: String? = null,
    val replayQueries: Boolean = true,
    val measureInserts: Boolean = true,
    val optimizeFinal: Boolean = true,
    val columnScans: Boolean = true,
    val runsPerQuery: Int = 5,
    /** Try several codecs on the largest columns and report the best one for each. Off for trials stored before it existed. */
    val codecBakeoff: Boolean = false,
    /**
     * Also build the table as it would be without any tuning: no skip indexes, no projections, the server's default
     * codec on every column. Keys and types stay. It shows how much today's tuning already buys.
     */
    val plainDefaults: Boolean = false,
)

data class TrialStep(val label: String, val state: String) // done | running | pending | failed

/** What keeps a trial with this request from starting, worded for the person who set it up; null when nothing does. */
internal fun TrialRequest.problem(): String? = when {
    database.isBlank() || table.isBlank() -> "Pick a table for the trial"
    rows < 1 && sampling != "all" -> "Rows to copy must be at least 1"
    runsPerQuery !in 1..20 -> "Runs per query must be 1 to 20"
    sampling !in setOf("even", "recent", "where", "all") -> "Unknown sampling method"
    sampling == "where" && where.isNullOrBlank() -> "Enter the WHERE clause for the sample"
    where.orEmpty().contains(';') -> "The WHERE clause must be a single expression"
    columns.isEmpty() -> "The candidate schema has no columns"
    else -> addIndexes.firstNotNullOfOrNull { it.problem() }
        ?: "Two new indexes share a name".takeIf { addIndexes.map { it.name.trim() }.distinct().size != addIndexes.size }
}

data class LogLine(val at: String, val message: String)

data class Trial(
    val id: String,
    val name: String,
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val status: String, // running | completed | failed | cancelled
    val error: String?,
    val createdAt: String,
    val finishedAt: String?,
    val progress: Int,
    val steps: List<TrialStep>,
    val request: TrialRequest,
    val scratchDatabase: String,
    val baseTable: String,
    val candTable: String,
    val scratchBytes: Long,
    val changedKeys: Int,
    val changedColumns: Int,
    val result: TrialResult?,
    val log: List<LogLine>,
    /** The last time the candidate was applied to the production table, if ever. */
    val apply: TrialApply? = null,
    /** The last OPTIMIZE … FINAL of the production table started from this trial, if any. */
    val optimize: TrialOptimize? = null,
)

data class TrialApply(
    val status: String, // running | completed | failed
    val startedAt: String,
    val finishedAt: String?,
    val error: String?,
    val sql: String,
    val done: Int,
    val total: Int,
)

data class TrialOptimize(
    val status: String, // running | completed | failed
    val startedAt: String,
    val finishedAt: String?,
    val error: String?,
    val sql: String,
)

data class TrialList(val trials: List<Trial>, val scratchUsedBytes: Long, val scratchFreeBytes: Long?)

/**
 * A trial as it stands after Granulee was restarted in the middle of it: whatever was running then is not
 * running any more, and says so. ClickHouse may well have carried on by itself.
 */
internal fun Trial.afterRestart(now: String): Trial {
    val trial = if (optimize?.status != Status.RUNNING) this else copy(
        optimize = optimize.copy(
            status = Status.FAILED,
            finishedAt = now,
            error = "Granulee restarted while OPTIMIZE FINAL was running. ClickHouse may still be merging: check system.merges.",
        ),
    )
    return when {
        trial.apply?.status == Status.RUNNING -> trial.copy(
            apply = trial.apply.copy(
                status = Status.FAILED,
                finishedAt = now,
                error = "Granulee restarted while the statements were running. ClickHouse may still be working on them: check system.mutations.",
            ),
        )
        trial.status != Status.RUNNING -> trial
        else -> trial.copy(
            status = Status.FAILED,
            error = "Granulee restarted while this trial was running. Its scratch tables may still exist; remove the trial to drop them.",
            finishedAt = now,
            steps = trial.steps.map { s -> if (s.state == Status.RUNNING) s.copy(state = Status.FAILED) else s },
        )
    }
}

/** Whether anything of the trial was under way: the run itself, applying it, or the OPTIMIZE after that. */
internal fun Trial.hasWorkRunning(): Boolean =
    status == Status.RUNNING || apply?.status == Status.RUNNING || optimize?.status == Status.RUNNING
