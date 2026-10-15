package app.alfrd.engram.cognitive.pipeline.horizon

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * What the interpretation model may write to the graph: a small, closed set of verbs. Each is a
 * *proposal* — [OperationParser] validates it in code (grounding, refs, negation, shape) before it
 * is trusted, and [HorizonOperationApplier] is the only thing that turns one into a graph write.
 *
 * Operations carry uids, not the short refs (`c1`, `i2`) the model saw: refs are resolved against
 * the [ViewSnapshot] at parse time, so a stored plan means the same thing when it is replayed after
 * a crash, even though the snapshot it was planned against no longer exists. The one exception is a
 * project created earlier in the same response, referred to by [RecordItem.projectName] and looked
 * up by name when applied.
 */
@Serializable
sealed interface HorizonOperation {
    /** The user's own words this operation rests on — verbatim from what they said. */
    val quote: String
    val verb: String
}

@Serializable
data class RecordItem(
    val kind: ItemKind,
    override val quote: String,
    val projectUid: String? = null,
    /** A project created earlier in this same response, by name — resolved when applied. */
    val projectName: String? = null,
    val dueAt: Long? = null,
    val about: List<String> = emptyList(),
    /** True when it clearly belongs to some project but the model couldn't tell which: saved unfiled, and the user is asked. */
    val askWhichProject: Boolean = false,
    /** A short label for a task or event (see [OperationParser.acceptableTitle]); the item's text when present, with [quote] kept as its source. */
    val title: String? = null,
    /**
     * The chunk this item was taken from, when it came from a read source rather than from the user's own words. Set only by
     * code that holds the chunk (never by [OperationParser]): [quote] must then be a verbatim passage of that chunk, and the
     * item is tied to it on apply. Null for everything the user says.
     */
    val chunkUid: String? = null,
    /** Who asserted the claim, when it is someone other than the writer (a sender, an author). Only meaningful with [chunkUid]. */
    val claimant: String? = null,
    /** Who recorded the item, for an item from a source: `director`, `user` or `hermes`. Only meaningful with [chunkUid]. */
    val writer: String? = null,
) : HorizonOperation { override val verb get() = "record_item" }

@Serializable
data class ResolveItem(val targetUid: String, override val quote: String) : HorizonOperation { override val verb get() = "resolve_item" }

@Serializable
data class ReopenItem(val targetUid: String, override val quote: String) : HorizonOperation { override val verb get() = "reopen_item" }

@Serializable
data class CorrectItem(val targetUid: String, override val quote: String, val dueAt: Long? = null, val title: String? = null) : HorizonOperation { override val verb get() = "correct_item" }

@Serializable
data class FileItem(val targetUid: String, val intoUid: String, override val quote: String = "") : HorizonOperation { override val verb get() = "file_item" }

@Serializable
data class CreateContainer(
    val kind: ContainerKind,
    val name: String,
    /** False when the name is the model's own label rather than something the user said — stored as tentative. */
    val namedByUser: Boolean,
    val parentUid: String? = null,
    override val quote: String = "",
) : HorizonOperation { override val verb get() = "create_container" }

@Serializable
data class SetFocus(val targetUid: String, override val quote: String) : HorizonOperation { override val verb get() = "set_focus" }

@Serializable
data class ReleaseFocus(override val quote: String) : HorizonOperation { override val verb get() = "release_focus" }

@Serializable
data class SetAttention(val targetUid: String, val attention: AttentionDirective?, override val quote: String) : HorizonOperation { override val verb get() = "set_attention" }

@Serializable
data class LinkItems(val fromUid: String, val toUid: String, override val quote: String) : HorizonOperation { override val verb get() = "link_items" }

/** The user voiced a strong feeling: write nothing yet, ask what they'd like. See [CycleFollowUp.AskEmotionChoice]. */
@Serializable
data class HoldForChoice(override val quote: String) : HorizonOperation { override val verb get() = "hold_for_choice" }

