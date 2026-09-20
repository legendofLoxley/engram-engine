package app.alfrd.engram.cognitive.pipeline.horizon

import org.slf4j.LoggerFactory

/**
 * What a turn leaves behind for the selector to pick up: fading nudges on what the user just
 * touched or mentioned, and — after a long silence — a question about what they have been up to.
 *
 * **Mentions become nudges, mechanically.** The interpretation model is asked only for explicit
 * focus changes; merely talking about something is handled here, in code, from what was said:
 * - a message that names a project or domain nudges it;
 * - a message that names an entity ("my sister") nudges the items tied to that entity — this is how
 *   an ordinary fact comes back into view without a separate lookup;
 * - a message that shares distinctive words with an item nudges that item (this is also how a
 *   question about something they once said finds it: recall is a mention like any other, and
 *   there is no separate lookup);
 * - anything the interpretation stage just created, resolved, corrected, filed or linked is nudged too.
 * A nudge is an ordinary `FOCUSES_ON` edge with a weight; it fades by itself (see [HorizonPolicy.nudgeStrength]),
 * so nothing here decides how long anything stays in view, and old ones are pruned so they can't pile up.
 *
 * Runs under [PerUserCycleLock] with the rest of the cycle. Everything is bounded by [HorizonPolicy]:
 * a message is matched against a capped number of the user's recent items and a capped number of words,
 * and at most [HorizonPolicy.maxMentionNudges] items are nudged per message.
 */
