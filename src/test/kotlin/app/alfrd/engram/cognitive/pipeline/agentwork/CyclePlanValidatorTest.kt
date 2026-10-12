package app.alfrd.engram.cognitive.pipeline.agentwork

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CyclePlanValidatorTest {

    private val memoRow = "https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000001"
    private val auditRow = "https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000002"
    private val outsideRow = "https://www.notion.so/aaaaaaaaaaaa4aaa8aaa000000000099"
    private val memoPdf = "https://example.org/board-memo.pdf"

    private val allowed = AllowedLinks(
        pageIds = setOf("aaaaaaaa-aaaa-4aaa-8aaa-000000000001", "aaaaaaaa-aaaa-4aaa-8aaa-000000000002"),
        externalUrls = setOf(memoPdf),
    )

    private fun task(
        title: String? = "Read the Oct 6 Board memo",
        type: String? = "Read",
        instructions: String? = "Read the memo in full and note the findings for each required claim it bears on.",
        vararg links: String,
    ) = ProposedTask(title, type, instructions, links.toList())

    private fun validate(vararg tasks: ProposedTask, budget: Int = 10) = CyclePlanValidator.validate(tasks.toList(), budget, allowed)

    private fun ValidatedPlan.onlyRejection(): Rejection = rejected.single()

    // ── What passes ──────────────────────────────────────────────────────────

    @Test
    fun `a good task is accepted with its links, and order is assigned by code`() {
        val plan = validate(
            task("Read the Oct 6 Board memo", "Read", links = arrayOf(memoRow, memoPdf)),
            task("Check the audit's headline figure", "Check", links = arrayOf(auditRow)),
            task("Consolidate the findings", "Consolidate"),
        )
        assertTrue(plan.rejected.isEmpty())
        assertEquals(listOf(1, 2, 3), plan.tasks.map { it.order })
        assertEquals(listOf(TaskType.READ, TaskType.CHECK, TaskType.CONSOLIDATE), plan.tasks.map { it.type })
        assertEquals(listOf(memoRow, memoPdf), plan.tasks[0].targetLinks)
        assertEquals(emptyList<String>(), plan.tasks[2].targetLinks, "a task need not link anything")
    }

    @Test
    fun `every type a plan may propose is accepted`() {
        val plan = validate(*TaskType.PROPOSABLE.mapIndexed { i, type -> task("Task $i", type.label) }.toTypedArray())
        assertEquals(TaskType.PROPOSABLE, plan.tasks.map { it.type })
    }

    @Test
    fun `text is trimmed, and links are trimmed and not repeated`() {
        val plan = validate(task("  Read the memo  ", "Read", "  Read it.  ", " $memoRow ", memoRow))
        assertEquals("Read the memo", plan.tasks.single().title)
        assertEquals("Read it.", plan.tasks.single().instructions)
        assertEquals(listOf(memoRow), plan.tasks.single().targetLinks)
    }

    // ── Budget ───────────────────────────────────────────────────────────────

    @Test
    fun `a plan never exceeds the task budget, and the rest are reported as over budget`() {
        val plan = validate(*(1..6).map { task("Task $it") }.toTypedArray(), budget = 4)
        assertEquals(4, plan.tasks.size)
        assertEquals(listOf(4, 5), plan.rejected.map { it.index })
        assertTrue(plan.rejected.all { it.reason == RejectReason.OVER_BUDGET })
    }

    @Test
    fun `a rejected task does not use up the budget`() {
        val plan = validate(task("Bad one", "Orchestrate"), task("Good 1"), task("Good 2"), budget = 2)
        assertEquals(listOf("Good 1", "Good 2"), plan.tasks.map { it.title })
        assertEquals(1, plan.rejected.size)
    }

    @Test
    fun `a budget of zero or less accepts nothing`() {
        assertTrue(validate(task("A"), budget = 0).tasks.isEmpty())
        assertTrue(validate(task("A"), budget = -3).tasks.isEmpty())
        assertEquals(RejectReason.OVER_BUDGET, validate(task("A"), budget = 0).onlyRejection().reason)
    }

    // ── Type ─────────────────────────────────────────────────────────────────

    @Test
    fun `the Director's own step types are never taken from a proposal`() {
        for (type in listOf("Orchestrate", "Gate", "QC")) {
            val rejection = validate(task(type = type)).onlyRejection()
            assertEquals(RejectReason.TYPE_NOT_ALLOWED, rejection.reason, type)
            assertEquals(type, rejection.detail)
        }
    }

    @Test
    fun `an unknown, differently cased or blank type is rejected`() {
        for (type in listOf("Write", "read", "READ", "Lock", "")) {
            val reason = validate(task(type = type)).onlyRejection().reason
            assertTrue(reason == RejectReason.TYPE_NOT_ALLOWED || reason == RejectReason.MISSING_FIELD, "'$type' was accepted or mis-reported: $reason")
        }
    }

    // ── Fields and lengths ───────────────────────────────────────────────────

    @Test
    fun `a task without a title, type or instructions is rejected`() {
        for (bad in listOf(task(title = null), task(title = "  "), task(type = null), task(instructions = null), task(instructions = ""))) {
            assertEquals(RejectReason.MISSING_FIELD, validate(bad).onlyRejection().reason)
        }
    }

    @Test
    fun `over-long titles and instructions are rejected, not truncated`() {
        assertEquals(RejectReason.TITLE_TOO_LONG, validate(task(title = "t".repeat(CyclePlanValidator.MAX_TITLE_CHARS + 1))).onlyRejection().reason)
        assertEquals(RejectReason.INSTRUCTIONS_TOO_LONG, validate(task(instructions = "i".repeat(CyclePlanValidator.MAX_INSTRUCTIONS_CHARS + 1))).onlyRejection().reason)
        assertEquals(1, validate(task(title = "t".repeat(CyclePlanValidator.MAX_TITLE_CHARS), instructions = "i".repeat(CyclePlanValidator.MAX_INSTRUCTIONS_CHARS))).tasks.size)
    }

    @Test
    fun `a repeated title is a duplicate`() {
        val plan = validate(task("Read the memo"), task("  read THE memo "))
        assertEquals(1, plan.tasks.size)
        assertEquals(RejectReason.DUPLICATE_TASK, plan.onlyRejection().reason)
    }

    // ── Links ────────────────────────────────────────────────────────────────

    @Test
    fun `a link outside what the Director supplied is rejected, whatever the prompt said`() {
        val rejection = validate(task(links = arrayOf(memoRow, outsideRow))).onlyRejection()
        assertEquals(RejectReason.LINK_NOT_ALLOWED, rejection.reason)
        assertEquals(outsideRow, rejection.detail)
        assertEquals(RejectReason.LINK_NOT_ALLOWED, validate(task(links = arrayOf("https://example.org/other.pdf"))).onlyRejection().reason)
    }

    @Test
    fun `a task is rejected as a whole for one bad link, with the good ones not rescued`() {
        val plan = validate(task("Mixed", links = arrayOf(memoRow, outsideRow)), task("Clean", links = arrayOf(memoRow)))
        assertEquals(listOf("Clean"), plan.tasks.map { it.title })
        assertEquals(0, plan.onlyRejection().index)
    }

    @Test
    fun `something that is not an http url is rejected as such`() {
        for (bad in listOf("not a url", "ftp://example.org/x", "javascript:alert(1)", "file:///etc/passwd", "notion.so/aaaaaaaaaaaa4aaa8aaa000000000001")) {
            assertEquals(RejectReason.LINK_NOT_A_URL, validate(task(links = arrayOf(bad))).onlyRejection().reason, bad)
        }
    }

    @Test
    fun `an allowed page is recognized in any of Notion's spellings`() {
        for (spelling in listOf(
            "https://app.notion.com/p/aaaaaaaaaaaa4aaa8aaa000000000001",
            "https://www.notion.so/Board-memo-aaaaaaaaaaaa4aaa8aaa000000000001?pvs=4",
            "https://www.notion.so/aaaaaaaa-aaaa-4aaa-8aaa-000000000001",
        )) {
            assertEquals(1, validate(task(links = arrayOf(spelling))).tasks.size, spelling)
        }
    }

    @Test
    fun `too many links on one task is rejected`() {
        val many = (1..CyclePlanValidator.MAX_LINKS_PER_TASK + 1).map { "https://example.org/$it" }
        assertEquals(RejectReason.TOO_MANY_LINKS, validate(task(links = many.toTypedArray())).onlyRejection().reason)
    }

    @Test
    fun `with nothing allowed, every link is rejected and a task without links still passes`() {
        val plan = CyclePlanValidator.validate(listOf(task(links = arrayOf(memoRow)), task("No links")), 10, AllowedLinks.NONE)
        assertEquals(listOf("No links"), plan.tasks.map { it.title })
        assertEquals(RejectReason.LINK_NOT_ALLOWED, plan.onlyRejection().reason)
    }

    // ── Locking, changing, deleting, overwriting ─────────────────────────────

    @Test
    fun `a task that asks to lock or change an answer standard is rejected`() {
        val asks = listOf(
            "Lock the answer standard for question 3.",
            "Read the memo, then finalize the answer standards.",
            "Unlock the standard and edit the required claims.",
            "Set Standard Locked to today.",
            "Please update the answer standard to include the memo's figure.",
            "Immediately modify the lock note.",
        )
        for (text in asks) {
            val rejection = validate(task(instructions = text)).onlyRejection()
            assertEquals(RejectReason.LOCKS_OR_CHANGES_ANSWER_STANDARD, rejection.reason, text)
        }
    }

    @Test
    fun `a task that asks to delete or overwrite a row is rejected`() {
        val asks = listOf(
            "Delete the duplicate claim row.",
            "Remove outdated evidence.",
            "Overwrite the strength on the evidence row.",
            "Read the memo, then replace the source record.",
            "Erase the finding and start again.",
        )
        for (text in asks) {
            assertEquals(RejectReason.DELETES_OR_OVERWRITES_ROW, validate(task(instructions = text)).onlyRejection().reason, text)
        }
    }

    @Test
    fun `the title is checked as well as the instructions`() {
        assertEquals(RejectReason.DELETES_OR_OVERWRITES_ROW, validate(task(title = "Delete the stale source")).onlyRejection().reason)
        assertEquals(RejectReason.LOCKS_OR_CHANGES_ANSWER_STANDARD, validate(task(title = "Lock the answer standards")).onlyRejection().reason)
    }

    @Test
    fun `a refusal about a forbidden action names the verb and object, never the instructions`() {
        val rejection = validate(task(instructions = "Lock the answer standard. SECRET-INSTRUCTION-TEXT")).onlyRejection()
        assertFalse(rejection.detail!!.contains("SECRET"))
        assertTrue(rejection.detail!!.contains("lock") && rejection.detail!!.contains("answer standard"), rejection.detail)
    }

    // ── What must not be flagged ─────────────────────────────────────────────

    @Test
    fun `plain reading and checking tasks that only mention the lock are accepted`() {
        val fine = listOf(
            "Read the Oct 6 Board memo before answer standards are locked and record the findings.",
            "Read the memo before locking the answer standards.",
            "Check which required claims the memo bears on.",
            "Do not lock the answer standard; only report what the memo establishes.",
            "Flag any change that would need a lock note, without making it.",
            "Find evidence the claim row is wrong and report it; never delete or overwrite the row.",
            "Summarize how the sources relate to the claims and the questions.",
            "Check whether any figure in the table differs from the narrative text.",
            "Read the final external bond audit and update Jacob on what it says about the contracts.",
        )
        for (text in fine) {
            val plan = validate(task(instructions = text))
            assertTrue(plan.rejected.isEmpty(), "wrongly rejected: '$text' -> ${plan.rejected}")
        }
    }

    @Test
    fun `a forbidden verb not aimed at one of the protected things is not flagged`() {
        assertTrue(validate(task(instructions = "Update the reading list with the memo's page numbers.")).rejected.isEmpty())
        assertTrue(validate(task(instructions = "Remove ambiguity by quoting the passage.")).rejected.isEmpty())
        assertTrue(validate(task(instructions = "Lock onto the table on page 4 and read it.")).rejected.isEmpty())
    }

    // ── The scanner on its own ───────────────────────────────────────────────

    @Test
    fun `the scanner finds nothing in an ordinary instruction and something in a forbidden one`() {
        assertNull(ForbiddenActions.scan("Read the memo", "Record findings for each required claim."))
        assertNotNull(ForbiddenActions.scan("Read the memo", "Then delete the claim row."))
        assertNull(ForbiddenActions.scan())
    }

    @Test
    fun `a verb is flagged where it is used as an instruction, with adverbs skipped, and not where it merely describes`() {
        assertNotNull(ForbiddenActions.scan("Carefully lock the answer standards"))
        assertNotNull(ForbiddenActions.scan("Be ready to overwrite the claim row"))
        // Conservative by design: "plans to lock" puts the verb in instruction position, so it is flagged.
        assertNotNull(ForbiddenActions.scan("The team plans to lock the answer standards later"))
        assertNull(ForbiddenActions.scan("The lock on the answer standards was set by the board last year"))
    }
}