/** One operation the model proposed that code refused to trust, and why — kept so a rejection is visible, never silently dropped. */
@Serializable
data class OperationRejection(val verb: String, val reason: String, val quote: String? = null)

// ── The view the model is shown ─────────────────────────────────────────────

data class SnapshotContainer(
    val ref: String,
    val uid: String,
    val kind: ContainerKind,
    val name: String,
    val parentRef: String?,
    val attention: AttentionDirective?,
)

data class SnapshotItem(
    val ref: String,
    val uid: String,
    val kind: ItemKind,
    val text: String,
    val status: String?,
    val dueAt: Long?,
    val projectRef: String?,
    val about: List<String>,
    val attention: AttentionDirective?,
)

/**
 * A bounded picture of what the interpretation model can refer to: the current focus, the user's
 * containers, and a handful of relevant items — each under a short ref (`c1`, `i2`), never a uid.
 * Refs are the only way the model may name something that already exists; [uidFor] is how code turns
 * one back into a uid, and returns null for anything not shown — so an invented or stale ref can
 * never reach the graph. Built fresh each turn ([ViewSnapshotBuilder]); never stored.
 */
data class ViewSnapshot(
    val focusLines: List<String>,
    val containers: List<SnapshotContainer>,
    val items: List<SnapshotItem>,
    /** A statement the user just made that carried strong feeling, and the question put to them about it — see [CycleFollowUp.AskEmotionChoice]. */
    val pendingEmotionQuote: String? = null,
    /** The item saved unfiled last turn while the user was asked which project it belongs to. */
    val pendingFilingRef: String? = null,
    val zone: ZoneId = ZoneId.of("UTC"),
) {
    private val byRef: Map<String, String> = (containers.map { it.ref to it.uid } + items.map { it.ref to it.uid }).toMap()

    fun uidFor(ref: String?): String? = ref?.trim()?.let { byRef[it] }
    fun container(ref: String?): SnapshotContainer? = containers.firstOrNull { it.ref == ref?.trim() }
    fun item(ref: String?): SnapshotItem? = items.firstOrNull { it.ref == ref?.trim() }
    fun containerByUid(uid: String?): SnapshotContainer? = containers.firstOrNull { it.uid == uid }
    fun itemByUid(uid: String?): SnapshotItem? = items.firstOrNull { it.uid == uid }
    fun isEmpty(): Boolean = containers.isEmpty() && items.isEmpty()

    /** The exact text placed in the model's prompt. */
    fun render(): String = buildString {
        // Qualified: a bare isEmpty() here resolves to the StringBuilder's (true while it is still empty).
        if (this@ViewSnapshot.isEmpty()) {
            append("Snapshot: nothing recorded yet.\n")
        } else {
            append("Snapshot of what is in view:\n")
        }
        append("Focus: ").append(if (focusLines.isEmpty()) "none (zoomed out)" else focusLines.joinToString("; ")).append('\n')
        if (containers.isNotEmpty()) {
            append("Containers:\n")
            containers.forEach { c ->
                append("  ${c.ref} ${c.name} (${c.kind.name.lowercase()}")
                c.parentRef?.let { append(", in $it") }
                c.attention?.let { append(", ${it.name.lowercase()}") }
                append(")\n")
            }
        }
        if (items.isNotEmpty()) {
            append("Items:\n")
            items.forEach { i ->
                append("  ${i.ref} ${i.kind.name.lowercase()} \"${i.text.replace('"', '\'').take(ITEM_TEXT_CAP)}\"")
                i.projectRef?.let { append(" [in $it]") }
                i.dueAt?.let { append(" due ${java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}") }
                if (i.status == "resolved") append(" (resolved)")
                if (i.about.isNotEmpty()) append(" (about: ${i.about.joinToString(", ")})")
                i.attention?.let { append(" (${it.name.lowercase()})") }
                append('\n')
            }
        }
        pendingFilingRef?.let { ref ->
            append("Pending: last turn the user was asked which project $ref belongs to. If their message answers that, use file_item on $ref.\n")
        }
        pendingEmotionQuote?.let { q ->
            append("Pending: the user said \"${q.replace('"', '\'')}\" and was asked whether they just want to talk or want a task created to help. ")
            append("If they want a task, record_item kind task with a quote from that earlier statement. If they just want to talk, or say no, return no operations.\n")
        }
    }.trimEnd()

    companion object {
        const val ITEM_TEXT_CAP = 120
        val EMPTY = ViewSnapshot(emptyList(), emptyList(), emptyList())
    }
}

