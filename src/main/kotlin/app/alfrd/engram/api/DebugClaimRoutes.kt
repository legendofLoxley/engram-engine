package app.alfrd.engram.api

import app.alfrd.engram.claims.ClaimExtractionService
import app.alfrd.engram.claims.ClaimPassModel
import app.alfrd.engram.claims.ClaimStore
import app.alfrd.engram.claims.ClaimantResolver
import app.alfrd.engram.claims.LocalClaimModel
import app.alfrd.engram.claims.PromptSet
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
    /** Wording to try instead of the built-in prompts (see [PromptSet.custom]). The run's record names it `custom-<hash>`; it never stores the text. */
    val prompts: DebugClaimPrompts? = null,
    /** `text` (default): the two local-model passes over the text chunks. `structure`: claims straight from vision chunks' checked entries. */
    val reader: String = "text",
)

@Serializable
data class DebugSyntheticRequest(val syntheticUserId: String)

@Serializable
data class DebugIssuerRequest(val syntheticUserId: String, val issuer: String? = null)

@Serializable
data class DebugNameDecision(val syntheticUserId: String, val a: String, val b: String, val decision: String, val canonical: String? = null)

@Serializable
data class DebugAliasActive(val syntheticUserId: String, val active: Boolean)

@Serializable
data class DebugClaimPrompts(val pass1: String, val pass2: String)

@Serializable
data class DebugClaimRunStarted(val runId: String, val sourceUid: String, val state: String, val report: RunReport? = null)

