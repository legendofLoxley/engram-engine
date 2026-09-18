package app.alfrd.engram.cognitive.pipeline.hermes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** What a real Hermes ACP session actually produced for one [HermesAssignment]. */
sealed interface HermesAssignmentOutcome {
    data class Completed(
        val findingsText: String,
        val toolName: String,
        val toolTargetPath: String?,
        /** Self-reported from the observed tool-call + stop-reason shape — not independently
         *  re-verified by re-reading the file ourselves (mirrors this codebase's existing
         *  [app.alfrd.engram.cognitive.pipeline.horizon.ActorEventKind.ToolResult] honesty
         *  convention: a tool result is a claim, not a verified fact). */
        val toolSucceeded: Boolean,
    ) : HermesAssignmentOutcome
    data class Failed(val reason: String) : HermesAssignmentOutcome

    /**
     * Cancellation was requested for this assignment (via [HermesCancelHandle]) before it settled
     * into an ordinary [Completed]/[Failed] result — see that handle's own doc for exactly how the
     * installed ACP runtime's `session/cancel` support was inspected and why this outcome is
     * chosen deliberately, regardless of what the underlying exchange itself reported. [reason]
     * distinguishes hermes-acp confirming the cancellation itself (`stopReason="cancelled"`) from
     * this client honoring the request anyway despite a different reported stop reason, or from a
     * forced process kill after the grace period elapsed with no response at all. [partialText] is
     * whatever content the exchange happened to produce regardless — informational only, and
     * [HermesCompletionDirector] never delivers it as a confirmed finding.
     */
    data class Cancelled(val partialText: String?, val reason: String) : HermesAssignmentOutcome
}

/**
 * A minimal client for the real hermes-agent Agent Client Protocol (ACP) interface — the
 * "installed Hermes invocation contract" for this box, confirmed by direct inspection: the
 * production `hermes-halo` image already bundles a working `hermes-acp` entry point (the
 * `acp` package and `venv/bin/hermes-acp` are present in the vendor image as shipped; no
 * image rebuild was needed). ACP is JSON-RPC 2.0 over newline-delimited stdio — confirmed
 * directly from the installed `acp/connection.py` docstring, not assumed.
 *
 * Every assignment spawns a fresh, isolated dev instance of the *exact* vendor image
 * (`docker run --rm`, one-shot, torn down after) — never the production `hermes-halo`
 * container and never its mounted personal data at `/var/lib/halo-home/hermes/data`. The
 * dev instance gets its own empty `HERMES_HOME` (`HERMES_DEV_HOME_DIR`) and a read-only bind
 * mount of a workspace directory containing exactly the one permitted fixture
 * (`HERMES_DEV_WORKSPACE_DIR`, `:ro`) as the ACP session's `cwd` — this is what "permission
 * limited to reading that fixture" means concretely: a sandboxed cwd Hermes's own tools
 * resolve paths against, backed by a read-only mount so no write is physically possible,
 * with [handleInboundRequest] below as a second, independent layer (deny any permission
 * request that doesn't target the fixture) rather than the only enforcement.
 *
 * `clientCapabilities.fs` is advertised as `false` for both read/write, so Hermes never asks
 * this client to proxy file I/O — it uses its own real internal tool against its own
 * sandboxed filesystem, which is the "real tool mechanism" the task requires, not a
 * client-side simulation of one.
 */
