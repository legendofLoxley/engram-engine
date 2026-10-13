package app.alfrd.engram.cognitive.pipeline.agentwork

/** What a model proposes for one task, before anything checks it. Every field may be missing or wrong. */
data class ProposedTask(
    val title: String?,
    val type: String?,
    val instructions: String?,
    val targetLinks: List<String>,
)

enum class RejectReason {
    OVER_BUDGET,
    MISSING_FIELD,
    TYPE_NOT_ALLOWED,
    TITLE_TOO_LONG,
    INSTRUCTIONS_TOO_LONG,
    TOO_MANY_LINKS,
    LINK_NOT_A_URL,
    LINK_NOT_ALLOWED,
    LOCKS_OR_CHANGES_ANSWER_STANDARD,
    DELETES_OR_OVERWRITES_ROW,
    DUPLICATE_TASK,
}

/** [detail] names the cause (a type, a link, a verb) and never quotes the task's instructions. */
data class Rejection(val index: Int, val title: String?, val reason: RejectReason, val detail: String? = null)

data class ValidatedPlan(val tasks: List<PlannedTask>, val rejected: List<Rejection>)

/**
 * Checks a proposed plan **in code**, task by task, and keeps only what passes. A model's output is
 * a proposal and never a permission: nothing here is decided by the prompt.
 *
 * A task is rejected if
 * - it would take the plan past the cycle's task budget (only accepted tasks count);
 * - its type is not one a plan may propose (Orchestrate, Gate and QC are the Director's own steps);
 * - it has no title, type or instructions, or a title or instructions beyond the length limits;
 * - it has a link that is not an http(s) URL, or not in the goal's [AllowedScope] (the pages and databases its
 *   Scope mentions, and the rows of a mentioned database; an external URL is never in scope);
 * - it asks to lock or change an answer standard, or to delete or overwrite a row ([ForbiddenActions]);
 * - it repeats the title of an earlier accepted task.
 *
 * Order is assigned here (1, 2, 3, ...) over the accepted tasks; the model never sets it.
 */
object CyclePlanValidator {
    const val MAX_TITLE_CHARS = 200
    const val MAX_INSTRUCTIONS_CHARS = 1800
    const val MAX_LINKS_PER_TASK = 8

    fun validate(proposed: List<ProposedTask>, budget: Int, allowed: AllowedScope): ValidatedPlan {
        val accepted = ArrayList<PlannedTask>()
        val rejected = ArrayList<Rejection>()
        val seenTitles = HashSet<String>()

        for ((index, task) in proposed.withIndex()) {
            val title = task.title?.trim().orEmpty()
            fun reject(reason: RejectReason, detail: String? = null) { rejected += Rejection(index, title.ifEmpty { null }, reason, detail) }

            if (accepted.size >= budget) { reject(RejectReason.OVER_BUDGET, "budget is $budget"); continue }

            val instructions = task.instructions?.trim().orEmpty()
            if (title.isEmpty() || instructions.isEmpty() || task.type.isNullOrBlank()) { reject(RejectReason.MISSING_FIELD); continue }

            val type = TaskType.fromLabel(task.type.trim())
            if (type == null || !type.proposable) { reject(RejectReason.TYPE_NOT_ALLOWED, task.type.trim().take(40)); continue }

            if (title.length > MAX_TITLE_CHARS) { reject(RejectReason.TITLE_TOO_LONG); continue }
            if (instructions.length > MAX_INSTRUCTIONS_CHARS) { reject(RejectReason.INSTRUCTIONS_TOO_LONG); continue }

            val links = task.targetLinks.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (links.size > MAX_LINKS_PER_TASK) { reject(RejectReason.TOO_MANY_LINKS, "more than $MAX_LINKS_PER_TASK"); continue }
            val badLink = links.firstOrNull { !isHttpUrl(it) } // top-level helper in ScopeContext.kt
            if (badLink != null) { reject(RejectReason.LINK_NOT_A_URL, badLink.take(120)); continue }
            val outside = links.firstOrNull { !allowed.allows(it) }
            if (outside != null) { reject(RejectReason.LINK_NOT_ALLOWED, outside.take(200)); continue }

            val forbidden = ForbiddenActions.scan(title, instructions)
            if (forbidden != null) { reject(forbidden.reason, forbidden.detail); continue }

            if (!seenTitles.add(title.lowercase())) { reject(RejectReason.DUPLICATE_TASK); continue }

            accepted += PlannedTask(order = accepted.size + 1, title = title, type = type, instructions = instructions, targetLinks = links)
        }
        return ValidatedPlan(accepted, rejected)
    }

}

