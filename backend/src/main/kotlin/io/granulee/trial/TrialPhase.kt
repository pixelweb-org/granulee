package io.granulee.trial

import io.granulee.Fmt
import java.time.Instant

/** The steps of a run in the order they happen. Which of them a trial has depends on what its request asks for. */
internal enum class TrialPhase(val percent: Int) {
    CREATE_TABLES(2),
    COPY_BASELINE(6),
    COPY_CANDIDATE(38),
    COPY_PLAIN(46),
    MEASURE_SIZES(60),
    SCAN_COLUMNS(70),
    REPLAY_QUERIES(85),
    COMPARE_CODECS(97),
    ;

    fun isPartOf(request: TrialRequest): Boolean = when (this) {
        COPY_PLAIN -> request.plainDefaults
        SCAN_COLUMNS -> request.columnScans
        REPLAY_QUERIES -> request.replayQueries
        COMPARE_CODECS -> request.codecBakeoff
        else -> true
    }

    fun label(request: TrialRequest): String = when (this) {
        CREATE_TABLES -> "Create baseline and candidate tables"
        COPY_BASELINE -> if (request.sampling == "all") "Copy the whole table to baseline" else "Copy ${Fmt.rows(request.rows)} rows to baseline"
        COPY_CANDIDATE -> "Copy the same rows to candidate"
        COPY_PLAIN -> "Copy the same rows to a table with plain defaults"
        MEASURE_SIZES -> if (request.optimizeFinal) "Full merge, measure sizes" else "Measure sizes"
        SCAN_COLUMNS -> "Scan every column"
        REPLAY_QUERIES -> "Replay queries"
        COMPARE_CODECS -> "Compare codecs for the largest columns"
    }

    companion object {
        /** The steps a trial with this request goes through; a trial's `steps` are these, in this order. */
        fun of(request: TrialRequest): List<TrialPhase> = entries.filter { it.isPartOf(request) }
    }
}

/** What a run tells about itself while it goes: log lines, progress, the step it is in. */
internal class TrialJournal(private val phases: List<TrialPhase>, private val write: ((Trial) -> Trial) -> Unit) {
    fun say(message: String) = write { it.copy(log = it.log + LogLine(Instant.now().toString(), message)) }

    fun progress(value: Int) = write { it.copy(progress = value.coerceIn(0, 100)) }

    /** The steps before this one are done, the ones after it still to come. */
    fun enter(phase: TrialPhase) {
        val step = phases.indexOf(phase)
        write {
            it.copy(
                progress = phase.percent,
                steps = it.steps.mapIndexed { i, s -> s.copy(state = if (i < step) Status.DONE else if (i == step) Status.RUNNING else Status.PENDING) },
            )
        }
    }

    fun update(change: (Trial) -> Trial) = write(change)
}
