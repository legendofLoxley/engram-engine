package app.alfrd.engram.director

import app.alfrd.engram.cognitive.pipeline.horizon.AssembleOutcome
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
) {
    data class Rendered(val text: String, val outcome: AssembleOutcome, val state: SelfState)

    suspend fun render(marker: ChangeMarker, changes: List<ChangeMarker.Change>, reason: String, now: Instant, cycleSeq: Long): Rendered {
        val outcome = assembler.assemble(email, cycleSeq)
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
        return Rendered(DirectorPromptRenderer.render(state, outcome, zone, now), outcome, state)
    }
}
