package consulting.primarykey.engram.model

import kotlinx.serialization.Serializable

@Serializable
data class Phrase(
    val uid: String,
    val text: String,
    val hash: String,
    val visibility: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class Concept(
    val uid: String,
    val name: String,
    val type: String,
    val normalizedName: String
)

@Serializable
data class Source(
    val uid: String,
    val name: String,
    val type: String,
    val metadata: String
)

@Serializable
data class User(
    val uid: String,
    val username: String,
    val tier: Int,
    val createdAt: Long
)

@Serializable
data class ScoreType(
    val uid: String,
    val name: String,
    val minValue: Double,
    val maxValue: Double,
    val aggregation: String,
    val description: String
)

@Serializable
data class Scope(
    val uid: String,
    val name: String,
    val parentScope: String? = null
)
