package io.granulee.trial

import io.granulee.clickhouse.ChConnection
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceService
import io.granulee.sql.Sql
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs trials from start to finish and settles what each one ends as: completed with a result, failed with
 * its error, or cancelled with its scratch tables dropped.
 */
@Component
class TrialRunner(
    private val sources: SourceService,
    private val snapshots: SnapshotStore,
    private val trials: TrialRepository,
) {
    private val log = LoggerFactory.getLogger(TrialRunner::class.java)
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val runningQuery = ConcurrentHashMap<String, String>()

    /** Stops a running trial before its next statement, and kills the statement it is in. */
    fun cancel(trial: Trial) {
        cancelled.add(trial.id)
        runningQuery[trial.id]?.let { queryId ->
            runCatching {
                sources.connection(sources.get(trial.sourceId)).exec("KILL QUERY WHERE query_id = ${Sql.lit(queryId)}")
            }
        }
    }

    fun run(trial: Trial) {
        val id = trial.id
        val journal = TrialJournal(TrialPhase.of(trial.request)) { change -> trials.update(id, change) }
        var ch: ChConnection? = null
        try {
            val connection = sources.connection(sources.get(trial.sourceId))
            ch = connection
            val statements = TrialStatements(
                trialId = id,
                ch = connection,
                cancelled = { id in cancelled },
                running = { queryId -> if (queryId == null) runningQuery.remove(id) else runningQuery[id] = queryId },
            )
            val result = TrialRun(trial, connection, statements, journal) { snapshots.of(trial.sourceId)?.table(trial.database, trial.table) }.execute()
            journal.update {
                it.copy(
                    status = Status.COMPLETED,
                    progress = 100,
                    finishedAt = Instant.now().toString(),
                    steps = it.steps.map { s -> s.copy(state = Status.DONE) },
                    result = result,
                )
            }
        } catch (e: TrialCancelled) {
            runCatching { ch?.let { TrialTables.drop(it, ScratchTables(trial)) } }
            journal.update {
                it.copy(
                    status = Status.CANCELLED,
                    finishedAt = Instant.now().toString(),
                    scratchBytes = 0,
                    steps = it.steps.failRunning(),
                    log = it.log + LogLine(Instant.now().toString(), "Cancelled; scratch tables dropped"),
                )
            }
        } catch (e: Exception) {
            log.warn("Trial {} failed: {}", id, e.message)
            val used = runCatching {
                ch?.let { TrialTables.sizes(it, trial.scratchDatabase, trial.baseTable).onDisk + TrialTables.sizes(it, trial.scratchDatabase, trial.candTable).onDisk }
            }.getOrNull() ?: 0
            journal.update {
                it.copy(
                    status = Status.FAILED,
                    error = e.message ?: e.javaClass.simpleName,
                    finishedAt = Instant.now().toString(),
                    scratchBytes = used,
                    steps = it.steps.failRunning(),
                )
            }
        } finally {
            cancelled.remove(id)
        }
    }

    private fun List<TrialStep>.failRunning() = map { s -> if (s.state == Status.RUNNING) s.copy(state = Status.FAILED) else s }
}
