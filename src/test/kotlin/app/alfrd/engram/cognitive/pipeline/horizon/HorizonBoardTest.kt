package app.alfrd.engram.cognitive.pipeline.horizon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The zoomed-out board, from synthetic data: nothing here touches a database, so the same tests run at 4, 40 and 400 projects. The live items that
 * are meant to read as "still live?" questions are not stale yet, so staleness is exercised only with items made old on purpose.
 */
class HorizonBoardTest {
    private val day = 86_400_000L
    private val now = ZonedDateTime.of(2026, 11, 11, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val policy = HorizonPolicy()
    private val composer = BoardComposer(policy, ZoneOffset.UTC)

    private fun item(
        uid: String, text: String = "Task $uid", state: WorkState = WorkState.OPEN, dueInDays: Double? = null, hard: Boolean = false, pinned: Boolean = false,
        evidenceDaysAgo: Double = 1.0, nudgeInDays: Double? = null, waitingOn: String? = null, kind: String = "task",
    ) = BoardItem(
        uid = uid, text = text, kind = kind, state = state, dueAt = dueInDays?.let { now + (it * day).toLong() }, dueHard = hard, dueHasTime = false, boundToMoment = kind == "event",
        nudgeAt = nudgeInDays?.let { now + (it * day).toLong() }, waitingOn = waitingOn, pinned = pinned, evidenceAt = now - (evidenceDaysAgo * day).toLong(), createdAt = now - 30 * day,
    )

    private fun quiet(i: Int, area: String?, items: Int = 5, pinned: Boolean = false) =
        BoardProject("p$i", "Project $i", area, pinned, 0.0, null, (1..items).map { item("p$i-i$it", dueInDays = 40.0) })

    private fun areas(n: Int) = (1..n).map { BoardArea("a$it", "Area $it", false, 0.0) }

    private fun data(projects: List<BoardProject>, areas: List<BoardArea> = areas(8), unfiled: List<BoardItem> = emptyList(), changes: List<BoardChange> = emptyList(), newItems: Int = 0, lookback: Boolean = false) =
        BoardData(now, now - day, lookback, areas, projects, unfiled, changes, newItems)

    /** Refs the way the listing gives them: areas then projects as cN, the items the board names as iN. */
    private fun refs(d: BoardData, plan: BoardPlan): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        (d.areas.map { it.uid } + d.projects.map { it.uid }).forEachIndexed { i, uid -> out[uid] = "c${i + 1}" }
        plan.itemUids.forEachIndexed { i, uid -> out[uid] = "i${i + 1}" }
        return out
    }

    private fun board(d: BoardData, p: HorizonPolicy = policy): RenderedBoard {
        val plan = BoardComposer(p, ZoneOffset.UTC).plan(d)
        return plan.render(refs(d, plan))
    }

    /** A graph shaped like the owner's: three projects that need attention, the rest quiet. */
    private fun live(quietProjects: Int): BoardData {
        val hot = listOf(
            BoardProject("w", "Williams", "a1", false, 0.0, null, listOf(
                item("w1", "File the permit", dueInDays = -2.0, hard = true), item("w2", "Reply from the inspector", WorkState.WAITING, nudgeInDays = -1.0, waitingOn = "Sam"),
                item("w3", "Order cable"), item("w4", "Book the crane", dueInDays = 20.0),
            )),
            BoardProject("m", "Meridian demo", "a2", false, 0.0, null, listOf(item("m1", "Send the deck", dueInDays = 1.0, hard = true), item("m2", "Rehearse"))),
            BoardProject("g", "Garden", "a3", false, 0.0, null, listOf(item("g1", "Order the soil"))),
        )
        val changes = listOf(BoardChange("g1", "Order the soil", "g1", "g", "state", "open", "done", now - day / 2))
        return data(hot + (1..quietProjects).map { quiet(it, "a${(it % 8) + 1}") }, changes = changes)
    }