// ── Parsing and validation ──────────────────────────────────────────────────

data class ParseResult(val operations: List<HorizonOperation>, val rejections: List<OperationRejection>)

/**
 * Turns the model's `ops` list into validated [HorizonOperation]s, refusing (with a recorded
 * reason) anything code can't stand behind. The model is trusted for *judgment* — which verb, which
 * thing — and for nothing that code can check:
 * - every operation's `quote` must be a real, verbatim part of what the user said (or of a statement
 *   the user is currently being asked about), never a paraphrase or invention;
 * - every ref must be one the snapshot actually showed, and must be the right kind of thing for the verb;
 * - resolving something is refused when the clause it rests on is negated or a question — the one
 *   mistake a small model made in testing ("I never got around to sending it" read as "sent it") that
 *   would silently close real work;
 * - a strong-feeling hold suppresses recording anything from the same turn: nothing the user felt is
 *   filed as a claim until they say what they want done with it;
 * - the number of operations per turn is capped.
 */
object OperationParser {

    const val MAX_OPERATIONS = 8
    private const val MAX_ABOUT = 5
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_TITLE_LENGTH = 80

    private val NEGATION_REGEX = Regex(
        """\b(not|never|no longer|isn't|wasn't|don't|doesn't|didn't|won't|can't|cannot|haven't|hasn't|hadn't|without)\b|n't\b""",
        RegexOption.IGNORE_CASE,
    )
    private val CLAUSE_SPLIT = Regex("""(?<=[.!?;])\s+|\s+(?:but|however|although|though|yet|while|whereas)\s+""", RegexOption.IGNORE_CASE)

    fun parse(input: JsonObject, utterance: String, snapshot: ViewSnapshot, zone: ZoneId): ParseResult {
        val rawOps = input["ops"] as? JsonArray
            ?: return ParseResult(emptyList(), listOf(OperationRejection("apply_operations", "malformed_tool_input")))
        val grounding = listOfNotNull(utterance, snapshot.pendingEmotionQuote)
        val accepted = mutableListOf<HorizonOperation>()
        val rejections = mutableListOf<OperationRejection>()
        val createdNames = mutableSetOf<String>()

        for ((index, element) in rawOps.withIndex()) {
            val obj = element as? JsonObject
            if (obj == null) {
                rejections += OperationRejection("?", "malformed_op")
                continue
            }
            if (index >= MAX_OPERATIONS) {
                rejections += OperationRejection(obj.str("verb") ?: "?", "too_many_operations", obj.str("quote"))
                continue
            }
            when (val outcome = parseOne(obj, utterance, grounding, snapshot, zone, createdNames)) {
                is One.Ok -> {
                    if (accepted.none { it == outcome.op }) accepted += outcome.op
                    (outcome.op as? CreateContainer)?.let { createdNames += normalizeName(it.name) }
                }
                is One.Rejected -> rejections += OperationRejection(obj.str("verb") ?: "?", outcome.reason, obj.str("quote"))
            }
        }

        // A held emotional statement means nothing from this turn is filed as a claim.
        if (accepted.any { it is HoldForChoice }) {
            val (recorded, kept) = accepted.partition { it is RecordItem }
            recorded.forEach { rejections += OperationRejection(it.verb, "held_for_emotion_choice", it.quote) }
            return ParseResult(kept, rejections)
        }
        return ParseResult(accepted, rejections)
    }