/**
 * Finds a task that asks to lock or change an answer standard, or to delete or overwrite a row, by
 * reading the text of its title and instructions. This is a **lexical** check, deliberately
 * conservative: it looks for an *action* (a verb used as an instruction) aimed at one of those
 * things, and ignores a mere mention of them. "Read the memo before answer standards are locked" is
 * fine; "Lock the answer standard" and "Delete the duplicate claim row" are not.
 *
 * A verb counts as an action when it opens a clause, or follows `to`, `and`, `then`, `please`,
 * `should`, `must` and the like (adverbs such as "immediately" are skipped over); the thing it acts on
 * must follow within six words. A verb after `not`, `never`, `before`, `without` or `avoid` is not in
 * that position and is not flagged. English only, and it can be fooled by a determined rewording, which
 * is why it is one layer: the guard still refuses every write to the ledger, and a task of a type that
 * writes does not exist in this increment.
 */
object ForbiddenActions {
    data class Finding(val reason: RejectReason, val detail: String)

    private val LOCK_VERBS = setOf("lock", "locking", "unlock", "unlocking", "finalize", "finalise", "finalizing", "finalising", "freeze", "freezing", "certify", "certifying")
    private val CHANGE_VERBS = setOf(
        "change", "changing", "modify", "modifying", "edit", "editing", "alter", "altering", "update", "updating",
        "revise", "revising", "rewrite", "rewriting", "adjust", "adjusting", "set", "setting", "reset", "resetting", "override", "overriding",
    )
    private val DELETE_VERBS = setOf(
        "delete", "deleting", "remove", "removing", "erase", "erasing", "purge", "purging", "destroy", "destroying",
        "overwrite", "overwriting", "wipe", "wiping", "discard", "discarding", "drop", "dropping", "truncate", "truncating", "replace", "replacing",
    )

    private val LOCK_OBJECT = Regex("""\b(answer standards?|standards?|standard locked|lock notes?)\b""")
    private val CHANGE_OBJECT = Regex("""\b(answer standards?|standard locked|lock notes?|required claims?)\b""")
    private val DELETE_OBJECT = Regex("""\b(rows?|pages?|claims?|sources?|evidence|questions?|records?|entry|entries|ledger|cells?|findings?)\b""")

    /** Words that may stand directly before a verb used as an instruction. */
    private val LEADERS = setOf("to", "and", "then", "also", "please", "now", "next", "finally", "first", "should", "must", "will", "can", "could", "just", "we", "you", "alfrd")

    private const val OBJECT_WINDOW = 6

    fun scan(vararg texts: String): Finding? {
        for (text in texts) {
            for (clause in clauses(text)) {
                scanClause(clause)?.let { return it }
            }
        }
        return null
    }

    private fun clauses(text: String): List<List<String>> =
        text.lowercase().split(Regex("""[.;:!?\n]+""")).map { clause -> clause.split(Regex("""[^a-z']+""")).filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }

    private fun scanClause(words: List<String>): Finding? {
        for (i in words.indices) {
            val word = words[i]
            val family = when (word) {
                in LOCK_VERBS -> Family.LOCK
                in CHANGE_VERBS -> Family.CHANGE
                in DELETE_VERBS -> Family.DELETE
                else -> continue
            }
            if (!inVerbPosition(words, i)) continue
            val window = words.subList(i + 1, minOf(words.size, i + 1 + OBJECT_WINDOW)).joinToString(" ")
            val hit = when (family) {
                Family.LOCK -> LOCK_OBJECT.find(window)
                Family.CHANGE -> CHANGE_OBJECT.find(window)
                Family.DELETE -> DELETE_OBJECT.find(window)
            } ?: continue
            return Finding(family.reason, "'$word' aimed at '${hit.value}'")
        }
        return null
    }

    private fun inVerbPosition(words: List<String>, index: Int): Boolean {
        var j = index - 1
        while (j >= 0 && words[j].endsWith("ly")) j--
        return j < 0 || words[j] in LEADERS
    }

    private enum class Family(val reason: RejectReason) {
        LOCK(RejectReason.LOCKS_OR_CHANGES_ANSWER_STANDARD),
        CHANGE(RejectReason.LOCKS_OR_CHANGES_ANSWER_STANDARD),
        DELETE(RejectReason.DELETES_OR_OVERWRITES_ROW),
    }
}
