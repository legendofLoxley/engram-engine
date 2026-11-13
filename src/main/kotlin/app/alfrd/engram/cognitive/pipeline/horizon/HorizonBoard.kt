package app.alfrd.engram.cognitive.pipeline.horizon

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * The zoomed-out board: a fixed-size status view of the whole graph, built by code from the graph on every read and never written by a model.
 *
 * Everything in this file is pure: data in (what [HorizonBoardSource] read), text out. Cost is bounded by a token budget measured on the rendered
 * text, not by how many projects or items there are: the nodes under the most pressure get lines, quiet ones roll up into their area, and the
 * rest are counted. Every line names the real thing it summarises by its ref, so a reader can open it with the existing focus and zoom verbs.
 */

/** One open item as the board reads it. [dueAt] is already normalised the way the "also due" block reads it (a date-only event is due at the end of its day). */
data class BoardItem(
    val uid: String,
    val text: String,
    val kind: String,
    val state: WorkState?,
    val dueAt: Long?,
    val dueHard: Boolean,
    val dueHasTime: Boolean,
    val boundToMoment: Boolean,
    val nudgeAt: Long?,
    val waitingOn: String?,
    val pinned: Boolean,
    val evidenceAt: Long,
    val createdAt: Long,
)

/** A project and the open items filed under it. Suppressed items and items of a done or silenced project are never here. */
data class BoardProject(
    val uid: String,
    val name: String,
    val areaUid: String?,
    val pinned: Boolean,
    /** A casual mention's strength (0 to 1), or 0. A pinned focus means the view is not zoomed out and there is no board. */
    val focusWeight: Double,
    val dueAt: Long?,
    val items: List<BoardItem>,
    /** Suppressed items, never counted in the open number. */
    val hiddenItems: Int = 0,
)

/** One goal as the board reads it: open (not done or dropped) and not suppressed. */
data class BoardGoal(
    val uid: String,
    val text: String,
    /** True once the owner has approved it; false while it is only proposed. */
    val approved: Boolean,
    val state: WorkState?,
    val evidenceAt: Long,
    val pinned: Boolean = false,
)

data class BoardArea(val uid: String, val name: String, val pinned: Boolean, val focusWeight: Double)

/**
 * One recorded change, with the words to show for it. [itemUid] is set when the target is an item (so the line can name it by ref),
 * [containerUid] is the project the target is in (or is).
 */
data class BoardChange(
    val targetUid: String, val label: String, val itemUid: String?, val containerUid: String?,
    val field: String, val oldValue: String?, val newValue: String?, val at: Long,
)

data class BoardData(
    val asOf: Long,
    /** The start of "since last time": the channel's last read, or the first-read lookback. */
    val sinceAt: Long,
    /** True when [sinceAt] is the lookback because nothing earlier is on record for the channel. */
    val sinceIsLookback: Boolean,
    val areas: List<BoardArea>,
    val projects: List<BoardProject>,
    /** Open items filed under no project. */
    val unfiled: List<BoardItem>,
    val changes: List<BoardChange>,
    val newItems: Int,
    /** True when a scan hit its bound, so the counts are a floor. */
    val truncated: Boolean = false,
    /** Suppressed items filed under no project. */
    val unfiledHidden: Int = 0,
    /** Open goals. They are shown in the Goals section, never counted among the open items. */
    val goals: List<BoardGoal> = emptyList(),
)

/** What a rendered board cost, and what it shows. */
data class RenderedBoard(
    val text: String,
    val tokens: Int,
    val budget: Int,
    val projectsShown: Int,
    val projectsTotal: Int,
    val itemUids: List<String>,
    /** The projects with a line on the board: the refs listing keeps these (and every area) when it is bounded. */
    val projectUids: List<String> = emptyList(),
)

