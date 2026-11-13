package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Evidence retrieval and the step prompt's budget, with no graph and no model: the same inputs give the same lists. */
class GoalEvidenceTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L

    private fun rec(uid: String, text: String, state: WorkState? = WorkState.DONE, kind: ItemKind = ItemKind.TASK, ageDays: Double = 10.0, about: List<String> = emptyList(), director: String? = null) =
        ItemRecord(
            uid = uid, kind = kind, text = text, status = if (state == WorkState.DONE || state == WorkState.DROPPED) "resolved" else "open", dueAt = null, projectUid = "p",
            createdAt = now - (ageDays * day).toLong(), about = about, state = state, lastEvidenceAt = now - (ageDays * day).toLong(), directorGoalUid = director,
        )

    private val open = rec("o1", "Fix the magic link login for Ron", WorkState.OPEN)

    @Test
    fun `the item sharing a rare word outranks the one sharing only a common word`() {
        val pool = listOf(
            rec("c1", "Magic link login shipped to Ron", ageDays = 30.0),
            rec("c2", "Login page colours", ageDays = 1.0),
            rec("c3", "Login timeout raised", ageDays = 2.0),
            rec("c4", "Login audit log added", ageDays = 3.0),
        )
        val df = GoalEvidence.documentFrequency(pool)
        val got = GoalEvidence.related(open, pool, df, now, 4)
        assertEquals("c1", got.first().item.uid, "magic and link are rare, login is in every item")
    }

    @Test
    fun `an item that shares nothing is not evidence however recent and the list is bounded`() {
        val pool = listOf(rec("c1", "Order cable for the garden", ageDays = 0.1)) + (1..10).map { rec("m$it", "Magic link login fix number $it", ageDays = it.toDouble()) }
        val got = GoalEvidence.related(open, pool, GoalEvidence.documentFrequency(pool), now, 4)
        assertEquals(4, got.size)
        assertTrue(got.none { it.item.uid == "c1" })
        assertEquals(listOf("m1", "m2", "m3", "m4"), got.map { it.item.uid }, "equally related: the more recent first")
    }

    @Test
    fun `a shared concept counts even with no shared word`() {
        val withConcept = rec("c1", "Handover notes", about = listOf("Ron"))
        val without = rec("c2", "Handover notes")
        val item = rec("o2", "Chase the contract", WorkState.OPEN, about = listOf("ron"))
        val got = GoalEvidence.related(item, listOf(without, withConcept), emptyMap(), now, 4)
        assertEquals(listOf("c1"), got.map { it.item.uid })
    }

    @Test
    fun `the pool is the owner's closed items and notes and never the Director's own writing or an open item`() {
        val items = listOf(
            rec("done", "Magic link done"), rec("dropped", "Magic link dropped", WorkState.DROPPED), rec("note", "Magic link note", null, ItemKind.FACT),
            rec("open", "Magic link open", WorkState.OPEN), rec("dir", "Magic link by the Director", director = "g1"), rec("goal", "Magic link goal", WorkState.ACTIVE, ItemKind.GOAL),
        )
        assertEquals(setOf("done", "dropped", "note"), GoalEvidence.pool(items).map { it.uid }.toSet())
        assertTrue(GoalEvidence.isOpen(items[3]) && !GoalEvidence.isOpen(items[0]) && !GoalEvidence.isOpen(items[2]) && !GoalEvidence.isOpen(items[5]))
    }

    @Test
    fun `retrieval is deterministic`() {
        val pool = (1..30).map { rec("c$it", "Magic link login item $it", ageDays = (it % 5).toDouble()) }
        val df = GoalEvidence.documentFrequency(pool)
        assertEquals(GoalEvidence.related(open, pool, df, now, 4).map { it.item.uid }, GoalEvidence.related(open, pool.shuffled(java.util.Random(7)), df, now, 4).map { it.item.uid })
    }

    // ── the step prompt's budget ─────────────────────────────────────────────

    private val goal = rec("g", "Review the open items", WorkState.ACTIVE, ItemKind.GOAL)
    private val step = PlanStep(1, "Review", StepKind.REVIEW, "Work the items against their evidence.", listOf("i1"))

    private fun views(n: Int, evidence: Int, evidenceChars: Int = 200) = (1..n).map { i ->
        GoalPrompts.ItemView("i$i", rec("o$i", "Open item number $i about the magic link ${"x".repeat(100)}", WorkState.OPEN),
            (1..evidence).map { e -> GoalPrompts.EvidenceLine("e${i}_$e", rec("c${i}_$e", "Closed evidence line $e for item $i ${"y".repeat(evidenceChars)}"), 3.0) })
    }

    @Test
    fun `a step prompt stays within its budget and says what was left out`() {
        val ctx = GoalPrompts.step(goal, step, views(8, 4), emptyList(), 5, 3_000, now)
        assertTrue(ctx.prompt.length <= 3_000 + 900, "within the budget (plus the fixed frame): ${ctx.prompt.length}")
        assertTrue(ctx.leftOutItems.isNotEmpty() || ctx.evidenceTrimmed > 0, "something had to go")
        assertTrue(ctx.prompt.contains("LEFT OUT FOR ROOM"))
        ctx.leftOutItems.forEach { assertTrue(it !in ctx.shownRefs, "a left-out item is not citable") }
        assertTrue(ctx.shownRefs.any { it.startsWith("i") }, "at least the first item fits")
    }

    @Test
    fun `with room for everything nothing is left out and nothing is said about it`() {
        val ctx = GoalPrompts.step(goal, step, views(2, 2, 20), emptyList(), 5, 12_000, now)
        assertTrue(ctx.leftOutItems.isEmpty() && ctx.evidenceTrimmed == 0)
        assertFalse(ctx.prompt.contains("LEFT OUT FOR ROOM"))
        assertEquals(setOf("i1", "i2", "e1_1", "e1_2", "e2_1", "e2_2"), ctx.shownRefs)
    }

    @Test
    fun `at most the item limit is shown and the rest are named as left out`() {
        val ctx = GoalPrompts.step(goal, step, views(8, 0), emptyList(), 5, 12_000, now, 3)
        assertEquals(3, ctx.shownRefs.count { it.startsWith("i") })
        assertEquals(5, ctx.leftOutItems.size)
        assertTrue(ctx.prompt.contains("open items i4"))
    }

    @Test
    fun `a ref outside what the step was shown is dropped from its answer`() {
        val scope = ScopeMap(listOf(rec("o1", "One", WorkState.OPEN), rec("o2", "Two", WorkState.OPEN)))
        val e = scope.evidenceRef(rec("c1", "Closed"))
        val input = kotlinx.serialization.json.Json.parseToJsonElement("""{"outcome":"done","summary":"s","impact":"none","items":[{"kind":"note","text":"A finding","about":"i1","support":["$e","i2","i1"]}]}""") as kotlinx.serialization.json.JsonObject
        val result = GoalStepValidator.parse(input, 5, scope, setOf("i1", e), CallUsage.NONE)
        assertEquals("i1", result.items.single().aboutRef)
        assertEquals(listOf(e), result.items.single().supportRefs, "i2 was not shown; i1 is the item itself")
    }

    // ── relevance: the minimum and the words that are everywhere ─────────────

    @Test
    fun `a weak match is not evidence when a minimum is set`() {
        val pool = (1..8).map { rec("c$it", "Login page number $it") } + rec("strong", "Magic link login shipped")
        val df = GoalEvidence.documentFrequency(pool)
        val all = GoalEvidence.related(open, pool, df, now, 10)
        val filtered = GoalEvidence.related(open, pool, df, now, 10, minRelevance = 1.5)
        assertTrue(all.size > filtered.size, "the minimum removes the items that share only a common word")
        assertEquals(listOf("strong"), filtered.map { it.item.uid })
        assertTrue(filtered.all { it.relevance >= 1.5 })
    }

    @Test
    fun `words in more than a quarter of the project's items are ignored`() {
        val projectItems = (1..24).map { rec("p$it", "Williams item number $it about something else") } + rec("x", "Magic link login shipped")
        val common = GoalEvidence.commonWords(projectItems)
        assertTrue("williams" in common && "magic" !in common)
        val item = rec("o", "Williams chase", WorkState.OPEN)
        val pool = listOf(rec("c1", "Williams parking permit", WorkState.DONE))
        assertEquals(1, GoalEvidence.related(item, pool, GoalEvidence.documentFrequency(pool), now, 4).size, "connected through the project's own name")
        assertTrue(GoalEvidence.related(item, pool, GoalEvidence.documentFrequency(pool, common), now, 4, common).isEmpty(), "not once that name is ignored")
        assertTrue(GoalEvidence.commonWords(projectItems.take(10)).isEmpty(), "a tiny project has no common words")
    }

    @Test
    fun `an item with no qualifying evidence is told so plainly`() {
        val view = GoalPrompts.ItemView("i1", open, emptyList())
        val ctx = GoalPrompts.step(goal, step, listOf(view), emptyList(), 5, 12_000, now)
        assertTrue(ctx.prompt.contains("NO EVIDENCE FOUND"))
        assertTrue(ctx.prompt.contains("unclassifiable"))
        assertEquals(setOf("i1"), ctx.shownRefs)
        assertEquals(emptySet<String>(), ctx.evidenceFor["i1"])
    }

    @Test
    fun `a concept every item shares counts for little and a rare concept still counts`() {
        val everywhere = (1..24).map { rec("p$it", "Unrelated thing number $it", about = listOf("Williams")) } + rec("rare", "Handover notes", about = listOf("Ron", "Williams"))
        val common = GoalEvidence.commonWords(everywhere)
        assertTrue("concept:williams" in common && "concept:ron" !in common)
        val item = rec("o", "Chase the contract", WorkState.OPEN, about = listOf("Williams", "Ron"))
        val pool = everywhere.filter { it.uid.startsWith("p") }.take(5) + rec("rare", "Handover notes", about = listOf("Ron", "Williams"))
        val got = GoalEvidence.related(item, pool, GoalEvidence.documentFrequency(pool, common), now, 10, common, 1.5)
        assertEquals(listOf("rare"), got.map { it.item.uid }, "only the item that shares the rare concept; the shared project name connects nothing")
    }
}
