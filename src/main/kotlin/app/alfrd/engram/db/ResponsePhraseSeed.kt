package app.alfrd.engram.db

import com.arcadedb.database.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.util.UUID

object ResponsePhraseSeed {

    private val json = Json { ignoreUnknownKeys = true }

    private val allPhases = listOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")
    private val allBranches = listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION", "META", "CLARIFICATION")

    private data class SeedPhrase(
        val text: String,
        val category: String,
        val expressionPhase: String,
        val branchAffinity: List<String>,
        val phaseAffinity: List<String>,
        val moveType: String? = null,
        val postureAffinityJson: String? = null,
        val requiresInterpolation: Boolean = false,
        val interpolationKeys: List<String>? = null,
    )

    // ── PostureAffinity JSON builders ─────────────────────────────────────────────

    private fun postureAffinity(
        turnShapes: List<String>,
        eMin: Double, eMax: Double,
        pMin: Double, pMax: Double,
    ): String = """{"turnShapes":${json.encodeToString(turnShapes)},"surfaceEnergyRange":{"min":$eMin,"max":$eMax},"responsePressureRange":{"min":$pMin,"max":$pMax}}"""

    // ── Pre-built postureAffinity JSON per move type ──────────────────────────────

    private val receiptAffinity = postureAffinity(
        listOf("SHORT_TURN", "STATEMENT", "FRAGMENT", "DIRECTIVE"),
        eMin = 0.0, eMax = 1.0, pMin = 0.0, pMax = 0.4,
    )
    private val orientAffinity = postureAffinity(
        listOf("STATEMENT", "TOPIC_REFERENCE", "DIRECTIVE", "QUESTION"),
        eMin = 0.2, eMax = 0.8, pMin = 0.0, pMax = 0.6,
    )
    private val holdAffinity = postureAffinity(
        listOf("VENT", "NARRATIVE", "STATEMENT"),
        eMin = 0.4, eMax = 1.0, pMin = 0.3, pMax = 0.8,
    )
    private val repairAffinity = postureAffinity(
        listOf("CORRECTION", "STATEMENT", "QUESTION"),
        eMin = 0.0, eMax = 1.0, pMin = 0.3, pMax = 0.8,
    )
    private val probeAffinity = postureAffinity(
        listOf("QUESTION", "FRAGMENT", "AMBIGUOUS"),
        eMin = 0.2, eMax = 0.8, pMin = 0.3, pMax = 0.7,
    )
    private val commitAffinity = postureAffinity(
        listOf("DIRECTIVE", "QUESTION", "STATEMENT"),
        eMin = 0.4, eMax = 1.0, pMin = 0.5, pMax = 1.0,
    )
    private val misreadRecoveryAffinity = postureAffinity(
        listOf("QUESTION", "STATEMENT", "CORRECTION"),
        eMin = 0.0, eMax = 1.0, pMin = 0.4, pMax = 0.9,
    )
    private val multiUtteranceHoldAffinity = postureAffinity(
        listOf("STATEMENT", "NARRATIVE", "FRAGMENT"),
        eMin = 0.2, eMax = 0.8, pMin = 0.1, pMax = 0.5,
    )

