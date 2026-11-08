package app.alfrd.engram.director

import app.alfrd.engram.api.DebugConverseService
import app.alfrd.engram.cognitive.pipeline.agentwork.NotionCycleStartup
import app.alfrd.engram.cognitive.pipeline.hermes.HermesActiveAssignmentRegistry
import app.alfrd.engram.cognitive.pipeline.horizon.AppliedOperation
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeCycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonAssembler
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonStructureStore
import app.alfrd.engram.cognitive.pipeline.horizon.CLAUDE_SESSION_SOURCE_TYPE
import app.alfrd.engram.cognitive.pipeline.horizon.CycleSequencer
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonOperationApplier
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.HorizonSeeder
import app.alfrd.engram.cognitive.pipeline.horizon.OperationParser
import app.alfrd.engram.cognitive.pipeline.horizon.PerUserCycleLock
import app.alfrd.engram.cognitive.pipeline.horizon.SalientTokenPropagator
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshot
import app.alfrd.engram.cognitive.pipeline.horizon.ViewSnapshotBuilder
import com.arcadedb.database.Database
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** What happened to one operation the caller sent. [applied] false means nothing was written for it; [detail] is the guard's reason or the change made. */
@Serializable
data class OwnerOperationReport(val verb: String, val applied: Boolean, val detail: String, val quote: String? = null)

/**
 * [horizon] is the standing prompt exactly as the Director tick renders it, after any writes. [refs] is what the operation verbs may name
 * (the short refs `c1`, `i2` that an operation's `item` / `target` / `into` fields take), built after the writes, so it is what the *next* call
 * resolves against. [cycleSeq] is the cycle this call allocated, or null for a pure read.
 */
@Serializable
data class OwnerHorizonResponse(val horizon: String, val refs: String, val cycleSeq: Long?, val operations: List<OwnerOperationReport>)

class OwnerHorizonUnavailable(message: String) : RuntimeException(message)

/**
 * The owner's Horizon tool: `horizon(instruction?)`. One call may record a message to the ledger, apply operations through the existing
 * guards, and always returns the refreshed Horizon.
 *
 * - **No message and no operations**: a pure read. Nothing is written and no cycle is allocated.
 * - **Otherwise** the whole call runs under the owner's [PerUserCycleLock], as a conversational turn does, and allocates a cycle number the
 *   same way ([CycleSequencer]). The message is written to the ledger as an `Utterance` with `channel = "claude"` (no model call). Operations
 *   go through [OperationParser] (grounding, refs, negation, shape: the message is the text a `quote` must be found in) and then
 *   [HorizonOperationApplier], whose writes are asserted by a `claude_session` Source the owner trusts.
 * - Seeding and propagation run as they do after a turn, so what was just touched or said is in view. The silence check does not: it is the
 *   conversation's, and a Claude session is not a conversation with Alfrd.
 */
