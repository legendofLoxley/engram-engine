package app.alfrd.engram.cognitive.pipeline

/**
 * Comprehension stage — Tier 1 pattern-based intent classification.
 *
 * Rules are evaluated in priority order; the first match wins.
 * If confidence < 0.7, the intent is demoted to AMBIGUOUS (Tier 2 LLM
 * escalation is deferred to a later prompt).
 */
class Comprehension : CognitiveStage {

    override suspend fun evaluate(ctx: CognitiveContext) {
        val lower = ctx.utterance.trim().lowercase()

        val (rawIntent, rawConfidence) = classifyTier1(ctx, lower)

        val (intent, confidence) = if (rawConfidence < 0.7 || rawIntent == IntentType.AMBIGUOUS) {
            IntentType.AMBIGUOUS to 0.30
        } else {
            rawIntent to rawConfidence
        }

        ctx.intent = intent
        ctx.intentConfidence = confidence
        ctx.comprehensionTier = 1
        ctx.requiresMemory = intent in setOf(IntentType.ONBOARDING, IntentType.QUESTION, IntentType.META)
        ctx.memoryQueryHint = when (intent) {
            IntentType.QUESTION   -> "answer: ${ctx.utterance}"
            IntentType.META       -> "profile: ${ctx.utterance}"
            IntentType.ONBOARDING -> "onboarding context"
            else                  -> null
        }
    }

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
