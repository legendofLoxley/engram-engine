package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import app.alfrd.engram.cognitive.pipeline.horizon.BoardComposer
import app.alfrd.engram.cognitive.pipeline.horizon.BoardPlan
import app.alfrd.engram.cognitive.pipeline.horizon.BoardSource
import app.alfrd.engram.cognitive.pipeline.horizon.DirectorMessageDigest
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPolicy
import app.alfrd.engram.cognitive.pipeline.horizon.RenderedBoard
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/**
 * Builds the Director's standing prompt for one identity: assembles the Context Horizon at a given cycle, reads the machine's state,
 * and renders both with [DirectorPromptRenderer]. The one place that does it, so the tick and the owner's Horizon tool
 * ([app.alfrd.engram.api.configureOwnerHorizonRoutes]) cannot drift: what the tool returns is what the tick would have written.
 *
 * [render] with `forChannel` swaps only the opening (a neutral preamble for a channel such as Claude, not the Director's policy).
 *
 * Zoomed out (no focus), the Horizon is the board: a fixed-budget status view built by [BoardComposer] from [BoardSource], the same for the tick and the
 * tool. "Since last time" is per [channel]; each channel's marker moves only when that channel is shown a board.
 *
 * Reads only; it writes nothing and allocates no cycle. Which cycle the Horizon is assembled at is the caller's to say.
 */
class StandingContext(
    private val email: String,
    private val tickEnabled: Boolean,
    private val intervalSeconds: Long,
    private val assembler: HorizonAssembler,
    private val probe: SelfStateProbe,
    private val notion: () -> NotionState,
    private val hermes: () -> Map<String, Int>,
    private val budgets: () -> BudgetDefaults,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Where the zoomed-out board gets its facts. Null only where no board is wanted (a test); the tick and the owner's tool both pass one. */
    private val board: BoardSource? = null,
    /** Where the focused-view size budget (`zoomTokenBudget`) comes from; the board's policy when there is a board, else the environment's. */
    private val policy: HorizonPolicy = board?.policy ?: HorizonPolicy.fromEnvironment(),
    /** Where the channel's "From the Director" section comes from; null where there is none (the tick, which is the Director itself, never reads it). */
    private val directorMessages: DirectorMessageDigest? = null,
) {
    data class Rendered(val text: String, val outcome: AssembleOutcome, val state: SelfState, val board: RenderedBoard? = null)

    /**
     * [refsFor], when given, is shown the assembled Horizon and returns the short ref of each phrase uid it will let the reader name (the owner's tool
     * builds its snapshot there, so the refs printed beside a stale question or an also-due line are the very ones the snapshot lists).
     */
    suspend fun render(
        marker: ChangeMarker, changes: List<ChangeMarker.Change>, reason: String, now: Instant, cycleSeq: Long, forChannel: Boolean = false,
        channel: String = DEFAULT_CHANNEL,
        refsFor: suspend (AssembleOutcome, BoardPlan?) -> Map<String, String> = { _, _ -> emptyMap() },
    ): Rendered {
        val assembled = assembler.assemble(email, cycleSeq)
        // A focused view is held to the zoom budget; zoomed out, the board has its own.
        // Hidden (suppressed) items are never in the open number here, so a project reads "5 open, 5 hidden" in the board and in its focused view alike.
        val counted = (assembled as? AssembleOutcome.Assembled)?.let { AssembleOutcome.Assembled(withoutHiddenInOpen(it.horizon)) }
        val fit = counted?.let { FocusedViewBudget.fit(it.horizon, zone, policy) }
        val outcome: AssembleOutcome = if (fit != null) AssembleOutcome.Assembled(fit.horizon) else assembled
        val omitted = fit?.omitted ?: 0
        // The board is for the zoomed-out view only; a focused view keeps its own listing.
        val zoomedOut = (outcome as? AssembleOutcome.Assembled)?.horizon?.focus == null && outcome is AssembleOutcome.Assembled
        val source = board.takeIf { zoomedOut }
        val data = source?.read(email, channel, cycleSeq)
        val plan = data?.let { BoardComposer(source.policy, source.zone).plan(it) }
        val refs = refsFor(outcome, plan)
        val shownBoard: RenderedBoard? = when {
            plan != null -> plan.render(refs)
            source != null -> RenderedBoard("Board unavailable this time: the graph read failed.", 0, source.policy.boardTokenBudget, 0, 0, emptyList())
            else -> null
        }
        if (plan != null) source?.markRead(channel, now.toEpochMilli())
        val state = SelfState(
            tickEnabled = tickEnabled,
            intervalSeconds = intervalSeconds,
            tickAt = now.toString(),
            renderReason = reason,
            servers = withContext(Dispatchers.IO) { probe.servers() },
            memory = withContext(Dispatchers.IO) { probe.memory() },
            notion = notion(),
            hermesInFlight = hermes(),
            budgets = budgets(),
            marker = marker,
            changes = changes,
        )
        val fromDirector = if (forChannel) directorMessages?.section(email, channel, now.toEpochMilli()) else null
        val text = if (forChannel) DirectorPromptRenderer.renderForChannel(state, outcome, zone, now, refs, shownBoard, omitted, fromDirector)
        else DirectorPromptRenderer.render(state, outcome, zone, now, refs, shownBoard, omitted)
        return Rendered(text, outcome, state, shownBoard)
    }

    /** The container views with suppressed items taken out of the open, active and waiting counts (the model keeps them in [app.alfrd.engram.cognitive.pipeline.horizon.HorizonContainerView.hiddenItems]). */
    private fun withoutHiddenInOpen(h: app.alfrd.engram.cognitive.pipeline.horizon.ContextHorizon) =
        if (h.containers.none { it.hiddenItems > 0 }) h else h.copy(containers = h.containers.map {
            it.copy(openItems = it.openItems - it.hiddenItems, activeItems = it.activeItems - it.hiddenActive, waitingItems = it.waitingItems - it.hiddenWaiting)
        })

    companion object {
        /** The channel name the Director tick reads the board under; the owner's tool uses its own. */
        const val DEFAULT_CHANNEL = "director-tick"
    }
}
