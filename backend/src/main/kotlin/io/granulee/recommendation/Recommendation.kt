package io.granulee.recommendation

import io.granulee.sql.TrialIndex

data class QueryEvidence(val query: String, val count: Long, val share: Double)

data class Recommendation(
    val id: String,
    val severity: String, // high | medium | low | review
    val category: String, // Partitioning | Sorting key | Codec | Data type | Skip index
    val changeKind: String, // online | mutation | rebuild
    val title: String,
    val body: String,
    val effect: String,
    val sql: String,
    val savingsBytes: Long,
    val column: String?,
    val queries: List<QueryEvidence> = emptyList(),
)

data class ColumnChange(val name: String, val type: String?, val codec: String?)

/** What a recommendation changes in a trial's candidate when it is ticked there. Exactly one of the changes is set. */
data class TrialPlanRecommendation(
    val id: String,
    val label: String,
    val partitionKey: String? = null,
    val sortingKey: String? = null,
    val column: ColumnChange? = null,
    val dropIndex: String? = null,
    val addIndex: TrialIndex? = null,
)

/** What a rule found: the recommendation as a person reads it, and how a trial would try it out, where one can. */
data class Finding(val rec: Recommendation, val plan: TrialPlanRecommendation?)
