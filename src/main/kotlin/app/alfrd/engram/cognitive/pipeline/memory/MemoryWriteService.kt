package app.alfrd.engram.cognitive.pipeline.memory

import app.alfrd.engram.cognitive.pipeline.scaffold.TransitionDecision
import app.alfrd.engram.cognitive.pipeline.scaffold.TrustPhaseTransitionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * Handles all memory writes asynchronously so phrase ingestion never blocks the pipeline.
 *
 * Each call to [captureUtterance] fires a coroutine in [scope] and returns immediately.
 * A [SupervisorJob] (when using the default scope) ensures one failed write does not
 * cancel other in-flight writes. All exceptions are caught and logged — write-path
 * failures must never surface to the user.
 *
 * After successful ingestion, if [captureUtterance] was given a [scaffoldCategory],
 * the scaffold state is updated via a read-modify-write so the answered category is
 * persisted in sync with the actual ingested data.
 *
 * When a new category is added (not an amendment of an existing one), [transitionService]
 * is asked to evaluate whether the updated state satisfies advancement criteria. Any
 * resulting transition is applied before the coroutine exits.
 *
 * @param engramClient      Memory backend to write to.
 * @param scope             Coroutine scope for launches. Override in tests to inject a
 *                          controllable scope (e.g. the [kotlinx.coroutines.test.TestScope]).
 * @param transitionService Optional phase-transition service. When null, transitions are
 *                          never evaluated — the phase stays unchanged until the service
 *                          is wired in.
 */
class MemoryWriteService(
    private val engramClient: EngramClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val transitionService: TrustPhaseTransitionService? = null,
) {

    private val logger = Logger.getLogger(MemoryWriteService::class.java.name)

    /**
     * Decomposes [utterance] into phrase candidates and ingests them asynchronously.
     *
     * @param utterance      Raw user utterance.
     * @param userId         User id — included in scaffold state updates.
     * @param sessionId      Session id — for log correlation.
     * @param turnIndex      Turn number within the session — for log correlation.
     * @param scaffoldCategory  If non-null, the [PhraseCategory] name that this utterance
     *                          was answering. After successful ingestion, that category is
     *                          added to the user's answered set in the scaffold state.
     * @param sourceTag      Source label stored with each ingested phrase (affects trust weight).
     */
    fun captureUtterance(
        utterance: String,
        userId: String,
        sessionId: String,
        turnIndex: Int,
        scaffoldCategory: String?,
        sourceTag: String = "onboarding_conversation",
    ) {
        scope.launch {
            try {
                val candidates = engramClient.decompose(utterance, emptyList())
                if (candidates.isNotEmpty()) {
                    engramClient.ingest(candidates)
                }

                // After successful ingestion, persist the answered scaffold category so
                // progress is in sync with what actually made it into the graph.
                if (scaffoldCategory != null) {
                    val category = try {
                        PhraseCategory.valueOf(scaffoldCategory)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                    if (category != null) {
                        try {
                            val state = engramClient.getScaffoldState(userId)
                            if (category !in state.answeredCategories) {
                                engramClient.updateScaffoldState(
                                    userId,
                                    state.copy(answeredCategories = state.answeredCategories + category),
                                )
                                // Only evaluate transition when a genuinely new category was added.
                                // Amending an existing phrase (same category already answered) skips this.
                                if (transitionService != null) {
                                    try {
                                        val updatedState = engramClient.getScaffoldState(userId)
                                        val decision = transitionService.evaluate(updatedState)
                                        if (decision is TransitionDecision.Transition) {
                                            transitionService.apply(userId, decision)
                                            logger.info(
                                                "Trust phase transition: user=$userId " +
                                                    "${transitionService.phaseIntToString(decision.from)} → " +
                                                    "${transitionService.phaseIntToString(decision.to)} " +
                                                    "evidence='${decision.evidence}'"
                                            )
                                        }
                                    } catch (e: Exception) {
                                        logger.warning(
                                            "Phase transition evaluation failed for userId=$userId " +
                                                "turn=$turnIndex: ${e.message}"
                                        )
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            logger.warning(
                                "Scaffold state update failed for userId=$userId turn=$turnIndex: ${e.message}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Never propagate — write failures must not affect the user-facing response
                logger.warning(
                    "Memory write failed for userId=$userId sessionId=$sessionId turn=$turnIndex: ${e.message}"
                )
            }
        }
    }
}
