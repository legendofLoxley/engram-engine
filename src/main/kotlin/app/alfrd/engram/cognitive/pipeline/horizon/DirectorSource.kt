package app.alfrd.engram.cognitive.pipeline.horizon

import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The Director as its own source (see "Director and goals"). Everything the Director writes for a goal is asserted by the Director's own Source
 * ([DIRECTOR_SOURCE_TYPE]) and carries the goal and the cycle it was written in, so it always reads as the Director's and never as the owner's statement.
 *
 * Two things keep it that way, and neither is the model's to honour:
 *  - the stamp ([DirectorStamp]) travels with the write as a coroutine context element ([DirectorContext]), is read by the store inside the writing transaction, and
 *    is written on the item, on the asserting edge, on every link and on every change record from the moment they exist. The store refuses the Director's Source
 *    without a stamp and a stamp without the Director's Source, so no other path can write one;
 *  - [DirectorGuard] decides, in code, what the Director may do at all, and [DirectorWriter] is the only way to write as the Director.
 */
data class DirectorStamp(
    /** The goal the Director is working. */
    val goalUid: String,
    /** Which cycle of that goal it is (1 for the first). Whoever starts a cycle numbers it; the stamp only carries it. */
    val cycle: Int,
) {
    /** Who a change record names as the actor of a Director write. */
    val actor: String get() = "director:$goalUid:$cycle"
}

/** Carries a [DirectorStamp] through a write. Only [DirectorWriter] puts one on a coroutine. */
class DirectorContext(val stamp: DirectorStamp) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<DirectorContext>
}

/** What the Director's scope is for one goal, worked out from the graph each time (never cached): the project the goal is filed under, or why there is none. */
sealed interface DirectorScope {
    data class Open(val goal: ItemRecord, val projectUid: String, val projectName: String) : DirectorScope
    data class Closed(val reason: String) : DirectorScope
}

/**
 * The write guard. [scopeOf] says whether the Director may write for a goal at all and where; [refusal] says, for one operation, why it may not (null when it may).
 *
 * What the Director may do, inside the goal's scope (the project the goal is filed under, frozen when the owner approves the goal):
 *  - create items and notes (a note is a fact), filed under the scope project, never from a source's chunk, never a goal;
 *  - link: from something it wrote, to something it wrote or something filed in the scope;
 *  - on what it wrote itself (for this goal), anything the owner's verbs do to an item: resolve, reopen, set a state, confirm, correct, re-file within the scope.
 * What it may not do: touch anything it did not write (edit, resolve, correct, re-file, reopen, confirm, suppress, pin), write outside the scope, create or
 * change containers, approve or edit goals, move the owner's focus, hold for a choice, or retract a cycle. It may suggest a change as a note ([DirectorWriter.suggestStateChange]).
 *
 * The `when` over the operation has no `else`: adding an operation type fails to compile until someone decides what the Director may do with it.
 */
open class DirectorGuard(private val structure: HorizonStructureStore) {

    open suspend fun scopeOf(userEmail: String, goalUid: String): DirectorScope {
        val goal = structure.getItem(userEmail, goalUid) ?: return DirectorScope.Closed("the goal does not exist")
        if (goal.kind != ItemKind.GOAL) return DirectorScope.Closed("that is not a goal")
        if (goal.approval != "approved") return DirectorScope.Closed("the goal is not approved; the Director works only what the owner has approved")
        when (goal.workState) {
            WorkState.ACTIVE -> Unit
            WorkState.WAITING -> return DirectorScope.Closed("the goal is paused")
            WorkState.DONE, WorkState.DROPPED -> return DirectorScope.Closed("the goal is done or stopped, which is final")
            else -> return DirectorScope.Closed("the goal is approved but not running")
        }
        val projectUid = goal.projectUid ?: return DirectorScope.Closed("the goal is not filed under a project, so its scope is empty")
        val project = structure.getContainer(userEmail, projectUid)
        if (project == null || project.status != "active") return DirectorScope.Closed("the goal's project is no longer active")
        return DirectorScope.Open(goal, projectUid, project.name)
    }

