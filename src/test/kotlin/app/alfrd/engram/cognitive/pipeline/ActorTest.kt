package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AppliedOperation
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.RecordItem
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventMetadata
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.BoundedText
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonContainerRef
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonContainerView
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonFocusRef
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItemCategory
import app.alfrd.engram.cognitive.pipeline.horizon.ProvenanceKind
import app.alfrd.engram.cognitive.pipeline.horizon.PropagationOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.ReactivationInfo
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
import app.alfrd.engram.cognitive.providers.LlmRequest
import app.alfrd.engram.cognitive.providers.LlmResponse
import app.alfrd.engram.cognitive.providers.TestLlmClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [Actor.compose]'s prompt-budget ladder ([recentTurns] first, then [Conditioners.horizonItems]
 * tail-trimmed but never past the last essential item, then an explicit [PromptDebugInfo]-carrying
 * `Failed` floor) plus [HorizonItemsRenderer]'s pure render/caveat functions — see `Actor.kt`'s
 * `assemblePrompt` doc for the priority order this asserts.
 */
class ActorTest {

    private fun baseConditioners(
        recentTurns: String? = null,
        horizonItems: List<HorizonPromptItem> = emptyList(),
        integrityCaveat: String? = null,
    ) = Conditioners(
        modality = Modality.TEXT,
        responseStrategy = ResponseStrategy.SIMPLE,
        directive = "Respond warmly and concisely.",
        attunement = "Neutral turn, no strong signal.",
        persona = "You are Alfrd.",
        selfDescription = "A calm, attentive assistant.",
        topicConfidence = null,
        mood = "warm",
        recentTurns = recentTurns,
        horizonItems = horizonItems,
        integrityCaveat = integrityCaveat,
    )

    // ---- HorizonItemsRenderer.render: what the structured selection adds ----

    private val dayMs = 86_400_000L

    private fun selectedItem(text: String, surfacing: SurfacingReason, kind: String? = null, askedBy: String? = null) = HorizonItem(
        category = HorizonItemCategory.INTENTION,
        text = BoundedText(text, truncated = false),
        status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = surfacing,
        sourceRefs = emptyList(),
        sourceCount = 1,
        kind = kind,
        askedBy = askedBy,
    )

    private fun renderedAt(asOf: Long, vararg items: HorizonItem) = HorizonItemsRenderer.render(
        ContextHorizon("u@test.alfrd.internal", asOf, 2, items.toList(), HorizonBudget(12, items.size, false), emptyList(), 0, false),
    )

    @Test
    fun `a pinned item says they asked for it, and cannot be dropped under budget pressure`() {
        val rendered = renderedAt(0L, selectedItem("Send the invoice", SurfacingReason.Pinned)).single()
        assertTrue(rendered.renderedLine.contains("they asked you to keep this in front of them"))
        assertTrue(rendered.essential)
    }

    @Test
    fun `an item in the focus names the focus, and is droppable`() {
        val rendered = renderedAt(0L, selectedItem("Book the room", SurfacingReason.InFocus("Demo", FocusZoom.BINOCULARS, 1))).single()
        assertTrue(rendered.renderedLine.contains("part of what they are focused on"), rendered.renderedLine)
        assertTrue(!rendered.essential)
    }

    @Test
    fun `an activated item says what brought it in`() {
        val rendered = renderedAt(0L, selectedItem("My sister is allergic to peanuts", SurfacingReason.Activated("about sister", 2))).single()
        assertTrue(rendered.renderedLine.contains("connected to what they have been talking about (about sister)"), rendered.renderedLine)
    }

    @Test
    fun `a deadline reads in days from when the Horizon was assembled, and is never droppable`() {
        val now = 10 * dayMs
        val lines = renderedAt(
            now,
            selectedItem("Overdue thing", SurfacingReason.Urgent(now - 2 * dayMs - 1_000, overdue = true)),
            selectedItem("Due today", SurfacingReason.Urgent(now + 2 * 3_600_000L, overdue = false)),
            selectedItem("Due tomorrow", SurfacingReason.Urgent(now + dayMs + 3_600_000L, overdue = false)),
            selectedItem("Due Friday", SurfacingReason.Urgent(now + 3 * dayMs + 3_600_000L, overdue = false)),
        ).map { it.renderedLine }

        assertTrue(lines[0].contains("overdue by 3 days"), lines[0])
        assertTrue(lines[1].contains("due today"), lines[1])
        assertTrue(lines[2].contains("due tomorrow"), lines[2])
        assertTrue(lines[3].contains("due in 3 days"), lines[3])
        assertTrue(renderedAt(now, selectedItem("x", SurfacingReason.Urgent(now + dayMs, false))).single().essential)
    }

