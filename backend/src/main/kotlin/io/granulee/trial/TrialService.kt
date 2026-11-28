package io.granulee.trial

import io.granulee.BadRequestException
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceService
import io.granulee.sql.normalizeKey
import io.granulee.user.CurrentUser
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Trials: a baseline and a candidate table in the scratch database, filled with the same sample of
 * production rows, merged, measured and queried side by side. This is their life from the plan a person
 * starts from to the moment the trial and its tables are removed; the run itself is the TrialRunner's.
 */
@Component
class TrialService(
    private val sources: SourceService,
    private val snapshots: SnapshotStore,
    private val trials: TrialRepository,
    private val runner: TrialRunner,
    private val workers: TrialWorkers,
) {
    init {
        // Nothing survives a restart: what was running when Granulee stopped is not running now.
        if (trials.all().any { it.hasWorkRunning() }) {
            val now = Instant.now().toString()
            trials.updateAll { it.afterRestart(now) }
        }
    }

    fun find(id: String): Trial = trials.owned(id)

    fun latestCompleted(sourceId: String, database: String, table: String): Trial? = trials.mine()
        .filter { it.sourceId == sourceId && it.database == database && it.table == table && it.status == Status.COMPLETED }
        .maxByOrNull { it.createdAt }

    fun list(sourceId: String?): TrialList {
        val listed = trials.mine().filter { sourceId.isNullOrBlank() || it.sourceId == sourceId }.sortedByDescending { it.createdAt }
        val source = if (sourceId.isNullOrBlank()) null else sources.find(sourceId)
        val free = source?.let { runCatching { TrialTables.freeBytes(sources.connection(it)) }.getOrNull() }
        return TrialList(listed, listed.sumOf { it.scratchBytes }, free)
    }

    fun plan(sourceId: String, database: String, table: String): TrialPlan {
        val source = sources.find(sourceId)
        val facts = snapshots.requireTable(sourceId, source.name, database, table)
        return trialPlan(source, facts, runCatching { TrialTables.freeBytes(sources.connection(source)) }.getOrNull())
    }

    fun start(request: TrialRequest): Trial {
        val source = sources.find(request.sourceId)
        if (!source.trialsEnabled) throw BadRequestException("Trials are switched off for ${source.name}. Allow them under Data sources.")
        request.problem()?.let { throw BadRequestException(it) }

        val id = trials.newId()
        val trial = Trial(
            id = id,
            ownerId = CurrentUser.id(),
            name = request.name.trim().ifEmpty { "Trial of ${request.table}" },
            sourceId = source.id,
            sourceName = source.name,
            database = request.database,
            table = request.table,
            status = Status.RUNNING,
            error = null,
            createdAt = Instant.now().toString(),
            finishedAt = null,
            progress = 0,
            steps = TrialPhase.of(request).map { TrialStep(it.label(request), Status.PENDING) },
            request = request.copy(sortingKey = normalizeKey(request.sortingKey), partitionKey = request.partitionKey.trim()),
            scratchDatabase = source.scratchDatabase,
            baseTable = "${request.table}_trial_${id}_base",
            candTable = "${request.table}_trial_${id}_cand",
            scratchBytes = 0,
            changedKeys = 0,
            changedColumns = 0,
            result = null,
            log = emptyList(),
        )
        trials.add(trial)
        workers.execute { runner.run(trial) }
        return trial
    }

    fun cancel(id: String): Trial {
        val trial = find(id)
        if (trial.status != Status.RUNNING) return trial
        runner.cancel(trial)
        return find(id)
    }

    /** Removes a trial that has come to rest, and drops whatever scratch tables it left. */
    fun delete(id: String) {
        val trial = find(id)
        if (trial.status == Status.RUNNING) throw BadRequestException("Trial #$id is still running. Cancel it first.")
        if (trial.apply?.status == Status.RUNNING) throw BadRequestException("Trial #$id is being applied to production. Wait for it to finish.")
        if (trial.optimize?.status == Status.RUNNING) throw BadRequestException("OPTIMIZE FINAL started from trial #$id is still running. Wait for it to finish.")
        // The tables of a trial whose source is gone cannot be reached any more.
        val source = sources.all().firstOrNull { it.id == trial.sourceId }
        if (source != null) TrialTables.drop(sources.connection(source), ScratchTables(trial))
        trials.remove(id)
    }
}
