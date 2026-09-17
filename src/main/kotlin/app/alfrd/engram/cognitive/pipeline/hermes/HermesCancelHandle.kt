package app.alfrd.engram.cognitive.pipeline.hermes

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.OutputStreamWriter

/**
 * Coordinates a single [HermesAssignment]'s cancellation between the requester (the debug
 * cancel route, via [HermesActiveAssignmentRegistry]) and the in-flight ACP exchange itself
 * ([HermesAcpClient]). Confirmed by direct inspection of the installed `hermes-acp` runtime
 * (`acp_adapter/server.py`): `session/cancel` is a real JSON-RPC *notification* the agent
 * actually honors — it sets an internal `cancel_event`, best-effort calls `agent.interrupt()`,
 * and (if the interrupt actually took effect before the turn's own executor call returned)
 * reports `stopReason="cancelled"` on the *original* `session/prompt` response. Critically, the
 * installed runtime checks that flag only *after* its own agent turn returns — so a `Completed`-
 * shaped response can still arrive after a cancellation was requested, if the agent's own work
 * happened to finish anyway. This handle's job is to make sure that never matters: whichever
 * outcome the exchange produces, if cancellation was requested at all, the caller must treat it
 * as [HermesAssignmentOutcome.Cancelled] — never as an ordinary accepted completion.
 *
 * All mutable state lives behind [lock], since the requester and the exchange's own thread run
 * concurrently. [requestCancel] and [checkCancelledAndFinish] race exactly once against each
 * other per assignment — see each method's own doc for what "winning" means.
 */
class HermesCancelHandle {
    private val logger = LoggerFactory.getLogger(HermesCancelHandle::class.java)
    private val lock = Any()
    private var cancelRequestedAtMs: Long? = null
    private var finished = false
    private var process: Process? = null
    private var sessionId: String? = null

    /**
     * Called once by [HermesAcpClient] as soon as the real ACP `sessionId` is known (right after
     * `session/new` resolves). If a cancellation was already requested by that point, sends the
     * ACP notification immediately — there was nothing to notify before this, since hermes-acp
     * addresses cancellation by session id, not by our own assignment id.
     */
    internal fun attach(process: Process, sessionId: String) {
        val shouldNotify = synchronized(lock) {
            this.process = process
            this.sessionId = sessionId
            cancelRequestedAtMs != null && !finished
        }
        if (shouldNotify) sendCancelNotification()
    }

    /**
     * Requests cancellation. Returns `true` if this assignment was still genuinely open to being
     * cancelled at the moment of the call (i.e. [checkCancelledAndFinish] had not yet run) —
     * `false` means it was already finished, so this request arrived too late to have any effect
     * at all. Idempotent: a second call while still open also returns `true` without re-sending
     * the ACP notification.
     */
    fun requestCancel(): Boolean {
        val shouldNotify = synchronized(lock) {
            if (finished) return false
            if (cancelRequestedAtMs != null) return true
            cancelRequestedAtMs = System.currentTimeMillis()
            process != null && sessionId != null
        }
        if (shouldNotify) sendCancelNotification()
        return true
    }

    fun isCancelRequested(): Boolean = synchronized(lock) { cancelRequestedAtMs != null }

    fun cancelRequestedAtMs(): Long? = synchronized(lock) { cancelRequestedAtMs }

    /**
     * Called exactly once by [HermesAcpClient], at the single point it has committed to a final
     * outcome for this assignment (whether that path was a normal return or an exception).
     * Atomically reads whether cancellation was ever requested *and* marks this handle finished,
     * in the same critical section [requestCancel] uses — so a [requestCancel] call that has not
     * yet acquired the lock when this runs will correctly see [finished] and report "too late",
     * and one that already set [cancelRequestedAtMs] before this runs is correctly honored here.
     * Returns the request timestamp, or null if cancellation was never requested for this
     * assignment at all.
     */
    internal fun checkCancelledAndFinish(): Long? = synchronized(lock) {
        val at = cancelRequestedAtMs
        finished = true
        at
    }

    private fun sendCancelNotification() {
        val (proc, sid) = synchronized(lock) { process to sessionId }
        val targetProcess = proc ?: return
        val targetSessionId = sid ?: return
        try {
            // A separate Writer over the same underlying pipe as HermesAcpClient's own — safe
            // in practice for these single, short (well under PIPE_BUF) JSON-RPC lines, each
            // flushed as one write. Worst case on a true race is send-order ambiguity between
            // this notification and the exchange's own next line, never byte-level corruption.
            val writer = BufferedWriter(OutputStreamWriter(targetProcess.outputStream, Charsets.UTF_8))
            val line = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("method", JsonPrimitive("session/cancel"))
                put("params", buildJsonObject { put("sessionId", JsonPrimitive(targetSessionId)) })
            }.toString()
            writer.write(line)
            writer.write("\n")
            writer.flush()
        } catch (e: Exception) {
            // Best-effort only — the watchdog's bounded grace-then-force-kill in HermesAcpClient
            // is the actual guarantee that this assignment stops, regardless of whether hermes-acp
            // ever sees or honors this notification.
            logger.debug("HermesCancelHandle: failed to send session/cancel notification: {}", e.message)
        }
    }
}
