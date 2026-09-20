package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.cognitive.pipeline.op
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * What [OperationParser] will and won't trust from the model. The model proposes; each of these
 * tests is a specific way a proposal could be wrong that code must catch.
 */
class OperationParserTest {

    private val zone = ZoneId.of("UTC")

    private fun snapshot(pendingEmotionQuote: String? = null) = ViewSnapshot(
        focusLines = emptyList(),
        containers = listOf(
            SnapshotContainer("c1", "u-c1", ContainerKind.DOMAIN, "Work", null, null),
            SnapshotContainer("c2", "u-c2", ContainerKind.PROJECT, "Demo", "c1", null),
            SnapshotContainer("c3", "u-c3", ContainerKind.PROJECT, "Garden redesign", null, null),
        ),
        items = listOf(
            SnapshotItem("i1", "u-i1", ItemKind.TASK, "Send the invoice to Acme", "open", null, "c2", emptyList(), null),
            SnapshotItem("i2", "u-i2", ItemKind.EVENT, "Sister's birthday", "open", null, null, listOf("sister"), null),
            SnapshotItem("i3", "u-i3", ItemKind.FACT, "My sister is allergic to peanuts", null, null, null, listOf("sister"), null),
            SnapshotItem("i4", "u-i4", ItemKind.QUESTION, "Should I take the job?", "open", null, null, emptyList(), null),
        ),
        pendingEmotionQuote = pendingEmotionQuote,
        zone = zone,
    )

    private fun parse(utterance: String, vararg ops: JsonObject, snapshot: ViewSnapshot = snapshot()): ParseResult =
        OperationParser.parse(buildJsonObject { put("ops", JsonArray(ops.toList())) }, utterance, snapshot, zone)

    private fun single(utterance: String, op: JsonObject, snapshot: ViewSnapshot = snapshot()): HorizonOperation {
        val result = parse(utterance, op, snapshot = snapshot)
        assertTrue(result.rejections.isEmpty(), "unexpected rejection: ${result.rejections}")
        return result.operations.single()
    }

    private fun rejection(utterance: String, op: JsonObject, snapshot: ViewSnapshot = snapshot()): String {
        val result = parse(utterance, op, snapshot = snapshot)
        assertTrue(result.operations.isEmpty(), "expected a rejection but accepted: ${result.operations}")
        return result.rejections.single().reason
    }

    // ── Grounding: the model may not invent what the user said ──────────────

    @Test
    fun `a quote that is not in the message is rejected, however plausible`() {
        assertEquals("ungrounded_quote", rejection("I need to call the plumber.", op("record_item", "kind" to "task", "quote" to "I need to call the electrician")))
    }

    @Test
    fun `grounding ignores case and punctuation but not words`() {
        val ok = single("I need to call the plumber, tomorrow!", op("record_item", "kind" to "task", "quote" to "i need to call the plumber tomorrow"))
        assertEquals("i need to call the plumber tomorrow", ok.quote)
        assertEquals("ungrounded_quote", rejection("I need to call the plumber.", op("record_item", "kind" to "task", "quote" to "I need to phone the plumber")))
    }

    @Test
    fun `a missing or blank quote is rejected`() {
        assertEquals("missing_quote", rejection("hello", op("record_item", "kind" to "task")))
        assertEquals("missing_quote", rejection("hello", op("release_focus", "quote" to "   ")))
    }

    @Test
    fun `a quote from the statement the user is being asked about is grounded, so a later yes can make it a task`() {
        val held = "I'm really stressed about the demo"
        val op = single(
            "yes, make it a task",
            op("record_item", "kind" to "task", "quote" to held),
            snapshot(pendingEmotionQuote = held),
        )
        assertEquals(held, op.quote)
        assertEquals("ungrounded_quote", rejection("yes, make it a task", op("record_item", "kind" to "task", "quote" to held)), "without the pending statement it is not grounded")
    }

    // ── record_item ──────────────────────────────────────────────────────────

