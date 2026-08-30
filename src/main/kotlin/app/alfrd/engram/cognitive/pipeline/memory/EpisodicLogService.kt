package app.alfrd.engram.cognitive.pipeline.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.logging.Logger

/**
 * Writes each turn to the durable episodic conversation log asynchronously so it never blocks
 * the pipeline — mirrors [MemoryWriteService]'s fire-and-forget contract exactly, but writes to
 * a structurally separate part of the graph (see [EngramClient.appendEpisodicTurn]).
 *
 * @param engramClient  Memory backend to write to.
 * @param scope         Coroutine scope for launches. Override in tests to inject a
 *                      controllable scope (e.g. the [kotlinx.coroutines.test.TestScope]).
 */
class EpisodicLogService(
    private val engramClient: EngramClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val logger = Logger.getLogger(EpisodicLogService::class.java.name)

    /**
     * Records one turn (both the user's utterance and alfrd's response) into the episodic log.
     *
     * @param sessionId     Session id — chain scope and log correlation.
     * @param userId        User id — for episodic-log attribution and log correlation.
     * @param turnIndex     Turn number within the session — for log correlation.
     * @param userUtterance Raw user utterance.
     * @param alfrdResponse alfrd's final response text for this turn.
     */
    fun recordTurn(
        sessionId: String,
        userId: String,
        turnIndex: Int,
        userUtterance: String,
        alfrdResponse: String,
    ) {
        scope.launch {
            try {
                engramClient.appendEpisodicTurn(sessionId, userId, turnIndex, userUtterance, alfrdResponse)
            } catch (e: Exception) {
                logger.warning(
                    "Episodic log write failed for userId=$userId sessionId=$sessionId turn=$turnIndex: ${e.message}"
                )
            }
        }
    }
}
