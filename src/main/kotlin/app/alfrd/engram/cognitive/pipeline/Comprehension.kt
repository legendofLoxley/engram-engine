package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.providers.LlmClient
import app.alfrd.engram.cognitive.providers.LlmModel
import app.alfrd.engram.cognitive.providers.LlmRequest

/**
 * Comprehension stage — Tier 1 pattern-based intent classification with optional
 * Tier 2 LLM escalation for AMBIGUOUS utterances.
 *
 * Tier 2 runs only when [llmClient] and [tier2Model] are both non-null,
 * so the stage degrades gracefully to Tier 1-only when no LLM is available.
 */
class Comprehension(
    private val llmClient: LlmClient? = null,
    private val tier2Model: LlmModel? = null,
) : CognitiveStage {

    override suspend fun evaluate(ctx: CognitiveContext) {
        val lower = ctx.utterance.trim().lowercase()

        val (rawIntent, rawConfidence) = classifyTier1(ctx, lower)

        val (intent, confidence, tier) =
            if ((rawConfidence < 0.7 || rawIntent == IntentType.AMBIGUOUS) && llmClient != null && tier2Model != null) {
                val tier2 = classifyTier2(ctx.utterance)
                if (tier2 != null) Triple(tier2, 0.75, 2)
                else Triple(IntentType.AMBIGUOUS, 0.30, 1)
            } else if (rawConfidence < 0.7 || rawIntent == IntentType.AMBIGUOUS) {
                Triple(IntentType.AMBIGUOUS, 0.30, 1)
            } else {
                Triple(rawIntent, rawConfidence, 1)
            }

        ctx.intent = intent
        ctx.intentConfidence = confidence
        ctx.comprehensionTier = tier
        ctx.requiresMemory = intent in setOf(IntentType.ONBOARDING, IntentType.QUESTION, IntentType.META)
        ctx.memoryQueryHint = when (intent) {
            IntentType.QUESTION   -> "answer: ${ctx.utterance}"
            IntentType.META       -> "profile: ${ctx.utterance}"
            IntentType.ONBOARDING -> "onboarding context"
            else                  -> null
        }
    }

    // ── Tier 2: LLM intent classification ────────────────────────────────────

    private suspend fun classifyTier2(utterance: String): IntentType? {
        val prompt = """
            Classify the following utterance into exactly one intent category.
            Reply with ONLY the category name — no punctuation, no explanation.
            
            Categories:
            - ONBOARDING  (user sharing personal info: identity, role, expertise, preferences, tools, routines)
            - TASK        (imperative request: do something, remind, schedule, create, find)
            - QUESTION    (asking for information or an answer)
            - SOCIAL      (greeting, farewell, small talk, thanks)
            - META        (asking what the assistant knows or remembers about them)
            - CORRECTION  (correcting or clarifying a prior response)
            - CLARIFY     (ambiguous — cannot determine intent)
            
            Utterance: "$utterance"
        """.trimIndent()

        return try {
            val response = llmClient!!.complete(
                LlmRequest(
                    prompt     = prompt,
                    model      = tier2Model!!,
                    maxTokens  = 10,
                    timeoutMs  = 8_000,
                )
            )
            parseIntentFromLlm(response.text.trim())
        } catch (e: Exception) {
            System.err.println("[Comprehension] Tier 2 LLM call failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseIntentFromLlm(raw: String): IntentType? =
        IntentType.entries.firstOrNull { it.name.equals(raw.uppercase().trim(), ignoreCase = false) }
            ?: IntentType.entries.firstOrNull { raw.uppercase().contains(it.name) }

    // ── Classification rules ──────────────────────────────────────────────────

    private fun classifyTier1(ctx: CognitiveContext, lower: String): Pair<IntentType, Double> {
        // Rule 0 — Scaffold context-default: treat utterance as onboarding answer
        if (ctx.scaffoldState != null) return IntentType.ONBOARDING to 0.95

        // Rule 1 — Social / phatic
        if (isSocial(lower)) return IntentType.SOCIAL to 0.90

        // Rule 2 — Correction
        if (isCorrection(lower)) return IntentType.CORRECTION to 0.80

        // Rule 3 — Meta query
        if (isMeta(lower)) return IntentType.META to 0.85

        // Rule 4 — Task request (imperative verbs)
        if (isTask(lower)) return IntentType.TASK to 0.70

        // Rule 5 — Question (interrogative or trailing "?")
        if (isQuestion(ctx.utterance.trim(), lower)) return IntentType.QUESTION to 0.70

        // Rule 6 — Onboarding fallback during active onboarding session
        if (ctx.trustPhase != null) return IntentType.ONBOARDING to 0.60

        // Rule 7 — Ambiguous
        return IntentType.AMBIGUOUS to 0.30
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────

    private fun isSocial(lower: String): Boolean {
        val greetings = listOf("hey", "hi", "hello", "good morning", "good evening", "good afternoon", "howdy", "yo")
        val phatic    = listOf("thanks", "thank you", "goodbye", "bye", "see you", "cheers", "how are you", "how's it going")
        return greetings.any { lower == it || lower.startsWith("$it ") || lower.startsWith("$it,") } ||
               phatic.any   { lower == it || lower.contains(it) }
    }

    private fun isCorrection(lower: String): Boolean {
        val markers = listOf("actually", "no i meant", "no, i meant", "that's not right", "thats not right", "wait")
        return markers.any { lower.startsWith(it) || lower.contains(it) }
    }

    private fun isMeta(lower: String): Boolean {
        val patterns = listOf("what do you know about", "what have i told you", "show me my")
        return patterns.any { lower.contains(it) }
    }

    private fun isTask(lower: String): Boolean {
        val imperatives = listOf(
            "remind", "send", "create", "open", "find",
            "schedule", "set", "add", "book", "call", "message",
        )
        return imperatives.any { lower.startsWith("$it ") || lower == it }
    }

    private fun isQuestion(original: String, lower: String): Boolean {
        val interrogatives = listOf("what ", "why ", "how ", "when ", "where ", "who ")
        return interrogatives.any { lower.startsWith(it) } || original.endsWith("?")
    }
}
