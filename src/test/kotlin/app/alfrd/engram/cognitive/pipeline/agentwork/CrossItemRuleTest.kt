package app.alfrd.engram.cognitive.pipeline.agentwork

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The cross-item plan rule, enforced in code: a comparison goal's plan needs a Check across two or more items. */
class CrossItemRuleTest {
    private fun t(order: Int, type: TaskType, title: String, instructions: String) = PlannedTask(order, title, type, instructions, emptyList())

    private val singleLookups = listOf(
        t(1, TaskType.READ, "Read the reference", "Read the reference list."),
        t(2, TaskType.CHECK, "Check item A against the reference", "Does item A match the reference? Quote both."),
        t(3, TaskType.CHECK, "Check item B against the reference", "Does item B match the reference? Quote both."),
        t(4, TaskType.CONSOLIDATE, "Consolidate", "List every answer."),
    )
    private val withCross = singleLookups + t(5, TaskType.CHECK, "Check items against each other", "Does one item retire or replace something another item depends on?")

    @Test
    fun `a comparison goal planned with only single-item lookups is flagged`() {
        assertTrue(CrossItemRule.isComparisonGoal("Compare the rows against the reference", "Every row is judged."))
        assertNotNull(CrossItemRule.flag("Compare the rows against the reference", "Every row is judged.", singleLookups))
    }

    @Test
    fun `a comparison goal planned with a cross-item check passes`() {
        assertNull(CrossItemRule.flag("Compare the rows against the reference", "Every row is judged.", withCross))
    }

    @Test
    fun `a goal that compares nothing is never flagged`() {
        assertFalse(CrossItemRule.isComparisonGoal("Summarize the memo", "A short summary exists."))
        assertNull(CrossItemRule.flag("Summarize the memo", "A short summary exists.", singleLookups))
    }

    @Test
    fun `only a Check task counts as the cross-item check`() {
        val readOnly = singleLookups + t(5, TaskType.READ, "Read items against each other", "Read how items depend on each other.")
        assertNotNull(CrossItemRule.flag("Compare rows to a reference", "Done.", readOnly))
    }
}
