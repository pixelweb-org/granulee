package io.granulee.source

import io.granulee.recommendation.DismissedStore
import io.granulee.scan.Scanner
import io.granulee.scan.SizeHistory
import io.granulee.scan.SnapshotStore
import io.granulee.user.UserService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * Scans the sources: when asked, when a source is added, and on each source's schedule. One scan per source at a time.
 * A scan is done for the source's owner and needs nobody signed in; whether a request may start one is the caller's check.
 */
@Component
class SourceScanService(
    private val sources: SourceService,
    private val scanner: Scanner,
    private val snapshots: SnapshotStore,
    private val history: SizeHistory,
    private val dismissed: DismissedStore,
    private val users: UserService,
) {
    private val log = LoggerFactory.getLogger(SourceScanService::class.java)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "granulee-scan").apply { isDaemon = true } }
    private val queued = ConcurrentHashMap.newKeySet<String>()
    private var ticks = 0

    fun scanInBackground(sourceId: String) {
        if (!queued.add(sourceId)) return
        executor.execute {
            try {
                scan(sourceId)
            } catch (e: Exception) {
                log.warn("Background scan of {} failed: {}", sourceId, e.message)
            } finally {
                queued.remove(sourceId)
            }
        }
    }

    /** Scans one source now. A failed scan is recorded on the source; the previous snapshot stays. */
    fun scan(sourceId: String): SourceRecord {
        val lock = locks.computeIfAbsent(sourceId) { ReentrantLock() }
        lock.lock()
        try {
            val source = sources.get(sourceId)
            val started = Instant.now()
            val scanned = try {
                val snapshot = scanner.scan(sources.connection(source), source.scanTarget(), started)
                snapshots.save(snapshot)
                history.record(sourceId, snapshot.tables.sumOf { it.compressedBytes })
                Result.success(snapshot)
            } catch (e: Exception) {
                log.warn("Scan of {} failed: {}", source.name, e.message)
                Result.failure(e)
            }
            sources.checkHealth(sourceId)
            val lastScan = scanned.fold(
                onSuccess = { LastScan(started.toString(), it.durationMs, null) },
                onFailure = { LastScan(started.toString(), Duration.between(started, Instant.now()).toMillis(), it.message ?: "Scan failed") },
            )
            return checkNotNull(sources.patch(sourceId) { it.copy(lastScan = lastScan) }) { "Data source $sourceId was removed while it was scanned" }
        } finally {
            lock.unlock()
        }
    }

    /** Drops what scans left of a source that was removed. */
    fun forget(sourceId: String) {
        snapshots.forget(sourceId)
        history.forget(sourceId)
        dismissed.forget(sourceId)
    }

    @Scheduled(initialDelay = 5_000, fixedDelay = 60_000)
    fun tick() {
        val now = Instant.now()
        val healthRound = ticks++ % 5 == 0
        // Nothing runs by itself for an account that was deactivated: its sources wait until it is active again.
        val active = users.activeIds()
        sources.everyones().filter { it.ownerId in active }.forEach { source ->
            // A source that was never scanned is left alone here: its first scan starts when it is added.
            val due = source.lastScan?.let { source.nextScanAt(now) }
            if (due != null && !due.isAfter(now)) scanInBackground(source.id)
            else if (healthRound) runCatching { sources.checkHealth(source.id) }
        }
    }
}