/** A board with its lines chosen, not yet worded with refs. [render] words it and checks the budget against the real refs. */
class BoardPlan internal constructor(
    /** The items the text names: the ones that need a ref in the listing. */
    val itemUids: List<String>,
    private val policy: HorizonPolicy,
    private val blocks: List<Block>,
    private val total: Int,
    private val assemble: (List<Block>, Map<String, String>?) -> String,
) {
    internal class Block(val projectUid: String?, val required: Boolean, val itemUids: List<String>, val render: (Map<String, String>?) -> String, val areaUid: String? = null, val unfiled: Boolean = false)

    fun render(refs: Map<String, String>): RenderedBoard {
        // The plan was made with a ref width guess; if the real refs make it longer than the budget, the least important blocks go (pinned last).
        val kept = blocks.toMutableList()
        var text = assemble(kept, refs)
        while (HorizonBoards.tokens(text, policy) > policy.boardTokenBudget && kept.isNotEmpty()) {
            val drop = kept.indexOfLast { !it.required }.takeIf { it >= 0 } ?: kept.lastIndex
            kept.removeAt(drop)
            text = assemble(kept, refs)
        }
        return RenderedBoard(text, HorizonBoards.tokens(text, policy), policy.boardTokenBudget, kept.count { it.projectUid != null }, total, kept.flatMap { it.itemUids }.distinct(), kept.mapNotNull { it.projectUid })
    }

    /** The text with refs of a guessed width, for measuring before the listing exists (and for tests that do not care about refs). */
    fun renderWithHintedRefs(): RenderedBoard = RenderedBoard(
        assemble(blocks, null), HorizonBoards.tokens(assemble(blocks, null), policy), policy.boardTokenBudget,
        blocks.count { it.projectUid != null }, total, itemUids, blocks.mapNotNull { it.projectUid },
    )
}

object HorizonBoards {
    fun tokens(text: String, policy: HorizonPolicy): Int = Math.ceil(text.length / policy.charsPerToken).toInt()
}

class BoardComposer(private val policy: HorizonPolicy = HorizonPolicy(), private val zone: ZoneId = ZoneId.of("UTC")) {

    private class Flags(
        val item: BoardItem,
        val hardOverdue: Boolean, val softOverdue: Boolean, val staleSoft: Boolean, val dueSoon: Boolean, val waitingDue: Boolean,
    ) {
        /** A reason, besides being pinned, that this item deserves its own line. Stale soft items are not here: they are asked about together. */
        val flagged: Boolean get() = hardOverdue || softOverdue || dueSoon || waitingDue
        val urgentMark: Boolean get() = hardOverdue || softOverdue || dueSoon
    }

    private class Stats(val project: BoardProject?, val unfiled: List<BoardItem>?, val unfiledHidden: Int, val flags: List<Flags>, val changedItems: Int, val pressure: Double, val projectDueSoon: Boolean) {
        val items: List<BoardItem> get() = project?.items ?: unfiled.orEmpty()
        val open: Int get() = flags.size
        val hidden: Int get() = project?.hiddenItems ?: unfiledHidden
        val active: Int get() = flags.count { it.item.state == WorkState.ACTIVE }
        val waiting: Int get() = flags.count { it.item.state == WorkState.WAITING }
        val urgent: Int get() = flags.count { it.urgentMark } + if (projectDueSoon) 1 else 0
        val waitingDue: Int get() = flags.count { it.waitingDue }
        val stale: List<Flags> get() = flags.filter { it.staleSoft }
        val pinnedItems: List<Flags> get() = flags.filter { it.item.pinned }
    }

