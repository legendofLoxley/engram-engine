package app.alfrd.engram.cognitive.pipeline.horizon

import com.arcadedb.database.Database
import com.arcadedb.event.BeforeRecordDeleteListener
import com.arcadedb.event.BeforeRecordUpdateListener
import com.arcadedb.graph.Vertex
import com.arcadedb.schema.Schema
import com.arcadedb.schema.Type
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Which changes leave a [ChangeRecord], in one short list. Opt-in: a field that is not named here never produces a record, and there is no
 * generic hook on property writes. Everything else the store writes (`updatedAt`, `lastEvidenceAt`, hashes, index keys, run ids, the coarse
 * `status`, `resolvedAt`, the `*HasTime` flags) is bookkeeping, not a change, and is deliberately absent.
 *
 * `dueAt` is the logical field for "the time the item is bound to": for an event the stored property is `occursAt`, recorded as `dueAt`.
 * `PART_OF` and `FOCUSES_ON` are edges: re-filing replaces a `PART_OF` edge and releasing a focus deletes a `FOCUSES_ON` edge, so the edge
 * is recorded before it goes. (`pruneNudges` also deletes `FOCUSES_ON` edges; that is a fade-out cleanup, not a change anyone made, and is not recorded.)
 */
object ChangeTracking {
    val FIELDS: Set<String> = linkedSetOf("state", "dueAt", "dueKind", "waitingOn", "nudgeAt", "attention")
    val EDGES: Set<String> = linkedSetOf("PART_OF", "FOCUSES_ON")

    const val TYPE = "ChangeRecord"
    /** `target -HAS_CHANGE-> ChangeRecord`. See [ChangeRecords.ensureSchema] for why this name. */
    const val EDGE = "HAS_CHANGE"
    const val FAMILY_CHANGE = "change"
    const val FAMILY_RETRACTION = "retraction"
    val FAMILIES: Set<String> = setOf(FAMILY_CHANGE, FAMILY_RETRACTION)

    fun tracked(field: String): Boolean = field in FIELDS || field in EDGES
}

/**
 * Who and what made a change, set by whoever knows (the operation applier sets the verb). Without one the store uses the name of its own
 * operation as the cause and the owner as the actor.
 */
class ChangeContext(val cause: String, val actor: String? = null) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ChangeContext>
}

/** One recorded change, as read back. Read-only by construction: the fields are vals and nothing here writes. */
data class ChangeRecordView(
    val uid: String,
    val ownerEmail: String,
    val targetUid: String,
    val targetType: String,
    val family: String,
    val field: String,
    val oldValue: String?,
    val newValue: String?,
    val changedAt: Long,
    /** When the change became true in the world, if that differs from [changedAt]. Null means the same moment as [changedAt]. Nothing sets it yet. */
    val effectiveAt: Long?,
    val cause: String,
    val actor: String,
    val reason: String?,
)

/**
 * The append-only change log. This is the only code that writes a `ChangeRecord`, and it has an `append` and reads, nothing else: no update
 * and no delete. The database backs that up (see [ensureSchema]): every property is read-only after the record is written, and an update
 * or delete of a `ChangeRecord` is vetoed in-process.
 *
 * A record is written in the same transaction as the change it describes, so the two exist together or not at all. The `retraction` family
 * is the record shape only: nothing here writes one yet, and nothing in the Horizon reads the family.
 */
internal class ChangeLog(private val db: Database) {
    private val logger = LoggerFactory.getLogger(ChangeLog::class.java)

    /**
     * Records `field` of [target] going from [old] to [new], unless they are the same value. Returns true if a record was written.
     * Must be called inside the writing transaction, after the change itself has been saved (so the edge is added to a fresh copy of the vertex,
     * never to a stale one that a later save would overwrite).
     */
    fun append(
        ownerEmail: String,
        target: Vertex,
        field: String,
        old: String?,
        new: String?,
        cause: String,
        actor: String,
        changedAt: Long = System.currentTimeMillis(),
        family: String = ChangeTracking.FAMILY_CHANGE,
        reason: String? = null,
        effectiveAt: Long? = null,
    ): Boolean {
        require(ChangeTracking.tracked(field)) { "'$field' is not a tracked field; add it to ChangeTracking.FIELDS first" }
        require(family in ChangeTracking.FAMILIES) { "unknown change family '$family'" }
        if (normalize(field, old) == normalize(field, new)) return false
        val targetUid = target.get("uid") as? String ?: run {
            logger.warn("change-log: target without a uid; not recording a change to {}", field)
            return false
        }
        val record = db.newVertex(ChangeTracking.TYPE).apply {
            set("uid", UUID.randomUUID().toString())
            set("ownerEmail", ownerEmail)
            set("targetUid", targetUid)
            set("targetType", target.typeName)
            set("family", family)
            set("field", field)
            if (old != null) set("oldValue", old)
            if (new != null) set("newValue", new)
            set("changedAt", changedAt)
            if (effectiveAt != null) set("effectiveAt", effectiveAt)   // written only when given; absent reads as "same as changedAt"
            set("cause", cause)
            set("actor", actor)
            if (!reason.isNullOrBlank()) set("reason", reason)
            save()
        }
        // A fresh copy of the target: the caller's may be stale, and saving the vertex back must not drop an edge added elsewhere in this transaction.
        val fresh = db.lookupByRID(target.identity, true).asVertex().modify()
        fresh.newEdge(ChangeTracking.EDGE, record, false).save()
        return true
    }

    /** A stored default reads as no change: an absent `dueKind` is soft. */
    private fun normalize(field: String, value: String?): String? = if (field == "dueKind" && value == null) "soft" else value

