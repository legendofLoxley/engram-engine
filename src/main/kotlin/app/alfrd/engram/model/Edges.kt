package app.alfrd.engram.model

import kotlinx.serialization.Serializable

@Serializable
data class FollowsEdge(
    val attributions: String,
    val scores: String
)

@Serializable
data class ContainsEdge(
    val position: Int,
    val salience: Double
)

@Serializable
data class AssertsEdge(
    val context: String,
    val timestamp: Long,
    val scores: String = "[]",
    // Context Horizon state (app.alfrd.engram.cognitive.pipeline.horizon) — deliberately separate
    // from `scores`, not a numeric score entry; see SchemaBootstrap for why.
    val status: String? = null,        // "open" | "resolved"; absent = not intention-shaped
    val statusHistory: String = "[]",  // JSON array of {"state","at"}, append-only
    val cycleSeq: Long? = null,        // ORIGINAL assertion cycle — set once, never overwritten by a status change
    val statusCycleSeq: Long? = null,  // cycle of the MOST RECENT status change — distinct from cycleSeq
)

@Serializable
data class RelatedToEdge(
    val relationType: String,
    val strength: Double,
    // Context Horizon state — see HorizonAssembler for the cycleSeq-based reactivation lifecycle
    // this backs. createdAt is an audit timestamp only, never used for identity/decay comparisons.
    val createdAt: Long? = null,
    val cycleSeq: Long? = null,
)

@Serializable
data class TrustsEdge(
    val scores: String
)

@Serializable
data class InvitedEdge(
    val timestamp: Long,
    val resultingTier: Int,
    val relationshipContext: String = "",
    val trustPhase: String = "",
    val engagementIntent: String = "",
    val tier: Int = 1,
)

@Serializable
data class QuotesEdge(
    val attributions: String,
    val scores: String
)
