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
 * @param engramClient  Memory backend to write to.
 * @param scope         Coroutine scope for launches. Override in tests to inject a
 *                      controllable scope (e.g. the [kotlinx.coroutines.test.TestScope]).
 */
class MemoryWriteService(
    private val engramClient: EngramClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
) {

    private val logger = Logger.getLogger(MemoryWriteService::class.java.name)

    /**
     * Decomposes [utterance] into phrase candidates and ingests them asynchronously.
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
            } catch (e: Exception) {
                logger.warning(
                    "Memory write failed for userId=$userId sessionId=$sessionId turn=$turnIndex: ${e.message}"
                )
            }
        }
    }
}
