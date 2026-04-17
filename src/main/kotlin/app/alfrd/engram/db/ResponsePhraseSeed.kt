package app.alfrd.engram.db

import com.arcadedb.database.Database
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.security.MessageDigest
import java.util.UUID

object ResponsePhraseSeed {

    private val json = Json { ignoreUnknownKeys = true }

    private val allPhases = listOf("ORIENTATION", "WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")

    private data class SeedPhrase(
        val text: String,
        val category: String,
        val expressionPhase: String,
        val branchAffinity: List<String>,
        val phaseAffinity: List<String>,
        val requiresInterpolation: Boolean = false,
        val interpolationKeys: List<String>? = null,
    )

    private val seedPhrases = listOf(
        // ── Greetings — time-of-day ────────────────────────────────────
        SeedPhrase("Good morning.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good afternoon.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good evening.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Good afternoon. The day's half gone — let's make the rest count.", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Early start today.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"), allPhases),
        SeedPhrase("Burning the midnight oil, I see.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"), allPhases),

        // ── Greetings — first-ever session (ORIENTATION only) ─────────
        SeedPhrase("Good to meet you. I'd like to get oriented so I can be useful quickly.", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("ORIENTATION")),
        SeedPhrase("Welcome. I'm alfrd — let's get acquainted.", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("ORIENTATION")),
        SeedPhrase("Hello. I work best when I know who I'm working with.", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("ORIENTATION")),

        // ── Greetings — returning user, early relationship ─────────────
        SeedPhrase("Good to see you.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Welcome back.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Welcome back. Where were we?", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM")),
        SeedPhrase("Good to see you again. Ready to pick up where we left off?", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("ORIENTATION", "WORKING_RHYTHM")),
        SeedPhrase("Back again. What's on your mind?", "GREETING", "ACKNOWLEDGE",
            listOf("SOCIAL"), listOf("WORKING_RHYTHM")),

        // ── Greetings — returning user, established relationship ───────
        SeedPhrase("Good to see you again, {userName}.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("userName")),
        SeedPhrase("Good {timeOfDay}. What are we working on?", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("timeOfDay")),
        SeedPhrase("Welcome back, {userName}. The usual?", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("CONTEXT", "UNDERSTANDING"),
            requiresInterpolation = true, interpolationKeys = listOf("userName")),

        // ── Greetings — session-gap-aware ──────────────────────────────
        SeedPhrase("It's been a while. Good to have you back.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Right where we left off.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),
        SeedPhrase("Good to have you back.", "GREETING", "ACKNOWLEDGE", listOf("SOCIAL"),
            listOf("WORKING_RHYTHM", "CONTEXT", "UNDERSTANDING")),

        // ── Acknowledgments ───────────────────────────────────────────
        SeedPhrase("Of course.", "ACKNOWLEDGMENT", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION"), allPhases),
        SeedPhrase("Got it.", "ACKNOWLEDGMENT", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION"), allPhases),
        SeedPhrase("Right.", "ACKNOWLEDGMENT", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION"), allPhases),
        SeedPhrase("Understood.", "ACKNOWLEDGMENT", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION"), allPhases),
        SeedPhrase("Mm-hmm.", "ACKNOWLEDGMENT", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "CORRECTION"), allPhases),

        // ── Sign-offs ─────────────────────────────────────────────────
        SeedPhrase("Until next time.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),
        SeedPhrase("Take care.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),
        SeedPhrase("I'll be here.", "SIGN_OFF", "SYNTHESIS", listOf("SOCIAL"), allPhases),

        // ── Fillers ───────────────────────────────────────────────────
        SeedPhrase("Mm", "FILLER", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
        SeedPhrase("Right", "FILLER", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
        SeedPhrase("Okay...", "FILLER", "ACKNOWLEDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
        SeedPhrase("Let me think about that...", "FILLER", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
        SeedPhrase("Give me a moment...", "FILLER", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
        SeedPhrase("There's a lot here...", "FILLER", "BRIDGE",
            listOf("SOCIAL", "QUESTION", "TASK", "META", "ONBOARDING"), allPhases),
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