    @Test
    fun `the rendered board is the same size at 4, 40 and 400 projects and stays inside the budget`() {
        val sizes = listOf(1, 37, 397).map { quiet -> live(quiet).let { d -> d.projects.size to board(d) } }
        println("BOARD-SAMPLE-40\n" + sizes[1].second.text)
        sizes.forEach { (n, b) ->
            println("BOARD-SIZE projects=$n tokens=${b.tokens} chars=${b.text.length} shown=${b.projectsShown} budget=${b.budget}")
            assertTrue(b.tokens <= policy.boardTokenBudget, "$n projects: ${b.tokens} tokens")
        }
        val (small, medium, large) = sizes.map { it.second.tokens }
        assertTrue(Math.abs(large - medium) <= 40, "400 projects ($large tokens) costs about what 40 ($medium) does")
        assertTrue(Math.abs(medium - small) <= 120, "40 ($medium) is within a few lines of 4 ($small): only the counts and the roll-up change")
        // The same three projects are listed at every size.
        sizes.forEach { (_, b) -> assertEquals(3, b.projectsShown, b.text) }
    }

    @Test
    fun `under budget every hot project is shown, and nothing is said to be left out for space`() {
        // 13 projects, 5 open items. Three projects need attention. Three more are empty but have a change record, one is empty with a faint mention:
        // they have some pressure but are not candidates (nothing open, or below the quiet floor), so they were never "cut for space".
        val hot = listOf(
            BoardProject("w", "Williams", "a1", false, 0.0, null, listOf(item("w1", "File the permit", dueInDays = -2.0, hard = true))),
            BoardProject("m", "Meridian demo", "a2", false, 0.0, null, listOf(item("m1", "Send the deck", dueInDays = 1.0, hard = true))),
            BoardProject("g", "Garden", "a3", false, 0.0, null, listOf(item("g1", "Reply from the landscaper", WorkState.WAITING, nudgeInDays = -1.0, waitingOn = "Sam"), item("g2"), item("g3"))),
        )
        val emptyChanged = (1..3).map { BoardProject("e$it", "Empty $it", "a4", false, 0.0, null, emptyList()) }
        val faint = BoardProject("f", "Faint mention", "a4", false, 0.1, null, emptyList())
        val rest = (1..6).map { BoardProject("r$it", "Rest $it", "a5", false, 0.0, null, emptyList()) }
        val changes = emptyChanged.map { BoardChange(it.uid, it.name, null, it.uid, "PART_OF", null, "a4", now - 1000) }
        val b = board(data(hot + emptyChanged + faint + rest, changes = changes))
        println("BOARD-13 tokens=${b.tokens}\n${b.text}")
        listOf("Williams", "Meridian demo", "Garden").forEach { name -> assertTrue(b.text.lines().any { it.startsWith("- ") && name in it }, "$name is shown:\n${b.text}") }
        assertEquals(3, b.projectsShown, b.text)
        assertFalse("Not shown for space" in b.text, "nothing was cut for space:\n${b.text}")
        assertTrue(b.tokens < policy.boardTokenBudget / 2, "and it is well under the budget: ${b.tokens}")
    }

    @Test
    fun `hidden items are named beside the open count and never counted in it`() {
        val p = BoardProject("p", "Pantry", "a1", false, 0.0, null, (1..5).map { item("p$it", dueInDays = if (it == 1) -2.0 else null, hard = it == 1) }, hiddenItems = 5)
        val b = board(data(listOf(p)))
        assertTrue("5 open, 5 hidden" in b.text, b.text)
        assertTrue("5 open items" in b.text.lines().first(), "the header counts open items only: ${b.text.lines().first()}")
        assertFalse("hidden" in board(data(listOf(p.copy(hiddenItems = 0)))).text.substringBefore("Legend:"), "nothing hidden, nothing said")
    }

    @Test
    fun `projects cut by the budget are the ones counted as not shown for space`() {
        val d = live(40)
        val tight = board(d, policy.copy(boardTokenBudget = 230))
        val cut = Regex("""Not shown for space: (\d+) project""").find(tight.text)?.groupValues?.get(1)?.toInt() ?: error(tight.text)
        assertEquals(3 - tight.projectsShown, cut, "exactly the hot projects that did not fit:\n${tight.text}")
    }