    @Test
    fun `record_item parses each kind, its date, and its entities`() {
        val task = single("I need to send the slides by 2026-09-23.", op("record_item", "kind" to "task", "quote" to "I need to send the slides", "due" to "2026-09-23", "about" to listOf("slides"))) as RecordItem
        assertEquals(ItemKind.TASK, task.kind)
        assertEquals(listOf("slides"), task.about)
        assertEquals(LocalDate.parse("2026-09-23").plusDays(1).atStartOfDay(zone).minusNanos(1_000_000).toInstant().toEpochMilli(), task.dueAt, "a task's bare date is a deadline: the end of that day")

        val event = single("My dentist appointment is on the 28th", op("record_item", "kind" to "event", "quote" to "My dentist appointment is on the 28th", "due" to "2026-09-28T15:00")) as RecordItem
        assertEquals(java.time.LocalDateTime.parse("2026-09-28T15:00").atZone(zone).toInstant().toEpochMilli(), event.dueAt)

        val dated = single("Sam visits on October 2nd", op("record_item", "kind" to "event", "quote" to "Sam visits on October 2nd", "due" to "2026-10-02")) as RecordItem
        assertEquals(LocalDate.parse("2026-10-02").atStartOfDay(zone).toInstant().toEpochMilli(), dated.dueAt, "an event's bare date is the start of that day")
    }

    @Test
    fun `an unparsable date costs the date, never the item`() {
        val recorded = single("I need to call the bank", op("record_item", "kind" to "task", "quote" to "I need to call the bank", "due" to "next-ish Thursday")) as RecordItem
        assertNull(recorded.dueAt)
        assertEquals(ItemKind.TASK, recorded.kind)
    }

    @Test
    fun `an unknown kind is rejected`() {
        assertEquals("unknown_item_kind", rejection("I love sailing", op("record_item", "kind" to "feeling", "quote" to "I love sailing")))
    }

    @Test
    fun `a fact or event phrased as a question is rejected, but a task or question item may end in a question mark`() {
        assertEquals("question_detected", rejection("Is my sister allergic to peanuts?", op("record_item", "kind" to "fact", "quote" to "Is my sister allergic to peanuts?")))
        assertEquals("question_detected", rejection("Is the party on Friday?", op("record_item", "kind" to "event", "quote" to "Is the party on Friday?")))
        single("Can you remind me to call mom?", op("record_item", "kind" to "task", "quote" to "Can you remind me to call mom?"))
        single("Should I take the job?", op("record_item", "kind" to "question", "quote" to "Should I take the job?"))
    }

    @Test
    fun `about is trimmed, de-duplicated, length-limited and capped`() {
        val many = (1..9).map { "person $it" } + listOf("person 1", "  ", "x".repeat(41))
        val op = single("hello there friend", op("record_item", "kind" to "fact", "quote" to "hello there friend", "about" to many)) as RecordItem
        assertEquals((1..5).map { "person $it" }, op.about)
    }

    // ── Which project ────────────────────────────────────────────────────────

    @Test
    fun `a project can be named by ref, by exact name, or by a project created earlier in the same response`() {
        val byRef = single("book the room for the demo", op("record_item", "kind" to "task", "quote" to "book the room for the demo", "project" to "c2")) as RecordItem
        assertEquals("u-c2", byRef.projectUid)
        val byName = single("book the room for the demo", op("record_item", "kind" to "task", "quote" to "book the room for the demo", "project" to "garden REDESIGN")) as RecordItem
        assertEquals("u-c3", byName.projectUid)

        val both = parse(
            "New project Kitchen remodel: order the cabinets",
            op("create_container", "kind" to "project", "name" to "Kitchen remodel", "named_by_user" to true, "quote" to "New project Kitchen remodel"),
            op("record_item", "kind" to "task", "quote" to "order the cabinets", "project" to "Kitchen remodel"),
        )
        assertTrue(both.rejections.isEmpty())
        assertEquals("Kitchen remodel", (both.operations[1] as RecordItem).projectName)
        assertNull((both.operations[1] as RecordItem).projectUid)
    }

