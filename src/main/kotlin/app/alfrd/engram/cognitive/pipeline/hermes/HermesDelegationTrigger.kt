package app.alfrd.engram.cognitive.pipeline.hermes

/**
 * Detects the narrow, closed set of utterance shapes this bounded slice delegates to Hermes:
 * the user asking about one of exactly two approved files, each with its own fixed task shape.
 * Deliberately NOT a general intent classifier, keyword router, or job-request DSL — the
 * increment this belongs to is scoped to controlled, read-only assignments against
 * pre-approved files, explicitly "no generic orchestration platform" and "no expansion into
 * general filesystem tools." Adding a genuinely new capability means adding a new named
 * filename constant and a new `detect*` function here, never accepting an arbitrary
 * user-supplied path — [HermesWorkspacePath] independently re-validates whichever constant is
 * matched here before it ever reaches Hermes, but this function is what keeps the *set* of
 * possible targets closed in the first place.
 */
object HermesDelegationTrigger {

    /** The fixture this bounded slice recognizes — see `hermes-dev/workspace/` on the dev host. */
    const val FIXTURE_FILENAME = "director-hermes-fixture.txt"

    /** The approved document this slice can summarize — same workspace, same read-only mount. */
    const val DOCUMENT_SUMMARY_FILENAME = "director-hermes-project-brief.md"

    private val INSPECT_VERB_PATTERN = Regex("\\b(check|inspect|read|look at|look in|open)\\b", RegexOption.IGNORE_CASE)
    private val SUMMARIZE_VERB_PATTERN = Regex("\\b(summarize|summarise|sum up|recap)\\b", RegexOption.IGNORE_CASE)

    /** True only when the utterance names the fixture AND uses an inspection-shaped verb. */
    fun detect(utterance: String): Boolean =
        utterance.contains(FIXTURE_FILENAME, ignoreCase = true) && INSPECT_VERB_PATTERN.containsMatchIn(utterance)

    /** True only when the utterance names the approved document AND uses a summarize-shaped verb. */
    fun detectDocumentSummary(utterance: String): Boolean =
        utterance.contains(DOCUMENT_SUMMARY_FILENAME, ignoreCase = true) && SUMMARIZE_VERB_PATTERN.containsMatchIn(utterance)
}