    fun plan(data: BoardData): BoardPlan {
        val now = data.asOf
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun endOfDay(d: LocalDate) = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val hardEnd = endOfDay(today.plusDays(policy.alsoDueHardDays))
        val softEnd = endOfDay(today.plusDays(policy.alsoDueSoftDays))
        val changedByContainer = data.changes.groupBy { it.containerUid }.mapValues { (_, v) -> v.map { it.targetUid }.distinct().size }

        fun flagsOf(i: BoardItem): Flags {
            val canBeOverdue = StateRules.canBeOverdue(i.state)
            val dueAt = i.dueAt
            // An event that has started is over, not overdue; every other open or active item past its date is owed.
            val overdue = canBeOverdue && dueAt != null && dueAt < now && !i.boundToMoment
            val stale = StateRules.stale(i.state, dueAt, i.evidenceAt, now, policy.staleEvidenceDays)
            val hard = i.dueHard
            return Flags(
                item = i,
                hardOverdue = overdue && hard,
                softOverdue = overdue && !hard && !stale,
                staleSoft = stale && !hard,
                dueSoon = canBeOverdue && dueAt != null && DueWindows.alsoDue(dueAt, hard, i.boundToMoment, now, hardEnd, softEnd),
                waitingDue = i.state == WorkState.WAITING && i.nudgeAt != null && i.nudgeAt <= now,
            )
        }

        fun statsOf(project: BoardProject?, unfiled: List<BoardItem>?, containerUid: String?, pinnedNode: Boolean, focus: Double, dueAt: Long?): Stats {
            val flags = (project?.items ?: unfiled.orEmpty()).map(::flagsOf)
            val changed = changedByContainer[containerUid] ?: 0
            val projectDueSoon = dueAt != null && policy.urgency(dueAt, now, policy.projectUrgencyHalfDays, false) >= policy.breakthroughUrgency
            val signals = listOf(
                policy.boardWeightOverdue * (if (flags.any { it.hardOverdue }) 1.0 else if (flags.any { it.softOverdue }) 0.7 else 0.0),
                policy.boardWeightDueSoon * (if (flags.any { it.dueSoon } || projectDueSoon) 1.0 else 0.0),
                policy.boardWeightWaitingDue * (if (flags.any { it.waitingDue }) 1.0 else 0.0),
                policy.boardWeightChanged * (if (changed > 0) minOf(1.0, 0.5 + 0.1 * changed) else 0.0),
                policy.boardWeightStale * (if (flags.any { it.staleSoft }) 1.0 else 0.0),
                policy.boardWeightFocused * focus.coerceIn(0.0, 1.0),
                policy.boardWeightPinned * (if (pinnedNode) 1.0 else 0.0),
            )
            return Stats(project, unfiled, if (project == null) data.unfiledHidden else 0, flags, changed, HorizonPolicy.combine(signals), projectDueSoon)
        }

        val areaName = data.areas.associate { it.uid to it.name }
        val projectStats = data.projects.map { p -> p to statsOf(p, null, p.uid, p.pinned, p.focusWeight, p.dueAt) }
        val unfiledStats = statsOf(null, data.unfiled, null, false, 0.0, null)

        fun requiredOf(s: Stats): Boolean = s.project?.pinned == true || s.pinnedItems.isNotEmpty()

        // Blocks, most important first: pinned (capped), then by pressure. A quiet, unpinned project is never a candidate.
        val candidates = (projectStats.map { (_, s) -> s } + unfiledStats)
            .filter { it.open > 0 || requiredOf(it) }
            .filter { requiredOf(it) || it.pressure >= policy.boardQuietBelow }
        val required = candidates.filter(::requiredOf).sortedWith(compareByDescending<Stats> { it.pressure }.thenByDescending { it.open })
        val pinnedAreas = data.areas.filter { it.pinned }
        val areaRoom = minOf(pinnedAreas.size, policy.boardPinnedCap)
        val requiredShown = required.take((policy.boardPinnedCap - areaRoom).coerceAtLeast(0))
        val requiredOverflow = required.size - requiredShown.size + (pinnedAreas.size - areaRoom)
        val rest = candidates.filterNot(::requiredOf)
            .sortedWith(compareByDescending<Stats> { it.pressure }.thenByDescending { it.open }.thenBy { it.project?.name ?: "" })

        fun nm(name: String) = if (name.length > NAME_MAX) name.take(NAME_MAX - 1) + "." else name
        fun handle(uid: String?, prefix: String, refs: Map<String, String>?): String =
            if (uid == null) "" else if (refs == null) "${prefix}000 " else refs[uid]?.let { "$it " } ?: ""
        fun q(text: String) = "\"" + text.replace('"', '\'').let { if (it.length > ITEM_TEXT) it.take(ITEM_TEXT - 1) + "." else it } + "\""

        fun dueWords(i: BoardItem): String {
            val dueAt = i.dueAt ?: return ""
            val due = Instant.ofEpochMilli(dueAt).atZone(zone)
            val days = ChronoUnit.DAYS.between(today, due.toLocalDate())
            val kind = if (i.dueHard) "hard" else "soft"
            return when {
                dueAt < now && days < 0 -> "overdue ${-days}d ($kind)"
                dueAt < now -> "overdue ($kind)"
                days <= 0 -> "due today ($kind)"
                days == 1L -> "due tomorrow ($kind)"
                else -> "due in ${days}d, ${due.toLocalDate()} ($kind)"
            }
        }

        fun itemLine(f: Flags, refs: Map<String, String>?): String {
            val i = f.item
            val mark = when {
                f.urgentMark -> "!"
                f.waitingDue -> "~"
                i.pinned -> "^"
                else -> "-"
            }
            val detail = buildList {
                if (i.dueAt != null && (f.urgentMark || i.pinned)) add(dueWords(i))
                if (f.waitingDue) add("waiting" + (i.waitingOn?.let { " on $it" } ?: "") + ", check-in date reached")
                if (i.pinned && f.urgentMark.not() && !f.waitingDue && i.dueAt == null) add("pinned")
            }.joinToString(", ")
            return "  $mark ${handle(i.uid, "i", refs)}${q(i.text)}" + if (detail.isNotEmpty()) ": $detail" else ""
        }

        fun staleLine(s: Stats, refs: Map<String, String>?): String {
            val stale = s.stale
            val first = stale.first().item
            if (stale.size == 1) {
                val evidence = Instant.ofEpochMilli(first.evidenceAt).atZone(zone).toLocalDate()
                val due = Instant.ofEpochMilli(first.dueAt!!).atZone(zone).toLocalDate()
                return "  ? ${q(first.text)}: due $due (soft), no evidence since $evidence. Still live?${handle(first.uid, "i", refs).trim().takeIf { it.isNotEmpty() }?.let { " [$it]" } ?: ""}"
            }
            val shown = stale.take(2).joinToString(", ") { "${handle(it.item.uid, "i", refs)}${q(it.item.text)}" }
            val more = if (stale.size > 2) " and ${stale.size - 2} more" else ""
            val where = s.project?.let { "in ${handle(it.uid, "c", refs)}${nm(it.name)}" } ?: "not filed under a project"
            return "  ? ${stale.size} items $where are past their dates with no evidence for ${policy.staleEvidenceDays}+ days: $shown$more. Still live?"
        }

        /** The listing of what is on the line: flagged items first (hard overdue, due soon, waiting past check-in), then pinned ones; stale soft ones are asked together. */
        fun itemsToList(s: Stats): List<Flags> {
            val order = compareBy<Flags>({ if (it.hardOverdue) 0 else if (it.softOverdue) 1 else if (it.dueSoon) 2 else if (it.waitingDue) 3 else 4 }, { it.item.dueAt ?: Long.MAX_VALUE })
            val listed = (s.flags.filter { it.flagged }.sortedWith(order) + s.pinnedItems.filterNot { it.flagged }).distinctBy { it.item.uid }
            return listed.take(policy.boardItemsPerNode)
        }

        fun nodeCounts(s: Stats): String = buildList {
            add("${s.open} open")
            if (s.hidden > 0) add("${s.hidden} hidden")
            if (s.urgent > 0) add("!${s.urgent}")
            if (s.waitingDue > 0) add("~${s.waitingDue}")
            if (s.changedItems > 0) add("*${s.changedItems}")
            if (s.stale.isNotEmpty()) add("?${s.stale.size}")
        }.joinToString(", ")

        fun block(s: Stats, compact: Boolean): BoardPlan.Block {
            val listed = if (compact) emptyList() else itemsToList(s)
            val staleShown = !compact && s.stale.isNotEmpty()
            val itemUids = listed.map { it.item.uid } + (if (staleShown) s.stale.take(2).map { it.item.uid } else emptyList())
            val projectUid = s.project?.uid
            val render: (Map<String, String>?) -> String = { refs ->
                val head = if (s.project != null) {
                    val area = s.project.areaUid?.let { areaName[it] }?.let { " [${nm(it)}]" } ?: ""
                    "- ${handle(s.project.uid, "c", refs)}${nm(s.project.name)}$area${if (requiredOf(s)) " ^" else ""}: ${nodeCounts(s)}"
                } else "- Not filed under a project: ${nodeCounts(s)}"
                buildString {
                    append(head)
                    listed.forEach { append('\n').append(itemLine(it, refs)) }
                    if (staleShown) append('\n').append(staleLine(s, refs))
                }
            }
            return BoardPlan.Block(projectUid, requiredOf(s), itemUids.distinct(), render, unfiled = s.project == null)
        }

        /** A pinned area keeps one line of its own: its projects and open items, and the same marks summed over its projects. */
        fun areaBlock(area: BoardArea): BoardPlan.Block {
            val inside = projectStats.filter { (p, _) -> p.areaUid == area.uid }.map { it.second }
            val marks = buildList {
                inside.sumOf { it.urgent }.takeIf { it > 0 }?.let { add("!$it") }
                inside.sumOf { it.waitingDue }.takeIf { it > 0 }?.let { add("~$it") }
                inside.sumOf { it.changedItems }.takeIf { it > 0 }?.let { add("*$it") }
                inside.sumOf { it.stale.size }.takeIf { it > 0 }?.let { add("?$it") }
            }
            return BoardPlan.Block(null, true, emptyList(), { refs ->
                "- ${handle(area.uid, "c", refs)}${nm(area.name)} ^: ${inside.size} project${if (inside.size == 1) "" else "s"}, ${inside.sumOf { it.open }} open" +
                    (if (marks.isEmpty()) "" else ", " + marks.joinToString(", "))
            }, areaUid = area.uid)
        }

        // ── goals ────────────────────────────────────────────────────────────
        // Running: approved and going. Waiting for approval: only proposed. Stalled: approved but paused, or open with no evidence for the stale window.
        // Zero goals renders nothing; with many, the section is the counts plus as many lines as fit its own small budget.
        class GoalView(val goal: BoardGoal, val bucket: Int)   // 0 stalled, 1 waiting for approval, 2 approved but not started, 3 running
        val goalViews = data.goals.map { g ->
            // Running means active and nothing else. An approved goal that has not been started is approved, not running; one that is paused, or running with no
            // evidence for the stale window, is stalled.
            val running = g.approved && g.state == WorkState.ACTIVE
            val paused = g.approved && g.state == WorkState.WAITING
            val quiet = now - g.evidenceAt > policy.staleEvidenceDays * 86_400_000L && g.evidenceAt > 0L
            GoalView(g, when { !g.approved -> 1; paused || (running && quiet) -> 0; running -> 3; else -> 2 })
        }.sortedWith(compareBy<GoalView> { it.bucket }.thenBy { it.goal.evidenceAt })
        val goalHeader: String? = if (goalViews.isEmpty()) null else buildList {
            goalViews.count { it.bucket == 3 }.takeIf { it > 0 }?.let { add("$it running") }
            goalViews.count { it.bucket == 2 }.takeIf { it > 0 }?.let { add("$it approved") }
            goalViews.count { it.bucket == 1 }.takeIf { it > 0 }?.let { add("$it waiting for approval") }
            goalViews.count { it.bucket == 0 }.takeIf { it > 0 }?.let { add("$it stalled") }
        }.joinToString(", ", "Goals: ", ".")
        fun goalLine(v: GoalView, refs: Map<String, String>?): String {
            val g = v.goal
            val mark = when (v.bucket) { 0 -> "!"; 1 -> "?"; else -> "-" }
            val words = when (v.bucket) {
                0 -> if (g.state == WorkState.WAITING) "stalled, paused" else "stalled, no evidence since ${Instant.ofEpochMilli(g.evidenceAt).atZone(zone).toLocalDate()}"
                1 -> "waiting for approval"
                2 -> "approved, not started"
                else -> "running"
            }
            return "  $mark ${handle(g.uid, "i", refs)}${q(g.text)}: $words"
        }
        // The most lines whose section fits the goal budget (measured with ref-width placeholders); the counts line is always there.
        val goalShown: List<GoalView> = run {
            var n = minOf(policy.boardGoalLines, goalViews.size)
            fun moreLine(k: Int) = (goalViews.size - k).takeIf { it > 0 }?.let { "  - $it more goal${if (it == 1) "" else "s"}; open the project or set_focus on one." }
            fun section(k: Int) = (listOfNotNull(goalHeader) + goalViews.take(k).map { goalLine(it, null) } + listOfNotNull(moreLine(k))).joinToString("\n")
            while (n > 0 && HorizonBoards.tokens(section(n), policy) > policy.boardGoalTokens) n--
            goalViews.take(n)
        }
        val moreGoals = goalViews.size - goalShown.size

        // ── since last time ──────────────────────────────────────────────────
        fun valueWords(field: String, v: String?): String = when {
            v == null -> "none"
            field == "dueAt" || field == "nudgeAt" -> v.toLongOrNull()?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString() } ?: v
            else -> v.take(24)
        }
        fun fieldWords(field: String): String = when (field) {
            "dueAt" -> "due date"; "dueKind" -> "hard or soft"; "waitingOn" -> "waiting on"; "nudgeAt" -> "check-in date"; "PART_OF" -> "filing"; "FOCUS" -> "focus"
            "FOCUSES_ON" -> "focus"; "doneWhen" -> "done-when"; "stopWhen" -> "stop-when"; "sentence" -> "sentence"; else -> field
        }
        val sinceLines: List<(Map<String, String>?) -> String> = data.changes.groupBy { it.targetUid }.values
            .sortedByDescending { group -> group.maxOf { it.at } }
            .take(policy.boardSinceLines)
            .map { group ->
                { refs: Map<String, String>? ->
                    val first = group.first()
                    val what = group.sortedBy { it.at }.take(2).joinToString("; ") { "${fieldWords(it.field)} ${valueWords(it.field, it.oldValue)} to ${valueWords(it.field, it.newValue)}" } +
                        if (group.size > 2) "; ${group.size - 2} more" else ""
                    "- ${handle(first.itemUid ?: first.targetUid, if (first.itemUid != null) "i" else "c", refs)}${q(first.label)}: $what"
                }
            }
        val sinceItemUids = data.changes.groupBy { it.targetUid }.values.sortedByDescending { g -> g.maxOf { it.at } }.take(policy.boardSinceLines).mapNotNull { it.first().itemUid }