class HorizonSeeder(
    private val structure: HorizonStructureStore,
    private val policy: HorizonPolicy = HorizonPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val logger = LoggerFactory.getLogger(HorizonSeeder::class.java)

    /** One nudge that was set: what it is on, where it came from (`touched`, `container`, `entity`, `text`), how strong, and a short label — for tracing. */
    data class SeededNudge(val uid: String, val source: String, val weight: Double, val label: String?)

    /** The pinned focus moved from [fromName] to [toName] because the message was about the latter. */
    data class FocusMove(val fromName: String, val toUid: String, val toName: String)

    /** How many nudges one turn set, by where they came from, and the nudges themselves — for tracing. [focusMove] is set when the subject changed and the focus followed. */
    data class Seeded(
        val touched: Int, val mentionedContainers: Int, val mentionedEntities: Int, val mentionedItems: Int, val pruned: Int,
        val nudges: List<SeededNudge> = emptyList(),
        val focusMove: FocusMove? = null,
    )

    suspend fun seedTurn(userEmail: String, cycleSeq: Long, utterance: String, applied: List<AppliedOperation>): Seeded {
        val nudged = LinkedHashMap<String, Double>() // uid -> weight; strongest wins
        val detail = LinkedHashMap<String, SeededNudge>()
        fun nudge(uid: String?, weight: Double, source: String, label: String? = null) {
            if (uid == null) return
            val strength = weight.coerceIn(0.0, 1.0)
            val previous = nudged[uid]
            if (previous == null || strength > previous) {
                nudged[uid] = strength
                detail[uid] = SeededNudge(uid, source, strength, label?.take(MAX_LABEL))
            }
        }

        // What the interpretation stage just did to.
        var touched = 0
        for (result in applied.filter { it.applied }) {
            val before = nudged.size
            val label = result.summary
            when (val op = result.operation) {
                is RecordItem -> nudge(result.resultUid ?: result.existingUid, 1.0, "touched", label)
                is CorrectItem -> nudge(result.resultUid, 1.0, "touched", label)
                is ResolveItem -> nudge(op.targetUid, 1.0, "touched", label)
                is ReopenItem -> nudge(op.targetUid, 1.0, "touched", label)
                is FileItem -> { nudge(op.targetUid, 1.0, "touched", label); nudge(op.intoUid, 1.0, "touched", label) }
                is SetAttention -> nudge(op.targetUid, 1.0, "touched", label)
                is LinkItems -> { nudge(op.fromUid, 1.0, "touched", label); nudge(op.toUid, 1.0, "touched", label) }
                is CreateContainer, is SetFocus, is ReleaseFocus, is HoldForChoice -> Unit // a pin is not a nudge; a held feeling touches nothing
            }
            touched += nudged.size - before
        }

        val words = wordsOf(utterance)
        val containers = mentionedContainers(userEmail, utterance)
        containers.forEach { nudge(it.uid, 1.0, "container", it.name) }
        val entityItems = mentionedEntities(userEmail, words)
        entityItems.forEach { nudge(it.uid, policy.entityMentionWeight, "entity", it.text) }
        val textItems = mentionedItems(userEmail, utterance)
        textItems.forEach { (item, weight) -> nudge(item.uid, weight, "text", item.text) }

        // The subject changed: the focus follows. Before the nudges, so the new focus is a pin, not a nudge that fades.
        val explicitFocus = applied.any { it.applied && (it.operation is SetFocus || it.operation is ReleaseFocus) }
        val focusMove = if (explicitFocus) null else followSubject(userEmail, cycleSeq, containers)

        for ((uid, weight) in nudged) structure.setFocus(userEmail, cycleSeq, uid, FocusMode.NUDGE, weight = weight)
        val pruned = structure.pruneNudges(userEmail, cycleSeq - policy.nudgePruneCycles)
        return Seeded(touched, containers.size, entityItems.size, textItems.size, pruned, detail.values.toList(), focusMove)
    }

    /**
     * The focus moves with the subject, whether or not there has been a long gap: while a project or domain is pinned,
     * a message that names exactly one *other* project or domain (and not the pinned one) moves the pin there. Nothing is
     * lost — projects, items and deadlines stay, only what is in view changes, and mentions still bring things back — so
     * it is better to follow than to make the user say "switch to" every time.
     * It does not fire when: nothing is pinned (a first pin is the user's to make); the message names several
     * containers or the pinned one (no clear change of subject); the pin is on a single item; or the interpretation
     * model already set or cleared the focus this turn (an explicit instruction wins).
     */
    private suspend fun followSubject(userEmail: String, cycleSeq: Long, named: List<ContainerRecord>): FocusMove? {
        val pinned = structure.getFocus(userEmail).firstOrNull { it.mode == FocusMode.PINNED } ?: return null
        val current = structure.getContainer(userEmail, pinned.targetUid) ?: return null
        if (named.any { it.uid == current.uid }) return null
        val target = named.singleOrNull() ?: return null
        if (!structure.setFocus(userEmail, cycleSeq, target.uid, FocusMode.PINNED)) return null
        return FocusMove(current.name, target.uid, target.name)
    }

    private suspend fun mentionedContainers(userEmail: String, utterance: String): List<ContainerRecord> {
        val padded = " ${normalize(utterance)} "
        return structure.listContainers(userEmail).filter { it.status == "active" && it.normalizedName.length >= MIN_NAME_LENGTH }
            .filter { padded.contains(" ${normalize(it.name)} ") }
    }

    /** Items tied to an entity the message names — one word or two, matched to the entity's normalized name. */
    private suspend fun mentionedEntities(userEmail: String, words: List<String>): List<ItemRecord> {
        val candidates = (words.filter { it !in STOP_WORDS && it.length >= MIN_NAME_LENGTH } + words.zipWithNext { a, b -> "$a $b" })
            .distinct().take(MAX_ENTITY_CANDIDATES)
        return candidates.flatMap { structure.itemsAbout(userEmail, it, ITEMS_PER_ENTITY) }
            .distinctBy { it.uid }.take(policy.maxMentionNudges * 2)
    }

    /** Items whose own words overlap the message's distinctive words enough to count as being talked about. */
    private suspend fun mentionedItems(userEmail: String, utterance: String): List<Pair<ItemText, Double>> {
        val said = SalientTokenPropagator.salientTokens(utterance)
        if (said.isEmpty()) return emptyList()
        return structure.scanItemTexts(userEmail, policy.mentionScanItems).mapNotNull { item ->
            val theirs = SalientTokenPropagator.salientTokens(item.text)
            if (theirs.isEmpty()) return@mapNotNull null
            val overlap = (said intersect theirs).size
            if (overlap == 0) return@mapNotNull null
            val weight = overlap.toDouble() / minOf(said.size, theirs.size)
            if (weight < policy.mentionMinOverlap) null else item to weight
        }.sortedByDescending { it.second }.take(policy.maxMentionNudges)
    }

    // ── Silence ──────────────────────────────────────────────────────────────

    /** Whether Alfrd is now waiting on the user after a long gap. */
    data class SilenceOutcome(val asked: Boolean, val questionUid: String?)

    /**
     * Called first thing in a cycle. If the user has been away longer than [HorizonPolicy.silenceThreshold],
     * opens a question about what they have been up to (an open `Question` item, so it shows in the overview
     * and the reply asks it). Any *earlier* such question is closed either way: this turn is the user coming
     * back, which is the answer. Idempotent per request, so a retry neither asks twice nor closes the one
     * this request opened.
     */
    suspend fun checkSilence(userEmail: String, cycleSeq: Long, requestId: String): SilenceOutcome {
        val last = structure.lastActiveAt(userEmail)
        val gap = last?.let { clock() - it }
        val shouldAsk = gap != null && gap >= policy.silenceThreshold.toMillis()
        val opened = if (shouldAsk) structure.createItem(userEmail, cycleSeq, ItemKind.QUESTION, SILENCE_QUESTION, askedBy = "alfrd", opKey = "$requestId:silence") else null
        structure.listRecentItems(userEmail, OPEN_QUESTION_SCAN, openOnly = true)
            .filter { it.kind == ItemKind.QUESTION && it.askedBy == "alfrd" && it.text == SILENCE_QUESTION && it.uid != opened?.uid }
            .forEach { structure.resolve(userEmail, cycleSeq, it.uid) }
        return SilenceOutcome(opened != null, opened?.uid)
    }

    /** Records that the user has just been here — what the next long silence is measured from. */
    suspend fun recordActivity(userEmail: String) {
        try {
            structure.touchLastActive(userEmail, clock())
        } catch (e: Exception) {
            logger.warn("recordActivity failed for userEmail=$userEmail: ${e.message}")
        }
    }

    private fun wordsOf(text: String): List<String> = normalize(text).split(' ').filter { it.isNotBlank() }.take(MAX_WORDS)

    private fun normalize(text: String): String = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    companion object {
        /** The question opened after a long silence. Matched by text to find and close it later. */
        const val SILENCE_QUESTION = "What have you been up to?"
        private const val MIN_NAME_LENGTH = 3
        private const val MAX_WORDS = 60
        private const val MAX_LABEL = 80
        private const val MAX_ENTITY_CANDIDATES = 24
        private const val ITEMS_PER_ENTITY = 8
        private const val OPEN_QUESTION_SCAN = 50
        private val STOP_WORDS = setOf(
            "the", "and", "for", "with", "that", "this", "what", "when", "where", "who", "how", "why", "was", "were", "are", "you",
            "have", "has", "had", "did", "does", "can", "could", "would", "should", "about", "from", "into", "than", "then", "them",
            "they", "their", "there", "here", "just", "like", "some", "any", "all", "not", "but", "his", "her", "its", "our", "your", "mine",
        )
    }
}
