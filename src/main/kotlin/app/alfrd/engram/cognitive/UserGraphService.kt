package app.alfrd.engram.cognitive

import app.alfrd.engram.api.OnboardingService
import com.arcadedb.database.Database
import java.util.UUID
import java.util.logging.Logger

/**
 * Read/write graph operations needed by the first-session identity verification flow.
 *
 * All methods run synchronously — callers must dispatch to [kotlinx.coroutines.Dispatchers.IO].
 */
open class UserGraphService(private val db: Database?) {

    private val logger = Logger.getLogger(UserGraphService::class.java.name)

    companion object {
        val JACOB_EMAIL: String = OnboardingService.JACOB_EMAIL
        /** Tier assigned to users who signed up directly via Supabase (no INVITED edge). */
        const val SELF_SIGNUP_TIER = 0
    }

    data class UserRecord(val uid: String, val email: String, val username: String)

    data class InvitedEdgeRecord(
        val relationshipContext: String,
        val trustPhase: String,
        val engagementIntent: String,
        val timestamp: Long,
        val openingContext: String? = null,
    )

    /**
     * Looks up a User vertex by [email]. Returns null if not found.
     */
    open fun findUserByEmail(email: String): UserRecord? {
        return try {
            db!!.query(
                "sql",
                "SELECT uid, email, username FROM User WHERE email = :email",
                mapOf("email" to email),
            ).use { rs ->
                if (rs.hasNext()) {
                    val row = rs.next().toMap()
                    UserRecord(
                        uid      = row["uid"] as? String ?: return null,
                        email    = row["email"] as? String ?: email,
                        username = row["username"] as? String ?: "",
                    )
                } else null
            }
        } catch (e: Exception) {
            logger.warning("findUserByEmail failed for email=$email: ${e.message}")
            null
        }
    }

    /**
     * Returns the existing User vertex for [email], or creates one with [SELF_SIGNUP_TIER]
     * if none exists. Used to bootstrap graph presence for Supabase-direct signups before
     * phrase ingestion runs.
     */
    open fun findOrCreateUser(email: String): UserRecord? {
        return try {
            db!!.let { database ->
                var result: UserRecord? = null
                database.transaction {
                    val existing = database.query(
                        "sql",
                        "SELECT FROM User WHERE email = :email",
                        mapOf("email" to email),
                    ).use { rs ->
                        if (rs.hasNext()) {
                            val v = rs.next().toElement().asVertex()
                            UserRecord(
                                uid      = v.get("uid") as? String ?: return@transaction,
                                email    = v.get("email") as? String ?: email,
                                username = v.get("username") as? String ?: "",
                            )
                        } else null
                    }
                    if (existing != null) {
                        result = existing
                        return@transaction
                    }
                    val now = System.currentTimeMillis()
                    val uid = UUID.randomUUID().toString()
                    database.newVertex("User").apply {
                        set("uid", uid)
                        set("username", email.substringBefore("@"))
                        set("email", email)
                        set("tier", SELF_SIGNUP_TIER)
                        set("createdAt", now)
                        set("updatedAt", now)
                        save()
                    }
                    result = UserRecord(uid = uid, email = email, username = email.substringBefore("@"))
                }
                result
            }
        } catch (e: Exception) {
            logger.warning("findOrCreateUser failed for email=$email: ${e.message}")
            null
        }
    }

    /**
     * Returns the most recent INVITED edge from Jacob → [userId].
     * Per spec: if multiple exist, use the most recent by timestamp.
     * Returns null if no such edge exists.
     */
    open fun findInvitedEdgeFromJacob(userId: String): InvitedEdgeRecord? {
        return try {
            db!!.query(
                "sql",
                """SELECT e.relationshipContext, e.trustPhase, e.engagementIntent, e.timestamp, e.openingContext
                   FROM INVITED e
                   WHERE e.@out.email = :jacobEmail AND e.@in.uid = :userId
                   ORDER BY e.timestamp DESC
                   LIMIT 1""",
                mapOf("jacobEmail" to JACOB_EMAIL, "userId" to userId),
            ).use { rs ->
                if (rs.hasNext()) {
                    val el = rs.next().toElement()
                    InvitedEdgeRecord(
                        relationshipContext = el.get("relationshipContext") as? String ?: "",
                        trustPhase          = el.get("trustPhase") as? String ?: "Acquaintance",
                        engagementIntent    = el.get("engagementIntent") as? String ?: "",
                        timestamp           = el.get("timestamp") as? Long ?: 0L,
                        openingContext      = el.get("openingContext") as? String,
                    )
                } else null
            }
        } catch (e: Exception) {
            logger.warning("findInvitedEdgeFromJacob failed for userId=$userId: ${e.message}")
            null
        }
    }

    /**
     * Returns true if [userId] has any outbound SELECTED edges — i.e. prior phrase interactions.
     */
    open fun hasSelectedEdges(userId: String): Boolean {
        return try {
            db!!.query(
                "sql",
                "SELECT count(*) as cnt FROM SELECTED WHERE userId = :userId LIMIT 1",
                mapOf("userId" to userId),
            ).use { rs ->
                if (rs.hasNext()) {
                    val count = rs.next().toMap()["cnt"] as? Number
                    count != null && count.toLong() > 0
                } else false
            }
        } catch (e: Exception) {
            logger.warning("hasSelectedEdges failed for userId=$userId: ${e.message}")
            false
        }
    }

    /**
     * Returns true if [userEmail] has an outbound VERIFIED edge to Jacob — i.e. identity
     * verification was completed in a prior session.
     */
    open fun hasVerifiedEdge(userEmail: String): Boolean {
        return try {
            db!!.query(
                "sql",
                "SELECT count(*) as cnt FROM VERIFIED WHERE @out.email = :userEmail LIMIT 1",
                mapOf("userEmail" to userEmail),
            ).use { rs ->
                if (rs.hasNext()) {
                    val count = rs.next().toMap()["cnt"] as? Number
                    count != null && count.toLong() > 0
                } else false
            }
        } catch (e: Exception) {
            logger.warning("hasVerifiedEdge failed for userEmail=$userEmail: ${e.message}")
            false
        }
    }

    /**
     * Writes a VERIFIED edge from the invited [userId]'s User vertex to Jacob's User vertex.
     * Records the [timestamp] of successful identity verification for audit.
     */
    open fun writeVerifiedEdge(userId: String, timestamp: Long) {
        try {
            db!!.transaction {
                val jacobVertex = db!!.query(
                    "sql",
                    "SELECT FROM User WHERE email = :email",
                    mapOf("email" to JACOB_EMAIL),
                ).use { rs ->
                    if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
                } ?: run {
                    logger.warning("writeVerifiedEdge: Jacob's User vertex not found")
                    return@transaction
                }

                val userVertex = db!!.query(
                    "sql",
                    "SELECT FROM User WHERE email = :email",
                    mapOf("email" to userId),
                ).use { rs ->
                    if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
                } ?: run {
                    logger.warning("writeVerifiedEdge: User vertex not found for email=$userId")
                    return@transaction
                }

                userVertex.newEdge("VERIFIED", jacobVertex, false).apply {
                    set("timestamp", timestamp)
                    save()
                }
            }
        } catch (e: Exception) {
            logger.warning("writeVerifiedEdge failed for userId=$userId: ${e.message}")
        }
    }
}