    /** The changes recorded for [targetUid] that belong to [ownerEmail], oldest first. Other owners' records for the same uid are never returned. */
    fun forTarget(ownerEmail: String, targetUid: String): List<ChangeRecordView> =
        db.query("sql", "SELECT FROM ${ChangeTracking.TYPE} WHERE ownerEmail = :e AND targetUid = :t", mapOf("e" to ownerEmail, "t" to targetUid)).use { rs ->
            val out = mutableListOf<ChangeRecordView>()
            while (rs.hasNext()) {
                val v = rs.next().toElement().asVertex()
                out += ChangeRecordView(
                    uid = v.get("uid") as String,
                    ownerEmail = v.get("ownerEmail") as String,
                    targetUid = v.get("targetUid") as String,
                    targetType = v.get("targetType") as String,
                    family = v.get("family") as String,
                    field = v.get("field") as String,
                    oldValue = v.get("oldValue") as? String,
                    newValue = v.get("newValue") as? String,
                    changedAt = (v.get("changedAt") as Number).toLong(),
                    effectiveAt = (v.get("effectiveAt") as? Number)?.toLong(),
                    cause = v.get("cause") as String,
                    actor = v.get("actor") as String,
                    reason = v.get("reason") as? String,
                )
            }
            out.sortedBy { it.changedAt }
        }
}

object ChangeRecords {
    private val required = listOf("uid" to Type.STRING, "ownerEmail" to Type.STRING, "targetUid" to Type.STRING, "targetType" to Type.STRING,
        "family" to Type.STRING, "field" to Type.STRING, "changedAt" to Type.LONG, "cause" to Type.STRING, "actor" to Type.STRING)
    private val optional = listOf("oldValue" to Type.STRING, "newValue" to Type.STRING, "reason" to Type.STRING, "effectiveAt" to Type.LONG)

    /**
     * The `ChangeRecord` vertex type and the `HAS_CHANGE` edge, additive and idempotent.
     *
     * - **Not a Phrase and not an item**, so no Horizon query, selector or scan can ever see one. (It is also not named `Event`: that is the
     *   world-event item type.)
     * - **`HAS_CHANGE`, target to record**: the thing that changed owns its history (`item -HAS_CHANGE-> record`), the same direction as the other
     *   "has" edges that hang detail off an item (`ABOUT`, `QUOTES`), it reads as what it is, and it does not collide with `FOLLOWS`/`RELATED_TO`
     *   (which carry supersession) or `CONTAINS`. It is created `bidirectional=false`; read it outward, or with SQL on `targetUid`.
     * - **`effectiveAt`** is optional: when a change became true in the world, if that differs from `changedAt` (when it was recorded). Absent
     *   means the same moment. It was added after the type first shipped, so `ensureSchema` adds it to a database that already holds records,
     *   without touching them. No verb sets it yet.
     * - **Append-only at the database level**: every property is mandatory (where required) and read-only, so a written record cannot be edited, and
     *   `uid` is unique. Deleting or updating a record is vetoed by a listener registered at every start (listeners are not persisted).
     *   Neither mechanism stops someone replacing the database files; they stop the engine, its SQL and its code from rewriting history.
     *   This type is new, so it has no legacy rows and its constraints do not depend on `SCHEMA_ENFORCE_CONSTRAINTS`.
     */
    fun ensureSchema(schema: Schema) {
        if (!schema.existsType(ChangeTracking.TYPE)) schema.createVertexType(ChangeTracking.TYPE)
        val type = schema.getType(ChangeTracking.TYPE)
        for ((name, t) in required + optional) if (!type.existsProperty(name)) type.createProperty(name, t)
        for ((name, _) in required) type.getProperty(name).apply { if (!isMandatory) setMandatory(true); if (!isNotNull) setNotNull(true) }
        for ((name, _) in required + optional) type.getProperty(name).apply { if (!isReadonly) setReadonly(true) }
        type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, true, "uid")
        type.getOrCreateTypeIndex(Schema.INDEX_TYPE.LSM_TREE, false, "ownerEmail", "targetUid")
        if (!schema.existsType(ChangeTracking.EDGE)) schema.createEdgeType(ChangeTracking.EDGE)
        // One listener object, so registering at every start never stacks copies.
        type.events.unregisterListener(AppendOnly as BeforeRecordDeleteListener).unregisterListener(AppendOnly as BeforeRecordUpdateListener)
        type.events.registerListener(AppendOnly as BeforeRecordDeleteListener).registerListener(AppendOnly as BeforeRecordUpdateListener)
    }

    /**
     * Runs [body] with the in-process delete veto lifted from `ChangeRecord`, then puts it back. This is the narrowest bypass there is, and it
     * exists for one caller: the offline fixture-purge tool ([app.alfrd.engram.db.FixturePurge]), which removes a fixture user's own records
     * and checks each record's owner before it deletes it. It is `internal`, it lifts only the delete veto (the update veto stays), it works on
     * the one `Database` handed in, and the veto is a listener registered per process, so nothing outside this call and this process is affected.
     */
    internal fun <T> withDeleteVetoLifted(schema: Schema, body: () -> T): T {
        if (!schema.existsType(ChangeTracking.TYPE)) return body()
        val events = schema.getType(ChangeTracking.TYPE).events
        events.unregisterListener(AppendOnly as BeforeRecordDeleteListener)
        try {
            return body()
        } finally {
            events.registerListener(AppendOnly as BeforeRecordDeleteListener)
        }
    }

    private object AppendOnly : BeforeRecordDeleteListener, BeforeRecordUpdateListener {
        override fun onBeforeDelete(record: com.arcadedb.database.Record): Boolean = false
        override fun onBeforeUpdate(record: com.arcadedb.database.Record): Boolean = false
    }
}
