package io.granulee.explorer

import io.granulee.BadRequestException
import io.granulee.recommendation.Analyzer
import io.granulee.recommendation.DismissedStore
import io.granulee.recommendation.Recommendation
import io.granulee.scan.SnapshotStore
import io.granulee.source.SourceService
import io.granulee.trial.Trial
import io.granulee.trial.TrialService
import org.springframework.stereotype.Component

data class ChangeCounts(val online: Int, val mutation: Int, val rebuild: Int)

data class TableRecommendations(
    val sourceId: String,
    val sourceName: String,
    val database: String,
    val table: String,
    val compressedBytes: Long,
    val items: List<Recommendation>,
    val dismissed: Int,
    val savingsBytes: Long,
    val counts: ChangeCounts,
    val queryLogWindowDays: Int,
    val queryLogQueries: Long?,
    val latestTrial: Trial?,
)

data class DismissRequest(val id: String = "")

/** A table's recommendations as its page shows them, and what a person does with them there. */
@Component
class RecommendationService(
    private val sources: SourceService,
    private val snapshots: SnapshotStore,
    private val dismissed: DismissedStore,
    private val trials: TrialService,
) {
    fun of(sourceId: String, database: String, table: String): TableRecommendations {
        val source = sources.find(sourceId)
        val facts = snapshots.requireTable(sourceId, source.name, database, table)
        val hidden = dismissed.of(sourceId, database, table)
        val all = Analyzer.analyze(facts, source.queryLogWindowDays).map { it.rec }
        val items = all.filter { it.id !in hidden }
        return TableRecommendations(
            sourceId = sourceId,
            sourceName = source.name,
            database = database,
            table = table,
            compressedBytes = facts.compressedBytes,
            items = items,
            dismissed = all.size - items.size,
            savingsBytes = items.sumOf { it.savingsBytes }.coerceAtMost(facts.compressedBytes),
            counts = ChangeCounts(
                online = items.count { it.changeKind == "online" },
                mutation = items.count { it.changeKind == "mutation" },
                rebuild = items.count { it.changeKind == "rebuild" },
            ),
            queryLogWindowDays = source.queryLogWindowDays,
            queryLogQueries = facts.queryTotal,
            latestTrial = trials.latestCompleted(sourceId, database, table),
        )
    }

    fun dismiss(sourceId: String, database: String, table: String, recommendationId: String) {
        sources.find(sourceId)
        if (recommendationId.isBlank()) throw BadRequestException("Name the recommendation to dismiss")
        dismissed.add(sourceId, database, table, recommendationId)
    }

    /** Brings back everything that was dismissed for the table. */
    fun restore(sourceId: String, database: String, table: String) {
        sources.find(sourceId)
        dismissed.clear(sourceId, database, table)
    }
}
