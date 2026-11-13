package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The claim check, the ask shape and the hand-over rule, pure: no graph and no model, and none of them reads a setting. */
class GoalClaimsTest {
    private fun rec(uid: String, state: WorkState?, text: String = "Item $uid", kind: ItemKind = ItemKind.TASK) =
        ItemRecord(uid = uid, kind = kind, text = text, status = if (state == WorkState.DONE || state == WorkState.DROPPED) "resolved" else "open", dueAt = null, projectUid = "p", createdAt = 1L, state = state)

    private val scope = ScopeMap(listOf(rec("o1", WorkState.OPEN), rec("o2", WorkState.OPEN)))
    private val eDone = scope.evidenceRef(rec("d1", WorkState.DONE))
    private val eDropped = scope.evidenceRef(rec("d2", WorkState.DROPPED))
    private val eOpenish = scope.evidenceRef(rec("d3", WorkState.WAITING))
    private val eNote = scope.evidenceRef(rec("n1", null, kind = ItemKind.FACT))
    private val eNotShownHere = scope.evidenceRef(rec("d4", WorkState.DONE))
    // i1 was shown with the done item, the dropped one and the waiting one; i2 only with a note. d4 was shown with nothing.
    private val shown = GoalClaims.Shown(mapOf("i1" to setOf(eDone, eDropped, eOpenish), "i2" to setOf(eNote)), scope, 1.5)

    private fun check(claim: Disposition?, about: String?, support: List<String>, none: Boolean = false) = GoalClaims.check(claim, about, support, none, shown)

    @Test
    fun `done stands only with a done item that was shown with that open item`() {
        assertTrue(check(Disposition.DONE, "i1", listOf(eDone)).stands)
        assertFalse(check(Disposition.DONE, "i1", emptyList()).stands, "nothing cited")
        assertFalse(check(Disposition.DONE, "i1", listOf(eDropped)).stands, "a dropped item does not show it was done")
        assertFalse(check(Disposition.DONE, "i1", listOf(eOpenish)).stands, "a waiting item does not either")
        assertFalse(check(Disposition.DONE, "i1", listOf("i2")).stands, "an open item is not evidence")
        assertFalse(check(Disposition.DONE, "i2", listOf(eDone)).stands, "that done item was shown with i1, not i2")
        assertFalse(check(Disposition.DONE, "i1", listOf(eNotShownHere)).stands, "a done item the step was not shown")
        assertFalse(check(Disposition.DONE, "i1", listOf("e99")).stands, "a ref that means nothing")
        assertFalse(check(Disposition.DONE, "i1", listOf(eNote)).stands, "a note shown with another item")
        assertFalse(check(Disposition.DONE, null, listOf(eDone)).stands, "no open item named")
        assertFalse(check(Disposition.DONE, "i1", emptyList(), none = true).stands, "'no evidence found' does not support done")
    }

    @Test
    fun `dropped needs a dropped item and a done one does not do`() {
        assertTrue(check(Disposition.DROPPED, "i1", listOf(eDropped)).stands)
        assertFalse(check(Disposition.DROPPED, "i1", listOf(eDone)).stands)
        assertFalse(check(Disposition.DROPPED, "i1", emptyList(), none = true).stands)
    }

    @Test
    fun `waiting and live need a cited related line or an explicit no evidence found`() {
        for (d in listOf(Disposition.WAITING, Disposition.LIVE)) {
            assertTrue(check(d, "i2", listOf(eNote)).stands, "a related note shown with it")
            assertTrue(check(d, "i1", emptyList(), none = true).stands, "an explicit no evidence found")
            assertFalse(check(d, "i1", emptyList()).stands, "neither")
            assertFalse(check(d, "i2", listOf(eDone)).stands, "a line not shown with that item")
        }
    }

    @Test
    fun `unclassifiable and no claim always stand`() {
        assertTrue(check(Disposition.UNCLASSIFIABLE, null, emptyList()).stands)
        assertTrue(check(null, null, emptyList()).stands)
    }

    @Test
    fun `a claim written into the text without the field is still a claim`() {
        assertEquals(Disposition.DONE, GoalClaims.inferClaim("Proposed disposition for i4: done. Supporting line: e1"))
        assertEquals(Disposition.DROPPED, GoalClaims.inferClaim("Disposition: dropped. Duplicate of i4."))
        assertEquals(Disposition.DONE, GoalClaims.inferClaim("Mark i7 done, the schema is finished"))
        assertEquals(null, GoalClaims.inferClaim("The invoice call looks still live"))
        assertEquals(Disposition.DONE, GoalClaims.inferClaim("Likely done: the report was sent"), "no word of doubt: it is a claim")
        assertEquals(Disposition.DONE, GoalClaims.inferClaim("i9 (Status: In Progress) is done — evidence e11, e12 show the dashboard was built"), "a plain statement with no disposition field")
        assertEquals(Disposition.DROPPED, GoalClaims.inferClaim("Duplicate of i4: both concern magic-link login"))
        assertEquals(null, GoalClaims.inferClaim("Related admin portal work is done (e1, e2), but no direct evidence this follow-on task is complete"), "doubt is not a claim")
        assertEquals(null, GoalClaims.inferClaim("i12 is waiting; the Daily Utility form work is done but the barcodes are not"), "waiting is not done")
        assertEquals(Disposition.DONE, Disposition.of("done")); assertEquals(Disposition.LIVE, Disposition.of("still live")); assertEquals(null, Disposition.of("deleted"))
    }

    // ── ask text ─────────────────────────────────────────────────────────────

    @Test
    fun `a question is cut to 300 characters and flagged`() {
        val (short, cutShort) = GoalAsk.shape("Does a full review include closed items from 2025?")
        assertEquals("Does a full review include closed items from 2025?", short); assertFalse(cutShort)
        val (long, cutLong) = GoalAsk.shape("Does the review include closed items? " + "x".repeat(400))
        assertTrue(long.length <= GoalAsk.MAX && cutLong)
    }

    @Test
    fun `a question that is a list is cut back to its first ask and flagged`() {
        val (cut, flagged) = GoalAsk.shape("Please confirm these: 1. i4 (magic-link login): waiting 2. i7 (magic link): waiting 3. i9: done")
        assertTrue(flagged)
        assertEquals("Please confirm these:", cut)
        val (byRefs, flaggedRefs) = GoalAsk.shape("Is i4 done? i7 and i9 and i11 look related. What about the rest of the schema work that was planned for August?")
        assertTrue(flaggedRefs); assertTrue(byRefs.endsWith("?") && byRefs.length < 60, byRefs)
        assertFalse(GoalAsk.shape("Should I treat i4 and i7 as the same task?").second)
    }

    // ── hand-over steps ──────────────────────────────────────────────────────

    @Test
    fun `a step whose purpose is to show or hand a list to the owner is rejected and a review step is not`() {
        listOf(
            "Show the full disposition list to the owner" to "Collect and display everything.",
            "Present the full disposition list to the owner" to "x",
            "Compile full disposition list and present to owner" to "x",
            "Send the findings to Jacob" to "x",
            "Wrap up" to "Hand the dispositions over to the owner for confirmation.",
        ).forEach { (t, i) -> assertTrue(GoalHandOver.isHandOver(t, i), t) }
        listOf(
            "Review login-related items" to "Work the items against their evidence.",
            "Classify magic-link and safety-email items" to "Write a disposition for each item.",
            "Ask what a full review means" to "Write one question.",
        ).forEach { (t, i) -> assertFalse(GoalHandOver.isHandOver(t, i), t) }
    }
}