    @Test
    fun `days are counted on the calendar in the user's zone, not in 24-hour blocks`() {
        val tokyo = java.time.ZoneId.of("Asia/Tokyo")
        // 3 a.m. on the 20th, and something due at midnight starting the 22nd: two calendar days away, though only 45 hours.
        val asOf = java.time.ZonedDateTime.of(2026, 9, 20, 3, 0, 0, 0, tokyo).toInstant().toEpochMilli()
        val dueAt = java.time.ZonedDateTime.of(2026, 9, 22, 0, 0, 0, 0, tokyo).toInstant().toEpochMilli()
        val horizon = ContextHorizon("u@test.alfrd.internal", asOf, 2, listOf(selectedItem("Birthday", SurfacingReason.Urgent(dueAt, false))), HorizonBudget(12, 1, false), emptyList(), 0, false)

        val line = HorizonItemsRenderer.render(horizon, tokyo).single().renderedLine

        assertTrue(line.contains("due in 2 days (2026-09-22)"), line)
    }

    @Test
    fun `an environment report is labeled as outside the conversation`() {
        val rendered = renderedAt(0L, selectedItem("Build finished", SurfacingReason.Peripheral(4))).single()
        assertTrue(rendered.renderedLine.contains("reported recently by the environment, independent of this conversation"))
    }

    @Test
    fun `a question Alfrd is waiting on is marked to be asked, not assumed answered`() {
        val asked = renderedAt(0L, selectedItem("What have you been up to?", SurfacingReason.JustAsserted(1), kind = "question", askedBy = "alfrd")).single()
        val theirs = renderedAt(0L, selectedItem("Should I take the job?", SurfacingReason.JustAsserted(1), kind = "question", askedBy = "user")).single()
        assertTrue(asked.renderedLine.contains("a question you want to ask them; ask it naturally, and do not assume an answer"))
        assertTrue(!theirs.renderedLine.contains("ask it naturally"), "their own open question is not Alfrd's to ask")
    }

    // ---- The Horizon read as places: headings, orientation, time-sensitive guidance ----

    private fun filedItem(text: String, container: HorizonContainerRef?, surfacing: SurfacingReason = SurfacingReason.InFocus(container?.name, FocusZoom.BINOCULARS, 1)) =
        selectedItem(text, surfacing).copy(container = container)

    private val demo = HorizonContainerRef("p1", "Meridian demo", "project")
    private val garden = HorizonContainerRef("p2", "Garden", "project")

    private fun horizonWith(
        asOf: Long,
        items: List<HorizonItem>,
        focus: HorizonFocusRef? = null,
        containers: List<HorizonContainerView> = emptyList(),
    ) = ContextHorizon(
        "u@test.alfrd.internal", asOf, 2, items, HorizonBudget(12, items.size, false), emptyList(), 0, false,
        zoom = focus?.zoom ?: FocusZoom.OVERVIEW, focus = focus, containers = containers,
    )

    @Test
    fun `items are headed by the project they are filed in, the focused one marked, and unfiled and outside reports get their own headings`() {
        val focus = HorizonFocusRef("p1", "Meridian demo", "project", FocusZoom.BINOCULARS)
        val rendered = HorizonItemsRenderer.render(
            horizonWith(
                0L,
                listOf(
                    filedItem("Book the room", demo),
                    filedItem("Water the tomatoes", garden, SurfacingReason.Activated("about garden", 1)),
                    selectedItem("Call the plumber", SurfacingReason.DormantOpen(1)),
                    selectedItem("Build finished", SurfacingReason.Peripheral(4)),
                ),
                focus = focus,
            ),
        )
        assertEquals(
            listOf(
                "In \"Meridian demo\" (project) — what they are focused on",
                "In \"Garden\" (project)",
                "Not filed under any project",
                "Reported from outside this conversation",
            ),
            rendered.map { it.group },
        )
    }

    @Test
    fun `the prompt lists items under their headings, in the order the highest-priority item of each first appears`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val items = HorizonItemsRenderer.render(
            horizonWith(0L, listOf(filedItem("Book the room", demo), selectedItem("Call the plumber", SurfacingReason.DormantOpen(1)), filedItem("Print the agenda", demo))),
        )

        Actor(client).compose("hi", script = null, conditioners = baseConditioners(horizonItems = items))