    private sealed interface One {
        data class Ok(val op: HorizonOperation) : One
        data class Rejected(val reason: String) : One
    }

    private fun parseOne(
        obj: JsonObject,
        utterance: String,
        grounding: List<String>,
        snapshot: ViewSnapshot,
        zone: ZoneId,
        createdNames: Set<String>,
    ): One {
        val verb = obj.str("verb") ?: return One.Rejected("missing_verb")
        val quote = obj.str("quote")?.trim().orEmpty()
        if (quote.isBlank()) return One.Rejected("missing_quote")
        if (!isGrounded(grounding, quote)) return One.Rejected("ungrounded_quote")

        return when (verb) {
            "record_item" -> {
                val kind = when (obj.str("kind")?.lowercase()) {
                    "task" -> ItemKind.TASK
                    "event" -> ItemKind.EVENT
                    "fact" -> ItemKind.FACT
                    "question" -> ItemKind.QUESTION
                    else -> return One.Rejected("unknown_item_kind")
                }
                if ((kind == ItemKind.FACT || kind == ItemKind.EVENT) && quote.endsWith("?")) return One.Rejected("question_detected")
                val projectRaw = obj.str("project")?.trim()?.takeIf { it.isNotBlank() }
                var projectUid: String? = null
                var projectName: String? = null
                if (projectRaw != null) {
                    val byRef = snapshot.container(projectRaw)
                    val byName = snapshot.containers.firstOrNull { it.kind == ContainerKind.PROJECT && normalizeName(it.name) == normalizeName(projectRaw) }
                    when {
                        byRef != null && byRef.kind == ContainerKind.PROJECT -> projectUid = byRef.uid
                        byName != null -> projectUid = byName.uid
                        normalizeName(projectRaw) in createdNames -> projectName = projectRaw.trim()
                        // A project reference that resolves to nothing is dropped, not fatal: the item is still worth saving, unfiled.
                    }
                }
                One.Ok(
                    RecordItem(
                        kind = kind,
                        quote = quote,
                        title = if (kind == ItemKind.TASK || kind == ItemKind.EVENT) acceptableTitle(obj.str("title"), quote) else null,
                        projectUid = projectUid,
                        projectName = projectName,
                        dueAt = parseWhen(obj.str("due"), zone, endOfDayForDateOnly = kind != ItemKind.EVENT),
                        about = obj.strList("about").map { it.trim() }.filter { it.isNotBlank() && it.length <= 40 }.distinct().take(MAX_ABOUT),
                        askWhichProject = obj.bool("ask_which_project") == true && projectUid == null && projectName == null,
                    ),
                )
            }
            "resolve_item", "reopen_item" -> {
                val target = snapshot.uidFor(obj.str("item")) ?: return One.Rejected("unknown_ref")
                if (!isResolvable(snapshot, target)) return One.Rejected("not_resolvable")
                if (verb == "resolve_item") {
                    val clause = clauseContaining(utterance, quote)
                    if (NEGATION_REGEX.containsMatchIn(clause)) return One.Rejected("negation_marker_detected")
                    if (clause.trim().endsWith("?")) return One.Rejected("question_detected")
                    One.Ok(ResolveItem(target, quote))
                } else {
                    One.Ok(ReopenItem(target, quote))
                }
            }
            "correct_item" -> {
                val ref = obj.str("item")
                val item = snapshot.item(ref) ?: return One.Rejected("unknown_or_unsupported_ref")
                if (quote.endsWith("?")) return One.Rejected("question_detected")
                val title = if (item.kind == ItemKind.TASK || item.kind == ItemKind.EVENT) acceptableTitle(obj.str("title"), quote, item.text) else null
                One.Ok(CorrectItem(item.uid, quote, parseWhen(obj.str("due"), zone, endOfDayForDateOnly = item.kind != ItemKind.EVENT), title))
            }
            "file_item" -> {
                val childRef = obj.str("item")
                val intoRef = obj.str("into")
                val childUid = snapshot.uidFor(childRef) ?: return One.Rejected("unknown_ref")
                val into = snapshot.container(intoRef) ?: return One.Rejected("destination_not_a_container")
                val childIsProject = snapshot.container(childRef)?.kind == ContainerKind.PROJECT
                val childIsDomain = snapshot.container(childRef)?.kind == ContainerKind.DOMAIN
                if (childIsDomain) return One.Rejected("cannot_file_a_domain")
                val expected = if (childIsProject) ContainerKind.DOMAIN else ContainerKind.PROJECT
                if (into.kind != expected) return One.Rejected("wrong_destination_kind")
                One.Ok(FileItem(childUid, into.uid, quote))
            }
            "create_container" -> {
                val kind = when (obj.str("kind")?.lowercase()) {
                    "project" -> ContainerKind.PROJECT
                    "domain" -> ContainerKind.DOMAIN
                    else -> return One.Rejected("unknown_container_kind")
                }
                val name = obj.str("name")?.trim().orEmpty()
                if (name.isBlank() || name.length > MAX_NAME_LENGTH) return One.Rejected("invalid_name")
                // The model's claim that the user said this name is checked, not trusted: a name that
                // isn't in what they said is the model's own label, and is stored as tentative.
                val namedByUser = obj.bool("named_by_user") == true && isGrounded(listOf(utterance), name)
                val parent = obj.str("parent")?.let { snapshot.container(it) }?.takeIf { it.kind == ContainerKind.DOMAIN && kind == ContainerKind.PROJECT }
                One.Ok(CreateContainer(kind, name, namedByUser, parent?.uid, quote))
            }
            "set_focus" -> One.Ok(SetFocus(snapshot.uidFor(obj.str("target")) ?: return One.Rejected("unknown_ref"), quote))
            "release_focus" -> One.Ok(ReleaseFocus(quote))
            "set_attention" -> {
                val target = snapshot.uidFor(obj.str("item")) ?: return One.Rejected("unknown_ref")
                val attention = when (obj.str("attention")?.lowercase()) {
                    "pinned" -> AttentionDirective.PINNED
                    "suppressed" -> AttentionDirective.SUPPRESSED
                    "normal" -> null
                    else -> return One.Rejected("unknown_attention")
                }
                One.Ok(SetAttention(target, attention, quote))
            }
            "link_items" -> {
                val a = snapshot.item(obj.str("item")) ?: return One.Rejected("unknown_or_unsupported_ref")
                val b = snapshot.item(obj.str("other_item")) ?: return One.Rejected("unknown_or_unsupported_ref")
                if (a.uid == b.uid) return One.Rejected("cannot_link_to_itself")
                One.Ok(LinkItems(a.uid, b.uid, quote))
            }
            "hold_for_choice" -> {
                if (quote.split(Regex("\\s+")).size < 2) return One.Rejected("too_short_to_hold")
                One.Ok(HoldForChoice(quote))
            }
            else -> One.Rejected("unknown_verb")
        }
    }

