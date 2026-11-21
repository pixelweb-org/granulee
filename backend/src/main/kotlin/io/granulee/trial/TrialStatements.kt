package io.granulee.trial

import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.ChException
import io.granulee.clickhouse.ExecStats

/** A trial was cancelled while it ran; whatever it was doing stops here. */
internal class TrialCancelled : RuntimeException("Cancelled")

/**
 * Runs the statements of one trial. Each gets a query id of the trial's, so it can be killed and found in
 * query_log, and none starts once the trial is cancelled.
 */
internal class TrialStatements(
    private val trialId: String,
    private val ch: ChConnection,
    private val cancelled: () -> Boolean,
    /** Told the id of the statement that runs right now, and null when it has ended. */
    private val running: (String?) -> Unit,
) {
    private var count = 0

    /** Settings every statement of the run carries; set once the production table is known. */
    var limits: Map<String, String> = emptyMap()

    fun exec(sql: String, settings: Map<String, String> = emptyMap(), database: String? = null): ExecStats {
        if (cancelled()) throw TrialCancelled()
        val queryId = "granulee-$trialId-${++count}"
        running(queryId)
        try {
            return ch.exec(sql, limits + settings, queryId, database = database)
        } catch (e: ChException) {
            if (cancelled()) throw TrialCancelled()
            throw e
        } finally {
            running(null)
        }
    }
}