    @Test
    fun `a project reference that resolves to nothing, or to a domain, is dropped but the item is still saved`() {
        val unknown = single("call the plumber", op("record_item", "kind" to "task", "quote" to "call the plumber", "project" to "c9")) as RecordItem
        assertNull(unknown.projectUid); assertNull(unknown.projectName)
        val domain = single("call the plumber", op("record_item", "kind" to "task", "quote" to "call the plumber", "project" to "c1")) as RecordItem
        assertNull(domain.projectUid, "a domain is not a project")
        val notYetCreated = single("call the plumber", op("record_item", "kind" to "task", "quote" to "call the plumber", "project" to "Bathroom")) as RecordItem
        assertNull(notYetCreated.projectName, "a name that was never created in this response is not trusted")
    }

    @Test
    fun `ask_which_project only stands when no project was resolved`() {
        val asks = single("review the budget", op("record_item", "kind" to "task", "quote" to "review the budget", "ask_which_project" to true)) as RecordItem
        assertTrue(asks.askWhichProject)
        val filed = single("review the budget", op("record_item", "kind" to "task", "quote" to "review the budget", "project" to "c2", "ask_which_project" to true)) as RecordItem
        assertTrue(!filed.askWhichProject)
    }

    // ── Resolving: the one operation that can silently close real work ──────

    @Test
    fun `resolve_item on a task, event, question or project is accepted`() {
        for (ref in listOf("i1", "i2", "i4", "c2")) {
            val op = single("I sent it all.", op("resolve_item", "item" to ref, "quote" to "I sent it all")) as ResolveItem
            assertEquals("u-$ref", op.targetUid)
        }
    }

    @Test
    fun `resolve_item on a fact or a domain, or on a ref that was never shown, is rejected`() {
        assertEquals("not_resolvable", rejection("It is done.", op("resolve_item", "item" to "i3", "quote" to "It is done")))
        assertEquals("not_resolvable", rejection("It is done.", op("resolve_item", "item" to "c1", "quote" to "It is done")))
        assertEquals("unknown_ref", rejection("It is done.", op("resolve_item", "item" to "i99", "quote" to "It is done")))
        assertEquals("unknown_ref", rejection("It is done.", op("resolve_item", "item" to "u-i1", "quote" to "It is done")), "a uid is not a ref")
    }

    @Test
    fun `resolve_item is refused when the clause is negated, even if the quote dropped the negation`() {
        // The verbatim quote "sending that invoice" sits inside "I never got around to sending that invoice".
        assertEquals("negation_marker_detected", rejection("I never got around to sending that invoice.", op("resolve_item", "item" to "i1", "quote" to "sending that invoice")))
        assertEquals("negation_marker_detected", rejection("I haven't sent the invoice yet.", op("resolve_item", "item" to "i1", "quote" to "sent the invoice")))
        assertEquals("negation_marker_detected", rejection("I'm not going to send the invoice this week.", op("resolve_item", "item" to "i1", "quote" to "send the invoice this week")))
    }

    @Test
    fun `a negation in a different clause does not block a genuine resolve`() {
        single("I sent the invoice, but I haven't booked the room.", op("resolve_item", "item" to "i1", "quote" to "I sent the invoice"))
        single("I haven't booked the room. I sent the invoice.", op("resolve_item", "item" to "i1", "quote" to "I sent the invoice"))
    }

    @Test
    fun `resolve_item is refused when it rests on a question`() {
        assertEquals("question_detected", rejection("Did I send the invoice?", op("resolve_item", "item" to "i1", "quote" to "Did I send the invoice")))
    }

    @Test
    fun `reopen_item needs no negation check, because saying it is not done is the point`() {
        val reopened = single("The invoice isn't actually done.", op("reopen_item", "item" to "i1", "quote" to "The invoice isn't actually done")) as ReopenItem
        assertEquals("u-i1", reopened.targetUid)
        assertEquals("not_resolvable", rejection("The fact isn't right.", op("reopen_item", "item" to "i3", "quote" to "The fact isn't right")))
    }

    // ── Correcting ───────────────────────────────────────────────────────────

