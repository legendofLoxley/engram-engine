package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState

/** What a step says should become of an open item. [UNCLASSIFIABLE] is the honest answer when nothing supports another. */
enum class Disposition(val label: String) {
    DONE("done"), DROPPED("dropped"), WAITING("waiting"), LIVE("live"), UNCLASSIFIABLE("unclassifiable");

    companion object {
        fun of(text: String?): Disposition? {
            val t = text?.trim()?.lowercase() ?: return null
            return when (t) { "still live", "still_live", "active", "open" -> LIVE; else -> entries.firstOrNull { it.label == t } }
        }
    }
}

/**
 * Whether a claim about an open item stands, decided in code from what the step was actually shown. **Applied to every setting** (reasoning on or off, any model): a
 * model's confidence is not evidence.
 *
 *  - **done** stands only if it cites at least one evidence line that was shown with *that* open item, scored at least the minimum (only qualifying lines are ever shown),
 *    and is itself a **done** item;
 *  - **dropped** the same, citing a **dropped** item (supersession is not read: a superseded item that is still open is not evidence);
 *  - **waiting** and **live** need at least one cited related line shown with the item, or an explicit "no evidence found" from the step;
 *  - **unclassifiable** always stands: it is what an unsupported claim is written as.
 * A claim that names no open item stands for nothing.
 */
object GoalClaims {
    /** What the check was given: for each open item's ref, the evidence refs shown with it, and the evidence itself. */
    class Shown(val evidenceFor: Map<String, Set<String>>, val scope: ScopeMap, val minRelevance: Double)

    data class Verdict(val stands: Boolean, val why: String? = null)

    fun check(claim: Disposition?, aboutRef: String?, supportRefs: List<String>, noEvidenceFound: Boolean, shown: Shown): Verdict {
        if (claim == null || claim == Disposition.UNCLASSIFIABLE) return Verdict(true)
        val about = aboutRef?.let { shown.scope.resolveOpen(it) } ?: return Verdict(false, "it names no open item that was shown")
        val allowed = shown.evidenceFor[aboutRef] ?: emptySet()
        val cited = supportRefs.filter { it in allowed }.mapNotNull { r -> shown.scope.resolve(r) }
        fun noun(d: Disposition) = d.label
        return when (claim) {
            Disposition.DONE -> if (cited.any { it.workState == WorkState.DONE }) Verdict(true) else Verdict(false, "no cited line is a done item that was shown with ${about.text.take(40)}")
            Disposition.DROPPED -> if (cited.any { it.workState == WorkState.DROPPED }) Verdict(true) else Verdict(false, "no cited line is a dropped item that was shown with ${about.text.take(40)}")
            Disposition.WAITING, Disposition.LIVE -> if (cited.isNotEmpty() || noEvidenceFound) Verdict(true) else Verdict(false, "${noun(claim)} needs a cited related line or an explicit \"no evidence found\"")
            Disposition.UNCLASSIFIABLE -> Verdict(true)
        }
    }

    private val DISPOSITION_PHRASE = Regex("""(?i)\bdisposition\b[^.:;]{0,40}[:\-—,]?\s*[^.]{0,20}\b(done|dropped)\b""")
    private val MARK_PHRASE = Regex("""(?i)^\s*(?:i\s+)?(?:propose|suggest|mark|recommend)\w*\b[^.]{0,60}\b(done|dropped)\b""")

    /** A claim written into the text with no `disposition` field ("Proposed disposition for i4: done"), so the check cannot be sidestepped by leaving the field out. */
    fun inferClaim(text: String): Disposition? {
        val m = DISPOSITION_PHRASE.find(text) ?: MARK_PHRASE.find(text) ?: return null
        return Disposition.of(m.groupValues[1])
    }
}

/** The shape of a question for the owner's channel: short and not a list. */
object GoalAsk {
    const val MAX = 300
    private val LIST_MARKER = Regex("""(?:^|\s)(?:\d+[.)]|[-*•])\s+\S""")
    private val REF = Regex("""\b[ie]\d+\b""")

    /** The question, cut to one short ask, and whether anything had to be cut. */
    fun shape(text: String): Pair<String, Boolean> {
        var out = text.trim()
        var cut = false
        val markers = LIST_MARKER.findAll(out).toList()
        if (markers.size >= 2) { out = out.take(markers.first().range.first).trim().ifEmpty { out.take(MAX) }; cut = true }
        else if (REF.findAll(out).count() >= 3) { out = firstSentence(out); cut = true }
        if (out.length > MAX) { out = out.take(MAX - 1).trimEnd() + "…"; cut = true }
        return out to cut
    }

    private fun firstSentence(t: String): String {
        val end = t.indexOfFirst { it == '?' || it == '.' }.let { if (it in 0 until MAX) it + 1 else MAX.coerceAtMost(t.length) }
        return t.take(end).trim()
    }
}

/** A step whose purpose is to show, send, present or hand a list to the owner is not work on the graph: the close-out and the channel do that. */
object GoalHandOver {
    private val VERBS = "show|send|present|hand ?over|deliver|share|display|give|provide|submit|email|notify"
    private val OBJECTS = "owner|jacob|channel|list|lists|dispositions?|results?|summary|findings|report|everything|the full"
    private val TITLE = Regex("""(?i)^\W*(?:$VERBS)\b[^.]{0,80}\b(?:$OBJECTS)\b""")
    private val TO_OWNER = Regex("""(?i)\b(?:show|send|present|hand|deliver|share|email|notify|give|submit)\b[^.]{0,60}\bto (?:the )?(?:owner|jacob|channel|user)\b""")

    fun isHandOver(title: String, instructions: String): Boolean = TITLE.containsMatchIn(title) || TO_OWNER.containsMatchIn(title) || TO_OWNER.containsMatchIn(instructions.take(160))
}