    @Test
    fun `when every project needs attention the board fills its budget and stops there, at 4, 40 and 400`() {
        fun allHot(n: Int) = data((1..n).map { i ->
            BoardProject("p$i", "Project $i", "a${(i % 8) + 1}", false, 0.0, null, listOf(
                item("p$i-a", "Overdue thing $i", dueInDays = -3.0, hard = i % 2 == 0), item("p$i-b", "Waiting thing $i", WorkState.WAITING, nudgeInDays = -1.0, waitingOn = "Sam"), item("p$i-c"),
            ))
        })
        val rows = listOf(4, 40, 400).map { n -> n to board(allHot(n)) }
        rows.forEach { (n, b) ->
            println("BOARD-SIZE-HOT projects=$n tokens=${b.tokens} chars=${b.text.length} shown=${b.projectsShown} budget=${b.budget}")
            assertTrue(b.tokens <= policy.boardTokenBudget, "$n hot projects: ${b.tokens} tokens")
        }
        val (four, forty, fourHundred) = rows.map { it.second }
        assertTrue(forty.tokens > 650 && fourHundred.tokens > 650, "with more to say than room, the budget is spent: ${forty.tokens} and ${fourHundred.tokens}")
        assertTrue(Math.abs(fourHundred.tokens - forty.tokens) <= 60, "and 400 costs what 40 does")
        assertTrue("Not shown for space" in fourHundred.text && fourHundred.projectsShown < 400, fourHundred.text)
        assertTrue(four.projectsShown <= 4)
    }

    @Test
    fun `overdue, due soon, waiting past check-in and changed nodes appear, and quiet ones roll up into their area`() {
        val b = board(live(20))
        assertTrue("Williams" in b.text && "Meridian demo" in b.text && "Garden" in b.text, b.text)
        assertTrue("File the permit" in b.text && "overdue 2d (hard)" in b.text, "the hard overdue item is listed with its words:\n${b.text}")
        assertTrue("Send the deck" in b.text && "due tomorrow (hard)" in b.text, b.text)
        assertTrue("Reply from the inspector" in b.text && "waiting on Sam, check-in date reached" in b.text, b.text)
        assertTrue("*1" in b.text, "Garden changed since the last read:\n${b.text}")
        assertFalse("Project 7" in b.text, "a quiet project does not get its own line")
        assertTrue("Everything else (quiet):" in b.text && "projects" in b.text, b.text)
    }

    @Test
    fun `counts on a project's line are what the zoomed view lists`() {
        val d = live(3)
        val b = board(d)
        val williams = b.text.lines().first { it.startsWith("- ") && "Williams" in it }
        val project = d.projects.first { it.uid == "w" }
        assertTrue("${project.items.size} open" in williams, williams)
        assertTrue("!1" in williams, "one hard overdue item, nothing else due soon: $williams")
        assertTrue("~1" in williams, williams)
    }

    @Test
    fun `a quiet pinned project keeps its line, and a pinned item keeps its line under its project`() {
        val projects = listOf(quiet(1, "a1", pinned = true), quiet(2, "a1"), BoardProject("q", "Quiet with a pin", "a2", false, 0.0, null, listOf(item("q1", "Keep this one in view", pinned = true), item("q2"))))
        val b = board(data(projects))
        assertTrue(b.text.lines().any { it.startsWith("- ") && "Project 1" in it && " ^" in it }, b.text)
        assertTrue("Keep this one in view" in b.text, b.text)
        assertFalse("Project 2" in b.text.lines().filter { it.startsWith("- ") }.joinToString("\n").substringBefore("Everything else"), "an unpinned quiet project is rolled up")
    }

