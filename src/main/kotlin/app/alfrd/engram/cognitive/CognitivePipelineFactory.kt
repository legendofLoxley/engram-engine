package app.alfrd.engram.cognitive

import app.alfrd.engram.cognitive.pipeline.CognitivePipeline
import app.alfrd.engram.cognitive.pipeline.FirstSessionHandler
import app.alfrd.engram.cognitive.pipeline.HorizonCycleCoordinator
import app.alfrd.engram.cognitive.pipeline.Interpreter
import app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService
import app.alfrd.engram.cognitive.pipeline.hermes.HermesAcpClient
import app.alfrd.engram.cognitive.pipeline.hermes.HermesDelegationDispatcher
import app.alfrd.engram.cognitive.pipeline.horizon.ActorEventIngestionService
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeRequestLedger
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.cognitive.pipeline.memory.DatabaseEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.EpisodicLogService
import app.alfrd.engram.cognitive.pipeline.memory.InMemoryEngramClient
import app.alfrd.engram.cognitive.pipeline.memory.MemoryWriteService
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import app.alfrd.engram.cognitive.pipeline.selection.ResponseSelectionService
import app.alfrd.engram.cognitive.providers.cloud.CloudLlmClient
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import com.arcadedb.database.Database

/**
 * Assembles a [CognitivePipeline] with production-grade dependencies:
 * - [InMemoryEngramClient] for memory (ArcadeDB-backed client is a future task)
 * - [CloudLlmClient] wired to Anthropic and Google AI when API keys are present
 * - [ResponseSelectionService] when a database instance is provided
 * - [FirstSessionHandler] when a database instance is provided (enables identity verification)
 *
 * If both API keys are absent the pipeline is created without an LLM client and
 * all branches degrade gracefully to their rule-based fallbacks.
 */
object CognitivePipelineFactory {

    /**
     * @param enableHermesDelegation Wires a real [HermesDelegationDispatcher] into the returned
     *   pipeline (only possible when [db] is non-null — it shares the same `ActorEventIngestionService`
     *   dependencies as `/debug/actor-event`). Defaults to `false` so every existing call site
     *   (including every test that constructs a pipeline via this factory) is unaffected. Pass
     *   `true` only for the isolated debug/dev session pool this bounded slice targets — see
     *   `Application.kt`'s `DEBUG_CONVERSE_ENABLED` block.
     */
    fun create(db: Database? = null, sessionManager: SessionManager? = null, enableHermesDelegation: Boolean = false): CognitivePipeline {
        val anthropicKey = System.getenv("ANTHROPIC_API_KEY") ?: ""
        val googleKey    = System.getenv("GOOGLE_AI_API_KEY") ?: ""

        // LLM_PROVIDER=local is an explicit opt-in (e.g. a dev box with a local model endpoint) —
        // it always wins over cloud keys so presence of both never silently prefers the cloud
        // provider. Unset (the default, including every existing deployment), behavior is
        // unchanged: cloud keys wire CloudLlmClient, their absence leaves the pipeline LLM-less.
        val llmClient = if (System.getenv("LLM_PROVIDER")?.lowercase() == "local") {
            LocalLlmClient()
        } else if (anthropicKey.isNotBlank() || googleKey.isNotBlank()) {
            CloudLlmClient(
                anthropicApiKey = anthropicKey,
                googleApiKey    = googleKey,
            )
        } else null

        val selectionService  = db?.let { ResponseSelectionService(it) }
        val engramClient      = if (db != null) DatabaseEngramClient(db) else InMemoryEngramClient()
        val transitionService = TrustPhaseTransitionService(engramClient)
        val confidenceService = TopicConfidenceService(engramClient)
        val episodicLogService = EpisodicLogService(engramClient)

        val firstSessionHandler = if (db != null && sessionManager != null) {
            FirstSessionHandler(
                userGraphService = UserGraphService(db),
                sessionManager   = sessionManager,
            )
        } else null

        // Owns interpretation -> graph mutation -> propagation -> refreshed-Horizon assembly for
        // every PROCESS turn (see HorizonCycleCoordinator). Only wired when a real Database is
        // available — the same condition ResponseSelectionService already requires — so any
        // DB-less pipeline (tests, or a deployment without one) falls back to CognitivePipeline's
        // legacy memoryWriteService path unchanged.
        val horizonCycleCoordinator = db?.let {
            val horizonGraphStore = ArcadeHorizonGraphStore(it)
            val horizonAssembler = ArcadeHorizonAssembler(it)
            HorizonCycleCoordinator(
                cycleSequencer    = ArcadeCycleSequencer(it),
                requestLedger     = ArcadeRequestLedger(it),
                interpreter       = Interpreter(llmClient),
                engramClient      = engramClient,
                horizonGraphStore = horizonGraphStore,
                horizonPropagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler),
                horizonAssembler  = horizonAssembler,
            )
        }

        // Shares the exact dependency shape DebugActorEventRoutes.kt already builds for
        // /debug/actor-event — same ActorEventIngestionService construction, just handed to a
        // real dispatcher instead of a debug-token-gated HTTP handler.
        val hermesDelegationDispatcher = if (enableHermesDelegation && db != null) {
            val horizonGraphStore = ArcadeHorizonGraphStore(db)
            val horizonAssembler = ArcadeHorizonAssembler(db)
            val ingestionService = ActorEventIngestionService(
                cycleSequencer = ArcadeCycleSequencer(db),
                horizonGraphStore = horizonGraphStore,
                horizonPropagator = SalientTokenPropagator(horizonGraphStore, horizonAssembler),
            )
            HermesDelegationDispatcher(HermesAcpClient(), ingestionService)
        } else null

        return CognitivePipeline(
            engramClient          = engramClient,
            llmClient             = llmClient,
            selectionService      = selectionService,
            memoryWriteService    = MemoryWriteService(engramClient),
            transitionService     = transitionService,
            firstSessionHandler   = firstSessionHandler,
            confidenceService     = confidenceService,
            episodicLogService    = episodicLogService,
            horizonCycleCoordinator = horizonCycleCoordinator,
            hermesDelegationDispatcher = hermesDelegationDispatcher,
        )
    }
}