open class HermesAcpClient(
    private val dockerImage: String = System.getenv("HERMES_DEV_IMAGE") ?: "halo-home/hermes-halo:0.0.1",
    private val homeDir: String = System.getenv("HERMES_DEV_HOME_DIR") ?: "/home/halo/development/hermes-dev/home",
    private val workspaceDir: String = System.getenv("HERMES_DEV_WORKSPACE_DIR") ?: "/home/halo/development/hermes-dev/workspace",
    private val containerWorkspacePath: String = System.getenv("HERMES_DEV_CONTAINER_WORKSPACE") ?: "/home/hermes/workspace",
    private val promptTimeoutMs: Long = System.getenv("HERMES_DEV_PROMPT_TIMEOUT_MS")?.toLongOrNull() ?: 180_000L,
    private val cancelGraceMs: Long = System.getenv("HERMES_DEV_CANCEL_GRACE_MS")?.toLongOrNull() ?: 3_000L,
    /**
     * TEST-ONLY. Zero (off) unless a developer deliberately exports `HERMES_DEV_TEST_DELAY_MS`
     * before starting this service for the specific purpose of exercising cancellation UI/wiring
     * (the real fixture exchange normally completes in only a few seconds — too narrow a window
     * to reliably drive browser automation against). Never set in production, never touched by
     * any normal chat turn or by any HTTP request parameter — it is read once from the process
     * environment at construction, exactly like [promptTimeoutMs] and [cancelGraceMs] above. See
     * its use in [exchange] for exactly what it does and does not change about the real exchange.
     */
    private val testDelayMs: Long = System.getenv("HERMES_DEV_TEST_DELAY_MS")?.toLongOrNull() ?: 0L,
) {
    private val logger = LoggerFactory.getLogger(HermesAcpClient::class.java)

    companion object {
        private const val WATCHDOG_POLL_MS = 100L

        /**
         * The grounding half of [summarizeDocument]'s instruction to Hermes — extracted as its own
         * named, `internal` constant (rather than inlined in the prompt-building lambda) so
         * [HermesAcpClientTest] can assert on its exact wording deterministically, without Docker.
         * That assertion only proves the *instruction sent* requires this; it cannot prove Hermes's
         * own model actually honors it on any given run — see this increment's own demonstration
         * record for why a real, live-observed unlabeled cross-section association (not a
         * hypothetical) is what this exists to close, and for why one successful live run afterward
         * is evidence, not a guarantee, that grounding holds in general.
         */
        internal const val SUMMARIZE_DOCUMENT_INSTRUCTION =
            "Summarize it for the user in four short labeled parts — Goal, Deadlines, Risks, Next " +
                "actions — based only on what the file actually says. For every date, owner, or " +
                "dependency you mention, attach it only to the specific item the source text itself " +
                "explicitly connects it to — never to a different item just because it appears nearby " +
                "or elsewhere in the document. If connecting a date, owner, or dependency to an item " +
                "would be useful but the source does not explicitly state that connection, either " +
                "leave it unspecified for that item, or state it and clearly label it as your own " +
                "inference (for example: \"(inferred — not stated directly in the source)\") — never " +
                "present an inferred connection as if the source stated it directly. If a part " +
                "genuinely isn't covered by the file, say so plainly instead of guessing or inventing " +
                "detail."
    }

    /** The bounded fixture-marker slice — see this class's own doc. Shares [runAssignment]'s ACP
     *  handshake/sandboxing with [summarizeDocument] below, differing only in the prompt text. */
    open suspend fun inspectFixture(
        assignment: HermesAssignment,
        fixtureFilename: String,
        cancelHandle: HermesCancelHandle,
    ): HermesAssignmentOutcome = runAssignment(assignment, fixtureFilename, cancelHandle) { absolutePath ->
        "Please read the file at the absolute path $absolutePath using your file-reading tool, then reply " +
            "with exactly the Marker value it contains and nothing else."
    }

    /**
     * Reads one approved document and summarizes it — the same real ACP tool-using round trip as
     * [inspectFixture], not a second, cheaper mechanism: same container, same sandboxed
     * read-only mount, same [HermesWorkspacePath] validation, same permission-approval callback,
     * same cancellation/watchdog handling. The only difference is the instruction Hermes
     * receives and, correspondingly, what [HermesAssignmentOutcome.Completed.findingsText] ends
     * up containing (a short summary instead of one marker token) — [HermesCompletionDirector]
     * needs no change to deliver either verbatim, since it was already content-agnostic.
     */
    open suspend fun summarizeDocument(
        assignment: HermesAssignment,
        targetFilename: String,
        cancelHandle: HermesCancelHandle,
    ): HermesAssignmentOutcome = runAssignment(assignment, targetFilename, cancelHandle) { absolutePath ->
        "Please read the file at the absolute path $absolutePath using your file-reading tool. " +
            SUMMARIZE_DOCUMENT_INSTRUCTION
    }

    private suspend fun runAssignment(
        assignment: HermesAssignment,
        targetFilename: String,
        cancelHandle: HermesCancelHandle,
        buildPrompt: (absolutePath: String) -> String,
    ): HermesAssignmentOutcome =
        withContext(Dispatchers.IO) {
            // Enforced here, in code, before anything is spawned or sent to Hermes — not merely
            // implied by the read-only bind mount or trusted from the prompt text alone. See
            // HermesWorkspacePath's own doc for exactly what this catches (traversal, symlink
            // escapes, a genuinely missing file) and why real path resolution is used rather than
            // string matching.
            if (HermesWorkspacePath.resolve(workspaceDir, targetFilename) == null) {
                logger.warn(
                    "hermes-acp: assignment {} rejected — {} does not resolve to an existing file inside the approved workspace",
                    assignment.assignmentId, targetFilename,
                )
                return@withContext HermesAssignmentOutcome.Failed(
                    "requested file is missing or not accessible within the approved workspace: $targetFilename",
                )
            }

            val process = try {
                ProcessBuilder(buildCommand()).redirectErrorStream(false).start()
            } catch (e: Exception) {
                logger.warn("hermes-acp: failed to spawn dev container for assignment {}: {}", assignment.assignmentId, e.message)
                return@withContext HermesAssignmentOutcome.Failed("spawn_failed: ${e.message}")
            }

            // The blocking readLine() loop in exchange() has no cooperative-cancellation
            // checkpoint of its own — this watchdog is what actually bounds both a hung/slow turn
            // (the original timeout path) and a requested-but-unhonored cancellation (the new
            // grace-then-kill path): destroying the process closes its stdout pipe, which unblocks
            // readLine() with EOF either way. Polls instead of a single blocking waitFor() so it
            // can also notice a cancellation request that arrives mid-wait.
            val watchdog = Thread {
                try {
                    val deadline = System.currentTimeMillis() + promptTimeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        if (!process.isAlive) return@Thread
                        val cancelRequestedAt = cancelHandle.cancelRequestedAtMs()
                        if (cancelRequestedAt != null && System.currentTimeMillis() - cancelRequestedAt > cancelGraceMs) {
                            logger.info(
                                "hermes-acp: assignment {} did not stop within {}ms of cancellation — force-killing",
                                assignment.assignmentId, cancelGraceMs,
                            )
                            process.destroyForcibly()
                            return@Thread
                        }
                        Thread.sleep(WATCHDOG_POLL_MS)
                    }
                    if (process.isAlive) {
                        logger.warn("hermes-acp: assignment {} exceeded {}ms — killing subprocess", assignment.assignmentId, promptTimeoutMs)
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    // Normal path: exchange() finished and this thread was interrupted before the deadline.
                }
            }.apply { isDaemon = true; start() }

            try {
                exchange(process, assignment, targetFilename, cancelHandle, buildPrompt)
            } catch (e: Exception) {
                logger.warn("hermes-acp: assignment {} failed: {}", assignment.assignmentId, e.message, e)
                val cancelRequestedAt = cancelHandle.checkCancelledAndFinish()
                if (cancelRequestedAt != null) {
                    HermesAssignmentOutcome.Cancelled(
                        partialText = null,
                        reason = "process terminated during cancellation handling (${e.message ?: e::class.simpleName})",
                    )
                } else {
                    HermesAssignmentOutcome.Failed(e.message ?: (e::class.simpleName ?: "unknown_error"))
                }
            } finally {
                watchdog.interrupt()
                process.destroyForcibly()
            }
        }

    /**
     * The vendor image's own entrypoint hardcodes `exec hermes "$@"`, so a plain `docker run
     * ... hermes-acp` would try to run `hermes hermes-acp` (not a subcommand). Overriding
     * `--entrypoint` to a small inline shell keeps the one useful step from that entrypoint
     * (symlinking the mnemosyne memory plugin — harmless no-op since `memory.memory_enabled:
     * false` in the dev config) and then execs the real installed `hermes-acp` binary
     * directly. No vendor image layer is modified — this only changes the container's
     * startup command for this one-shot dev run.
     */
    private fun buildCommand(): List<String> = listOf(
        "docker", "run", "--rm", "-i",
        "--add-host", "host.docker.internal:host-gateway",
        "-v", "$homeDir:/home/hermes/.hermes",
        "-v", "$workspaceDir:$containerWorkspacePath:ro",
        "--entrypoint", "/bin/bash",
        dockerImage,
        "-c",
        "mkdir -p /home/hermes/.hermes/plugins && " +
            "ln -sfn /opt/hermes-agent/venv/lib/python3.11/site-packages/hermes_memory_provider " +
            "/home/hermes/.hermes/plugins/mnemosyne 2>/dev/null; " +
            "exec /opt/hermes-agent/venv/bin/hermes-acp",
    )

    /**
     * The full `initialize` → `session/new` → `session/prompt` handshake, blocking-sequential
     * by design (one assignment, one session, no concurrent exchange to coordinate) — verified
     * against the real installed runtime before this client was written (a raw JSON-RPC smoke
     * script against this exact image/config reproduced a genuine `read_file` tool call and a
     * reply containing the fixture's own marker token). [buildPrompt] is the one thing that
     * varies between [inspectFixture] and [summarizeDocument] — everything else here is shared,
     * unparameterized wire protocol.
     */
    private fun exchange(
        process: Process,
        assignment: HermesAssignment,
        targetFilename: String,
        cancelHandle: HermesCancelHandle,
        buildPrompt: (absolutePath: String) -> String,
    ): HermesAssignmentOutcome {
        val stdin = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
        val stdout = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val nextId = AtomicInteger(0)

        fun sendRequest(method: String, params: JsonObject, id: Int) {
            val line = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", JsonPrimitive(id))
                put("method", JsonPrimitive(method))
                put("params", params)
            }.toString()
            stdin.write(line); stdin.write("\n"); stdin.flush()
        }

        fun sendResult(id: JsonElement, result: JsonObject) {
            val line = buildJsonObject {
                put("jsonrpc", JsonPrimitive("2.0"))
                put("id", id)
                put("result", result)
            }.toString()
            stdin.write(line); stdin.write("\n"); stdin.flush()
        }

        var observedToolName: String? = null
        var observedToolPath: String? = null
        val messageText = StringBuilder()

        fun handleNotification(method: String, params: JsonObject?) {
            if (method != "session/update" || params == null) return
            val update = params["update"]?.jsonObject ?: return
            when (update["sessionUpdate"]?.jsonPrimitive?.contentOrNull) {
                "tool_call" -> {
                    observedToolName = update["kind"]?.jsonPrimitive?.contentOrNull ?: observedToolName
                    observedToolPath = update["locations"]?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("path")?.jsonPrimitive?.contentOrNull
                        ?: observedToolPath
                }
                "agent_message_chunk" -> {
                    update["content"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        ?.let { messageText.append(it) }
                }
                else -> Unit
            }
        }

        // The only inbound request this slice expects: approving (or not) a permission check
        // for a tool call. Allowed only when every location it names is the permitted fixture;
        // anything else — a different path, a write, a shell command — is denied. This is
        // defense in depth on top of the read-only mount and sandboxed cwd, not the only
        // enforcement (a plain read tool did not trigger this at all in the verified smoke
        // test, since `approvals.mode: auto` in the dev config didn't gate it — this handler
        // exists for correctness regardless of that config detail).
        fun handleInboundRequest(method: String, id: JsonElement, params: JsonObject?) {
            if (method != "session/request_permission" || params == null) {
                sendResult(id, buildJsonObject {
                    put("outcome", buildJsonObject { put("outcome", JsonPrimitive("selected")); put("optionId", JsonPrimitive("deny")) })
                })
                return
            }
            val locations = params["toolCall"]?.jsonObject?.get("locations")?.jsonArray
            val targetsFixtureOnly = locations != null && locations.isNotEmpty() &&
                locations.all { it.jsonObject["path"]?.jsonPrimitive?.contentOrNull?.contains(targetFilename) == true }
            val options = params["options"]?.jsonArray.orEmpty().map { it.jsonObject }
            val optionId = if (targetsFixtureOnly) {
                options.firstOrNull { it["optionId"]?.jsonPrimitive?.contentOrNull in setOf("allow_once", "allow_session") }
                    ?.get("optionId")?.jsonPrimitive?.contentOrNull
            } else null
            val resolvedOptionId = optionId
                ?: options.firstOrNull { it["optionId"]?.jsonPrimitive?.contentOrNull?.startsWith("deny") == true }
                    ?.get("optionId")?.jsonPrimitive?.contentOrNull
                ?: "deny"
            sendResult(id, buildJsonObject {
                put("outcome", buildJsonObject { put("outcome", JsonPrimitive("selected")); put("optionId", JsonPrimitive(resolvedOptionId)) })
            })
        }

        fun readUntil(targetId: Int): JsonObject {
            while (true) {
                val line = stdout.readLine()
                    ?: throw IllegalStateException("hermes-acp closed stdout before responding to request $targetId")
                if (line.isBlank()) continue
                val msg = Json.parseToJsonElement(line).jsonObject
                val id = msg["id"]
                if (id != null && (msg.containsKey("result") || msg.containsKey("error"))) {
                    if (id.jsonPrimitive.intOrNull == targetId) {
                        if (msg.containsKey("error")) {
                            throw IllegalStateException("hermes-acp error for request $targetId: ${msg["error"]}")
                        }
                        return msg["result"]?.jsonObject ?: JsonObject(emptyMap())
                    }
                    continue
                }
                val method = msg["method"]?.jsonPrimitive?.contentOrNull ?: continue
                if (id != null) handleInboundRequest(method, id, msg["params"]?.jsonObject)
                else handleNotification(method, msg["params"]?.jsonObject)
            }
        }

        val initId = nextId.getAndIncrement()
        sendRequest("initialize", buildJsonObject {
            put("protocolVersion", JsonPrimitive(1))
            put("clientCapabilities", buildJsonObject {
                put("fs", buildJsonObject { put("readTextFile", JsonPrimitive(false)); put("writeTextFile", JsonPrimitive(false)) })
                put("terminal", JsonPrimitive(false))
            })
            put("clientInfo", buildJsonObject { put("name", JsonPrimitive("alfrd-director")); put("version", JsonPrimitive("0.1.0")) })
        }, initId)
        readUntil(initId)

        val sessionId2 = nextId.getAndIncrement()
        sendRequest("session/new", buildJsonObject {
            put("cwd", JsonPrimitive(containerWorkspacePath))
            put("mcpServers", JsonArray(emptyList()))
        }, sessionId2)
        val sessionResult = readUntil(sessionId2)
        val sessionId = sessionResult["sessionId"]?.jsonPrimitive?.contentOrNull
            ?: return HermesAssignmentOutcome.Failed("session/new returned no sessionId")
        // Only from this point can a cancellation actually be notified to hermes-acp — it
        // addresses cancellation by ACP session id, which does not exist before this response.
        // A cancellation requested before now is still honored: attach() sends it immediately.
        cancelHandle.attach(process, sessionId)

        // TEST-ONLY (see [testDelayMs] doc on the constructor). Pauses here — after the real ACP
        // session exists and is genuinely cancellable, but before the prompt that would otherwise
        // let the ~4-8s real exchange run to completion — to produce a long, reliably-observable
        // "assignment running" window for exercising the native WebUI Stop button. A cancellation
        // requested during this pause goes through the exact same requestCancel()/attach() path
        // as one requested during the real prompt wait below (this loop only polls the same
        // cancelHandle the watchdog and the debug cancel route already share) — nothing about the
        // real ACP notification or the eventual outcome computation is faked or shortcut.
        if (testDelayMs > 0) {
            val delayDeadline = System.currentTimeMillis() + testDelayMs
            while (System.currentTimeMillis() < delayDeadline && !cancelHandle.isCancelRequested()) {
                Thread.sleep(WATCHDOG_POLL_MS)
            }
        }

        var stopReason: String? = null
        var promptSent = false
        if (!cancelHandle.isCancelRequested()) {
            promptSent = true
            // Sent as an ABSOLUTE path, not assignment.task verbatim (a relative filename +
            // relying on session/new's own cwd param) — verified live that Hermes's read_file
            // tool does not reliably resolve a bare relative filename against the ACP session's
            // cwd: reproduced against this exact isolated dev instance resolving instead against
            // the agent's own install directory (/opt/hermes-agent) and failing with "File not
            // found", then confirmed fixed by sending the absolute path directly. cwd (via
            // session/new) plus the read-only bind mount remain the actual sandboxing mechanism
            // ("permission limited to reading that fixture") — this only fixes how the path is
            // named in the prompt so the tool can find it at all.
            val absoluteTargetPath = "$containerWorkspacePath/$targetFilename"
            val promptId = nextId.getAndIncrement()
            sendRequest("session/prompt", buildJsonObject {
                put("sessionId", JsonPrimitive(sessionId))
                put("prompt", JsonArray(listOf(
                    buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive(buildPrompt(absoluteTargetPath)))
                    },
                )))
            }, promptId)
            val promptResult = readUntil(promptId)
            stopReason = promptResult["stopReason"]?.jsonPrimitive?.contentOrNull
        }

        val toolName = observedToolName
        val toolPath = observedToolPath
        val computedOutcome = if (toolName != null) {
            HermesAssignmentOutcome.Completed(
                findingsText = messageText.toString().trim().ifBlank { "Hermes completed the read with no text reply." },
                toolName = toolName,
                toolTargetPath = toolPath,
                toolSucceeded = stopReason == "end_turn" && toolPath?.contains(targetFilename) == true,
            )
        } else if (!promptSent) {
            HermesAssignmentOutcome.Failed("cancelled during the test-only delay window, before session/prompt was ever sent")
        } else {
            HermesAssignmentOutcome.Failed("no tool call observed (stopReason=$stopReason)")
        }

        // The single point this client commits to a final outcome — see HermesCancelHandle's own
        // doc for why a cancellation request must override whatever the exchange itself produced,
        // never the reverse: the installed runtime only labels stopReason="cancelled" when its own
        // agent turn happened to notice the interrupt before finishing, which is not guaranteed.
        val cancelRequestedAt = cancelHandle.checkCancelledAndFinish()
        return if (cancelRequestedAt != null) {
            val partialText = (computedOutcome as? HermesAssignmentOutcome.Completed)?.findingsText
            val reason = when {
                !promptSent -> "cancellation was requested before session/prompt was ever sent (during the test-only delay window) — no real exchange occurred"
                stopReason == "cancelled" -> "hermes confirmed the cancellation itself (stopReason=cancelled)"
                else -> "cancellation was requested; hermes nonetheless reported stopReason=$stopReason — honoring the cancellation regardless"
            }
            HermesAssignmentOutcome.Cancelled(partialText, reason)
        } else {
            computedOutcome
        }
    }
}
