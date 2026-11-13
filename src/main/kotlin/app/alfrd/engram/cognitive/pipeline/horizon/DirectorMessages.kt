package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * What a message from the Director to the channel is about. A question, a failure and a finished goal are read by the channel; routine progress is kept but
 * stays quiet unless the policy says otherwise ([HorizonPolicy.directorShowProgress]).
 */
enum class DirectorMessageKind(val wire: String, val quiet: Boolean) {
    QUESTION("question", false),
    FAILURE("failure", false),
    FINISHED("finished", false),
    PROGRESS("progress", true);

    companion object {
        fun fromWire(value: String?): DirectorMessageKind? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * One message from the Director, as a node: who sent it ([sender], always `director`), when, which goal and which cycle of it, what kind, and what it answers
 * ([answersUid]: another message, an item, anything the channel can point at). Never an item: it is not a statement about the owner's world, so no item scan,
 * selector or audit sees it. [retracted] is set when its cycle is retracted; a retracted message is not shown.
 */
data class DirectorMessage(
    val uid: String,
    val sender: String,
    val goalUid: String,
    val cycle: Int,
    val kind: DirectorMessageKind,
    val text: String,
    val answersUid: String?,
    val createdAt: Long,
    val retracted: Boolean = false,
    /** What the cycle that sent it used, when it is a goal runner's end-of-cycle message; 0 otherwise. Summed per goal to enforce the goal's budget. */
    val usageTokens: Long = 0L,
    val usageMillis: Long = 0L,
    /** How the cycle that sent this closing message ended (`completed`, `yielded`, `failed`, `paused`); null for every other message. A closing message also has a change record (`running` to this). */
    val closedAs: String? = null,
)

/**
 * The Director's messages to the channel. A message is a `DirectorMessage` vertex with an edge `MESSAGE_FOR` to the goal it belongs to and, when it answers
 * something, an edge `ANSWERS` to that node, so a thread can be walked either way. Messages are appended and never edited or deleted; retracting a cycle marks
 * them dropped (with a change record), like the items.
 */
interface DirectorMessageStore {
    /** Writes a message; null when the goal is not one of the owner's goals, the text is blank, or [answersUid] names nothing the owner has. */
    suspend fun post(userEmail: String, goalUid: String, cycle: Int, kind: DirectorMessageKind, text: String, answersUid: String? = null, usageTokens: Long = 0L, usageMillis: Long = 0L, closedAs: String? = null): DirectorMessage?

    /** The highest cycle number any message of this goal carries (0 when there is none). Retracted messages count: a retracted cycle's number is never reused. */
    suspend fun latestCycle(userEmail: String, goalUid: String): Int = 0

    /** Everything the goal's cycles have used so far, summed over their messages (retracted ones included: the time and tokens were spent). */
    suspend fun usage(userEmail: String, goalUid: String): Pair<Long, Long> = 0L to 0L

    /** Every message of a goal's thread, oldest first, retracted ones included (marked). */
    suspend fun thread(userEmail: String, goalUid: String): List<DirectorMessage>

    /**
     * What the channel should be shown now: messages sent after [sinceAt], plus any question still unanswered that is no older than [openQuestionDays]. Quiet kinds
     * (routine progress) only when [includeProgress]. Retracted messages never. Newest last, at most [limit] (the newest, if there are more). Read only.
     */
    suspend fun visible(userEmail: String, sinceAt: Long, now: Long, includeProgress: Boolean, openQuestionDays: Int, limit: Int): List<DirectorMessage>

    /** Marks the messages of one goal cycle dropped, with a change record each; returns how many it changed. Idempotent. */
    suspend fun retractCycle(userEmail: String, goalUid: String, cycle: Int): Int
}

class ArcadeDirectorMessageStore(
    private val db: Database,
    private val lockTimeoutMs: Long = HorizonConsistencyLock.DEFAULT_TIMEOUT_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) : DirectorMessageStore {
    private val logger = LoggerFactory.getLogger(ArcadeDirectorMessageStore::class.java)
    private val changeLog = ChangeLog(db)

    override suspend fun post(userEmail: String, goalUid: String, cycle: Int, kind: DirectorMessageKind, text: String, answersUid: String?, usageTokens: Long, usageMillis: Long, closedAs: String?): DirectorMessage? {
        val body = text.trim().take(MAX_TEXT)
        if (userEmail.isBlank() || body.isBlank() || cycle < 1) return null
        return write("post") {
            val goal = HorizonOwnership.findPhraseOwnedByUser(db, userEmail, goalUid)?.takeIf { it.typeName == ItemKind.GOAL.typeName } ?: return@write null
            val answers: Vertex? = answersUid?.let { uid -> findNode(userEmail, uid) ?: return@write null }
            val now = clock()
            val vertex = db.newVertex(TYPE).apply {
                set("uid", UUID.randomUUID().toString())
                set("ownerEmail", userEmail)
                set("sender", SENDER)
                set("goalUid", goalUid)
                set("directorCycle", cycle)
                set("kind", kind.wire)
                set("text", body)
                if (answersUid != null) set("answersUid", answersUid)
                set("createdAt", now)
                if (usageTokens > 0) set("usageTokens", usageTokens)
                if (usageMillis > 0) set("usageMillis", usageMillis)
                if (closedAs != null) set("closedAs", closedAs)
                save()
            }
            // The end of a cycle is a change: the cycle was running and now it is not, and the record says how. Actor director, cause the outcome.
            if (closedAs != null) changeLog.append(userEmail, vertex, "state", "running", closedAs, cause = "goal_runner:cycle_$closedAs", actor = "director", changedAt = now)
            vertex.newEdge("MESSAGE_FOR", goal, false).save()
            if (answers != null) vertex.newEdge("ANSWERS", answers, false).save()
            vertex.toMessage()
        }
    }

    override suspend fun latestCycle(userEmail: String, goalUid: String): Int = threadAll(userEmail, goalUid).maxOfOrNull { it.cycle } ?: 0

    override suspend fun usage(userEmail: String, goalUid: String): Pair<Long, Long> =
        threadAll(userEmail, goalUid).let { all -> all.sumOf { it.usageTokens } to all.sumOf { it.usageMillis } }

    private suspend fun threadAll(userEmail: String, goalUid: String): List<DirectorMessage> = thread(userEmail, goalUid)

    override suspend fun thread(userEmail: String, goalUid: String): List<DirectorMessage> = read("thread") {
        db.query("sql", "SELECT FROM $TYPE WHERE ownerEmail = :e AND goalUid = :g", mapOf("e" to userEmail, "g" to goalUid)).use { rs ->
            val out = mutableListOf<DirectorMessage>()
            while (rs.hasNext()) out += rs.next().toElement().asVertex().toMessage()
            out.sortedBy { it.createdAt }
        }
    }

    override suspend fun visible(userEmail: String, sinceAt: Long, now: Long, includeProgress: Boolean, openQuestionDays: Int, limit: Int): List<DirectorMessage> = read("visible") {
        val all = mutableListOf<DirectorMessage>()
        db.query("sql", "SELECT FROM $TYPE WHERE ownerEmail = :e", mapOf("e" to userEmail)).use { rs ->
            while (rs.hasNext()) all += rs.next().toElement().asVertex().toMessage()
        }
        val live = all.filter { !it.retracted }
        // A question is answered when a later message of the thread says it answers it.
        val answered = live.mapNotNull { it.answersUid }.toSet()
        val oldestOpenQuestion = now - openQuestionDays * DAY_MS
        live.filter { m ->
            (includeProgress || !m.kind.quiet) &&
                (m.createdAt > sinceAt || (m.kind == DirectorMessageKind.QUESTION && m.uid !in answered && m.createdAt >= oldestOpenQuestion))
        }.sortedBy { it.createdAt }.takeLast(limit)
    }

    override suspend fun retractCycle(userEmail: String, goalUid: String, cycle: Int): Int = write("retractCycle") {
        var changed = 0
        db.query("sql", "SELECT FROM $TYPE WHERE ownerEmail = :e AND goalUid = :g AND directorCycle = :c", mapOf("e" to userEmail, "g" to goalUid, "c" to cycle)).use { rs ->
            val targets = mutableListOf<Vertex>()
            while (rs.hasNext()) targets += rs.next().toElement().asVertex()
            for (message in targets) {
                if (message.get("retractedAt") != null) continue
                val now = clock()
                message.modify().apply { set("retractedAt", now); save() }
                changeLog.append(userEmail, message, "state", "sent", "dropped", cause = "retract_cycle", actor = userEmail, changedAt = now)
                changed++
            }
        }
        changed
    } ?: 0

    /** The node a message may answer: one of the owner's items, or one of their Director messages. */
    private fun findNode(userEmail: String, uid: String): Vertex? =
        HorizonOwnership.findPhraseOwnedByUser(db, userEmail, uid)
            ?: db.query("sql", "SELECT FROM $TYPE WHERE uid = :u AND ownerEmail = :e", mapOf("u" to uid, "e" to userEmail)).use { rs -> if (rs.hasNext()) rs.next().toElement().asVertex() else null }

    private fun Vertex.toMessage() = DirectorMessage(
        uid = get("uid") as String,
        sender = get("sender") as? String ?: SENDER,
        goalUid = get("goalUid") as? String ?: "",
        cycle = (get("directorCycle") as? Number)?.toInt() ?: 0,
        kind = DirectorMessageKind.fromWire(get("kind") as? String) ?: DirectorMessageKind.PROGRESS,
        text = get("text") as? String ?: "",
        answersUid = get("answersUid") as? String,
        createdAt = (get("createdAt") as? Number)?.toLong() ?: 0L,
        retracted = get("retractedAt") != null,
        usageTokens = (get("usageTokens") as? Number)?.toLong() ?: 0L,
        usageMillis = (get("usageMillis") as? Number)?.toLong() ?: 0L,
        closedAs = get("closedAs") as? String,
    )

    private suspend fun <T> write(op: String, body: () -> T?): T? = withContext(Dispatchers.IO) {
        try {
            withHorizonWriteLock(db, lockTimeoutMs) {
                var result: T? = null
                db.transaction { result = body() }
                result
            } ?: run {
                logger.warn("director-messages: {} could not get the Horizon write lock within {}ms; nothing written", op, lockTimeoutMs)
                null
            }
        } catch (e: Exception) {
            logger.warn("director-messages: {} failed: {}", op, e::class.java.simpleName)
            null
        }
    }

    private suspend fun <T> read(op: String, body: () -> List<T>): List<T> = withContext(Dispatchers.IO) {
        try {
            body()
        } catch (e: Exception) {
            logger.warn("director-messages: {} failed: {}", op, e::class.java.simpleName)
            emptyList()
        }
    }

    companion object {
        const val TYPE = "DirectorMessage"
        const val SENDER = "director"
        const val MAX_TEXT = 600
        private const val DAY_MS = 24L * 3_600_000L
    }
}
