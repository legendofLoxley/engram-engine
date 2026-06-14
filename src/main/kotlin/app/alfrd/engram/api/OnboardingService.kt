package app.alfrd.engram.api

import com.arcadedb.database.Database
import com.arcadedb.graph.MutableVertex
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.UUID

// ── Request shapes ────────────────────────────────────────────────────────────

@Serializable
data class PhraseInput(
    val text: String,
    val salience: Double? = null,
)

@Serializable
data class InviteeManifest(
    val name: String,
    val email: String,
    val relationshipContext: String,
    val trustPhase: String,
    val engagementIntent: String,
    val openingContext: String? = null,
    val personalPhrases: List<PhraseInput> = emptyList(),
    val globalPhrases: List<PhraseInput> = emptyList(),
)

// ── Response shapes ───────────────────────────────────────────────────────────

@Serializable
data class InviteeResult(
    val name: String,
    val email: String,
    val success: Boolean,
    val userUid: String? = null,
    val personalSourceUid: String? = null,
    val globalSourceUid: String? = null,
    val personalPhraseCount: Int = 0,
    val globalPhrasesCreated: Int = 0,
    val error: String? = null,
)

// ── Service ───────────────────────────────────────────────────────────────────

/**
 * Handles all graph wiring for the POST /onboard/seed endpoint.
 *
 * Each invitee is processed in a single atomic transaction:
 *   User vertex → INVITED edge from Jacob → personal Source → TRUSTS → personal Phrases
 *   → global Source (idempotent) → TRUSTS → global Phrases (deduplicated by hash)
 *
 * TODO: add auth gating before this service is exposed beyond closed beta.
 */
class OnboardingService(private val db: Database) {

    companion object {
        const val JACOB_EMAIL = "jmac@primarykey.consulting"
        const val GLOBAL_SOURCE_TYPE = "pre_onboarding_global"
        const val PERSONAL_SOURCE_TYPE = "pre_onboarding"

        /** Moderate source weight: pre-onboarding, not user-authored. */
        private const val TRUST_WEIGHT_MODERATE = 0.5
    }

    fun seedBatch(manifests: List<InviteeManifest>): List<InviteeResult> =
        manifests.map { seedInvitee(it) }