        val prompt = receivedRequest!!.systemPrompt!!
        val demoHeading = prompt.indexOf("In \"Meridian demo\" (project):")
        val unfiledHeading = prompt.indexOf("Not filed under any project:")
        assertTrue(demoHeading >= 0 && unfiledHeading > demoHeading, prompt)
        assertTrue(prompt.indexOf("Book the room") in demoHeading until unfiledHeading)
        assertTrue(prompt.indexOf("Print the agenda") in demoHeading until unfiledHeading, "both demo items sit under the one heading, though another item ranks between them")
        assertTrue(prompt.indexOf("Call the plumber") > unfiledHeading)
    }

    @Test
    fun `items with no heading are listed flat, as before`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val items = listOf(HorizonPromptItem("\"One\" — a", essential = true), HorizonPromptItem("\"Two\" — b", essential = false))

        Actor(client).compose("hi", script = null, conditioners = baseConditioners(horizonItems = items))

        assertTrue(receivedRequest!!.systemPrompt!!.contains("- \"One\" — a\n- \"Two\" — b"))
    }

    @Test
    fun `trimming still drops from the priority-ordered tail, so a heading is never left empty`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val essential = HorizonPromptItem("\"Send the invoice\" — a", essential = true, group = "In \"Work\" (project)")
        val droppable = List(90) { HorizonPromptItem("\"Filler item $it ${"x".repeat(400)}\" — b", essential = false, group = "Not filed under any project") }

        val result = Actor(client).compose("hi", script = null, conditioners = baseConditioners(horizonItems = listOf(essential) + droppable))

        val prompt = receivedRequest!!.systemPrompt!!
        assertTrue(prompt.contains("Send the invoice"))
        val kept = result.promptDebug!!.horizonItemsIncluded
        assertTrue(kept.size < 91 && kept.first() == essential, "only the tail was trimmed, and the essential item stayed at the head")
        if (kept.none { it.group == "Not filed under any project" }) assertTrue(!prompt.contains("Not filed under any project:"), "a heading with nothing under it must not appear")
    }

    @Test
    fun `orientation says what they are focused on, how closely, and what else they have going`() {
        val now = 10 * dayMs
        val focus = HorizonFocusRef("p1", "Meridian demo", "project", FocusZoom.BINOCULARS)
        val orientation = HorizonItemsRenderer.composeOrientation(
            horizonWith(
                now, emptyList(), focus,
                listOf(
                    HorizonContainerView("p1", "Meridian demo", "project", openItems = 4, itemsInView = 3, nextDueAt = null, inFocus = true),
                    HorizonContainerView("p2", "Garden", "project", openItems = 3, itemsInView = 0),
                    HorizonContainerView("p3", "Home", "project", openItems = 0, itemsInView = 0, nextDueAt = now + 3 * dayMs + 3_600_000L),
                ),
            ),
        )!!
        assertTrue(orientation.contains("focused on the project \"Meridian demo\" (4 open) — looking closely at just that project"), orientation)
        assertTrue(orientation.contains("\"Garden\" (3 open)"), orientation)
        assertTrue(orientation.contains("\"Home\" (nothing open, next: due in 3 days"), orientation)
        assertTrue(!orientation.contains("\"Meridian demo\" (4 open), "), "the focused project is not repeated among the others")
    }

    @Test
    fun `orientation with no focus says it is the broad view, and there is nothing to say when there are no projects either`() {
        val broad = HorizonItemsRenderer.composeOrientation(
            horizonWith(0L, emptyList(), null, listOf(HorizonContainerView("p2", "Garden", "project", openItems = 1, itemsInView = 0))),
        )!!
        assertTrue(broad.contains("no particular focus — the broad view"), broad)
        assertNull(HorizonItemsRenderer.composeOrientation(horizonWith(0L, emptyList())))
    }

    @Test
    fun `the time-sensitive guidance appears only when a deadline item is in view`() {
        val focus = HorizonFocusRef("p1", "Meridian demo", "project", FocusZoom.BINOCULARS)
        val plain = HorizonItemsRenderer.composeOrientation(horizonWith(0L, listOf(filedItem("Book the room", demo)), focus))!!
        val withDeadline = HorizonItemsRenderer.composeOrientation(
            horizonWith(0L, listOf(selectedItem("Sister's birthday", SurfacingReason.Urgent(2 * dayMs, overdue = false))), focus),
        )!!
        assertTrue(!plain.contains("time-sensitive"), plain)
        assertTrue(withDeadline.contains("bring one up when it bears on what they are saying"), withDeadline)
        assertTrue(withDeadline.contains("already mentioned it"), withDeadline)
    }

    private fun deadlineItem(text: String, uid: String, dueAt: Long) =
        selectedItem(text, SurfacingReason.Urgent(dueAt, overdue = false)).copy(sourceRefs = listOf(app.alfrd.engram.cognitive.pipeline.horizon.SourceRef(uid, "src", "conversation", 1L, 1L)))

    @Test
    fun `a deadline already raised is still shown and still undroppable, but told not to be raised again`() {
        val horizon = horizonWith(0L, listOf(deadlineItem("Sister's birthday", "u1", 2 * dayMs), deadlineItem("Pay the invoice", "u2", 3 * dayMs)))

        val rendered = HorizonItemsRenderer.render(horizon, alreadyRaised = setOf("u1"))

        val raised = rendered.first { it.renderedLine.contains("Sister's birthday") }
        val fresh = rendered.first { it.renderedLine.contains("Pay the invoice") }
        assertTrue(raised.renderedLine.contains("due in 2 days") && raised.renderedLine.contains("already reminded them of this recently"), raised.renderedLine)
        assertTrue(raised.essential, "it must stay answerable when they ask")
        assertTrue(!fresh.renderedLine.contains("already reminded"), fresh.renderedLine)
    }

    @Test
    fun `urgentDeadlines names each deadline item's source, words and closeness as the prompt words it`() {
        val horizon = horizonWith(0L, listOf(deadlineItem("Sister's birthday", "u1", 2 * dayMs), filedItem("Book the room", demo)))

        val urgent = HorizonItemsRenderer.urgentDeadlines(horizon)

        assertEquals(listOf(HorizonItemsRenderer.UrgentDeadline("u1", "Sister's birthday", "due in 2 days (1970-01-03)")), urgent)
    }

    @Test
    fun `the time-sensitive guidance is offered for a user with no projects and no focus, and dropped once every deadline was raised`() {
        val horizon = horizonWith(0L, listOf(deadlineItem("Sister's birthday", "u1", 2 * dayMs)))

        val fresh = HorizonItemsRenderer.composeOrientation(horizon)
        assertNotNull(fresh, "a deadline in view is worth an orientation even with no projects")
        assertTrue(fresh!!.contains("time-sensitive") && !fresh.contains("no particular focus"), fresh)
        assertNull(HorizonItemsRenderer.composeOrientation(horizon, alreadyRaised = setOf("u1")), "nothing left to say once it has been raised")
    }

    @Test
    fun `the orientation heads the Horizon block, and a user with projects but nothing in view still gets it`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val frame = "Where they are right now: no particular focus — the broad view.\nWhat else they have going: \"Garden\" (1 open)."

        Actor(client).compose("hi", script = null, conditioners = baseConditioners(horizonItems = emptyList()).copy(horizonFrame = frame))

        val prompt = receivedRequest!!.systemPrompt!!
        assertTrue(prompt.contains("Contextual awareness"))
        assertTrue(prompt.contains(frame))
    }

    // ---- HorizonItemsRenderer.render ----

    private fun justAssertedItem(text: String, cycleSeq: Long) = HorizonItem(
        category = HorizonItemCategory.FACT,
        text = BoundedText(text, truncated = false),
        status = null,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.JustAsserted(cycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun dormantOpenItem(text: String, lastChangeCycleSeq: Long) = HorizonItem(
        category = HorizonItemCategory.INTENTION,
        text = BoundedText(text, truncated = false),
        status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.DormantOpen(lastChangeCycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun activeReactivationItem(text: String, triggeringText: String) = HorizonItem(
        category = HorizonItemCategory.INTENTION,
        text = BoundedText(text, truncated = false),
        status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.ActiveReactivation(
            ReactivationInfo(
                triggeringPhraseUid = "trigger-uid",
                triggeringPhraseText = BoundedText(triggeringText, truncated = false),
                strength = 0.5,
                edgeCycleSeq = 3,
                edgeCreatedAt = 0L,
            ),
        ),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun recentActorEvidenceItem(text: String, assertedCycleSeq: Long) = HorizonItem(
        category = HorizonItemCategory.FACT,
        text = BoundedText(text, truncated = false),
        status = null,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.ACTOR_OBSERVATION,
        surfacing = SurfacingReason.RecentActorEvidence(assertedCycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
    )

    private fun interpretationItem(text: String, assertedCycleSeq: Long, basis: String?) = HorizonItem(
        category = HorizonItemCategory.FACT,
        text = BoundedText(text, truncated = false),
        status = null,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.ACTOR_INTERPRETATION,
        surfacing = SurfacingReason.RecentActorEvidence(assertedCycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
        actorMetadata = ActorEventMetadata(basis = basis),
    )

    private fun toolResultItem(text: String, assertedCycleSeq: Long, toolSucceeded: Boolean?) = HorizonItem(
        category = HorizonItemCategory.FACT,
        text = BoundedText(text, truncated = false),
        status = null,
        attentionDirective = null as AttentionDirective?,
        provenance = ProvenanceKind.ACTOR_TOOL_RESULT,
        surfacing = SurfacingReason.JustAsserted(assertedCycleSeq),
        sourceRefs = emptyList(),
        sourceCount = 1,
        actorMetadata = ActorEventMetadata(toolName = "deploy_service", toolSucceeded = toolSucceeded),
    )

    @Test
    fun `render marks RecentActorEvidence droppable, framing distinct from DormantOpen`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(recentActorEvidenceItem("Arx build finished compiling", assertedCycleSeq = 5)),
            budget = HorizonBudget(maxItems = 12, itemCount = 1, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )

        val rendered = HorizonItemsRenderer.render(horizon)

        assertEquals(1, rendered.size)
        assertTrue(!rendered[0].essential, "RecentActorEvidence must be droppable under budget pressure, exactly like DormantOpen — bounded-recency eligibility is not a guarantee of a rendered place")
        assertTrue(rendered[0].renderedLine.contains("reported recently, independent of this conversation"))
        assertTrue(!rendered[0].renderedLine.contains("noted earlier, still open"), "must never be confused with DormantOpen's framing — it is not an open intention")
    }

    @Test
    fun `render marks ActiveReactivation and JustAsserted essential, DormantOpen droppable, order preserved`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(
                activeReactivationItem("Arx build is ready", "Arx developer build finished compiling"),
                justAssertedItem("Newton is my dog", cycleSeq = 5),
                dormantOpenItem("Weekly backup pending review", lastChangeCycleSeq = 1),
            ),
            budget = HorizonBudget(maxItems = 12, itemCount = 3, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )

        val rendered = HorizonItemsRenderer.render(horizon)

        assertEquals(3, rendered.size)
        assertTrue(rendered[0].essential, "ActiveReactivation must be essential")
        assertTrue(rendered[0].renderedLine.contains("Arx developer build finished compiling"), "must surface the triggering evidence text")
        assertTrue(rendered[1].essential, "JustAsserted must be essential")
        assertTrue(!rendered[2].essential, "DormantOpen must be droppable")
        assertTrue(rendered[2].renderedLine.contains("noted earlier, still open"))
    }

    @Test
    fun `ActiveReactivation framing never implies shared authorship between the reactivated item and its trigger`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(activeReactivationItem("I need to submit the Q3 budget review", "Hermes reported an unrelated document's own goal")),
            budget = HorizonBudget(maxItems = 12, itemCount = 1, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )

        val line = HorizonItemsRenderer.render(horizon).single().renderedLine

        assertTrue(
            !line.contains("made this relevant again"),
            "must not use causal-sounding wording a model could read as the two items sharing a source, got: $line",
        )
        assertTrue(
            line.contains("does not mean they share a source or confirm each other"),
            "must state plainly that the computed link is not itself a claim of shared authorship, got: $line",
        )
    }

    @Test
    fun `render distinguishes observation, interpretation with basis, and tool result success-failure, regardless of surfacing reason`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(
                recentActorEvidenceItem("Arx build finished compiling", assertedCycleSeq = 5),
                interpretationItem("the user seems blocked on deploy", assertedCycleSeq = 5, basis = "no commits in 3 days"),
                interpretationItem("something changed", assertedCycleSeq = 5, basis = null),
                toolResultItem("deploy_service completed", assertedCycleSeq = 6, toolSucceeded = true),
                toolResultItem("deploy_service completed", assertedCycleSeq = 6, toolSucceeded = false),
            ),
            budget = HorizonBudget(maxItems = 12, itemCount = 5, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )

        val rendered = HorizonItemsRenderer.render(horizon)

        assertEquals(5, rendered.size)
        assertTrue(rendered[0].renderedLine.contains("[Actor-observed]"), "an observation must be tagged distinctly from an interpretation or tool result")
        assertTrue(rendered[1].renderedLine.contains("[Actor interpretation, based on: \"no commits in 3 days\"]"), "an interpretation must carry its basis")
        assertTrue(rendered[2].renderedLine.contains("[Actor interpretation]"), "an interpretation with no recorded basis still reads as an interpretation, never silently as fact")
        assertTrue(rendered[3].renderedLine.contains("reported success"), "a tool result must read as a self-reported claim")
        assertTrue(rendered[3].renderedLine.contains("not independently verified"))
        assertTrue(rendered[4].renderedLine.contains("reported failure"), "a failed tool result must be distinguishable from a successful one, never silently dropped")
        assertTrue(rendered[4].renderedLine.contains("not independently verified"))
        // The distinction must survive regardless of which SurfacingReason produced the framing —
        // these items use two different surfacing reasons (RecentActorEvidence, JustAsserted) above.
        assertTrue(!rendered[0].renderedLine.contains("[Actor interpretation"), "observation must never read as interpretation")
        assertTrue(!rendered[3].renderedLine.contains("[Actor-observed]"), "a tool result must never read as a plain observation")
    }

    @Test
    fun `render never tags an ordinary user-stated or environment-signal item with an Actor provenance note`() {
        val horizon = ContextHorizon(
            userEmail = "u@test.alfrd.internal",
            asOf = 0L,
            schemaVersion = 1,
            items = listOf(justAssertedItem("Newton is my dog", cycleSeq = 5)),
            budget = HorizonBudget(maxItems = 12, itemCount = 1, truncated = false),
            omittedSample = emptyList(),
            omittedAtLeast = 0,
            moreCandidatesAvailable = false,
        )
        val rendered = HorizonItemsRenderer.render(horizon)
        assertTrue(!rendered[0].renderedLine.contains("Actor"), "an explicit user statement must never be mistaken for Actor-attributed evidence")
    }

    // ---- HorizonItemsRenderer.composeIntegrityCaveat ----

    @Test
    fun `composeIntegrityCaveat is null when result is null`() {
        assertNull(HorizonItemsRenderer.composeIntegrityCaveat(null))
    }

    @Test
    fun `composeIntegrityCaveat is null when everything applied and nothing failed`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(MutationOutcome.Fact("p1", applied = true), MutationOutcome.Operation(AppliedOperation(RecordItem(ItemKind.TASK, "priority"), applied = true, summary = "Recorded task: \"priority\"", resultUid = "p2"))),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        assertNull(HorizonItemsRenderer.composeIntegrityCaveat(result))
    }

    @Test
    fun `a failed operation caveats only that operation, a successful fact is never gagged`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(
                MutationOutcome.Fact("p1", applied = true),
                MutationOutcome.Operation(AppliedOperation(RecordItem(ItemKind.TASK, "getting Arx running is a priority"), applied = false, summary = "Could not record task \"getting Arx running is a priority\"")),
            ),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat)
        assertTrue(caveat!!.contains("getting Arx running is a priority"))
        assertTrue(caveat.contains("NOT confirmed"))
        assertTrue(!caveat.contains("Something from this turn"), "the successful fact must not be caveated")
    }

    @Test
    fun `a failed fact write produces a general non-recorded caveat`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = listOf(MutationOutcome.Fact("p1", applied = false)),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat)
        assertTrue(caveat!!.contains("not confirmed recorded"))
    }

    @Test
    fun `an allocation failure produces its own visible caveat, mutationOutcomes being empty is not read as success`() {
        val result = HorizonCycleResult.allocationFailed()
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "nothing was captured this cycle — that must never be silent")
        assertTrue(caveat!!.contains("Nothing from this turn could be confirmed recorded"))
    }

    @Test
    fun `an LlmFailure interpretation caveats that something may have gone unrecorded, not just silence`() {
        val result = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = InterpretOutcome.LlmFailure("timeout"),
            mutationOutcomes = listOf(MutationOutcome.Fact("p1", applied = true)),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(result)
        assertNotNull(caveat, "an interpreter failure must not be indistinguishable from a quiet turn with nothing to say")
        assertTrue(caveat!!.contains("could not be fully interpreted"))
        assertTrue(!caveat.contains("not confirmed recorded"), "the successful fact write must not also be caveated")
    }

    @Test
    fun `propagation failure and non-Assembled outcomes each add an honest contextual-awareness caveat`() {
        val propagationFailedResult = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Failed("boom"),
            assembleOutcome = null,
        )
        val caveat1 = HorizonItemsRenderer.composeIntegrityCaveat(propagationFailedResult)
        assertNotNull(caveat1)
        assertTrue(caveat1!!.contains("missing some"))

        val assembleFailedResult = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Propagated(emptyList()),
            assembleOutcome = AssembleOutcome.QueryFailure("boom"),
        )
        val caveat2 = HorizonItemsRenderer.composeIntegrityCaveat(assembleFailedResult)
        assertNotNull(caveat2)
        assertTrue(caveat2!!.contains("missing some"))
    }

    @Test
    fun `a Propagated outcome with non-empty incompleteTargets caveats exactly like a Failed outcome`() {
        // Before this fix, composeIntegrityCaveat only recognized PropagationOutcome.Failed — a
        // Propagated outcome that left some markRelevant attempts incomplete (a real, partial write
        // failure — see PropagationOutcome.Propagated's own doc) silently read as full success.
        val partiallyIncompleteResult = HorizonCycleResult(
            cycleSeq = 1,
            interpretOutcome = null,
            mutationOutcomes = emptyList(),
            propagationOutcome = PropagationOutcome.Propagated(
                edgesCreated = listOf(app.alfrd.engram.cognitive.pipeline.horizon.RelevanceEdgeSummary("p1", "p2", 1.0)),
                incompleteTargets = listOf(app.alfrd.engram.cognitive.pipeline.horizon.RelevanceEdgeSummary("p1", "p3", 0.5)),
            ),
            assembleOutcome = null,
        )
        val caveat = HorizonItemsRenderer.composeIntegrityCaveat(partiallyIncompleteResult)
        assertNotNull(caveat, "a partially incomplete propagation must be caveated exactly like a fully failed one")
        assertTrue(caveat!!.contains("missing some"))

        val fullyCompleteResult = partiallyIncompleteResult.copy(
            propagationOutcome = PropagationOutcome.Propagated(
                edgesCreated = listOf(app.alfrd.engram.cognitive.pipeline.horizon.RelevanceEdgeSummary("p1", "p2", 1.0)),
                incompleteTargets = emptyList(),
            ),
        )
        assertNull(HorizonItemsRenderer.composeIntegrityCaveat(fullyCompleteResult), "a fully completed propagation must not be caveated")
    }

    // ---- Actor.compose budget ladder ----

    // ── Standing context policy (Director/Hermes role, Horizon framing) ────────

    @Test
    fun `the standing context policy is present even with an empty Horizon`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        val conditioners = baseConditioners(recentTurns = null, horizonItems = emptyList())

        actor.compose("hi", script = null, conditioners = conditioners)

        val systemPrompt = receivedRequest!!.systemPrompt!!
        assertTrue(systemPrompt.contains("conversational Director"), "must state alfrd's Director role even with no Horizon items")
        assertTrue(systemPrompt.contains("Hermes"), "must name Hermes's subordinate-Actor relationship even with no Horizon items")
        assertTrue(systemPrompt.contains("never an exhaustive memory inventory"), "must state the Horizon is a selected excerpt even with no Horizon items")
        assertTrue(systemPrompt.contains("Missing evidence does not establish that something never happened"))
        assertTrue(systemPrompt.contains("only the user's explicit adoption of it does"))
        assertTrue(
            systemPrompt.contains("\"The document describes X; you told me you intend Y\""),
            "must name the concrete two-clause surface form, not just the abstract principle, even with no Horizon items",
        )
        // No Horizon items were supplied, so the conditional "Contextual awareness" header must not appear at all.
        assertTrue(!systemPrompt.contains("Contextual awareness"))
    }

    @Test
    fun `the standing context policy is present exactly once alongside real Horizon items, never duplicated`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req -> receivedRequest = req; LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        val items = listOf(HorizonPromptItem("\"Arx build is ready\" — new evidence just made this relevant again", essential = true))
        val conditioners = baseConditioners(recentTurns = null, horizonItems = items)

        actor.compose("hi", script = null, conditioners = conditioners)

        val systemPrompt = receivedRequest!!.systemPrompt!!
        assertTrue(systemPrompt.contains("conversational Director"))
        assertTrue(systemPrompt.contains("Contextual awareness"), "the Horizon header still renders when items exist")
        // The "use it naturally, don't recite everything" guidance must appear exactly once — from
        // the standing policy — never duplicated by the (now-shortened) conditional Horizon header.
        val occurrences = Regex(Regex.escape("without reciting everything available")).findAll(systemPrompt).count()
        assertEquals(1, occurrences, "the 'use naturally, don't recite everything' guidance must not be duplicated")
        assertTrue(!systemPrompt.contains("mention only what's genuinely relevant"), "the old, now-redundant per-cycle wording must be gone")
    }

    @Test
    fun `everything fits, prompt debug reports Fits with no omissions and all horizon items included`() = runBlocking {
        var receivedRequest: LlmRequest? = null
        val client = TestLlmClient { req ->
            receivedRequest = req
            LlmResponse(text = "The grocery app layout sounds solid.", latencyMs = 1L, retryCount = 0)
        }
        val actor = Actor(client)
        val items = listOf(HorizonPromptItem("\"Arx build is ready\" — new evidence just made this relevant again", essential = true))
        val conditioners = baseConditioners(recentTurns = "user: hi\nalfrd: hello!", horizonItems = items)

        val result = actor.compose("What do you think of this grocery app layout?", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("The grocery app layout sounds solid.", result.text)
        assertNotNull(result.promptDebug)
        assertEquals("Fits", result.promptDebug!!.outcome)
        assertTrue(result.promptDebug!!.omissions.isEmpty())
        assertEquals(items, result.promptDebug!!.horizonItemsIncluded)
        assertNotNull(receivedRequest)
        assertTrue(receivedRequest!!.systemPrompt!!.contains("Arx build is ready"))
    }

    @Test
    fun `an oversized recentTurns is dropped first, everything else including horizon items survives`() = runBlocking {
        val client = TestLlmClient { LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        // Alone this pushes the system prompt well past the 32,000-char budget; nothing else here is large.
        val hugeRecentTurns = "x".repeat(40_000)
        val items = listOf(HorizonPromptItem("a small horizon note", essential = false))
        val conditioners = baseConditioners(recentTurns = hugeRecentTurns, horizonItems = items)

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("Degraded", result.promptDebug!!.outcome)
        assertEquals(listOf("recentTurns"), result.promptDebug!!.omissions)
        assertEquals(items, result.promptDebug!!.horizonItemsIncluded, "horizon items are untouched when dropping recentTurns alone is enough")
        assertTrue(!result.promptDebug!!.finalSystemPrompt!!.contains(hugeRecentTurns))
    }

    @Test
    fun `dormant horizon items are trimmed from the tail one at a time, essential items are never dropped`() = runBlocking {
        val client = TestLlmClient { LlmResponse(text = "ok", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        val essential = HorizonPromptItem("essential: new evidence just made this relevant again", essential = true)
        // Each dormant item alone, added to the small essential-only prompt, already exceeds the
        // 32,000-char budget — so the ladder cannot stop early with any dormant item still present;
        // it must keep dropping from the tail until only the essential item remains.
        val dormant = (1..5).map { HorizonPromptItem("dormant note #$it: ".plus("y".repeat(34_000)), essential = false) }
        val items = listOf(essential) + dormant
        val conditioners = baseConditioners(recentTurns = null, horizonItems = items)

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("llm", result.source)
        assertEquals("Degraded", result.promptDebug!!.outcome)
        // recentTurns was already null, so the ladder's recentTurns-drop attempt is a documented
        // no-op step — it still records the attempt before moving on to horizon-item trimming.
        assertTrue(result.promptDebug!!.omissions.drop(1).all { it == "horizon item (dormant, lowest priority)" })
        assertTrue(result.promptDebug!!.omissions.count { it == "horizon item (dormant, lowest priority)" } == dormant.size)
        assertEquals(listOf(essential), result.promptDebug!!.horizonItemsIncluded, "only the essential item should survive full dormant trimming")
        assertTrue(result.promptDebug!!.finalSystemPrompt!!.contains("essential: new evidence"))
    }

    @Test
    fun `when even essential-only content does not fit, assembly fails explicitly rather than truncating silently`() = runBlocking {
        var llmCalled = false
        val client = TestLlmClient { llmCalled = true; LlmResponse(text = "should never be reached", latencyMs = 1L, retryCount = 0) }
        val actor = Actor(client)
        // The essential item alone is larger than the whole budget — dropping every dormant item cannot help.
        val hugeEssential = HorizonPromptItem("essential note: ".plus("z".repeat(40_000)), essential = true)
        val conditioners = baseConditioners(recentTurns = null, horizonItems = listOf(hugeEssential))

        val result = actor.compose("short utterance", script = null, conditioners = conditioners)

        assertEquals("degraded", result.source)
        assertEquals(Actor.DEGRADED_TEXT, result.text)
        assertNotNull(result.promptDebug)
        assertTrue(result.promptDebug!!.outcome.startsWith("Failed"), "expected a Failed outcome, got ${result.promptDebug!!.outcome}")
        assertNull(result.promptDebug!!.finalSystemPrompt)
        assertNull(result.promptDebug!!.finalUserPrompt)
        assertTrue(!llmCalled, "the LLM must never be called once assembly has already failed the essential-only floor")
    }

    @Test
    fun `no LLM client configured degrades without ever assembling a prompt`() = runBlocking {
        val actor = Actor(null)
        val result = actor.compose("hi", script = null, conditioners = baseConditioners())
        assertEquals("degraded", result.source)
        assertEquals(Actor.DEGRADED_TEXT, result.text)
        assertNull(result.promptDebug, "no prompt was ever assembled — nothing to report")
    }
}
