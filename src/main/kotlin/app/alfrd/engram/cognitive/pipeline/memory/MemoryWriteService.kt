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
 * @param engramClient      Memory backend to write to.
 * @param scope             Coroutine scope for launches. Override in tests to inject a
 *                          controllable scope (e.g. the [kotlinx.coroutines.test.TestScope]).
 * @param transitionService When non-null, [captureUtterance] also folds newly-disclosed
 *                          [PhraseCategory]s into scaffold state and checks whether the
 *                          trust-phase advance rule now fires. Null (the default) skips
 *                          this — matches every other optional-service param in this codebase.
 */
class MemoryWriteService(
    private val engramClient: EngramClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val transitionService: TrustPhaseTransitionService? = null,
) {

    private val logger = Logger.getLogger(MemoryWriteService::class.java.name)

    /**
     * Decomposes [utterance] into phrase candidates and ingests them asynchronously, then
     * folds any newly-disclosed categories into scaffold state and checks the trust-phase
     * advance rule. Runs every turn regardless of whether this utterance itself produced
     * candidates — sessionCount (bumped once per session at [CognitivePipeline.initSession])
     * can independently cross an advance threshold, and a contentless turn ("ok", "hmm")
     * must not delay noticing that.
     *
     * @param utterance   Raw user utterance.
     * @param userId      User id — for memory attribution and log correlation.
     * @param sessionId   Session id — for log correlation.
     * @param turnIndex   Turn number within the session — for log correlation.
     * @param sourceTag   Source label stored with each ingested phrase.
     */
    fun captureUtterance(
        utterance: String,
        userId: String,
        sessionId: String,
        turnIndex: Int,
        sourceTag: String = "conversation",
    ) {
        scope.launch {
            try {
                val candidates = engramClient.decompose(utterance, emptyList())
                if (candidates.isNotEmpty()) {
                    engramClient.ingest(candidates, userId)
                }

                // Fold newly-disclosed categories into scaffold state, then check whether
                // the trust-phase advance rule now fires. Read-modify-write, best-effort —
                // a missed update on one turn self-heals on the next.
                val current = engramClient.getScaffoldState(userId)
                val merged = current.answeredCategories + candidates.map { it.category }
                val updated = if (merged.size > current.answeredCategories.size) {
                    current.copy(answeredCategories = merged).also {
                        engramClient.updateScaffoldState(userId, it)
                    }
                } else {
                    current
                }

                transitionService?.let { svc ->
                    val decision = svc.evaluate(updated)
                    if (decision is TransitionDecision.Transition) {
                        svc.apply(userId, decision)
                    }
                }
            } catch (e: Exception) {
                logger.warning(
                    "Memory write failed for userId=$userId sessionId=$sessionId turn=$turnIndex: ${e.message}"
                )
            }
        }
    }
}
