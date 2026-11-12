package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.graph.Vertex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the zoomed-out board gets its facts. Read only: it writes nothing to the graph. "Since last time" needs to know when a channel last read,
 * and a pure read writes nothing, so that moment is kept in memory per channel (see [ArcadeBoardSource]).
 */
interface BoardSource {
    val policy: HorizonPolicy
    val zone: ZoneId

    /**
     * The board's facts for [email] as one [channel] sees them, or null if the graph could not be read this time. [cycleSeq] is the cycle the Horizon is being read
     * at (for a pure read, the one that would come next; nothing is allocated or written): a casual mention fades by how many cycles old it is at that cycle.
     */
    suspend fun read(email: String, channel: String, cycleSeq: Long): BoardData?

    /** Records that [channel] has been shown the board as of [at], so its next "since last time" starts there. */
    fun markRead(channel: String, at: Long)
}

/**
 * Reads the board's facts straight from the graph in one bounded pass: the live areas and projects, every open item (up to a scan bound),
 * the casual-mention focus edges, and the change records since the channel last read.
 *
 * "Since last read" is kept per channel, in memory, because a pure read of the Horizon writes nothing and the schema is not changed for it. A restart
 * forgets it; the first read after one looks back [HorizonPolicy.boardFirstReadLookbackHours] instead and says so. Channels are told apart by name
 * (the owner's Horizon tool and the Director tick each have their own), so one reading does not move another's marker.
 */