    /**
     * A label the model wrote for a task or event, if it can be trusted — otherwise null, and the item's
     * text stays the user's own words. A label isn't verbatim by nature ("Order the soil" for "I never got
     * around to ordering the soil"), so what is checked is that it isn't inventing: it must be short, and at
     * least half of its content words (four letters or more) must come from what the user said, matching
     * on the first four letters so "order" meets "ordering". [alsoFrom] adds text a correction may reuse
     * (the item's old label). A label that fails is dropped, never repaired.
     */
    internal fun acceptableTitle(raw: String?, quote: String, vararg alsoFrom: String): String? {
        val title = raw?.trim()?.trim('"', '\'')?.trim()?.takeIf { it.isNotBlank() && it.length <= MAX_TITLE_LENGTH && '\n' !in it } ?: return null
        val content = title.split(Regex("[^A-Za-z0-9]+")).filter { it.length >= 4 }.map { it.lowercase().take(4) }
        if (content.isEmpty()) return title
        val known = (listOf(quote) + alsoFrom).flatMap { it.split(Regex("[^A-Za-z0-9]+")) }.filter { it.length >= 4 }.map { it.lowercase().take(4) }.toSet()
        val matched = content.count { it in known }
        return if (matched * 2 >= content.size) title else null
    }

    /** A task, event, question or project can be finished; a plain fact or a domain can't. */
    private fun isResolvable(snapshot: ViewSnapshot, uid: String): Boolean =
        snapshot.itemByUid(uid)?.let { it.kind != ItemKind.FACT }
            ?: (snapshot.containerByUid(uid)?.kind == ContainerKind.PROJECT)