    /** Why [op] is refused for the Director working [scope] (a reason that says what the rule is), or null when it is allowed. */
    open suspend fun refusal(userEmail: String, scope: DirectorScope.Open, op: HorizonOperation): String? = when (op) {
        is RecordItem -> when {
            op.kind == ItemKind.GOAL -> "the Director does not propose goals; goals come from the owner's own words"
            op.chunkUid != null -> "the Director's items rest on its own words, not on a source's chunk"
            op.projectName != null -> OUTSIDE_SCOPE
            op.projectUid != null && op.projectUid != scope.projectUid -> OUTSIDE_SCOPE
            else -> null
        }
        is ResolveItem -> own(userEmail, scope, op.targetUid)
        is ReopenItem -> own(userEmail, scope, op.targetUid)
        is SetState -> own(userEmail, scope, op.targetUid)
        is ConfirmItem -> own(userEmail, scope, op.targetUid)
        is CorrectItem -> own(userEmail, scope, op.targetUid)
        is FileItem -> own(userEmail, scope, op.targetUid) ?: if (op.intoUid != scope.projectUid) OUTSIDE_SCOPE else null
        is SetAttention -> if (op.attention == AttentionDirective.PINNED) "the Director does not pin things; the owner decides what is kept up front" else own(userEmail, scope, op.targetUid)
        is LinkItems -> own(userEmail, scope, op.fromUid) ?: if (inScope(userEmail, scope, op.toUid)) null else OUTSIDE_SCOPE
        is CreateContainer -> "the Director does not create projects or areas; its scope is the goal's project"
        is ApproveGoal -> "only the owner approves a goal"
        is EditGoal -> "only the owner changes a goal"
        is SetFocus -> "focus is the owner's instrument; the Director does not move it"
        is ReleaseFocus -> "focus is the owner's instrument; the Director does not move it"
        is HoldForChoice -> "the Director asks through its messages, not by holding the owner's words"
        is RetractCycle -> "only the owner retracts what a goal cycle wrote"
    }

    /** The operation as the Director may run it: filed under the scope project, nothing a chunk or a key could smuggle in. Call only after [refusal] returned null. */
    fun shaped(scope: DirectorScope.Open, op: HorizonOperation): HorizonOperation = when (op) {
        is RecordItem -> op.copy(
            projectUid = scope.projectUid, projectName = null, askWhichProject = false,
            chunkUid = null, claimant = null, writer = null, itemKey = null, runId = null, replacesUid = null,
        )
        else -> op
    }

    /** Null when [uid] is an item this goal's Director wrote (in any cycle); otherwise the reason it is not the Director's to change. */
    private suspend fun own(userEmail: String, scope: DirectorScope.Open, uid: String): String? {
        val item = structure.getItem(userEmail, uid)
        return when {
            item == null -> if (structure.getContainer(userEmail, uid) != null) "the Director does not change projects or areas" else "there is no such item"
            item.directorGoalUid == scope.goal.uid -> null
            item.directorGoalUid != null -> "that item was written by the Director for another goal"
            else -> NOT_OWN
        }
    }

    private suspend fun inScope(userEmail: String, scope: DirectorScope.Open, uid: String): Boolean {
        val item = structure.getItem(userEmail, uid) ?: return false
        return item.directorGoalUid == scope.goal.uid || item.projectUid == scope.projectUid
    }

    companion object {
        const val NOT_OWN = "that item was not written by the Director. The Director may not edit, resolve, correct, re-file, reopen or confirm anything it did not write; it can suggest the change in a note"
        const val OUTSIDE_SCOPE = "that is outside the goal's scope: the Director writes only inside the project the goal is filed under"
    }
}

/**
 * The only way to write as the Director. Takes operations already built (the cycle plans them; nothing here talks to a model), puts each through [DirectorGuard],
 * and applies what passes through the ordinary [HorizonOperationApplier] under the Director's Source and stamp. A refused operation writes nothing and says why.
 *
 * It does not seed focus or propagate, as the owner's route does after a turn: a Director write must not move what the owner is looking at.
 */