@Serializable
data class DebugClaimView(
    val uid: String, val quote: String, val place: String?, val claimant: String?, val claimantAsWritten: String?, val subject: String, val kind: String,
    val checkState: String, val runId: String?, val recheckRunId: String?, val locations: List<String>,
    /** The passage the claim was decomposed from, the things it is about and the period it refers to (claims in context). */
    val passage: String? = null, val about: List<app.alfrd.engram.db.ConceptRef> = emptyList(), val asOf: String? = null,
    /** Claims of the other reader that state the same fact, and that disagree with it (cross-reader linking). */
    val corroboratedBy: List<String> = emptyList(), val conflictsWith: List<String> = emptyList(),
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
    modelFactory: (PromptSet) -> ClaimPassModel = { prompts -> LocalClaimModel(LocalLlmClient(), System.getenv("LOCAL_LLM_BASE_URL") ?: "http://127.0.0.1:8081/v1", prompts) },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    isLoopback: (ApplicationCall) -> Boolean = ::connectedFromLoopback,
) {
    val logger = LoggerFactory.getLogger("DebugClaimRoutes")
    val sources = SourceStore(db)
    val claims = ClaimStore(db)
    val structure = ArcadeHorizonStructureStore(db)
    val commit = SourceCommitService(sources, structure, HorizonOperationApplier(structure, ArcadeHorizonGraphStore(db), sources), ArcadeCycleSequencer(db))
    val active = ConcurrentHashMap<String, Boolean>()
    // Runs asked to stop: each finishes the batch it is in, then marks itself stopped (resumable with the same run id).
    val stopRequested = ConcurrentHashMap.newKeySet<String>()
    val registry = app.alfrd.engram.db.AliasRegistry(db)

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
                    val prompts = try { req.prompts?.let { PromptSet.custom(it.pass1, it.pass2) } ?: PromptSet.DEFAULT } catch (e: IllegalArgumentException) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "bad prompts")))
                    }
                    val stored = claims.run(email, runId)
                    if (stored != null && stored.sourceUid != uid) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "that run id belongs to another source"))
                    if (stored?.status == "complete" && stored.report != null) return@post call.respond(HttpStatusCode.OK, DebugClaimRunStarted(runId, uid, "complete", stored.report))
                    if (active.putIfAbsent("$email|$runId", true) != null) return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "that run is already in progress"))
                    DebugConverseService.ensureSyntheticUser(db, email)
                    if (req.reader !in setOf("text", "structure")) return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reader must be text or structure"))
                    val structured = req.reader == "structure"
                    // A structure run calls no model: the vision model that read each page is on its chunk, checked when the chunk was made.
                    val expect = if (structured || req.anyModel) null else req.expectModel?.takeIf { it.isNotBlank() } ?: ClaimExtractionService.DEFAULT_EXPECTED_MODEL
                    scope.launch {
                        try {
                            ClaimExtractionService(sources, claims, commit, resolver).run(
                                email, uid, runId, if (structured) app.alfrd.engram.claims.StructuredClaimModel() else modelFactory(prompts), expect,
                                shouldStop = { stopRequested.contains("$email|$runId") },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The class name only: a message could carry a passage.
                            logger.warn("claims: run failed: {}", e::class.java.simpleName)
                        } finally {
                            active.remove("$email|$runId"); stopRequested.remove("$email|$runId")
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
                // Links the claims the two readers made of the same fact (one fact, one claim, however many readers saw it) and says how many agree,
                // disagree (both kept, both Re-checking) or stand on one reader's say-so. Idempotent; extraction runs also do this when they finish.
                post("/sources/{uid}/claim-links") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugSyntheticRequest>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    val uid = call.parameters["uid"].orEmpty()
                    if (sources.sourceByUid(email, uid) == null) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    call.respond(HttpStatusCode.OK, claims.linkReaders(sources, email, uid, "link-" + java.util.UUID.randomUUID().toString().take(8)))
                }
                get("/sources/{uid}/claims") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    val uid = call.parameters["uid"].orEmpty()
                    if (sources.sourceByUid(email, uid) == null) return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    call.respond(HttpStatusCode.OK, claims.claims(email, uid).map { c ->
                        DebugClaimView(c.uid, c.quote, c.place, c.claimant, c.claimantAsWritten, c.subject, c.kind, c.checkState, c.runId, c.recheckRunId,
                            sources.provenanceOf(email, c.uid).map { it.location }, c.passage, c.about, c.asOf, c.corroboratedBy, c.conflictsWith)
                    })
                }
                // Things by name, as read time sees them: every Concept with that name, its kind and parent, how many of this user's claims
                // are about it, and the Concepts it may be the same as. Nothing is merged or chosen here.
                get("/concepts") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    val name = call.request.queryParameters["name"]?.takeIf { it.isNotBlank() } ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "name is required"))
                    call.respond(HttpStatusCode.OK, app.alfrd.engram.db.ConceptStore(db, registry.resolverFor(email)).candidates(email, name, registry))
                }
                // Asks a running claim run to stop: it finishes the batch it is in, keeps it, and is recorded as stopped. Start it again with the same id to continue.
                post("/claim-runs/{runId}/stop") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugSyntheticRequest>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    val runId = call.parameters["runId"].orEmpty()
                    if (!active.containsKey("$email|$runId")) {
                        val run = claims.run(email, runId) ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown run"))
                        return@post call.respond(HttpStatusCode.Conflict, mapOf("error" to "that run is not in progress", "status" to run.status))
                    }
                    stopRequested.add("$email|$runId")
                    call.respond(HttpStatusCode.Accepted, mapOf("runId" to runId, "state" to "stopping"))
                }
                // Who issued a Source: the parent a school it names takes unless the page names another. Set by a person; never read off the page.
                post("/sources/{uid}/issuer") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugIssuerRequest>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    if (!sources.setIssuer(email, call.parameters["uid"].orEmpty(), req.issuer)) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown source"))
                    call.respond(HttpStatusCode.OK, mapOf("issuer" to (req.issuer?.trim().orEmpty())))
                }
                // Pairs of things that may be one thing, waiting for a person: the box merges nothing it cannot prove.
                get("/name-checks") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    call.respond(HttpStatusCode.OK, app.alfrd.engram.db.ConceptStore(db, registry.resolverFor(email)).nameChecks(email))
                }
                post("/name-checks/decide") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugNameDecision>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    val same = when (req.decision) { "same" -> true; "different" -> false; else -> return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "decision must be same or different")) }
                    if (!app.alfrd.engram.db.ConceptStore(db, registry.resolverFor(email)).decide(email, req.a, req.b, same, registry, req.canonical))
                        return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "that pair is not waiting for an answer"))
                    call.respond(HttpStatusCode.OK, mapOf("decision" to req.decision))
                }
                // The alias list: every recorded "these are one name", with where it came from. Turning one off splits the merge from the next run.
                get("/entity-aliases") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    call.respond(HttpStatusCode.OK, registry.list(email))
                }
                post("/entity-aliases/{uid}/active") {
                    if (!isLoopback(call)) return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "loopback only"))
                    val req = call.receive<DebugAliasActive>()
                    val email = call.userOrRefuse(req.syntheticUserId) ?: return@post
                    if (!registry.setActive(email, call.parameters["uid"].orEmpty(), req.active)) return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "unknown alias"))
                    call.respond(HttpStatusCode.OK, mapOf("active" to req.active.toString()))
                }
                // The owner's things, merged through the alias list at read time, each with every name it goes by and what may still be the same.
                get("/entities") {
                    val email = call.userOrRefuse(call.request.queryParameters["syntheticUserId"]) ?: return@get
                    call.respond(HttpStatusCode.OK, app.alfrd.engram.db.ConceptStore(db, registry.resolverFor(email)).entities(email, registry))
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
