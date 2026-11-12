package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import java.time.ZoneId

/**
 * Holds a focused view (a project, an area or one item) to [HorizonPolicy.zoomTokenBudget], the way the board is held to its own: when the view would be
 * larger, the lowest-pressure items are dropped until it fits, and the count of what was left out is returned so the view can say so.
 *
 * Used by the standing context (the Director tick and the owner's tool). Conversational turns do not go through it.
 *
 * The size is measured on the rendered text of the whole section (orientation, also-due lines, grouped items), with a small allowance per item for the ref the owner's
 * tool prints beside it and for the heading and the "left out" note, so the section stays inside the budget once those are added.
 */
object FocusedViewBudget {
    /** [omitted] is how many items the view does not list: the project's open items beyond those kept (including ones never assembled), or the items dropped. */
    data class Fit(val horizon: ContextHorizon, val omitted: Int)

    private const val REF_ALLOWANCE = 6
    private const val FIXED_ALLOWANCE = 300

    fun fit(horizon: ContextHorizon, zone: ZoneId, policy: HorizonPolicy): Fit {
        val focus = horizon.focus ?: return Fit(horizon, 0)
        val budgetChars = (policy.zoomTokenBudget * policy.charsPerToken).toInt()

        fun size(h: ContextHorizon): Int {
            val items = HorizonItemsRenderer.render(h, zone)
            val frame = HorizonItemsRenderer.composeOrientation(h, zone)
            return (frame?.length ?: 0) + DirectorPromptRenderer.groupedLength(items) + REF_ALLOWANCE * (h.items.size + h.alsoDue.size + h.hidden.size) + FIXED_ALLOWANCE
        }

        // Lowest pressure first; among equals, the one later in the list goes first.
        val dropOrder = horizon.items.withIndex().sortedWith(compareBy({ it.value.score }, { -it.index })).map { it.index }
        fun without(n: Int): ContextHorizon {
            val gone = dropOrder.take(n).toSet()
            return horizon.copy(items = horizon.items.filterIndexed { i, _ -> i !in gone })
        }

        // The smallest number of items to drop that makes it fit; the size only falls as more go, so a binary search finds it. Keep one item if there is one.
        val most = (horizon.items.size - 1).coerceAtLeast(0)
        var dropped = 0
        if (size(horizon) > budgetChars) {
            var lo = 1
            var hi = most
            while (lo < hi) {
                val mid = (lo + hi) / 2
                if (size(without(mid)) <= budgetChars) hi = mid else lo = mid + 1
            }
            dropped = if (most == 0) 0 else lo
        }
        val kept = if (dropped > 0) without(dropped) else horizon

        // What the view does not list. For a project, its open items are known whether or not they were assembled, so the count is honest about those too.
        val projectOpen = horizon.containers.firstOrNull { it.uid == focus.uid && focus.kind == "project" }?.openItems
        val omitted = if (projectOpen != null) {
            val keptInProject = kept.items.count { it.container?.uid == focus.uid }
            val droppedElsewhere = horizon.items.count { it.container?.uid != focus.uid } - kept.items.count { it.container?.uid != focus.uid }
            (projectOpen - keptInProject).coerceAtLeast(0) + droppedElsewhere.coerceAtLeast(0)
        } else dropped
        return Fit(kept, omitted)
    }
}