    @Test
    fun `correct_item takes an item and an optional new date, by the target's own date convention`() {
        val event = single("Actually the birthday is on the 24th, not the 21st.", op("correct_item", "item" to "i2", "quote" to "the birthday is on the 24th", "due" to "2026-09-24")) as CorrectItem
        assertEquals(LocalDate.parse("2026-09-24").atStartOfDay(zone).toInstant().toEpochMilli(), event.dueAt, "an event date is a start of day")
        val task = single("Actually the invoice is due the 25th.", op("correct_item", "item" to "i1", "quote" to "the invoice is due the 25th", "due" to "2026-09-25")) as CorrectItem
        assertEquals(LocalDate.parse("2026-09-25").plusDays(1).atStartOfDay(zone).minusNanos(1_000_000).toInstant().toEpochMilli(), task.dueAt, "a task date is a deadline")
    }

    @Test
    fun `correct_item is refused for a container, an unknown ref, or a question`() {
        assertEquals("unknown_or_unsupported_ref", rejection("Actually it is Garden.", op("correct_item", "item" to "c3", "quote" to "it is Garden")))
        assertEquals("unknown_or_unsupported_ref", rejection("Actually it is Garden.", op("correct_item", "item" to "i77", "quote" to "it is Garden")))
        assertEquals("question_detected", rejection("Actually, is it the 24th?", op("correct_item", "item" to "i2", "quote" to "is it the 24th?")))
    }

    // ── Filing ───────────────────────────────────────────────────────────────

    @Test
    fun `file_item moves an item into a project and a project into a domain`() {
        assertEquals(FileItem("u-i1", "u-c3", "move it to the garden"), single("Please move it to the garden.", op("file_item", "item" to "i1", "into" to "c3", "quote" to "move it to the garden")))
        assertEquals(FileItem("u-c3", "u-c1", "garden is work"), single("The garden is work.", op("file_item", "item" to "c3", "into" to "c1", "quote" to "garden is work")))
    }

    @Test
    fun `file_item refuses pairings the hierarchy does not allow`() {
        assertEquals("wrong_destination_kind", rejection("Put it under work.", op("file_item", "item" to "i1", "into" to "c1", "quote" to "Put it under work")))
        assertEquals("wrong_destination_kind", rejection("Put it under the garden.", op("file_item", "item" to "c2", "into" to "c3", "quote" to "Put it under the garden")))
        assertEquals("cannot_file_a_domain", rejection("Put work under demo.", op("file_item", "item" to "c1", "into" to "c2", "quote" to "Put work under demo")))
        assertEquals("destination_not_a_container", rejection("Put it under that.", op("file_item", "item" to "i1", "into" to "i2", "quote" to "Put it under that")))
        assertEquals("unknown_ref", rejection("Put it under the garden.", op("file_item", "item" to "i42", "into" to "c3", "quote" to "Put it under the garden")))
    }

    // ── Containers ───────────────────────────────────────────────────────────

    @Test
    fun `a container name the user said is trusted, one they didn't is stored as tentative`() {
        val said = single("Create a project called Taxes.", op("create_container", "kind" to "project", "name" to "Taxes", "named_by_user" to true, "quote" to "Create a project called Taxes")) as CreateContainer
        assertTrue(said.namedByUser)
        val invented = single("I need to start planning the Portugal trip.", op("create_container", "kind" to "project", "name" to "Travel planning", "named_by_user" to true, "quote" to "I need to start planning the Portugal trip")) as CreateContainer
        assertTrue(!invented.namedByUser, "the model's claim that the user said the name is checked against what they said")
        val admitted = single("I need to start planning the Portugal trip.", op("create_container", "kind" to "project", "name" to "Portugal trip", "named_by_user" to false, "quote" to "I need to start planning the Portugal trip")) as CreateContainer
        assertTrue(!admitted.namedByUser)
    }