        val sinceHeader = if (data.sinceIsLookback) "Since the last day (no earlier read on record)" else "Since you last read (${Instant.ofEpochMilli(data.sinceAt).atZone(zone).toLocalDate()} ${"%02d:%02d".format(Instant.ofEpochMilli(data.sinceAt).atZone(zone).hour, Instant.ofEpochMilli(data.sinceAt).atZone(zone).minute)})"
        val moreChanges = (data.changes.groupBy { it.targetUid }.size - sinceLines.size).coerceAtLeast(0)

        val totalOpen = projectStats.sumOf { it.second.open } + unfiledStats.open
        val totalProjects = data.projects.size

        fun assemble(blocks: List<BoardPlan.Block>, refs: Map<String, String>?): String {
            val shown = blocks.mapNotNull { it.projectUid }.toSet()
            val shownAreas = blocks.mapNotNull { it.areaUid }.toSet()
            val sb = StringBuilder()
            sb.append("Board as of ${Instant.ofEpochMilli(data.asOf).atZone(zone).toLocalDate()} ${"%02d:%02d".format(Instant.ofEpochMilli(data.asOf).atZone(zone).hour, Instant.ofEpochMilli(data.asOf).atZone(zone).minute)}: ")
            sb.append("$totalProjects project${if (totalProjects == 1) "" else "s"} in ${data.areas.size} area${if (data.areas.size == 1) "" else "s"}, $totalOpen open item${if (totalOpen == 1) "" else "s"}")
            sb.append(if (data.truncated) " (at least; a scan hit its limit).\n" else ".\n")
            if (totalOpen == 0 && blocks.isEmpty() && goalHeader == null) sb.append("Nothing is open.\n")
            if (goalHeader != null) {
                sb.append(goalHeader).append('\n')
                goalShown.forEach { sb.append(goalLine(it, refs)).append('\n') }
                if (moreGoals > 0) sb.append("  - $moreGoals more goal${if (moreGoals == 1) "" else "s"}; open the project or set_focus on one.\n")
            }
            if (blocks.isNotEmpty()) {
                sb.append("Needs attention:\n")
                blocks.forEach { sb.append(it.render(refs)).append('\n') }
            }
            // Everything else, by area. Honest about what was left out for space.
            val leftOver = projectStats.filter { (p, _) -> p.uid !in shown }
            // Only a project that qualified for a line (pressure at or above the quiet floor, something open) and did not fit is "not shown for space".
            // A project that merely has some pressure (an empty one with a change record, a faint mention) was never a candidate and is not counted.
            val hotLeft = candidates.count { it.project != null && it.project.uid !in shown && !requiredOf(it) }
            if (leftOver.isNotEmpty() || (unfiledStats.open > 0 && blocks.none { it.unfiled })) {
                val byArea = leftOver.filter { (p, _) -> p.areaUid !in shownAreas || p.areaUid == null }.groupBy { (p, _) -> p.areaUid }
                    .map { (area, ps) -> Triple(area, ps.size, ps.sumOf { it.second.open }) }
                    .sortedByDescending { it.third }
                val listed = byArea.take(policy.boardRollupLines)
                val rest = byArea.drop(policy.boardRollupLines)
                val parts = buildList {
                    listed.forEach { (area, n, open) ->
                        add("${handle(area, "c", refs)}${area?.let { areaName[it] }?.let(::nm) ?: "no area"}: $n project${if (n == 1) "" else "s"}, $open open")
                    }
                    if (rest.isNotEmpty()) add("${rest.size} more area${if (rest.size == 1) "" else "s"}: ${rest.sumOf { it.second }} projects, ${rest.sumOf { it.third }} open")
                    if (unfiledStats.open > 0 && blocks.none { it.unfiled }) add("not filed under a project: ${unfiledStats.open} open")
                }
                if (parts.isNotEmpty()) sb.append("Everything else (quiet): ").append(parts.joinToString("; ")).append(".\n")
                if (hotLeft > 0) sb.append("Not shown for space: $hotLeft project${if (hotLeft == 1) "" else "s"} with something due, waiting or changed; open an area or project by its ref.\n")
            }
            if (requiredOverflow > 0) sb.append("$requiredOverflow more pinned not shown for space.\n")
            sb.append(sinceHeader).append(":\n")
            if (sinceLines.isEmpty() && data.newItems == 0) sb.append("- nothing changed.\n")
            sinceLines.forEach { sb.append(it(refs)).append('\n') }
            if (moreChanges > 0) sb.append("- $moreChanges more changed.\n")
            if (data.newItems > 0) sb.append("- ${data.newItems} new item${if (data.newItems == 1) "" else "s"}.\n")
            sb.append(LEGEND)
            return sb.toString()
        }

