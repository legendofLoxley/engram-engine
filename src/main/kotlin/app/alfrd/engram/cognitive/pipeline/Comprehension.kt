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

        val (rawIntent, rawConfidence, ruleName) = classifyTier1(ctx, lower)

        var tier2Fired = false
        var tier2RawResult: String? = null

        val (intent, confidence, tier) =
            if ((rawConfidence < 0.7 || rawIntent == IntentType.AMBIGUOUS) && llmClient != null && tier2Model != null) {
                tier2Fired = true
                val tier2 = classifyTier2(ctx.utterance) { raw -> tier2RawResult = raw }
                if (tier2 != null) Triple(tier2, 0.75, 2)
                else Triple(IntentType.AMBIGUOUS, 0.30, 1)
            } else if (rawConfidence < 0.7 || rawIntent == IntentType.AMBIGUOUS) {
                Triple(IntentType.AMBIGUOUS, 0.30, 1)
            } else {
                Triple(rawIntent, rawConfidence, 1)
            }

        ctx.trace?.comprehension?.let { c ->
            c.tier = tier
            c.tierOneRuleMatched = ruleName
            c.tierOneConfidence = rawConfidence
            c.tierTwoFired = tier2Fired
            c.tierTwoResult = tier2RawResult
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

    private suspend fun classifyTier2(utterance: String, onRawResult: (String) -> Unit = {}): IntentType? {
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
            - CLARIFICATION (ambiguous — cannot determine intent)
            
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
            val raw = response.text.trim()
            onRawResult(raw)
            parseIntentFromLlm(raw)
        } catch (e: Exception) {
            System.err.println("[Comprehension] Tier 2 LLM call failed: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    private fun parseIntentFromLlm(raw: String): IntentType? =
        IntentType.entries.firstOrNull { it.name.equals(raw.uppercase().trim(), ignoreCase = false) }
            ?: IntentType.entries.firstOrNull { raw.uppercase().contains(it.name) }

    // ── Classification rules ──────────────────────────────────────────────────

    private data class Tier1Result(val intent: IntentType, val confidence: Double, val ruleName: String)

    private fun classifyTier1(ctx: CognitiveContext, lower: String): Tier1Result {
        // Rule 0 — Scaffold context-default: treat utterance as onboarding answer
        if (ctx.scaffoldState != null) return Tier1Result(IntentType.ONBOARDING, 0.95, "scaffold_context_default")

        // Rule 1 — Social / phatic
        if (isSocial(lower)) return Tier1Result(IntentType.SOCIAL, 0.90, "social_phatic")

        // Rule 1.5 — Modality check ("can you hear me", "are you there", etc.)
        if (isModalityCheck(lower)) return Tier1Result(IntentType.SOCIAL, 0.90, "modality_check")

        // Rule 2 — Correction
        if (isCorrection(lower)) return Tier1Result(IntentType.CORRECTION, 0.80, "correction")

        // Rule 3 — Meta query
        if (isMeta(lower)) return Tier1Result(IntentType.META, 0.85, "meta_query")

        // Rule 4 — Task request (imperative verbs)
        if (isTask(lower)) return Tier1Result(IntentType.TASK, 0.70, "task_imperative")

        // Rule 5 — Question (interrogative or trailing "?")
        if (isQuestion(ctx.utterance.trim(), lower)) return Tier1Result(IntentType.QUESTION, 0.70, "question_interrogative")

        // Rule 6 — Onboarding fallback during active onboarding session
        if (ctx.trustPhase != null) return Tier1Result(IntentType.ONBOARDING, 0.60, "onboarding_fallback")

        // Rule 7 — Ambiguous
        return Tier1Result(IntentType.AMBIGUOUS, 0.30, "ambiguous")
    }

    // ── Pattern helpers ───────────────────────────────────────────────────────

    private fun isModalityCheck(lower: String): Boolean {
        val patterns = listOf(
            "can you hear me", "are you listening", "are you there",
            "is this working", "can you see me", "hello?",
            "is anyone there", "can you understand me",
        )
        return patterns.any { lower == it || lower.contains(it) }
    }

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
