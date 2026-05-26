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
)

@Serializable
data class RelatedToEdge(
    val relationType: String,
    val strength: Double
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
