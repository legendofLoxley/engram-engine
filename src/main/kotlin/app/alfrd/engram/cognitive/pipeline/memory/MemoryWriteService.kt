package app.alfrd.engram.cognitive.pipeline.memory

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
 * @param engramClient Memory backend to write to.
 * @param scope        Coroutine scope for launches. Override in tests to inject a
 *                     controllable scope (e.g. the [kotlinx.coroutines.test.TestScope]).
 */
class MemoryWriteService(
    private val engramClient: EngramClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
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
