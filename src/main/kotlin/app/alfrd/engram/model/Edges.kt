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
    val timestamp: Long
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
    val resultingTier: Int
)

@Serializable
data class QuotesEdge(
    val attributions: String,
    val scores: String
)
