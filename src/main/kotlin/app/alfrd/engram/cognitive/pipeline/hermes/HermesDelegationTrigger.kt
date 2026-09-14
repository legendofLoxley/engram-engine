package app.alfrd.engram.cognitive.pipeline.hermes

/**
 * Detects the one narrow utterance shape this bounded slice delegates to Hermes: the user
 * asking about the labeled demonstration fixture file. Deliberately NOT a general intent
 * classifier, keyword router, or job-request DSL — the increment this belongs to is scoped
 * to exactly one controlled, read-only assignment, explicitly "no generic orchestration
 * platform." A real delegation-worthy-intent detector is future work; this is the smallest
 * thing that lets a genuine browser conversation exercise the real round trip.
 */
object HermesDelegationTrigger {

    /** The fixture this bounded slice recognizes — see `hermes-dev/workspace/` on the dev host. */
    const val FIXTURE_FILENAME = "director-hermes-fixture.txt"

    private val VERB_PATTERN = Regex("\\b(check|inspect|read|look at|look in|open)\\b", RegexOption.IGNORE_CASE)

    /** True only when the utterance names the fixture AND uses an inspection-shaped verb. */
    fun detect(utterance: String): Boolean =
        utterance.contains(FIXTURE_FILENAME, ignoreCase = true) && VERB_PATTERN.containsMatchIn(utterance)
}
