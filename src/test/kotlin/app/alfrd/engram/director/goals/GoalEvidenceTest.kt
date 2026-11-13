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
        assertEquals("c1", got.first().uid, "magic and link are rare, login is in every item")
    }

    @Test
    fun `an item that shares nothing is not evidence however recent and the list is bounded`() {
        val pool = listOf(rec("c1", "Order cable for the garden", ageDays = 0.1)) + (1..10).map { rec("m$it", "Magic link login fix number $it", ageDays = it.toDouble()) }
        val got = GoalEvidence.related(open, pool, GoalEvidence.documentFrequency(pool), now, 4)
        assertEquals(4, got.size)
        assertTrue(got.none { it.uid == "c1" })
        assertEquals(listOf("m1", "m2", "m3", "m4"), got.map { it.uid }, "equally related: the more recent first")
    }

    @Test
    fun `a shared concept counts even with no shared word`() {
        val withConcept = rec("c1", "Handover notes", about = listOf("Ron"))
        val without = rec("c2", "Handover notes")
        val item = rec("o2", "Chase the contract", WorkState.OPEN, about = listOf("ron"))
        val got = GoalEvidence.related(item, listOf(without, withConcept), emptyMap(), now, 4)
        assertEquals(listOf("c1"), got.map { it.uid })
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
        assertEquals(GoalEvidence.related(open, pool, df, now, 4).map { it.uid }, GoalEvidence.related(open, pool.shuffled(java.util.Random(7)), df, now, 4).map { it.uid })
    }

    // ── the step prompt's budget ─────────────────────────────────────────────

    private val goal = rec("g", "Review the open items", WorkState.ACTIVE, ItemKind.GOAL)
    private val step = PlanStep(1, "Review", StepKind.REVIEW, "Work the items against their evidence.", listOf("i1"))

    private fun views(n: Int, evidence: Int, evidenceChars: Int = 200) = (1..n).map { i ->
        GoalPrompts.ItemView("i$i", rec("o$i", "Open item number $i about the magic link ${"x".repeat(100)}", WorkState.OPEN),
            (1..evidence).map { e -> "e${i}_$e" to rec("c${i}_$e", "Closed evidence line $e for item $i ${"y".repeat(evidenceChars)}") })
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
        val ctx = GoalPrompts.step(goal, step, views(8, 0), emptyList(), 3, 12_000, now)
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
}
