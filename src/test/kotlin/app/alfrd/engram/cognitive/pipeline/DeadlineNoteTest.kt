package app.alfrd.engram.cognitive.pipeline

import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.BoundedText
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItemCategory
import app.alfrd.engram.cognitive.pipeline.horizon.ProvenanceKind
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset

/**
 * The deadline wording belongs to the item's due date, not to which surfacing reason won. An overdue task that was also mentioned (or nudged,
 * or activated) used to read only "connected to what they have been talking about (mentioned)": the reason is the highest score component, an
 * overdue task's urgency floor (0.6) ties a fresh mention (0.6), and the tie-break puts the mention first. Scores, the tie-break and selection
 * are untouched; the line now carries the same wording an Urgent line does, after the reason.
 */
class DeadlineNoteTest {
    private val day = 86_400_000L
    private val utc = ZoneOffset.UTC
    /** 2027-01-15 08:00 UTC. */
    private val now = 1_799_999_999_000L + 86_400_000L * 0 + 1_000L

    private fun at(daysFromToday: Long, hour: Int = 23) =
        java.time.Instant.ofEpochMilli(now).atZone(utc).toLocalDate().plusDays(daysFromToday).atTime(hour, 0).atZone(utc).toInstant().toEpochMilli()

    private fun item(
        text: String, surfacing: SurfacingReason, dueAt: Long? = null, dueKind: String? = "soft", state: String? = "open", kind: String? = "task", hasTime: Boolean = false,
    ) = HorizonItem(
        category = HorizonItemCategory.INTENTION, text = BoundedText(text, truncated = false), status = AssertionStatus.OPEN,
        attentionDirective = null as AttentionDirective?, provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT, surfacing = surfacing,
        sourceRefs = emptyList(), sourceCount = 1, kind = kind, dueAt = dueAt, state = state, dueKind = if (dueAt != null) dueKind else null, dueHasTime = hasTime,
    )

    private fun line(vararg items: HorizonItem): String =
        HorizonItemsRenderer.render(ContextHorizon("u@test.alfrd.internal", now, 2, items.toList(), HorizonBudget(12, items.size, false), emptyList(), 0, false), utc).single().renderedLine

    private val overdueSoft get() = at(-78)

    @Test
    fun `a mentioned overdue task shows both the mention and the overdue text, soft label kept`() {
        val l = line(item("Williams report", SurfacingReason.Activated("mentioned", 0), dueAt = overdueSoft, state = "active"))
        assertTrue(l.contains("connected to what they have been talking about (mentioned)"), l)
        assertTrue(l.contains("overdue by 78 days"), l)
        assertTrue(l.contains("soft)"), l)
        assertTrue(l.indexOf("(mentioned)") < l.indexOf("overdue by"), "the deadline follows the reason: $l")
    }

    @Test
    fun `a nudged or in-progress overdue task likewise`() {
        val nudged = line(item("A", SurfacingReason.Activated("about Williams", 1), dueAt = overdueSoft))
        assertTrue(nudged.contains("about Williams") && nudged.contains("overdue by 78 days"), nudged)
        val inProgress = line(item("B", SurfacingReason.InProgress, dueAt = overdueSoft, state = "active"))
        assertTrue(inProgress.contains("they are working on this") && inProgress.contains("overdue by 78 days"), inProgress)
    }

    @Test
    fun `a hard overdue item keeps its hard label`() {
        val l = line(item("C", SurfacingReason.Activated("mentioned", 0), dueAt = overdueSoft, dueKind = "hard"))
        assertTrue(l.contains("overdue by 78 days") && l.contains("hard)"), l)
    }

    @Test
    fun `due today and inside the also-due window gets the wording, outside it does not`() {
        val today = line(item("D", SurfacingReason.Activated("mentioned", 0), dueAt = at(0)))
        assertTrue(today.contains("due today"), today)
        val tomorrowSoft = line(item("E", SurfacingReason.Activated("mentioned", 0), dueAt = at(1)))
        assertTrue(tomorrowSoft.contains("due tomorrow"), tomorrowSoft)
        val farSoft = line(item("F", SurfacingReason.Activated("mentioned", 0), dueAt = at(2)))
        assertFalse(farSoft.contains("due in") || farSoft.contains("due tomorrow"), farSoft)
        val hardIn3 = line(item("G", SurfacingReason.Activated("mentioned", 0), dueAt = at(3), dueKind = "hard"))
        assertTrue(hardIn3.contains("due in 3 days") && hardIn3.contains("hard"), hardIn3)
        val hardIn4 = line(item("H", SurfacingReason.Activated("mentioned", 0), dueAt = at(4), dueKind = "hard"))
        assertFalse(hardIn4.contains("due in"), hardIn4)
    }

    @Test
    fun `an Urgent line is unchanged and the wording is not duplicated`() {
        val l = line(item("I", SurfacingReason.Urgent(overdueSoft, overdue = true), dueAt = overdueSoft))
        assertEquals(1, Regex("overdue by").findAll(l).count(), l)
        assertTrue(l.endsWith("overdue by 78 days (${java.time.Instant.ofEpochMilli(overdueSoft).atZone(utc).toLocalDate()}, soft)"), l)
    }

    @Test
    fun `an item with no due date, a closed item, and a finished event are unchanged`() {
        val none = line(item("J", SurfacingReason.Activated("mentioned", 0)))
        assertEquals("\"J\" [open] — connected to what they have been talking about (mentioned)", none)
        assertFalse(none.contains("overdue") || none.contains("due "), none)
        val done = line(item("K", SurfacingReason.Activated("mentioned", 0), dueAt = overdueSoft, state = "done"))
        assertFalse(done.contains("overdue"), done)
        val pastEvent = line(item("L", SurfacingReason.Activated("mentioned", 0), dueAt = at(-1), kind = "event"))
        assertFalse(pastEvent.contains("overdue"), pastEvent)
    }
}