    @Test
    fun `a pinned area keeps one line of its own and does not force its projects in`() {
        val areas = listOf(BoardArea("a1", "Home", true, 0.0), BoardArea("a2", "Work", false, 0.0))
        val projects = (1..6).map { quiet(it, "a1") } + (7..9).map { quiet(it, "a2") }
        val b = board(data(projects, areas = areas))
        val lines = b.text.lines().filter { it.startsWith("- ") }
        assertTrue(lines.any { "Home ^: 6 projects, 30 open" in it }, b.text)
        assertFalse(lines.any { "Project 1" in it }, "its projects stay rolled up:\n${b.text}")
        assertTrue("Work: 3 projects, 15 open" in b.text && "Home: 6 projects" !in b.text.substringAfter("Everything else"), "and it is not counted twice in the roll-up:\n${b.text}")
    }

    @Test
    fun `pinned lines count against the budget and are capped, the rest are counted`() {
        val projects = (1..12).map { quiet(it, "a1", pinned = true) }
        val b = board(data(projects))
        assertEquals(policy.boardPinnedCap, b.text.lines().count { it.startsWith("- ") && " ^" in it })
        assertTrue("${12 - policy.boardPinnedCap} more pinned not shown for space" in b.text, b.text)
        assertTrue(b.tokens <= policy.boardTokenBudget)
    }

    @Test
    fun `several stale soft items in one project are one question, and a single one keeps the existing wording`() {
        val stale = (1..3).map { item("s$it", "Stale thing $it", dueInDays = -40.0, evidenceDaysAgo = 30.0) }
        val one = listOf(item("o1", "Only stale one", dueInDays = -40.0, evidenceDaysAgo = 30.0), item("o2"))
        val projects = listOf(BoardProject("s", "Williams", "a1", false, 0.0, null, stale), BoardProject("o", "Garden", "a1", false, 0.0, null, one))
        val b = board(data(projects))
        val questions = b.text.lines().filter { "Still live?" in it }
        assertEquals(2, questions.size, "one question per project, not one line per item:\n${b.text}")
        assertTrue(questions.any { "3 items in" in it && "Williams" in it && "Stale thing 1" in it && "1 more" in it }, questions.toString())
        assertTrue(questions.any { it.trim().startsWith("? \"Only stale one\": due ") && ", no evidence since " in it }, questions.toString())
        assertTrue("?3" in b.text, "the project line counts them: ${b.text}")
        assertFalse(b.text.lines().any { "overdue" in it && "Stale thing" in it }, "a stale soft item is asked about, not announced as overdue")
    }

    @Test
    fun `a stale hard item stays an overdue line and is never softened into a question`() {
        val p = BoardProject("h", "Permits", "a1", false, 0.0, null, listOf(item("h1", "File the permit", dueInDays = -40.0, hard = true, evidenceDaysAgo = 30.0)))
        val b = board(data(listOf(p)))
        assertFalse("Still live?" in b.text, b.text)
        assertTrue("File the permit" in b.text && "overdue 40d (hard)" in b.text, b.text)
    }

    @Test
    fun `the staleness rule is the one the Horizon uses, not a second copy`() {
        // On the edge: exactly the policy window ago is not stale yet; just beyond it is.
        val fresh = BoardProject("f", "Edge", "a1", false, 0.0, null, listOf(item("f1", "On the edge", dueInDays = -40.0, evidenceDaysAgo = policy.staleEvidenceDays.toDouble() - 0.01)))
        val old = BoardProject("e", "Beyond", "a1", false, 0.0, null, listOf(item("e1", "Beyond the edge", dueInDays = -40.0, evidenceDaysAgo = policy.staleEvidenceDays.toDouble() + 0.01)))
        assertFalse("Still live?" in board(data(listOf(fresh))).text)
        assertTrue("Still live?" in board(data(listOf(old))).text)
        assertEquals(
            StateRules.stale(WorkState.OPEN, now - 40 * day, now - (policy.staleEvidenceDays + 1) * day, now, policy.staleEvidenceDays),
            true,
        )
    }

