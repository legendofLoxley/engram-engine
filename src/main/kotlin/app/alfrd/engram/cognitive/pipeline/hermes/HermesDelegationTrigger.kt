package app.alfrd.engram.cognitive.pipeline.hermes

/** One document Hermes is approved to read and summarize — [filename] is what's actually sent to
 *  Hermes (validated again, in code, by [HermesWorkspacePath] before any dispatch); [description]
 *  is what a caller (currently [HermesDocumentIntentDirector]) shows a model or a user to name it
 *  without requiring them to already know its exact filename. */
data class HermesApprovedDocument(val filename: String, val description: String)

/**
 * Detects the one remaining regex-based utterance shape this bounded slice delegates to Hermes:
 * the user asking about the labeled demonstration fixture file. Deliberately NOT a general intent
 * classifier — the increment this belongs to is scoped to controlled, read-only assignments
 * against pre-approved files, explicitly "no generic orchestration platform" and "no expansion
 * into general filesystem tools."
 *
 * The *document-summary* case this object used to also gate by filename-and-verb regex now goes
 * through [HermesDocumentIntentDirector] instead — a bounded Director decision (paraphrase
 * understanding, contextual reference resolution, clarification) rather than exact-string
 * matching. [APPROVED_DOCUMENTS] is the closed set that decision is still not allowed to exceed:
 * [HermesDocumentIntentDirector] validates its own model-proposed target against this exact list,
 * and [HermesWorkspacePath] independently re-validates the resulting path in code before anything
 * reaches Hermes — two layers, neither of which is "trust the prompt."
 */
object HermesDelegationTrigger {

    /** The fixture this bounded slice recognizes — see `hermes-dev/workspace/` on the dev host. */
    const val FIXTURE_FILENAME = "director-hermes-fixture.txt"

    private val INSPECT_VERB_PATTERN = Regex("\\b(check|inspect|read|look at|look in|open)\\b", RegexOption.IGNORE_CASE)

    /** True only when the utterance names the fixture AND uses an inspection-shaped verb. */
    fun detect(utterance: String): Boolean =
        utterance.contains(FIXTURE_FILENAME, ignoreCase = true) && INSPECT_VERB_PATTERN.containsMatchIn(utterance)

    /** Closed set of documents [HermesDocumentIntentDirector] may ever propose delegating to —
     *  same read-only workspace directory as [FIXTURE_FILENAME], not a separate mount. */
    val APPROVED_DOCUMENTS: List<HermesApprovedDocument> = listOf(
        HermesApprovedDocument(
            filename = "director-hermes-project-brief.md",
            description = "a short project brief for the \"Alfrd Local Demo Readiness\" effort — goal, " +
                "deadlines, risks, and next actions for an internal product demo",
        ),
        HermesApprovedDocument(
            filename = "director-hermes-release-checklist.md",
            description = "a short release checklist for shipping engram-engine itself — goal, deadlines, " +
                "risks, and next actions for a version release",
        ),
    )
}
