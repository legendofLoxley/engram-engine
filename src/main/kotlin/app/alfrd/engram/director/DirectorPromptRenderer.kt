package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.Actor
import app.alfrd.engram.cognitive.pipeline.HorizonItemsRenderer
import app.alfrd.engram.cognitive.pipeline.HorizonPromptItem
import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The Director's standing prompt when no one has spoken: the same standing policy a conversational turn carries, then "Your situation",
 * then the Context Horizon rendered by the very functions a turn uses ([HorizonItemsRenderer]). Pure: text in, text out.
 *
 * The identity's own memory text is in this output (item texts), so it is written only to a file readable by the owner and is never logged.
 */
object DirectorPromptRenderer {

    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

    fun render(state: SelfState, horizon: AssembleOutcome, zone: ZoneId, now: Instant): String = buildString {
        append(Actor.STANDING_CONTEXT_POLICY)
        append("\n\nThis is your standing context, built without a message from the user: no one is speaking to you, and nothing here was just said.\n\n")
        append(situation(state, zone))
        append("\n\n")
        append(horizonSection(horizon, zone, now))
        append('\n')
    }

    /** Opening of the channel rendering: a description of what follows, with provenance guidance for a reader; no identity or role. */
    internal const val CHANNEL_PREAMBLE =
        "alfrd Horizon for the owner, as read by a channel. Below: alfrd's own situation, what changed in memory, and what the Context Horizon " +
            "brings into view. The Horizon is a selection, not an exhaustive inventory; missing evidence does not mean something never happened. " +
            "Each item keeps its own source, uncertainty and status; something a document or Hermes reported is not the owner's commitment " +
            "unless the owner adopted it."

    /**
     * The same situation and Horizon as [render], opened with [CHANNEL_PREAMBLE] instead of the Director's policy. Used only by the owner's
     * Horizon tool: the channel reading it is Claude and stays itself; alfrd is the memory behind it.
     */
    fun renderForChannel(state: SelfState, horizon: AssembleOutcome, zone: ZoneId, now: Instant): String = buildString {
        append(CHANNEL_PREAMBLE)
        append("\n\n")
        append(situation(state, zone))
        append("\n\n")
        append(horizonSection(horizon, zone, now))
        append('\n')
    }

    fun situation(s: SelfState, zone: ZoneId): String = buildString {
        appendLine("Your situation:")
        appendLine("- Tick: ${if (s.tickEnabled) "enabled" else "disabled"}, every ${s.intervalSeconds} s; this one at ${stamp.format(Instant.parse(s.tickAt).atZone(zone))} (${s.renderReason}).")
        appendLine("- Model servers: " + s.servers.joinToString(", ") { "${it.name} :${it.port} ${if (it.up) "up" else "DOWN"}" } + ".")
        appendLine("- Memory: GPU ${bytes(s.memory.vramUsedBytes)} of ${bytes(s.memory.vramTotalBytes)} in use; system RAM ${bytes(s.memory.ramUsedBytes)} of ${bytes(s.memory.ramTotalBytes)} in use.")
        appendLine("- Notion cycle channel: " + notion(s.notion) + ".")
        appendLine("- Hermes assignments in flight: " + (if (s.hermesInFlight.isEmpty()) "none" else s.hermesInFlight.entries.joinToString(", ") { "${it.value} ${it.key}" }) + ".")
        val b = s.budgets
        appendLine("- Cycle defaults: up to ${b.taskBudget} tasks per cycle (hard ceiling ${b.taskBudgetCeiling}); plan call timeout ${b.planTimeoutSeconds} s, at most ${b.planMaxTokens} tokens" + (if (b.configured) " (as configured)" else " (built-in defaults; no plan endpoint configured)") + ".")
        appendLine("- Memory marker: " + marker(s.marker) + ".")
        append("- Changed since the last tick: " + when {
            s.changes.isEmpty() -> "nothing."
            s.changes.all { it.before == null } -> "nothing to compare with: this is the first tick since the engine started."
            else -> s.changes.joinToString("; ") + "."
        })
    }

    private fun notion(n: NotionState): String =
        if (!n.enabled) "not running (${n.reason ?: "no reason given"})"
        else "running, polls every ${n.pollSeconds} s, last poll ${n.lastPollAt ?: "not yet"}, ${n.consecutiveFailures ?: 0} failures in a row, ${n.cyclesHeld ?: 0} cycles held under lease"

    private fun marker(m: ChangeMarker): String =
        "cycle ${m.lastCycleSeq}; " + ChangeMarker.KEYS.joinToString(", ") { "$it ${m.counts[it] ?: 0}" } +
            "; newest source read ${m.newestSourceReadAt?.let { Instant.ofEpochMilli(it) } ?: "never"}" +
            ", newest extraction run started ${m.newestRunStartedAt?.let { Instant.ofEpochMilli(it) } ?: "never"}"

    private fun horizonSection(outcome: AssembleOutcome, zone: ZoneId, now: Instant): String = when (outcome) {
        is AssembleOutcome.Assembled -> {
            val horizon = outcome.horizon
            val items = HorizonItemsRenderer.render(horizon, zone)
            val frame = HorizonItemsRenderer.composeOrientation(horizon, zone)
            "Contextual awareness — background orientation from your durable memory of this user:\n" +
                (frame?.let { "$it\n\n" } ?: "") +
                if (items.isEmpty()) "(Nothing is in view: no focus, no open items, no recent reports. This says nothing about what is in memory.)" else grouped(items)
        }
        is AssembleOutcome.ConsistencyFailure -> "Contextual awareness: unavailable this tick (a writer held the graph: ${outcome.reason})."
        is AssembleOutcome.QueryFailure -> "Contextual awareness: unavailable this tick (the graph read failed: ${outcome.reason})."
        is AssembleOutcome.BudgetExceeded -> "Contextual awareness: unavailable this tick (${outcome.reason})."
    }

    /** The same grouping a conversational turn uses (see Actor.renderGrouped): headings in the order their first item appears. */
    private fun grouped(items: List<HorizonPromptItem>): String {
        if (items.all { it.group == null }) return items.joinToString("\n") { "- ${it.renderedLine}" }
        val byGroup = LinkedHashMap<String?, MutableList<HorizonPromptItem>>()
        for (item in items) byGroup.getOrPut(item.group) { mutableListOf() } += item
        return byGroup.entries.joinToString("\n\n") { (heading, group) ->
            (heading?.let { "$it:\n" } ?: "") + group.joinToString("\n") { "- ${it.renderedLine}" }
        }
    }

    private fun bytes(b: Long?): String = if (b == null) "unknown" else "%.1f GB".format(b / 1_073_741_824.0)
}