    @Test
    fun `since last time lists what changed and what is new, or says nothing changed`() {
        val none = board(data(listOf(quiet(1, "a1"))))
        assertTrue("Since you last read" in none.text && "- nothing changed." in none.text, none.text)
        val changes = (1..6).map { BoardChange("t$it", "Item $it", "t$it", "p1", "dueAt", (now - 20 * day).toString(), (now + it * day).toString(), now - it * 1000L) }
        val some = board(data(listOf(quiet(1, "a1")), changes = changes, newItems = 3))
        assertEquals(policy.boardSinceLines, some.text.lines().count { it.startsWith("- ") && "due date" in it }, some.text)
        assertTrue("- 2 more changed." in some.text && "- 3 new items." in some.text, some.text)
        val lookback = board(data(listOf(quiet(1, "a1")), lookback = true))
        assertTrue("Since the last day (no earlier read on record)" in lookback.text, lookback.text)
    }

    @Test
    fun `every item the board names is a real item, and every handle opens through a ref`() {
        val d = live(30)
        val plan = composer.plan(d)
        val real = (d.projects.flatMap { it.items } + d.unfiled).map { it.uid }.toSet()
        assertTrue(plan.itemUids.all { it in real || d.changes.any { c -> c.itemUid == it } }, plan.itemUids.toString())
        val b = plan.render(refs(d, plan))
        val handles = Regex("""\b[ci]\d+\b""").findAll(b.text).map { it.value }.toSet()
        val known = refs(d, plan).values.toSet()
        assertTrue(handles.all { it in known }, "unknown handles: ${handles - known}")
    }

    @Test
    fun `the budget is a setting and is measured on the rendered text`() {
        val tight = policy.copy(boardTokenBudget = 230)
        val b = board(live(40), tight)
        assertTrue(b.tokens <= 230, "${b.tokens} tokens")
        assertEquals(Math.ceil(b.text.length / tight.charsPerToken).toInt(), b.tokens, "tokens are the rendered length over the setting, nothing else")
        assertTrue(b.projectsShown < 3, "a tighter budget lists fewer:\n${b.text}")
        assertTrue("Not shown for space" in b.text, "and says what it left out:\n${b.text}")
    }

    @Test
    fun `an unfiled item with pressure gets a line, and a quiet unfiled item is counted`() {
        val loud = board(data(emptyList(), unfiled = listOf(item("u1", "Call the plumber", dueInDays = -1.0, hard = true))))
        assertTrue("Not filed under a project" in loud.text && "Call the plumber" in loud.text, loud.text)
        val calm = board(data(listOf(quiet(1, "a1")), unfiled = listOf(item("u2", "Someday"), item("u3", "Maybe"))))
        assertTrue("not filed under a project: 2 open" in calm.text, calm.text)
    }

    @Test
    fun `the legend appears once, defines every mark, and the board carries no dash or model voice`() {
        val b = board(live(10))
        assertEquals(1, Regex("Legend:").findAll(b.text).count())
        listOf("!n overdue or due soon", "~n waiting past check-in", "*n changed since you last read", "?n maybe stale", "^ pinned", "cN project or area, iN item").forEach { assertTrue(it in b.text, it) }
        assertFalse('—' in b.text || '–' in b.text, "no em or en dashes")
        assertTrue("Code-built, no model opinion" in b.text)
    }

    @Test
    fun `an empty graph is a short, honest board`() {
        val b = board(data(emptyList(), areas = emptyList()))
        assertTrue("0 projects in 0 areas, 0 open items" in b.text && "Nothing is open." in b.text, b.text)
        assertTrue(b.tokens < 160, "${b.tokens}")
    }

    @Test
    fun `a focused area or a casual mention raises a node's pressure, and weights are settings`() {
        val plain = quiet(1, "a1"); val mentioned = quiet(2, "a1").copy(focusWeight = 0.9)
        val b = board(data(listOf(plain, mentioned)))
        assertTrue(b.text.lines().any { it.startsWith("- ") && "Project 2" in it }, "a mentioned project gets a line:\n${b.text}")
        assertFalse(b.text.lines().any { it.startsWith("- ") && "Project 1" in it })
        val off = board(data(listOf(plain, mentioned)), policy.copy(boardWeightFocused = 0.0))
        assertFalse(off.text.lines().any { it.startsWith("- ") && "Project 2" in it }, "with the weight at zero it is quiet again")
    }
}