    private fun seedInvitee(manifest: InviteeManifest): InviteeResult {
        return try {
            var userUid = ""
            var personalSourceUid = ""
            var globalSourceUid = ""
            var personalPhraseCount = 0
            var globalPhrasesCreated = 0

            db.transaction {
                val now = System.currentTimeMillis()

                // ── 1. Look up Jacob's User vertex ────────────────────────
                val jacobVertex = db.query(
                    "sql",
                    "SELECT FROM User WHERE email = :email",
                    mapOf("email" to JACOB_EMAIL),
                ).use { rs ->
                    if (rs.hasNext()) rs.next().toElement().asVertex().modify() else null
                } ?: throw IllegalStateException(
                    "Jacob's User vertex not found (email=$JACOB_EMAIL). " +
                        "Ensure the seed user exists before calling /onboard/seed."
                )

                // ── 2. Create new User vertex ─────────────────────────────
                val uid = UUID.randomUUID().toString()
                userUid = uid
                val userVertex = db.newVertex("User").apply {
                    set("uid", uid)
                    set("username", manifest.name)
                    set("email", manifest.email)
                    set("tier", 1)
                    set("createdAt", now)
                    set("updatedAt", now)
                    save()
                }

                // ── 3. INVITED edge: Jacob → new User ─────────────────────
                jacobVertex.newEdge("INVITED", userVertex, false).apply {
                    set("relationshipContext", manifest.relationshipContext)
                    set("trustPhase", manifest.trustPhase)
                    set("engagementIntent", manifest.engagementIntent)
                    manifest.openingContext?.let { set("openingContext", it) }
                    set("tier", 1)
                    set("resultingTier", 1)
                    set("timestamp", now)
                    save()
                }

                // ── 4. Personal Source vertex ─────────────────────────────
                val personalSourceId = UUID.randomUUID().toString()
                personalSourceUid = personalSourceId
                val personalSourceMeta = buildJsonObject {
                    put("inviteeEmail", manifest.email)
                    put("originatorEmail", JACOB_EMAIL)
                    put("createdAt", now)
                }.toString()
                val personalSource = db.newVertex("Source").apply {
                    set("uid", personalSourceId)
                    set("name", "$PERSONAL_SOURCE_TYPE:${manifest.email}")
                    set("type", PERSONAL_SOURCE_TYPE)
                    set("metadata", personalSourceMeta)
                    save()
                }

                // ── 5. TRUSTS edge: new User → personal Source ────────────
                userVertex.newEdge("TRUSTS", personalSource, false).apply {
                    set("scores", "[]")
                    save()
                }

                // ── 6. Personal Phrase vertices + ASSERTS + FOLLOWS chain ─
                val personalPhraseVertices = manifest.personalPhrases.map { phraseInput ->
                    val phraseUid = UUID.randomUUID().toString()
                    val pv = db.newVertex("Phrase").apply {
                        set("uid", phraseUid)
                        set("text", phraseInput.text)
                        set("hash", textHash(phraseInput.text))
                        set("visibility", "private")
                        set("createdAt", now)
                        set("updatedAt", now)
                        save()
                    }
                    personalSource.newEdge("ASSERTS", pv, false).apply {
                        set("context", PERSONAL_SOURCE_TYPE)
                        set("timestamp", now)
                        set("scores", buildScores(phraseInput.salience))
                        save()
                    }
                    pv
                }
                buildFollowsChain(personalPhraseVertices)
                personalPhraseCount = personalPhraseVertices.size

                // ── 7. Global Source vertex (idempotent) ──────────────────
                val globalSource = getOrCreateGlobalSource(now)
                globalSourceUid = globalSource.get("uid") as? String ?: ""

                // ── 8. TRUSTS edge: new User → global Source ──────────────
                userVertex.newEdge("TRUSTS", globalSource, false).apply {
                    set("scores", "[]")
                    save()
                }

                // ── 9. Global Phrase vertices (deduplicated by hash) ───────
                val newGlobalPhraseVertices = mutableListOf<MutableVertex>()
                for (phraseInput in manifest.globalPhrases) {
                    val hash = textHash(phraseInput.text)
                    val alreadyExists = db.query(
                        "sql",
                        "SELECT FROM Phrase WHERE hash = :hash",
                        mapOf("hash" to hash),
                    ).use { rs -> rs.hasNext() }

                    if (!alreadyExists) {
                        val phraseUid = UUID.randomUUID().toString()
                        val pv = db.newVertex("Phrase").apply {
                            set("uid", phraseUid)
                            set("text", phraseInput.text)
                            set("hash", hash)
                            set("visibility", "public")
                            set("createdAt", now)
                            set("updatedAt", now)
                            save()
                        }
                        globalSource.newEdge("ASSERTS", pv, false).apply {
                            set("context", GLOBAL_SOURCE_TYPE)
                            set("timestamp", now)
                            set("scores", buildScores(phraseInput.salience))
                            save()
                        }
                        newGlobalPhraseVertices.add(pv)
                        globalPhrasesCreated++
                    }
                }
                buildFollowsChain(newGlobalPhraseVertices)
            }

            InviteeResult(
                name = manifest.name,
                email = manifest.email,
                success = true,
                userUid = userUid,
                personalSourceUid = personalSourceUid,
                globalSourceUid = globalSourceUid,
                personalPhraseCount = personalPhraseCount,
                globalPhrasesCreated = globalPhrasesCreated,
            )
        } catch (e: Exception) {
            InviteeResult(
                name = manifest.name,
                email = manifest.email,
                success = false,
                error = e.message,
            )
        }
    }

    /**
     * Returns the shared global Source vertex, creating it if it does not exist.
     * Safe to call within an open transaction — the query will see previously committed data.
     */
    private fun getOrCreateGlobalSource(now: Long): MutableVertex {
        val existing = db.query(
            "sql",
            "SELECT FROM Source WHERE type = :type",
            mapOf("type" to GLOBAL_SOURCE_TYPE),
        ).use { rs ->
            if (rs.hasNext()) rs.next().toElement().asVertex() else null
        }
        if (existing != null) return existing.modify()

        val uid = UUID.randomUUID().toString()
        return db.newVertex("Source").apply {
            set("uid", uid)
            set("name", GLOBAL_SOURCE_TYPE)
            set("type", GLOBAL_SOURCE_TYPE)
            set("metadata", buildJsonObject { put("createdAt", now) }.toString())
            save()
        }
    }

    /** Creates FOLLOWS edges connecting [vertices] in order: [0]→[1]→[2]… */
    private fun buildFollowsChain(vertices: List<MutableVertex>) {
        for (i in 0 until vertices.size - 1) {
            vertices[i].newEdge("FOLLOWS", vertices[i + 1], false).apply {
                set("attributions", "[]")
                set("scores", "[]")
                save()
            }
        }
    }

    /** Score array for an ASSERTS edge. Always includes a moderate trust score; adds salience if provided. */
    private fun buildScores(salience: Double?): String {
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "trust")
                put("perspective", "system")
                put("value", TRUST_WEIGHT_MODERATE)
            })
            if (salience != null) {
                add(buildJsonObject {
                    put("type", "salience")
                    put("perspective", "author")
                    put("value", salience)
                })
            }
        }.toString()
    }

    private fun textHash(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(text.trim().lowercase().toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