class OwnerHorizonService(
    private val email: String,
    private val db: Database,
    private val markers: ChangeMarkerReader,
    private val standing: StandingContext,
    private val sequencer: CycleSequencer,
    private val applier: HorizonOperationApplier,
    private val snapshots: ViewSnapshotBuilder,
    private val seeder: HorizonSeeder,
    private val propagator: HorizonPropagator,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Instant = Instant::now,
) {
    private val logger = LoggerFactory.getLogger(OwnerHorizonService::class.java)

    suspend fun handle(message: String?, ops: JsonArray?): OwnerHorizonResponse {
        val text = message?.trim()?.takeIf { it.isNotEmpty() }
        val sent = ops ?: JsonArray(emptyList())
        if (text == null && sent.isEmpty()) return read()
        return PerUserCycleLock.withLock(email) { write(text, sent) }
    }

    private suspend fun read(): OwnerHorizonResponse {
        val marker = markerOrThrow()
        val rendered = standing.render(marker, emptyList(), "Horizon tool", clock(), marker.lastCycleSeq + 1, forChannel = true)
        return OwnerHorizonResponse(rendered.text, refs(), null, emptyList())
    }

    private suspend fun write(text: String?, sent: JsonArray): OwnerHorizonResponse {
        val before = markerOrThrow()
        val cycleSeq = sequencer.allocateCycle(email) ?: throw OwnerHorizonUnavailable("could not allocate a cycle")
        if (text != null) recordUtterance(text, cycleSeq)

        val reports = mutableListOf<OwnerOperationReport>()
        var applied: List<AppliedOperation> = emptyList()
        if (sent.isNotEmpty()) {
            // hold_for_choice asks the user a question in a reply; there is no reply here, so it is refused before it can suppress the rest.
            val allowed = mutableListOf<kotlinx.serialization.json.JsonElement>()
            for (element in sent) {
                val verb = ((element as? JsonObject)?.get("verb") as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (verb == "hold_for_choice") {
                    reports += OwnerOperationReport(verb, false, "not_available_on_owner_route", ((element as JsonObject)["quote"] as? JsonPrimitive)?.content)
                } else allowed += element
            }
            val snapshot = try {
                snapshots.build(email, zone)
            } catch (e: Exception) {
                logger.warn("owner-horizon: view snapshot failed: {}", e::class.java.simpleName)
                ViewSnapshot(emptyList(), emptyList(), emptyList(), zone = zone)
            }
            val parsed = OperationParser.parse(JsonObject(mapOf("ops" to JsonArray(allowed))), text.orEmpty(), snapshot, zone)
            applied = applier.apply(email, cycleSeq, "claude-owner-${UUID.randomUUID()}", parsed.operations, zone, CLAUDE_SESSION_SOURCE_TYPE)
            applied.forEach { reports += OwnerOperationReport(it.operation.verb, it.applied, it.summary, it.operation.quote.takeIf { q -> q.isNotBlank() }) }
            parsed.rejections.forEach { reports += OwnerOperationReport(it.verb, false, it.reason, it.quote) }
        }

        if (text != null || applied.isNotEmpty()) {
            try {
                seeder.seedTurn(email, cycleSeq, text.orEmpty(), applied)
                propagator.propagate(email, cycleSeq, applied.filter { it.applied && it.resultUid != null }.map { it.resultUid!! to (it.resultText ?: "") })
            } catch (e: Exception) {
                logger.warn("owner-horizon: seeding or propagation failed: {}", e::class.java.simpleName)
            }
        }

        val after = markerOrThrow()
        val rendered = standing.render(after, after.changesSince(before), "Horizon tool", clock(), cycleSeq, forChannel = true)
        logger.info("owner-horizon: cycle={} message={} operations={} applied={}", cycleSeq, text != null, reports.size, reports.count { it.applied })
        return OwnerHorizonResponse(rendered.text, refs(), cycleSeq, reports)
    }

    private suspend fun refs(): String = try {
        snapshots.build(email, zone).render()
    } catch (e: Exception) {
        "Refs unavailable: the graph read failed."
    }

    private suspend fun markerOrThrow(): ChangeMarker =
        withContext(Dispatchers.IO) { markers.read(email) } ?: throw OwnerHorizonUnavailable("the owner identity has no User vertex")

    /** The message as the ledger keeps it: one `user` Utterance, `channel = "claude"`, chained after the last of this channel's with the same FOLLOWS edge a conversation uses. */
    private suspend fun recordUtterance(text: String, cycleSeq: Long) = withContext(Dispatchers.IO) {
        db.transaction {
            val tail = db.query("sql", "SELECT FROM Utterance WHERE sessionId = :s ORDER BY createdAt DESC LIMIT 1", mapOf("s" to LEDGER_SESSION))
                .use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }
            val vertex = db.newVertex("Utterance").apply {
                set("uid", UUID.randomUUID().toString())
                set("sessionId", LEDGER_SESSION)
                set("userId", email)
                set("turnIndex", cycleSeq.toInt())
                set("role", "user")
                set("text", text)
                set("channel", CHANNEL)
                set("createdAt", System.currentTimeMillis())
                save()
            }
            tail?.newEdge("FOLLOWS", vertex, false)?.apply { set("attributions", "[]"); set("scores", "[]"); save() }
        }
    }

    companion object {
        const val CHANNEL = "claude"
        const val LEDGER_SESSION = "claude-owner"
    }
}

/** The owner token file's outcome. The token itself is never logged or placed in a message. */
sealed interface OwnerTokenResult {
    data class Ready(val token: String, val created: Boolean) : OwnerTokenResult
    data class Refused(val reason: String) : OwnerTokenResult
}

object OwnerToken {
    fun defaultPath(): Path = Path.of(System.getProperty("user.home"), ".config", "engram", "owner-token")