class DirectorWriter(
    private val structure: HorizonStructureStore,
    private val applier: HorizonOperationApplier,
    private val guard: DirectorGuard = DirectorGuard(structure),
    private val messages: DirectorMessageStore? = null,
) {
    private val logger = LoggerFactory.getLogger(DirectorWriter::class.java)

    /** The only public way to write: under a cycle the runner issued ([DirectorCycles.begin]). The caller cannot name a cycle number. */
    suspend fun apply(
        userEmail: String, cycle: DirectorCycle, cycleSeq: Long, operations: List<HorizonOperation>,
        zone: ZoneId = ZoneId.systemDefault(), requestId: String = "director-${UUID.randomUUID()}",
    ): List<AppliedOperation> = apply(userEmail, cycle.goalUid, cycle.number, cycleSeq, operations, zone, requestId)

    /** Tests of the stamp itself name a number; production code cannot (it is `internal`). */
    internal suspend fun apply(
        userEmail: String, goalUid: String, cycle: Int, cycleSeq: Long, operations: List<HorizonOperation>,
        zone: ZoneId = ZoneId.systemDefault(), requestId: String = "director-${UUID.randomUUID()}",
    ): List<AppliedOperation> {
        val stamp = DirectorStamp(goalUid, cycle)
        return operations.mapIndexed { index, op ->
            val scope = guard.scopeOf(userEmail, goalUid)
            val why = when (scope) {
                is DirectorScope.Closed -> scope.reason
                is DirectorScope.Open -> guard.refusal(userEmail, scope, op)
            }
            if (why != null || scope !is DirectorScope.Open) {
                logger.info("director-writer: refused {} for goal {}: {}", op.verb, goalUid, why)
                AppliedOperation(op, false, "Refused: $why")
            } else {
                withContext(DirectorContext(stamp)) {
                    applier.apply(userEmail, cycleSeq, "$requestId-$index", listOf(guard.shaped(scope, op)), zone, sourceType = DIRECTOR_SOURCE_TYPE)
                }.single()
            }
        }
    }

    /**
     * Suggests moving [targetUid] to [suggested] without doing it: a note in the scope, in the Director's words, linked to the item. The item itself is not
     * touched, so the owner (or the channel) decides. Refused, like any write, when the goal is not in a state the Director may write for.
     */
    suspend fun suggestStateChange(
        userEmail: String, cycle: DirectorCycle, cycleSeq: Long, targetUid: String, suggested: WorkState, reason: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AppliedOperation> = suggestStateChange(userEmail, cycle.goalUid, cycle.number, cycleSeq, targetUid, suggested, reason, zone)

    internal suspend fun suggestStateChange(
        userEmail: String, goalUid: String, cycle: Int, cycleSeq: Long, targetUid: String, suggested: WorkState, reason: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<AppliedOperation> {
        val target = structure.getItem(userEmail, targetUid)
        val label = target?.text?.take(80) ?: "an item"
        val text = "Suggestion: mark \"$label\" ${suggested.wire()} — ${reason.trim().take(300)}"
        val note = apply(userEmail, goalUid, cycle, cycleSeq, listOf(RecordItem(kind = ItemKind.FACT, quote = text)), zone).single()
        val noteUid = note.resultUid ?: return listOf(note)
        // The link is the pointer from the note to what it is about. It is refused, like any link, when the target is outside the scope.
        val link = apply(userEmail, goalUid, cycle, cycleSeq, listOf(LinkItems(noteUid, targetUid, text)), zone).single()
        return listOf(note, link)
    }

    /** Sends the channel a message about a goal, as a node (see [DirectorMessageStore]). Null when there is nothing to send it from: no message store, or a goal the Director may not speak for. */
    suspend fun send(
        userEmail: String, cycle: DirectorCycle, kind: DirectorMessageKind, text: String, answersUid: String? = null, usageTokens: Long = 0L, usageMillis: Long = 0L,
    ): DirectorMessage? = send(userEmail, cycle.goalUid, cycle.number, kind, text, answersUid, usageTokens, usageMillis)

    internal suspend fun send(
        userEmail: String, goalUid: String, cycle: Int, kind: DirectorMessageKind, text: String, answersUid: String? = null, usageTokens: Long = 0L, usageMillis: Long = 0L,
    ): DirectorMessage? {
        val store = messages ?: return null
        val goal = structure.getItem(userEmail, goalUid) ?: return null
        // A message may be sent for any approved goal, running or not: a finished or stopped goal is exactly when one is wanted.
        if (goal.kind != ItemKind.GOAL || goal.approval != "approved") return null
        return store.post(userEmail, goalUid, cycle, kind, text, answersUid, usageTokens, usageMillis)
    }
}

/** One cycle of one goal, as the runner numbered it. Only [DirectorCycles.begin] makes one, so a caller of [DirectorWriter] cannot pick a number. */
class DirectorCycle internal constructor(val goalUid: String, val number: Int)

/**
 * Numbers a goal's cycles: 1, 2, 3 ..., never reused, never lowered. The next number is one past the highest the goal has ever carried on a message or on an
 * item it wrote (retracted ones included), and the cycle is recorded on a quiet message the moment it begins, so even a cycle that writes nothing and is
 * interrupted has used its number. Serialised per goal so two starts cannot take the same one.
 */
class DirectorCycles(private val structure: HorizonStructureStore, private val messages: DirectorMessageStore) {
    private val locks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()

    suspend fun begin(userEmail: String, goalUid: String): DirectorCycle? {
        val lock = locks.computeIfAbsent("$userEmail/$goalUid") { kotlinx.coroutines.sync.Mutex() }
        return lock.withLock {
            val goal = structure.getItem(userEmail, goalUid)?.takeIf { it.kind == ItemKind.GOAL && it.approval == "approved" } ?: return@withLock null
            val fromItems = structure.directorItems(userEmail, goalUid).maxOfOrNull { it.directorCycle ?: 0 } ?: 0
            val number = maxOf(messages.latestCycle(userEmail, goalUid), fromItems) + 1
            // The start is the record that the number is taken. If it cannot be written, there is no cycle.
            messages.post(userEmail, goal.uid, number, DirectorMessageKind.PROGRESS, "Cycle $number started.") ?: return@withLock null
            DirectorCycle(goalUid, number)
        }
    }
}
