package io.granulee.trial

import io.granulee.BadRequestException
import io.granulee.clickhouse.ChConnection
import io.granulee.clickhouse.long
import io.granulee.source.SourceService
import io.granulee.sql.Sql
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

/** What "Apply to production" would run right now, computed against the table as it is. */
data class ApplyPlan(
    val kind: String, // none | alter | rebuild
    val sql: String,
    val statements: Int,
    val sourceName: String,
)

data class ApplyRequest(val sql: String = "")

/**
 * What a finished trial may do to the production table: run the ALTERs that bring it to the candidate's schema,
 * and after that an OPTIMIZE … FINAL so existing parts are rewritten too. A rebuild is only ever shown.
 * Only the trial's owner gets this far; what was started then finishes without anybody signed in.
 */
@Component
class ProductionChanges(
    private val sources: SourceService,
    private val trials: TrialRepository,
    private val workers: TrialWorkers,
) {
    private val log = LoggerFactory.getLogger(ProductionChanges::class.java)

    /** The migration against the table as it is right now, which may no longer be the table the trial copied. */
    private fun currentMigration(trial: Trial): Migration {
        val live = TrialTables.live(sources.connection(sources.find(trial.sourceId)), trial.database, trial.table)
        return migration(trial.database, trial.table, live, trial.request)
    }

    private fun completed(id: String): Trial {
        val trial = trials.owned(id)
        if (trial.status != Status.COMPLETED) throw BadRequestException("Only a completed trial can be applied")
        return trial
    }

    fun plan(id: String): ApplyPlan {
        val trial = completed(id)
        val m = currentMigration(trial)
        return ApplyPlan(m.kind, m.sql, m.statements.size, trial.sourceName)
    }

    /** Runs the ALTER statements the user has just reviewed; refuses if the table has changed since. */
    fun apply(id: String, reviewed: String): Trial {
        val trial = completed(id)
        if (trial.apply?.status == Status.RUNNING) throw BadRequestException("Trial #$id is already being applied")
        val alter = when (val m = currentMigration(trial)) {
            Migration.None -> throw BadRequestException("${trial.database}.${trial.table} already matches the candidate")
            is Migration.Rebuild -> throw BadRequestException("This candidate needs a table rebuild, which Granulee does not run")
            is Migration.Alter -> m
        }
        if (alter.sql != reviewed) throw BadRequestException("${trial.database}.${trial.table} changed since you opened the review. Review the statements again.")
        // Looked up here, where the user is known: the statements run after the request is over.
        val source = sources.find(trial.sourceId)
        val started = trials.update(id) {
            it.copy(apply = TrialApply(Status.RUNNING, Instant.now().toString(), null, null, alter.sql, 0, alter.statements.size))
        }
        workers.execute {
            fun progress(change: (TrialApply) -> TrialApply) = trials.update(id) { it.copy(apply = it.apply?.let(change)) }
            try {
                val ch = sources.connection(source)
                alter.statements.forEachIndexed { i, statement ->
                    ch.exec(statement, queryId = "granulee-$id-apply-${i + 1}")
                    progress { it.copy(done = i + 1) }
                }
                progress { it.copy(status = Status.COMPLETED, finishedAt = Instant.now().toString()) }
                log.info("Applied trial {} to {}.{}", id, trial.database, trial.table)
            } catch (e: Exception) {
                log.warn("Applying trial {} failed: {}", id, e.message)
                progress { it.copy(status = Status.FAILED, finishedAt = Instant.now().toString(), error = e.message ?: e.javaClass.simpleName) }
            }
        }
        return started
    }

    /**
     * OPTIMIZE … FINAL of the production table, so parts written before the ALTERs get the new codecs too.
     * Only after the candidate has been applied.
     */
    fun optimize(id: String): Trial {
        val trial = trials.owned(id)
        if (trial.apply?.status != Status.COMPLETED) throw BadRequestException("Apply the trial to ${trial.database}.${trial.table} first")
        if (trial.optimize?.status == Status.RUNNING) throw BadRequestException("OPTIMIZE FINAL is already running for trial #$id")
        val source = sources.find(trial.sourceId)
        val sql = "OPTIMIZE TABLE ${Sql.table(trial.database, trial.table)} FINAL"
        val started = trials.update(id) { it.copy(optimize = TrialOptimize(Status.RUNNING, Instant.now().toString(), null, null, sql)) }
        val queryId = "granulee-$id-optimize-${System.currentTimeMillis()}"
        workers.execute {
            fun finish(error: String?) = trials.update(id) {
                it.copy(optimize = it.optimize?.copy(status = if (error == null) Status.COMPLETED else Status.FAILED, finishedAt = Instant.now().toString(), error = error))
            }
            val ch = sources.connection(source)
            try {
                ch.exec(sql, queryId = queryId)
                finish(null)
            } catch (e: Exception) {
                // A merge of a large table can outlast the HTTP request. The statement keeps running on the server
                // then, so follow it there instead of calling it failed.
                if (!stillRunning(ch, queryId)) {
                    log.warn("OPTIMIZE FINAL for trial {} failed: {}", id, e.message)
                    finish(e.message ?: e.javaClass.simpleName)
                } else {
                    log.info("OPTIMIZE FINAL for trial {} outlasted the request; following it in system.processes", id)
                    while (stillRunning(ch, queryId)) Thread.sleep(10_000)
                    finish(null)
                }
            }
        }
        return started
    }

    private fun stillRunning(ch: ChConnection, queryId: String): Boolean = runCatching {
        ch.first("SELECT count() AS c FROM system.processes WHERE query_id = ${Sql.lit(queryId)}")?.long("c") ?: 0
    }.getOrDefault(0) > 0
}