    /** Normalized substring containment of [quote] in any of [texts] — proves the words are real, not that they mean what the model says. */
    fun isGrounded(texts: List<String>, quote: String): Boolean {
        val nq = normalize(quote)
        return nq.isNotBlank() && texts.any { normalize(it).contains(nq) }
    }

    /**
     * The clause of [utterance] that [quote] sits in — where a negation or question mark that
     * governs the quote would be. A verbatim quote can drop the negation that scopes it ("I never
     * got around to sending that invoice" quoted as "sending that invoice"), so the check is on the
     * surrounding clause, not the quote. Falls back to the whole utterance when no single clause holds it.
     */
    internal fun clauseContaining(utterance: String, quote: String): String {
        val nq = normalize(quote)
        val clauses = utterance.split(CLAUSE_SPLIT).filter { it.isNotBlank() }
        return clauses.firstOrNull { normalize(it).contains(nq) } ?: utterance
    }

    /**
     * ISO `YYYY-MM-DD` or `YYYY-MM-DDTHH:MM` in [zone] to epoch millis; null if absent or unparsable
     * (a bad date never costs the item). A bare date is a deadline (end of that day) for tasks and
     * projects, and the start of the day for an event — so a task due today isn't overdue until tomorrow.
     */
    internal fun parseWhen(text: String?, zone: ZoneId, endOfDayForDateOnly: Boolean): Long? {
        val raw = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return try {
            if (raw.contains('T')) {
                LocalDateTime.parse(raw.take(16)).atZone(zone).toInstant().toEpochMilli()
            } else {
                val date = LocalDate.parse(raw.take(10))
                val moment = if (endOfDayForDateOnly) date.plusDays(1).atStartOfDay(zone).minusNanos(1_000_000) else date.atStartOfDay(zone)
                moment.toInstant().toEpochMilli()
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun normalizeName(name: String): String = name.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } ?: emptyList()
}

/** JSON codec for a stored operation plan — polymorphic over [HorizonOperation]. */
object OperationPlanCodec {
    private val json = Json { classDiscriminator = "op"; ignoreUnknownKeys = true }

    fun encode(ops: List<HorizonOperation>): String = json.encodeToString<List<HorizonOperation>>(ops)

    fun decode(raw: String?): List<HorizonOperation>? = try {
        if (raw == null) null else json.decodeFromString<List<HorizonOperation>>(raw)
    } catch (_: Exception) {
        null
    }
}

/** What the reply must do next because of an operation, beyond acknowledging it. */
sealed interface CycleFollowUp {
    /** An item was saved unfiled because the model couldn't tell which project it belongs to — ask which. */
    data class AskWhichProject(val itemUid: String, val itemText: String, val projectNames: List<String>) : CycleFollowUp
    /** The user voiced strong feeling — ask whether they just want to talk or want a task created to help. */
    data class AskEmotionChoice(val quote: String) : CycleFollowUp
}