    /**
     * Reads the token at [path], or creates it (32 random bytes, hex) with mode 600 when the file is missing. Refuses a file readable or
     * writable by group or others, and an empty one: a token other users could read is not a secret. The parent directory is created 700.
     */
    fun loadOrCreate(path: Path): OwnerTokenResult {
        try {
            if (!Files.exists(path)) {
                path.parent?.let { dir ->
                    if (!Files.exists(dir)) Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                }
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val token = bytes.joinToString("") { "%02x".format(it) }
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(path, token + "\n")
                return OwnerTokenResult.Ready(token, created = true)
            }
            val loose = Files.getPosixFilePermissions(path).filter {
                it in setOf(
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE,
                )
            }
            if (loose.isNotEmpty()) return OwnerTokenResult.Refused("the owner token file is accessible to group or others; it must be mode 600")
            val token = Files.readString(path).trim()
            if (token.length < MIN_LENGTH) return OwnerTokenResult.Refused("the owner token file is empty or too short")
            return OwnerTokenResult.Ready(token, created = false)
        } catch (e: Exception) {
            return OwnerTokenResult.Refused("the owner token file could not be read or created (${e::class.java.simpleName})")
        }
    }

    const val MIN_LENGTH = 16
}

/** Whether the owner's Horizon route is serving, and why not when it is not. */
sealed interface OwnerHorizonStartup {
    data class Running(val service: OwnerHorizonService, val token: String) : OwnerHorizonStartup
    data class Disabled(val reason: String) : OwnerHorizonStartup
}

private val ownerLog = LoggerFactory.getLogger("app.alfrd.engram.director.OwnerHorizonBootstrap")

const val ENV_OWNER_IDENTITY = "OWNER_IDENTITY"
const val ENV_OWNER_TOKEN_FILE = "OWNER_TOKEN_FILE"

/**
 * Builds the owner's Horizon tool from the environment. Off unless `OWNER_IDENTITY` is set; the identity is fixed by configuration, never
 * by the caller. Off, too, when the token file cannot be trusted (the route then answers 503 and says why, never 200).
 */
fun bootstrapOwnerHorizon(
    db: Database,
    notionStartup: NotionCycleStartup,
    hermesActive: HermesActiveAssignmentRegistry,
    tickStartup: DirectorTickStartup,
    env: Map<String, String> = System.getenv(),
    probe: SelfStateProbe = SystemProbe(),
): OwnerHorizonStartup {
    val raw = env[ENV_OWNER_IDENTITY]?.trim().orEmpty()
    if (raw.isEmpty()) return OwnerHorizonStartup.Disabled("$ENV_OWNER_IDENTITY is not set").also { ownerLog.info("owner-horizon: not serving: {}", it.reason) }
    val path = env[ENV_OWNER_TOKEN_FILE]?.trim()?.takeIf { it.isNotEmpty() }?.let(Path::of) ?: OwnerToken.defaultPath()
    val token = when (val loaded = OwnerToken.loadOrCreate(path)) {
        is OwnerTokenResult.Refused -> return OwnerHorizonStartup.Disabled(loaded.reason).also { ownerLog.error("owner-horizon: NOT SERVING: {}", it.reason) }
        is OwnerTokenResult.Ready -> loaded.also { if (it.created) ownerLog.info("owner-horizon: created the owner token file at {}", path) }.token
    }
    val email = DebugConverseService.resolveUserId(raw)
    val structure = ArcadeHorizonStructureStore(db)
    val graph = ArcadeHorizonGraphStore(db)
    val assembler = ArcadeHorizonAssembler(db)
    val tick = (tickStartup as? DirectorTickStartup.Running)?.tick
    val service = OwnerHorizonService(
        email = email,
        db = db,
        markers = ChangeMarkerReader(db),
        standing = StandingContext(
            email, tick != null, tick?.interval?.seconds ?: 0L, assembler, probe,
            notion = { notionState(notionStartup) },
            hermes = { hermesActive.activeByKind() },
            budgets = { budgetDefaults(notionStartup) },
        ),
        sequencer = ArcadeCycleSequencer(db),
        applier = HorizonOperationApplier(structure, graph),
        snapshots = ViewSnapshotBuilder(structure),
        seeder = HorizonSeeder(structure),
        propagator = SalientTokenPropagator(graph, assembler),
    )
    ownerLog.info("owner-horizon: serving identity={}", raw)
    return OwnerHorizonStartup.Running(service, token)
}