class ArcadeBoardSource(
    private val db: Database,
    override val policy: HorizonPolicy = HorizonPolicy.fromEnvironment(),
    override val zone: ZoneId = ZoneId.systemDefault(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val structure: HorizonStructureStore = ArcadeHorizonStructureStore(db),
) : BoardSource {
    private val logger = LoggerFactory.getLogger(ArcadeBoardSource::class.java)
    private val lastRead = ConcurrentHashMap<String, Long>()

    override fun markRead(channel: String, at: Long) { lastRead[channel] = at }

    override suspend fun read(email: String, channel: String, cycleSeq: Long): BoardData? {
        // Focus edges at their strength now, by the selector's own fade (a mention halves every few cycles and is nothing below the floor; a pin never fades).
        val nudges = try {
            structure.getFocus(email).associate { it.targetUid to policy.focusStrength(it.mode == FocusMode.PINNED, it.weight, it.setCycleSeq, cycleSeq) }.filterValues { it > 0.0 }
        } catch (e: Exception) {
            logger.warn("board: focus read failed: {}", e::class.java.simpleName)
            emptyMap()
        }
        val previous = lastRead[channel]
        return withContext(Dispatchers.IO) {
            try {
                var out: BoardData? = null
                withHorizonReadLock(db) { db.transaction { out = collect(email, nudges, previous) } }
                out
            } catch (e: Exception) {
                logger.warn("board: read failed: {}", e.message)
                null
            }
        }
    }

    private fun collect(email: String, nudges: Map<String, Double>, previous: Long?): BoardData {
        val now = clock()
        val sinceAt = previous ?: (now - policy.boardFirstReadLookbackHours * 3_600_000L)
        var truncated = false
        fun attention(v: Vertex) = v.get("attention") as? String

        // Areas and projects: live ones only (a done or silenced container is out of the picture, as everywhere else).
        val areas = LinkedHashMap<String, BoardArea>()
        db.query("sql", "SELECT FROM Domain WHERE ownerEmail = :e LIMIT ${policy.boardScanLimit}", mapOf("e" to email)).use { rs ->
            while (rs.hasNext()) {
                val v = rs.next().toElement().asVertex()
                val uid = v.get("uid") as? String ?: continue
                if ((v.get("status") as? String ?: "active") != "active" || attention(v) == "suppressed") continue
                areas[uid] = BoardArea(uid, v.get("name") as? String ?: "", attention(v) == "pinned", nudges[uid] ?: 0.0)
            }
        }
        class ProjectInfo(val uid: String, val name: String, val areaUid: String?, val pinned: Boolean, val dueAt: Long?)
        val projects = LinkedHashMap<String, ProjectInfo>()
        db.query("sql", "SELECT FROM Project WHERE ownerEmail = :e LIMIT ${policy.boardScanLimit}", mapOf("e" to email)).use { rs ->
            while (rs.hasNext()) {
                val v = rs.next().toElement().asVertex()
                val uid = v.get("uid") as? String ?: continue
                if ((v.get("status") as? String ?: "active") != "active" || attention(v) == "suppressed") continue
                val area = v.getVertices(Vertex.DIRECTION.OUT, "PART_OF").firstOrNull { it.typeName == "Domain" }?.get("uid") as? String
                projects[uid] = ProjectInfo(uid, v.get("name") as? String ?: "", area?.takeIf { it in areas }, attention(v) == "pinned", (v.get("dueAt") as? Number)?.toLong())
            }
        }

        // Open items. Done and dropped ones are not here (their status is not open); suppressed and corrected-away ones are left out.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val byProject = LinkedHashMap<String, MutableList<BoardItem>>()
        val unfiled = mutableListOf<BoardItem>()
        val hiddenByProject = HashMap<String, Int>()
        var unfiledHidden = 0
        val itemInfo = HashMap<String, Pair<String, String?>>()   // uid to (text, project uid)
        var newItems = 0
        for (spec in ItemKinds.stateful()) {
            var seen = 0
            db.query("sql", "SELECT FROM ${spec.typeName} WHERE ownerEmail = :e AND status = 'open' LIMIT ${policy.boardScanLimit}", mapOf("e" to email)).use { rs ->
                while (rs.hasNext()) {
                    val v = rs.next().toElement().asVertex()
                    seen++
                    val uid = v.get("uid") as? String ?: continue
                    val project = v.getVertices(Vertex.DIRECTION.OUT, "PART_OF").firstOrNull { it.typeName == "Project" }
                    val projectUid = project?.get("uid") as? String
                    if (project != null && projectUid !in projects) continue   // filed under a done or silenced project
                    // A suppressed item is hidden: counted apart, never in the open number (the focused view says the same).
                    if (attention(v) == "suppressed") { if (projectUid != null) hiddenByProject.merge(projectUid, 1, Int::plus) else unfiledHidden++; continue }
                    if (v.getEdges(Vertex.DIRECTION.IN, "RELATED_TO").any { it.get("relationType") == "supersedes" }) continue
                    val hasTime = v.get("dueHasTime") == true
                    val raw = spec.dueProperty?.let { (v.get(it) as? Number)?.toLong() }
                    // A date-only event is stored at the start of its day; it is happening today until the day is over (as the "also due" block reads it).
                    val dueAt = raw?.let { if (spec.boundToMoment && !hasTime) Instant.ofEpochMilli(it).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1 else it }
                    val text = v.get("text") as? String ?: ""
                    val createdAt = (v.get("createdAt") as? Number)?.toLong() ?: 0L
                    val item = BoardItem(
                        uid = uid, text = text, kind = spec.horizonLabel.lowercase(),
                        state = WorkState.of(v.get("state") as? String, v.get("status") as? String),
                        dueAt = dueAt, dueHard = DueKind.fromWire(v.get("dueKind") as? String) == DueKind.HARD, dueHasTime = hasTime, boundToMoment = spec.boundToMoment,
                        nudgeAt = (v.get("nudgeAt") as? Number)?.toLong(), waitingOn = v.get("waitingOn") as? String,
                        pinned = attention(v) == "pinned",
                        evidenceAt = (v.get("lastEvidenceAt") as? Number)?.toLong() ?: (v.get("updatedAt") as? Number)?.toLong() ?: createdAt,
                        createdAt = createdAt,
                    )
                    if (createdAt > sinceAt) newItems++
                    itemInfo[uid] = text to projectUid
                    if (projectUid != null) byProject.getOrPut(projectUid) { mutableListOf() } += item else unfiled += item
                }
            }
            if (seen >= policy.boardScanLimit) truncated = true
        }

        // Changes since this channel last read. A target that is no longer open (a task just marked done) is looked up by its uid so it can still be named.
        val log = ChangeLog(db)
        val records = log.since(email, sinceAt, policy.boardChangeScanLimit)
        if (records.size >= policy.boardChangeScanLimit) truncated = true
        val containerNames = projects.mapValues { it.value.name } + areas.mapValues { it.value.name }
        val recent = records.groupBy { it.targetUid }.entries.sortedByDescending { e -> e.value.maxOf { it.changedAt } }.take(policy.boardSinceLines * 3)
        val lookedUp = HashMap<String, Pair<String, String?>>()
        for ((uid, _) in recent) {
            if (uid in itemInfo || uid in containerNames) continue
            db.query("sql", "SELECT FROM Phrase WHERE uid = :u AND ownerEmail = :e", mapOf("u" to uid, "e" to email)).use { rs ->
                if (rs.hasNext()) {
                    val v = rs.next().toElement().asVertex()
                    lookedUp[uid] = (v.get("text") as? String ?: "") to ((v.getVertices(Vertex.DIRECTION.OUT, "PART_OF").firstOrNull { it.typeName == "Project" }?.get("uid")) as? String)
                }
            }
        }
        val changes = recent.flatMap { (uid, group) ->
            val info = itemInfo[uid] ?: lookedUp[uid]
            val label = info?.first ?: containerNames[uid] ?: return@flatMap emptyList()
            val container = if (info != null) info.second else uid
            group.map { BoardChange(uid, label, if (info != null) uid else null, container, it.field, it.oldValue, it.newValue, it.changedAt) }
        }

        return BoardData(
            asOf = now, sinceAt = sinceAt, sinceIsLookback = previous == null,
            areas = areas.values.toList(),
            projects = projects.values.map { p ->
                BoardProject(p.uid, p.name, p.areaUid, p.pinned, maxOf(nudges[p.uid] ?: 0.0, p.areaUid?.let { nudges[it] } ?: 0.0), p.dueAt, byProject[p.uid].orEmpty(), hiddenByProject[p.uid] ?: 0)
            },
            unfiled = unfiled, changes = changes, newItems = newItems, truncated = truncated, unfiledHidden = unfiledHidden,
        )
    }
}
