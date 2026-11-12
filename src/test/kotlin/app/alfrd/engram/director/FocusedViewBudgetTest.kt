package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.BoundedText
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonBudget
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonContainerRef
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonContainerView
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonFocusRef
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItem
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonItemCategory
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import app.alfrd.engram.cognitive.pipeline.horizon.ProvenanceKind
import app.alfrd.engram.cognitive.pipeline.horizon.SurfacingReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/** A focused view is held to the zoom budget: the lowest-pressure items go first, and the view says how many it left out. */
class FocusedViewBudgetTest {
    private val policy = HorizonPolicy()
    private val project = HorizonContainerRef("p1", "Big project", "project")
    private val now = Instant.parse("2026-11-12T09:00:00Z")

    private fun item(i: Int, score: Double) = HorizonItem(
        category = HorizonItemCategory.INTENTION, text = BoundedText("Task number $i for the big project, with enough words to be a realistic line", false),
        status = AssertionStatus.OPEN, attentionDirective = null, provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT,
        surfacing = SurfacingReason.InFocus("Big project", FocusZoom.BINOCULARS, 1), sourceRefs = emptyList(), sourceCount = 1, score = score, kind = "task", container = project,
    )

    private fun focused(n: Int, openItems: Int = n) = ContextHorizon(
        "u@test.alfrd.internal", now.toEpochMilli(), 2, (1..n).map { item(it, it / 1000.0) }, HorizonBudget(12, n, false), emptyList(), 0, false,
        focus = HorizonFocusRef("p1", "Big project", "project", FocusZoom.BINOCULARS),
        containers = listOf(HorizonContainerView("p1", "Big project", "project", openItems, n)),
    )

    private fun state() = SelfState(
        true, 300, "2026-11-12T09:00:00Z", "test", listOf(ServerStatus("text reader", 8081, true, 1)), MemoryUse(1, 2, 3, 4),
        NotionState(false), emptyMap(), BudgetDefaults(10, 25, 600, 12_000, false), ChangeMarker(1L, emptyMap(), null, null), emptyList(),
    )

    private fun section(h: ContextHorizon, omitted: Int) =
        DirectorPromptRenderer.renderForChannel(state(), AssembleOutcome.Assembled(h), ZoneOffset.UTC, now, omitted = omitted).substringAfter("Contextual awareness")

    private fun tokens(text: String) = Math.ceil(text.length / policy.charsPerToken).toInt()

    @Test
    fun `a focused project of 100 items fits the budget, drops the lowest pressure first, and says how many it left out`() {
        val h = focused(100)
        val before = tokens(section(h, 0))
        assertTrue(before > policy.zoomTokenBudget, "the untrimmed view is $before tokens, over the budget")

        val fit = FocusedViewBudget.fit(h, ZoneOffset.UTC, policy)
        val text = section(fit.horizon, fit.omitted)
        println("FOCUS-UNIT before=$before after=${tokens(text)} kept=${fit.horizon.items.size} omitted=${fit.omitted} budget=${policy.zoomTokenBudget}")
        assertTrue(tokens(text) <= policy.zoomTokenBudget, "${tokens(text)} tokens")
        assertTrue(fit.horizon.items.size in 10..60, "a useful number is kept: ${fit.horizon.items.size}")
        assertEquals(100 - fit.horizon.items.size, fit.omitted)
        assertTrue("${fit.omitted} more items were left out of this view to stay within its size budget (lowest pressure first)" in text, text)
        // The kept ones are the highest scoring, in their original order.
        val keptScores = fit.horizon.items.map { it.score }
        assertEquals(keptScores.sortedDescending().take(keptScores.size).sorted(), keptScores.sorted())
        assertTrue(keptScores.min() >= (100 - fit.horizon.items.size + 1) / 1000.0 - 1e-9, "nothing kept scores below something dropped")
        assertEquals(h.items.filter { it in fit.horizon.items }, fit.horizon.items, "order is preserved")
    }

    @Test
    fun `a view that already fits is returned as it was`() {
        val h = focused(8)
        val fit = FocusedViewBudget.fit(h, ZoneOffset.UTC, policy)
        assertSame(h, fit.horizon)
        assertEquals(0, fit.omitted)
        assertFalse("left out" in section(fit.horizon, fit.omitted))
    }

    @Test
    fun `items the project holds but the Horizon never assembled are counted as left out`() {
        val h = focused(8, openItems = 100)
        val fit = FocusedViewBudget.fit(h, ZoneOffset.UTC, policy)
        assertEquals(92, fit.omitted, "100 open in the project, 8 listed")
        assertTrue("92 more items were left out" in section(fit.horizon, fit.omitted))
    }

    @Test
    fun `zoomed out is untouched by it, and the budget is a setting`() {
        val zoomedOut = focused(100).copy(focus = null)
        val fit = FocusedViewBudget.fit(zoomedOut, ZoneOffset.UTC, policy)
        assertSame(zoomedOut, fit.horizon)
        assertEquals(0, fit.omitted)
        val tight = FocusedViewBudget.fit(focused(100), ZoneOffset.UTC, policy.copy(zoomTokenBudget = 400))
        val loose = FocusedViewBudget.fit(focused(100), ZoneOffset.UTC, policy.copy(zoomTokenBudget = 2000))
        assertTrue(tight.horizon.items.size < FocusedViewBudget.fit(focused(100), ZoneOffset.UTC, policy).horizon.items.size)
        assertTrue(loose.horizon.items.size > FocusedViewBudget.fit(focused(100), ZoneOffset.UTC, policy).horizon.items.size)
    }

    @Test
    fun `conversational turns are not trimmed, because they never go through it`() {
        // The turn renderer lists every item it is given; only the standing context applies the budget.
        val h = focused(100)
        assertEquals(100, HorizonItemsRenderer.render(h, ZoneOffset.UTC).count { it.renderedLine.startsWith("\"Task number") })
    }
}