    private val seedPhrases = listOf(

        // ── First Response: RECEIPT (7) ─────────────────────────────────────────────
        SeedPhrase("Right.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("Okay.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("Got it.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("I see.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("Mm-hmm.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("Of course.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),
        SeedPhrase("Understood.", "RECEIPT", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "RECEIPT", postureAffinityJson = receiptAffinity),

        // ── First Response: ORIENT (4) ────────────────────────────────────────────
        SeedPhrase("Okay — calendar.", "ORIENT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "META"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "ORIENT", postureAffinityJson = orientAffinity),
        SeedPhrase("Right — voice pipeline.", "ORIENT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "META"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "ORIENT", postureAffinityJson = orientAffinity),
        SeedPhrase("Alright, architecture.", "ORIENT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "META"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "ORIENT", postureAffinityJson = orientAffinity),
        SeedPhrase("Okay — {lastTopic}.", "ORIENT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "META"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "ORIENT", postureAffinityJson = orientAffinity,
            requiresInterpolation = true, interpolationKeys = listOf("lastTopic")),

        // ── First Response: HOLD (4) ──────────────────────────────────────────────────
        SeedPhrase("Yeah, I hear you.", "HOLD", "FIRST_RESPONSE",
            listOf("SOCIAL", "CLARIFICATION", "META"), allPhases,
            moveType = "HOLD", postureAffinityJson = holdAffinity),
        SeedPhrase("That part is messy.", "HOLD", "FIRST_RESPONSE",
            listOf("SOCIAL", "CLARIFICATION", "META"), allPhases,
            moveType = "HOLD", postureAffinityJson = holdAffinity),
        SeedPhrase("I see why that\u2019s frustrating.", "HOLD", "FIRST_RESPONSE",
            listOf("SOCIAL", "CLARIFICATION", "META"), allPhases,
            moveType = "HOLD", postureAffinityJson = holdAffinity),
        SeedPhrase("That\u2019s a lot.", "HOLD", "FIRST_RESPONSE",
            listOf("SOCIAL", "CLARIFICATION", "META"), allPhases,
            moveType = "HOLD", postureAffinityJson = holdAffinity),

        // ── First Response: REPAIR (3) ────────────────────────────────────────────
        SeedPhrase("Ah \u2014 got it.", "REPAIR", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "REPAIR", postureAffinityJson = repairAffinity),
        SeedPhrase("My mistake.", "REPAIR", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "REPAIR", postureAffinityJson = repairAffinity),
        SeedPhrase("Right, I had that wrong.", "REPAIR", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "REPAIR", postureAffinityJson = repairAffinity),

        // ── First Response: PROBE (5) ─────────────────────────────────────────────
        SeedPhrase("Where should we start?", "PROBE", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "CLARIFICATION"), allPhases,
            moveType = "PROBE", postureAffinityJson = probeAffinity),
        SeedPhrase("Which part first?", "PROBE", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "CLARIFICATION"), allPhases,
            moveType = "PROBE", postureAffinityJson = probeAffinity),
        SeedPhrase("What\u2019s the blocker?", "PROBE", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "CLARIFICATION"), allPhases,
            moveType = "PROBE", postureAffinityJson = probeAffinity),
        SeedPhrase("Want the short version?", "PROBE", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "CLARIFICATION"), allPhases,
            moveType = "PROBE", postureAffinityJson = probeAffinity),
        SeedPhrase("Anything specific?", "PROBE", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION", "CLARIFICATION"), allPhases,
            moveType = "PROBE", postureAffinityJson = probeAffinity),

        // ── First Response: COMMIT (4) ────────────────────────────────────────────
        SeedPhrase("On it.", "COMMIT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "COMMIT", postureAffinityJson = commitAffinity),
        SeedPhrase("I\u2019ll handle that.", "COMMIT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "COMMIT", postureAffinityJson = commitAffinity),
        SeedPhrase("Okay, I can do that.", "COMMIT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "COMMIT", postureAffinityJson = commitAffinity),
        SeedPhrase("Let me pull that up.", "COMMIT", "FIRST_RESPONSE",
            listOf("TASK", "QUESTION"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING"),
            moveType = "COMMIT", postureAffinityJson = commitAffinity),

        // ── First Response: MISREAD_RECOVERY (3) ──────────────────────────────────────
        SeedPhrase("Ah \u2014 wrong track.", "MISREAD_RECOVERY", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MISREAD_RECOVERY", postureAffinityJson = misreadRecoveryAffinity),
        SeedPhrase("Let me back up.", "MISREAD_RECOVERY", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MISREAD_RECOVERY", postureAffinityJson = misreadRecoveryAffinity),
        SeedPhrase("Not what you meant.", "MISREAD_RECOVERY", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MISREAD_RECOVERY", postureAffinityJson = misreadRecoveryAffinity),

        // ── First Response: MULTI_UTTERANCE_HOLD (3) ─────────────────────────────────────
        SeedPhrase("Still with you.", "MULTI_UTTERANCE_HOLD", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MULTI_UTTERANCE_HOLD", postureAffinityJson = multiUtteranceHoldAffinity),
        SeedPhrase("Take your time.", "MULTI_UTTERANCE_HOLD", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MULTI_UTTERANCE_HOLD", postureAffinityJson = multiUtteranceHoldAffinity),
        SeedPhrase("Mm-hmm.", "MULTI_UTTERANCE_HOLD", "FIRST_RESPONSE", allBranches, allPhases,
            moveType = "MULTI_UTTERANCE_HOLD", postureAffinityJson = multiUtteranceHoldAffinity),

        // ── Greetings ─────────────────────────────────────────────────────
        SeedPhrase("Good morning.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good afternoon.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good evening.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good afternoon. The day\u2019s half gone \u2014 let\u2019s make the rest count.",
            "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Early start today.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Burning the midnight oil, I see.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good to meet you. I\u2019d like to get oriented so I can be useful quickly.",
            "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), listOf("ORIENTATION")),
        SeedPhrase("Welcome. I\u2019m alfrd \u2014 let\u2019s get acquainted.",
            "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), listOf("ORIENTATION")),
        SeedPhrase("Hello. I work best when I know who I\u2019m working with.",
            "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), listOf("ORIENTATION")),
        SeedPhrase("Good to see you.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Welcome back.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Welcome back. Where were we?", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM")),
        SeedPhrase("Good to see you again. Ready to pick up where we left off?",
            "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"), listOf("ORIENTATION", "WORKING_RHYTHM")),
        SeedPhrase("Back again. What\u2019s on your mind?", "GREETING", "FIRST_RESPONSE",
            listOf("SOCIAL"), listOf("WORKING_RHYTHM")),
        SeedPhrase("Good to see you again, {userName}.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("userName")),
        SeedPhrase("Good {timeOfDay}. What are we working on?", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("timeOfDay")),
        SeedPhrase("Welcome back, {userName}. The usual?", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("userName")),
        SeedPhrase("It\u2019s been a while. Good to have you back.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Right where we left off.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Good to have you back.", "GREETING", "FIRST_RESPONSE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),

        // ── Sign-offs ─────────────────────────────────────────────────
        SeedPhrase("Until next time.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),
        SeedPhrase("Take care.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),
        SeedPhrase("I\u2019ll be here.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),

        // ── Bridge (post-comprehension) ───────────────────────────────────────────
        SeedPhrase("Let me think about that...", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Give me a moment...", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("There\u2019s a lot here...", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Bear with me.", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Give me a second.", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Pulling that together...", "BRIDGE", "BRIDGE",
            listOf("TASK", "QUESTION", "META"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Let me work through that.", "BRIDGE", "BRIDGE",
            listOf("TASK", "QUESTION", "META"), allPhases),
        SeedPhrase("One moment.", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Thinking...", "BRIDGE", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),

        // ── Fillers ──────────────────────────────────────────────────────────
        SeedPhrase("Mm", "FILLER", "FIRST_RESPONSE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Right", "FILLER", "FIRST_RESPONSE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
        SeedPhrase("Okay...", "FILLER", "FIRST_RESPONSE",
            listOf("SOCIAL", "QUESTION", "TASK", "META"), allPhases),
    )

    fun seed(db: Database) {
        db.transaction {
            // Skip if already seeded
            val existing = db.query("sql", "SELECT count(*) as cnt FROM ResponsePhrase")
            val count = existing.use { rs ->
                if (rs.hasNext()) rs.next().toMap()["cnt"] as? Long ?: 0L else 0L
            }
            if (count > 0) return@transaction

            // Ensure system_response_pool source exists
            val sourceUid = "system_response_pool"
            val sourceExists = db.query("sql",
                "SELECT count(*) as cnt FROM Source WHERE uid = :uid",
                mapOf("uid" to sourceUid)
            ).use { rs ->
                if (rs.hasNext()) (rs.next().toMap()["cnt"] as? Long ?: 0L) > 0 else false
            }

            val sourceVertex = if (!sourceExists) {
                db.newVertex("Source").apply {
                    set("uid", sourceUid)
                    set("name", "System Response Pool")
                    set("type", "system")
                    set("metadata", "{}")
                    save()
                }
            } else {
                db.query("sql", "SELECT FROM Source WHERE uid = :uid",
                    mapOf("uid" to sourceUid)
                ).use { rs -> rs.next().toElement().asVertex() }
            }

            val now = System.currentTimeMillis()

            for (phrase in seedPhrases) {
                val uid = UUID.randomUUID().toString()
                val hash = sha256(phrase.text)

                val vertex = db.newVertex("ResponsePhrase").apply {
                    set("uid", uid)
                    set("text", phrase.text)
                    set("hash", hash)
                    set("visibility", "internal")
                    set("createdAt", now)
                    set("updatedAt", now)
                    set("branchAffinity", json.encodeToString(phrase.branchAffinity))
                    set("phaseAffinity", json.encodeToString(phrase.phaseAffinity))
                    set("expressionPhase", phrase.expressionPhase)
                    set("category", phrase.category)
                    set("moveType", phrase.moveType as String?)
                    set("postureAffinity", phrase.postureAffinityJson as String?)
                    set("variants", null as String?)
                    set("requiresInterpolation", phrase.requiresInterpolation)
                    set("interpolationKeys",
                        phrase.interpolationKeys?.let { json.encodeToString(it) })
                    save()
                }

                vertex.newEdge("ASSERTS", sourceVertex, false).apply {
                    set("context", "seed")
                    set("timestamp", now)
                    save()
                }
            }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