        // ── choose blocks under the budget ───────────────────────────────────
        val chosen = mutableListOf<BoardPlan.Block>()
        val budget = policy.boardTokenBudget
        fun fits(blocks: List<BoardPlan.Block>) = HorizonBoards.tokens(assemble(blocks, null), policy) <= budget
        for (area in pinnedAreas.take(areaRoom)) areaBlock(area).let { if (fits(chosen + it)) chosen += it }
        for (s in requiredShown + rest) {
            val full = block(s, compact = false)
            val compact = block(s, compact = true)
            val next = when {
                fits(chosen + full) -> full
                fits(chosen + compact) -> compact
                else -> if (s in requiredShown) null else break
            } ?: continue
            chosen += next
        }
        return BoardPlan(
            itemUids = (goalShown.map { it.goal.uid } + chosen.flatMap { it.itemUids } + sinceItemUids).distinct(),
            policy = policy,
            blocks = chosen,
            total = totalProjects,
            assemble = ::assemble,
        )
    }

    companion object {
        private const val ITEM_TEXT = 60
        private const val NAME_MAX = 32

        /** What a mark means, once, so a reader never guesses. */
        const val LEGEND =
            "Legend: cN project or area, iN item; open either with set_focus on its ref. !n overdue or due soon, ~n waiting past check-in, " +
                "*n changed since you last read, ?n maybe stale (ask \"still live?\"; confirm_item keeps it, set_state done or dropped closes it), " +
                "^ pinned. Quiet: nothing due, changed or waiting. Code-built, no model opinion."
    }
}
