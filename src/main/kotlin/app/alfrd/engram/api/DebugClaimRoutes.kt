package app.alfrd.engram.api

import app.alfrd.engram.claims.ClaimExtractionService
import app.alfrd.engram.claims.ClaimPassModel
import app.alfrd.engram.claims.ClaimStore
import app.alfrd.engram.claims.ClaimantResolver
import app.alfrd.engram.claims.LocalClaimModel
import app.alfrd.engram.claims.RunReport
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.SourceCommitService
import app.alfrd.engram.cognitive.providers.local.LocalLlmClient
import app.alfrd.engram.db.SourceStore
import com.arcadedb.database.Database
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * [expectModel] defaults to Qwen3.6-35B-A3B; the server's reported model name must contain it. [anyModel] runs without that check and the
 * run's record says so. [aliases] maps a canonical claimant to the other names it goes by.
 */
@Serializable
data class DebugClaimRunRequest(
    val syntheticUserId: String,
    val runId: String? = null,
    val expectModel: String? = null,
    val anyModel: Boolean = false,
    val aliases: Map<String, List<String>> = emptyMap(),
)

@Serializable
data class DebugClaimRunStarted(val runId: String, val sourceUid: String, val state: String, val report: RunReport? = null)

@Serializable
data class DebugClaimView(
    val uid: String, val quote: String, val place: String?, val claimant: String?, val claimantAsWritten: String?, val subject: String, val kind: String,
    val checkState: String, val runId: String?, val recheckRunId: String?, val locations: List<String>,
)

@Serializable
data class DebugClaimDisagreementView(val runId: String, val chunkUid: String, val reason: String, val versionA: app.alfrd.engram.claims.ClaimVersion?, val versionB: app.alfrd.engram.claims.ClaimVersion?)

/**
 * Starts a baseline claim extraction over a stored Source and shows what it made. Same `debug-token` auth, `DEBUG_CONVERSE_ENABLED` gate and
 * loopback-only rule as the other `/debug/sources` routes, synthetic identities only, never owner-jacob; the loopback check comes before the body
 * is read. Extraction runs in this process, on the box, against the local model: the route has no way to name another model server.
 * A run takes a long time on a long document, so the POST starts it and returns at once; the run's record (`GET /debug/claim-runs/{runId}`)
 * carries its progress and its report. Starting a run id that finished returns its report and starts nothing. One run per run id at a time.
 */
fun Application.configureDebugClaimRoutes(
    db: Database,
    modelFactory: () -> ClaimPassModel = { LocalClaimModel(LocalLlmClient(), System.getenv("LOCAL_LLM_BASE_URL") ?: "http://127.0.0.1:8081/v1") },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    val logger = LoggerFactory.getLogger("DebugClaimRoutes")
    val sources = SourceStore(db)
    val claims = ClaimStore(db)
    val structure = ArcadeHorizonStructureStore(db)
    val commit = SourceCommitService(sources, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), sources), ArcadeCycleSequencer(db))
    val active = ConcurrentHashMap<String, Boolean>()

    suspend fun ApplicationCall.userOrRefuse(label: String?): String? {
        if (!isLoopback(this)) { respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only")); return null }
        val email = DebugConverseService.resolveUserId(label?.takeIf { it.isNotBlank() } ?: return run { respond(HttpStatusCode.BadRequest, mapOf("error" to "syntheticUserId is required")); null })
        if (email.contains("owner-jacob")) { respond(HttpStatusCode.Forbidden, mapOf("error" to "that identity is real data and is never used by the debug routes")); return null }
        return email
    }

    routing {
        authenticate("debug-token") {
            route("/debug") {
                post("/sources/{uid}/claims/extract") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugClaimRunRequest>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    val uid = call.parameters["uid"].orEmpty()
                    if (sources.sourceByUid(email, uid) == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    val runId = req.runId?.takeIf { it.isNotBlank() } ?: "run-" + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now()) + "-" + java.util.UUID.randomUUID().toString().take(4)
                    val resolver = try { ClaimantResolver(req.aliases) } catch (e: IllegalArgumentException) { return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad aliases"))) }
                    val stored = claims.run(email, runId)
                    if (stored != null && stored.sourceUid != uid) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "that run id belongs to another source"))
                    if (stored?.status == "complete" && stored.report != null) return@post call.respond(HttpStatusCode.OK, DebugClaimRunStarted(runId, uid, "complete", stored.report))
                    if (active.putIfAbsent("$email|$runId", true) != null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "that run is already in progress"))
                    DebugConverseService.ensureSyntheticUser(db, email)
                    val expect = if (req.anyModel) null else req.expectModel?.takeIf { it.isNotBlank() } ?: ClaimExtractionService.DEFAULT_EXPECTED_MODEL
                    scope.launch {
                        try {
                            ClaimExtractionService(sources, claims, commit, resolver).run(email, uid, runId, modelFactory(), expect)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The class name only: a message could carry a passage.
                            logger.warn("claims: run failed: {}", e::class.java.simpleName)
                        } finally {
                            active.remove("$email|$runId")
                        }
                    }
                    call.respond(HttpStatusCode.Accepted, DebugClaimRunStarted(runId, uid, if (stored == null) "started" else "resumed"))
                }
                get("/claim-runs/{runId}") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    val run = claims.run(email, call.parameters["runId"].orEmpty()) ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown run"))
                    call.respond(HttpStatusCode.OK, mapOf(
                        "runId" to run.runId, "sourceUid" to run.sourceUid, "status" to run.status, "modelName" to run.modelName, "modelEndpoint" to run.modelEndpoint.orEmpty(),
                        "promptVersion" to run.promptVersion, "chunksDone" to run.chunksDone.toString(), "chunksTotal" to run.chunksTotal.toString(),
                        "inProgress" to active.containsKey("$email|${run.runId}").toString(),
                    ) + (run.report?.let { mapOf("report" to kotlinx.serialization.json.Json.encodeToString(RunReport.serializer(), it)) } ?: emptyMap()))
                }
                get("/sources/{uid}/claims") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    val uid = call.parameters["uid"].orEmpty()
                    if (sources.sourceByUid(email, uid) == null) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    call.respond(HttpStatusCode.OK, claims.claims(email, uid).map { c ->
                        DebugClaimView(c.uid, c.quote, c.place, c.claimant, c.claimantAsWritten, c.subject, c.kind, c.checkState, c.runId, c.recheckRunId,
                            sources.provenanceOf(email, c.uid).map { it.location })
                    })
                }
                get("/sources/{uid}/claim-disagreements") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    val uid = call.parameters["uid"].orEmpty()
                    if (sources.sourceByUid(email, uid) == null) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    call.respond(HttpStatusCode.OK, claims.disagreements(email, uid, call.request.queryParameters["runId"]).map {
                        DebugClaimDisagreementView(it.runId, it.chunkUid, it.reason, it.versionA, it.versionB)
                    })
                }
            }
        }
    }
}
