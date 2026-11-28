package io.granulee.scan

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

data class HistoryPoint(val day: String = "", val sourceId: String = "", val compressedBytes: Long = 0)

/** Where the size history is kept: at most one point per source and day. */
interface HistoryStorage {
    fun all(): List<HistoryPoint>

    /** Stores the point in place of the source's earlier one of the same day. */
    fun put(point: HistoryPoint)

    fun removeBefore(day: String)

    fun forget(sourceId: String)
}

/** How large each source was on the days it was scanned: one point per source and day, kept for 120 days. */
@Component
class SizeHistory(private val storage: HistoryStorage) {
    private var points: List<HistoryPoint> = storage.all().sortedBy { it.day }

    @Synchronized
    fun all(): List<HistoryPoint> = points

    @Synchronized
    fun record(sourceId: String, compressedBytes: Long, today: LocalDate = LocalDate.now(ZoneId.systemDefault())) {
        val point = HistoryPoint(today.toString(), sourceId, compressedBytes)
        storage.put(point)
        storage.removeBefore(cutoff(today))
        points = withPoint(points, point, today)
    }

    @Synchronized
    fun forget(sourceId: String) {
        storage.forget(sourceId)
        points = points.filter { it.sourceId != sourceId }
    }

    companion object {
        private const val KEPT_DAYS = 120L

        private fun cutoff(today: LocalDate) = today.minusDays(KEPT_DAYS).toString()

        /** The history with this point in it: it replaces the source's earlier point of the same day, and old days fall out. */
        internal fun withPoint(points: List<HistoryPoint>, point: HistoryPoint, today: LocalDate): List<HistoryPoint> {
            val cutoff = cutoff(today)
            return (points.filter { !(it.day == point.day && it.sourceId == point.sourceId) && it.day >= cutoff } + point).sortedBy { it.day }
        }
    }
}