    @Test
    fun `a project may name a domain parent, a domain may not have one, and bad names are rejected`() {
        val underDomain = single("A new project Budget under work.", op("create_container", "kind" to "project", "name" to "Budget", "named_by_user" to true, "parent" to "c1", "quote" to "A new project Budget under work")) as CreateContainer
        assertEquals("u-c1", underDomain.parentUid)
        val domain = single("A new area called Home.", op("create_container", "kind" to "domain", "name" to "Home", "named_by_user" to true, "parent" to "c1", "quote" to "A new area called Home")) as CreateContainer
        assertNull(domain.parentUid)
        val notADomain = single("A new project Budget.", op("create_container", "kind" to "project", "name" to "Budget", "named_by_user" to true, "parent" to "c3", "quote" to "A new project Budget")) as CreateContainer
        assertNull(notADomain.parentUid, "a project can only sit under a domain")

        assertEquals("invalid_name", rejection("Make a project.", op("create_container", "kind" to "project", "name" to " ", "named_by_user" to true, "quote" to "Make a project")))
        assertEquals("invalid_name", rejection("Make a project.", op("create_container", "kind" to "project", "name" to "x".repeat(81), "named_by_user" to true, "quote" to "Make a project")))
        assertEquals("unknown_container_kind", rejection("Make a thing.", op("create_container", "kind" to "folder", "name" to "Thing", "named_by_user" to true, "quote" to "Make a thing")))
    }

    // ── Focus and attention ──────────────────────────────────────────────────

    @Test
    fun `set_focus targets a container or an item that was shown, and release_focus needs only a quote`() {
        assertEquals(SetFocus("u-c2", "Let's work on the demo"), single("Let's work on the demo.", op("set_focus", "target" to "c2", "quote" to "Let's work on the demo")))
        assertEquals(SetFocus("u-i1", "focus on the invoice"), single("Please focus on the invoice.", op("set_focus", "target" to "i1", "quote" to "focus on the invoice")))
        assertEquals("unknown_ref", rejection("Let's work on taxes.", op("set_focus", "target" to "c7", "quote" to "Let's work on taxes")))
        assertEquals(ReleaseFocus("Zoom out"), single("Zoom out.", op("release_focus", "quote" to "Zoom out")))
    }

    @Test
    fun `set_attention pins, suppresses, or restores, and rejects anything else`() {
        assertEquals(AttentionDirective.SUPPRESSED, (single("Stop reminding me about the invoice.", op("set_attention", "item" to "i1", "attention" to "suppressed", "quote" to "Stop reminding me about the invoice")) as SetAttention).attention)
        assertEquals(AttentionDirective.PINNED, (single("Keep the invoice up front.", op("set_attention", "item" to "i1", "attention" to "pinned", "quote" to "Keep the invoice up front")) as SetAttention).attention)
        assertNull((single("Go back to normal on the invoice.", op("set_attention", "item" to "i1", "attention" to "normal", "quote" to "Go back to normal on the invoice")) as SetAttention).attention)
        assertEquals("unknown_attention", rejection("Mute it.", op("set_attention", "item" to "i1", "attention" to "muted", "quote" to "Mute it")))
    }

    // ── Links ────────────────────────────────────────────────────────────────

    @Test
    fun `link_items relates two distinct items, and nothing else`() {
        assertEquals(LinkItems("u-i3", "u-i2", "the allergy matters for the birthday"), single("The allergy matters for the birthday.", op("link_items", "item" to "i3", "other_item" to "i2", "quote" to "the allergy matters for the birthday")))
        assertEquals("cannot_link_to_itself", rejection("Same thing.", op("link_items", "item" to "i1", "other_item" to "i1", "quote" to "Same thing")))
        assertEquals("unknown_or_unsupported_ref", rejection("Tie them.", op("link_items", "item" to "i1", "other_item" to "c2", "quote" to "Tie them")), "a container is not an item")
    }

    // ── Holding a feeling ────────────────────────────────────────────────────

    @Test
    fun `a held feeling suppresses recording anything from the same turn, but not unrelated changes`() {
        val utterance = "I sent the invoice. I'm honestly panicking about the demo and I need to book the room."
        val result = parse(
            utterance,
            op("hold_for_choice", "quote" to "I'm honestly panicking about the demo"),
            op("record_item", "kind" to "task", "quote" to "I need to book the room"),
            op("resolve_item", "item" to "i1", "quote" to "I sent the invoice"),
        )

        assertEquals(listOf("hold_for_choice", "resolve_item"), result.operations.map { it.verb })
        assertEquals("held_for_emotion_choice", result.rejections.single().reason)
        assertEquals("record_item", result.rejections.single().verb)
    }

    @Test
    fun `a one-word hold is too short to be a statement of feeling`() {
        assertEquals("too_short_to_hold", rejection("Hi!", op("hold_for_choice", "quote" to "Hi")))
    }

