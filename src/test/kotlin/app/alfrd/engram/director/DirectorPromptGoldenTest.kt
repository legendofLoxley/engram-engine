package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.AssertionStatus
import app.alfrd.engram.cognitive.pipeline.horizon.BoardArea
import app.alfrd.engram.cognitive.pipeline.horizon.BoardComposer
import app.alfrd.engram.cognitive.pipeline.horizon.BoardData
import app.alfrd.engram.cognitive.pipeline.horizon.BoardItem
import app.alfrd.engram.cognitive.pipeline.horizon.BoardProject
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonFocusRef
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState
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
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Director tick's prompt, byte for byte, against a golden file. Zoomed out, the Horizon section is now the board (a fixed-budget status view built by
 * code); the golden was regenerated for that on purpose, and only the Horizon section differs from the one captured before the board
 * (the standing policy, the situation and the memory marker are unchanged). A focused view keeps its listing, which the second test pins.
 * `UPDATE_GOLDEN=1` rewrites the file.
 */
class DirectorPromptGoldenTest {
    private val marker = ChangeMarker(5L, mapOf("Source" to 2L, "Task" to 1L), 1_700_000_000_000L, null)
    private val tickAt = Instant.parse("2026-11-07T12:00:00Z")
    private val day = 86_400_000L

    private fun item(text: String) = HorizonItem(
        category = HorizonItemCategory.INTENTION, text = BoundedText(text, false), status = AssertionStatus.OPEN, attentionDirective = null,
        provenance = ProvenanceKind.EXPLICIT_USER_STATEMENT, surfacing = SurfacingReason.DormantOpen(3), sourceRefs = emptyList(), sourceCount = 1, kind = "task",
    )

    private fun state() = SelfState(
        true, 300, "2026-11-07T12:00:00Z", "marker changed", listOf(ServerStatus("text reader", 8081, true, 1)), MemoryUse(1, 2, 3, 4),
        NotionState(false), emptyMap(), BudgetDefaults(10, 25, 600, 12_000, false), marker, emptyList(),
    )

    private fun boardItem(uid: String, text: String, dueInDays: Double? = null, hard: Boolean = false) = BoardItem(
        uid, text, "task", WorkState.OPEN, dueInDays?.let { tickAt.toEpochMilli() + (it * day).toLong() }, hard, false, false, null, null, false, tickAt.toEpochMilli() - day, tickAt.toEpochMilli() - 30 * day,
    )

    @Test
    fun `the tick's prompt carries the board when zoomed out, byte-identical to the golden`() {
        val data = BoardData(
            asOf = tickAt.toEpochMilli(), sinceAt = tickAt.toEpochMilli() - day, sinceIsLookback = true,
            areas = listOf(BoardArea("a1", "Home", false, 0.0)),
            projects = listOf(BoardProject("p1", "Garden", "a1", false, 0.0, null, listOf(boardItem("t1", "Order the soil", -2.0, hard = true), boardItem("t2", "Plant the beans")))),
            unfiled = listOf(boardItem("t3", "Call the plumber")), changes = emptyList(), newItems = 0,
        )
        val plan = BoardComposer(HorizonPolicy(), ZoneOffset.UTC).plan(data)
        val board = plan.render(mapOf("a1" to "c1", "p1" to "c2", "t1" to "i1"))
        val horizon = ContextHorizon("u@test.alfrd.internal", 1_700_000_000_000L, 2, emptyList(), HorizonBudget(12, 0, false), emptyList(), 0, false)
        val actual = DirectorPromptRenderer.render(state(), AssembleOutcome.Assembled(horizon), ZoneOffset.UTC, tickAt, board = board)
        val golden = Path.of("src", "test", "resources", "director-tick-prompt.golden.txt")
        if (System.getenv("UPDATE_GOLDEN") == "1") Files.writeString(golden, actual)
        assertEquals(Files.readString(golden), actual)
    }

    @Test
    fun `a focused view keeps its own listing and carries no board`() {
        val focus = HorizonFocusRef("p1", "Garden", "project", FocusZoom.BINOCULARS)
        val horizon = ContextHorizon("u@test.alfrd.internal", 1_700_000_000_000L, 2, listOf(item("Order the soil")), HorizonBudget(12, 1, false), emptyList(), 0, false, focus = focus)
        val actual = DirectorPromptRenderer.render(state(), AssembleOutcome.Assembled(horizon), ZoneOffset.UTC, tickAt)
        assertFalse("Board as of" in actual, actual)
        assertTrue("\"Order the soil\" [open] — noted earlier, still open" in actual, actual)
        assertTrue("Where they are right now: focused on the project \"Garden\"" in actual, actual)
    }

    @Test
    fun `without a board source, zoomed out says so rather than listing items`() {
        val horizon = ContextHorizon("u@test.alfrd.internal", 1_700_000_000_000L, 2, listOf(item("Order the soil")), HorizonBudget(12, 1, false), emptyList(), 0, false)
        val actual = DirectorPromptRenderer.render(state(), AssembleOutcome.Assembled(horizon), ZoneOffset.UTC, tickAt)
        assertTrue("Board unavailable: no board source is configured." in actual, actual)
        assertFalse("Order the soil" in actual, "there is no second zoomed-out path that lists items")
    }
}
