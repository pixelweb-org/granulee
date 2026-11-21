package io.granulee.scan

import io.granulee.state.JsonStore
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneId

data class HistoryPoint(val day: String = "", val sourceId: String = "", val compressedBytes: Long = 0)
data class HistoryFile(val points: List<HistoryPoint> = emptyList())

/** How large each source was on the days it was scanned: one point per source and day, kept for 120 days. */
@Component
class SizeHistory(private val store: JsonStore) {
    private var points: List<HistoryPoint> = store.read(FILE, HistoryFile::class.java)?.points ?: emptyList()

    @Synchronized
    fun all(): List<HistoryPoint> = points

    @Synchronized
    fun record(sourceId: String, compressedBytes: Long, today: LocalDate = LocalDate.now(ZoneId.systemDefault())) {
        save(withPoint(points, HistoryPoint(today.toString(), sourceId, compressedBytes), today))
    }

    @Synchronized
    fun forget(sourceId: String) {
        save(points.filter { it.sourceId != sourceId })
    }

    private fun save(next: List<HistoryPoint>) {
        points = next
        store.write(FILE, HistoryFile(next))
    }

    companion object {
        private const val FILE = "history.json"
        private const val KEPT_DAYS = 120L

        /** The history with this point in it: it replaces the source's earlier point of the same day, and old days fall out. */
        internal fun withPoint(points: List<HistoryPoint>, point: HistoryPoint, today: LocalDate): List<HistoryPoint> {
            val cutoff = today.minusDays(KEPT_DAYS).toString()
            return (points.filter { !(it.day == point.day && it.sourceId == point.sourceId) && it.day >= cutoff } + point).sortedBy { it.day }
        }
    }
}