    // ── Shape, size and repetition ───────────────────────────────────────────

    @Test
    fun `an op list larger than the cap keeps the first eight and rejects the rest visibly`() {
        val utterance = (1..10).joinToString(" ") { "Thing number $it happened." }
        val ops = (1..10).map { op("record_item", "kind" to "fact", "quote" to "Thing number $it happened") }.toTypedArray()

        val result = parse(utterance, *ops)

        assertEquals(OperationParser.MAX_OPERATIONS, result.operations.size)
        assertEquals(2, result.rejections.count { it.reason == "too_many_operations" })
    }

    @Test
    fun `an identical operation repeated is applied once`() {
        val one = op("record_item", "kind" to "task", "quote" to "call the plumber")
        assertEquals(1, parse("call the plumber", one, one).operations.size)
    }

    @Test
    fun `a missing ops list, a non-object entry, a missing verb and an unknown verb are all rejected, never crashed on`() {
        val noOps = OperationParser.parse(buildJsonObject { put("something", JsonPrimitive("else")) }, "hi", snapshot(), zone)
        assertEquals("malformed_tool_input", noOps.rejections.single().reason)

        val mixed = OperationParser.parse(
            buildJsonObject { put("ops", JsonArray(listOf(JsonPrimitive("not an object"), buildJsonObject { put("quote", JsonPrimitive("hi there")) }, op("delete_all", "quote" to "hi there")))) },
            "hi there", snapshot(), zone,
        )
        assertTrue(mixed.operations.isEmpty())
        assertEquals(listOf("malformed_op", "missing_verb", "unknown_verb"), mixed.rejections.map { it.reason })
    }

    // ── Helpers exposed for the checks above ─────────────────────────────────

    @Test
    fun `the clause around a quote is where its negation and question mark are looked for`() {
        assertEquals("I never got around to sending that invoice.", OperationParser.clauseContaining("Hello. I never got around to sending that invoice.", "sending that invoice").trim())
        assertEquals("I sent the invoice", OperationParser.clauseContaining("I sent the invoice, but I haven't booked the room.", "I sent the invoice").trim().trimEnd(','))
        assertEquals("a b c. d e f", OperationParser.clauseContaining("a b c. d e f", "c d"), "a quote spanning clauses falls back to the whole message")
    }

    @Test
    fun `dates parse to the zone they were said in`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val utc = OperationParser.parseWhen("2026-09-23T09:00", zone, endOfDayForDateOnly = false)!!
        val local = OperationParser.parseWhen("2026-09-23T09:00", tokyo, endOfDayForDateOnly = false)!!
        assertEquals(9L * 3600_000, utc - LocalDate.parse("2026-09-23").atStartOfDay(zone).toInstant().toEpochMilli())
        assertEquals(9L * 3600_000, local - LocalDate.parse("2026-09-23").atStartOfDay(tokyo).toInstant().toEpochMilli())
        assertNull(OperationParser.parseWhen(null, zone, true))
        assertNull(OperationParser.parseWhen("  ", zone, true))
        assertNull(OperationParser.parseWhen("2026-13-45", zone, true))
    }

    @Test
    fun `every operation survives the stored-plan round trip`() {
        val plan = listOf(
            RecordItem(ItemKind.TASK, "send it", projectUid = "p", projectName = "n", dueAt = 5L, about = listOf("a"), askWhichProject = true),
            ResolveItem("x", "done"), ReopenItem("x", "not done"), CorrectItem("x", "fixed", 9L), FileItem("x", "y", "move"),
            CreateContainer(ContainerKind.DOMAIN, "Home", namedByUser = false, parentUid = null, quote = "q"),
            SetFocus("x", "focus"), ReleaseFocus("out"), SetAttention("x", AttentionDirective.PINNED, "pin"), SetAttention("x", null, "normal"),
            LinkItems("x", "y", "together"), HoldForChoice("I feel it"),
        )
        assertEquals(plan, OperationPlanCodec.decode(OperationPlanCodec.encode(plan)))
        assertNull(OperationPlanCodec.decode("not json"))
        assertNull(OperationPlanCodec.decode(null))
    }
}
