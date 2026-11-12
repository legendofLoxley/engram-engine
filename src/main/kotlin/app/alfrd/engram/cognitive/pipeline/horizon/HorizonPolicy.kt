package app.alfrd.engram.cognitive.pipeline.horizon

import java.time.Duration

/**
 * Every tunable in what decides what is in view, in one place. None of these numbers is a
 * commitment: they are first guesses, expected to be adjusted against the scenarios in
 * `HorizonScenarioTest` and against live use — which is why nothing else in the selection code
 * carries a magic number of its own. Inject a different instance to change behavior (tests do).
 *
 * The model, in brief. Each item gets **activation** from independent sources — the user's pinned
 * focus, casual mentions (nudges) that fade, things stated this turn, older `relevant_to` links,
 * deadlines drawing near, an explicit "keep this in front of me" — each spreading a little way along
 * the graph's edges, losing strength at every step. An item's score is those contributions combined
 * so that each adds but none can exceed 1 ([combine]). Nothing here is stored: it is all derived
 * from the graph and the clock every turn, so it is always current and always explainable.
 */
data class HorizonPolicy(
    // ── How strongly each edge type carries activation ───────────────────────
    /** Container -> member (project to its items, domain to its projects). */
    val partOfDown: Double = 0.8,
    /** Member -> container (an item lights its project, a project its domain). */
    val partOfUp: Double = 0.5,
    /** Item <-> the entity it is about (item to entity, then entity to other items about it). */
    val about: Double = 0.65,
    /** Multiplies a `relevant_to` edge's own recorded strength. */
    val relevantTo: Double = 1.0,
    /** Applied once per hop after the first, so far-away things fade faster than near ones. */
    val hopDecay: Double = 0.6,
    /** A step that would carry less than this is not taken — bounds the walk. */
    val spreadFloor: Double = 0.1,
    /** Hard cap on nodes expanded per spread, so a dense graph can't make selection unbounded. */
    val maxExpansions: Int = 200,

    // ── How far focus reaches, by zoom ───────────────────────────────────────
    val hopsOverview: Int = 1,
    val hopsBinoculars: Int = 2,
    val hopsTelescope: Int = 4,
    /** Reach of everything that isn't the pinned focus (nudges, things said this turn). */
    val hopsSeed: Int = 2,

    // ── Where activation starts ──────────────────────────────────────────────
    val pinnedFocusActivation: Double = 1.0,
    /** A nudge's strength at the cycle it was set, before its own weight. */
    val nudgeActivation: Double = 0.6,
    /** A nudge loses half its strength every this many cycles. Turns, not wall-clock, so it means the same at any pace. */
    val nudgeHalfLifeCycles: Double = 3.0,
    /** A nudge weaker than this has faded out. */
    val nudgeFloor: Double = 0.12,
    /** Nudges older than this many cycles are deleted, so they can't accumulate forever. */
    val nudgePruneCycles: Long = 20,
    val justAssertedActivation: Double = 0.85,
    /** A `relevant_to` link asserted within the relevance window. */
    val reactivationActivation: Double = 0.9,
    /** An item the user asked to keep in front of them. */
    val pinnedItemActivation: Double = 1.0,
    /** An open item that belongs to no project, with nothing else drawing it in — it doesn't have a container to be out of view with. */
    val unfiledOpenActivation: Double = 0.4,
    /** How many of the most recently changed open, unfiled items get that baseline; the rest are not lost, they just are not put forward until something else draws them in. */
    val openBaselineItems: Int = 24,
    /** A waiting item whose check-in date has arrived: it comes into view whatever the focus, to be asked about. */
    val checkInActivation: Double = 0.7,
    /** Recent evidence reported by Hermes or the environment, outside anything the user said. */
    val peripheralActivation: Double = 0.6,

    // ── What counts as a mention ─────────────────────────────────────────────
    /** Fraction of the shorter side's distinctive words that must overlap for a message to nudge an item. */
    val mentionMinOverlap: Double = 0.34,
    /** An item reached because the user named an entity it is about. */
    val entityMentionWeight: Double = 0.8,
    /** At most this many items are nudged by one message. */
    val maxMentionNudges: Int = 6,
    /**
     * How many of the user's most recent items a message is matched against. This is the whole of
     * recall — there is no separate lookup — so it reaches well back; the scan is one query over
     * item words, and only [maxMentionNudges] of the matches are kept.
     */
    val mentionScanItems: Int = 1500,

    // ── Deadlines ────────────────────────────────────────────────────────────
    /** An item's urgency halves this many days out (so 0.5 at that distance, 1.0 when due, rising toward it). */
    val taskUrgencyHalfDays: Double = 2.0,
    val eventUrgencyHalfDays: Double = 2.0,
    val projectUrgencyHalfDays: Double = 5.0,
    /** An overdue task stays at full urgency this long, then eases to [overdueUrgencyFloor]. */
    val overdueFullUrgencyDays: Double = 3.0,
    val overdueUrgencyFloor: Double = 0.6,
    /** An event this far in the past is over and no longer urgent; until then it is "happening now". */
    val eventGraceHours: Double = 3.0,
    /** Beyond this many days a deadline isn't looked at at all. */
    val urgencyLookaheadDays: Long = 30,
    /** Urgency at or above this is shown whatever the focus — the birthday two days away is not hidden by "let's work on the demo". */
    val breakthroughUrgency: Double = 0.4,
    /** Below this an item isn't worth a slot on urgency alone. */
    val urgencyFloor: Double = 0.1,
    /** How much an urgent project lends its items. */
    val projectUrgencyToMembers: Double = 0.7,
    /** Bound on how many of the user's open tasks, events and projects (each) one selection reads. */
    val urgencyScanLimit: Int = 500,

    // ── What is allowed in from outside the conversation ─────────────────────
    /** How many slots reports from Hermes or the environment may take, in total, outside the focus. */
    val peripheralSlots: Int = 2,

    // ── Silence ──────────────────────────────────────────────────────────────
    /** A gap this long since the user's last turn makes Alfrd open a question about what they have been up to. */
    val silenceThreshold: Duration = Duration.ofDays(7),

    // ── Staleness, breakthrough deadlines, and what a focus lists ────────────
    /** An open or active item with a passed due date and no evidence for more than this many days is asked about, not alarmed over (a hard one keeps its alarm). */
    val staleEvidenceDays: Long = 14,
    /** "Also due": a hard deadline this many calendar days out (or any hard one overdue) breaks through whatever the focus. */
    val alsoDueHardDays: Long = 3,
    /** "Also due": a soft deadline this many calendar days after today (so 1 is today and tomorrow) breaks through whatever the focus. */
    val alsoDueSoftDays: Long = 1,
    /** At most this many lines in the "also due" block; the rest are counted. */
    val alsoDueMaxLines: Int = 10,
    /** Zoomed out, an item the user is working on (active) comes into view on its own. */
    val activeActivation: Double = 0.5,
    /** The most items of a focused project listed in full (grouped by state), however many the budget allows others. */
    val focusItemCap: Int = 40,
    /** The most suppressed items of a focused project listed under "Hidden". */
    val hiddenItemCap: Int = 20,

    // ── Overall ──────────────────────────────────────────────────────────────
    /** Anything scoring below this stays out of view, however much room there is. */
    val visibilityFloor: Double = 0.1,
    /** Most containers shown in the overview. */
    val maxContainersInView: Int = 12,

    // ── The zoomed-out board ─────────────────────────────────────────────────
    // A fixed-size status view of the whole graph, built by code on every read. Budgets are measured on the rendered text, not on item counts.
    /** Tokens the zoomed-out board may use, however many projects there are. A starting guess, to be tuned against the real graph. */
    val boardTokenBudget: Int = 800,
    /** Tokens each focused view (a project, an area or one item) may use in the Director tick and the owner's tool; the lowest-pressure items are left out, and counted. */
    val zoomTokenBudget: Int = 1000,
    /** How a rendered size becomes tokens: characters divided by this. 3.5 is a little pessimistic for English with refs and digits, so a board errs small. */
    val charsPerToken: Double = 3.5,
    // Pressure: each signal is 0 to 1, scaled by its weight, and the node's pressure combines them as the selector does (each adds, none passes 1).
    val boardWeightOverdue: Double = 0.9,
    val boardWeightDueSoon: Double = 0.7,
    val boardWeightWaitingDue: Double = 0.6,
    val boardWeightChanged: Double = 0.5,
    val boardWeightStale: Double = 0.5,
    val boardWeightFocused: Double = 0.8,
    /** A pinned node always keeps a line; this only orders it among the rest. */
    val boardWeightPinned: Double = 1.0,
    /** Below this pressure a node is quiet and rolls up into its parent. */
    val boardQuietBelow: Double = 0.15,
    /** Pinned nodes are admitted to the board first and count against its budget; at most this many keep a line, the rest are counted. */
    val boardPinnedCap: Int = 8,
    /** At most this many items listed under one project line. */
    val boardItemsPerNode: Int = 2,
    /** At most this many lines in the "since last time" section. */
    val boardSinceLines: Int = 4,
    /** At most this many quiet-area lines before the rest are counted in one. */
    val boardRollupLines: Int = 3,
    /** With no earlier read of a channel on record (a restart forgets), "since last time" looks back this many hours. */
    val boardFirstReadLookbackHours: Long = 24,
    /** Bounds on what one board read scans: open items, and change records. */
    val boardScanLimit: Int = 5000,
    val boardChangeScanLimit: Int = 2000,
) {

    fun hopsFor(zoom: FocusZoom): Int = when (zoom) {
        FocusZoom.OVERVIEW -> hopsOverview
        FocusZoom.BINOCULARS -> hopsBinoculars
        FocusZoom.TELESCOPE -> hopsTelescope
    }

    /**
     * A nudge set [ageCycles] ago with [weight]: halves every [nudgeHalfLifeCycles]. Returns 0 once it
     * falls below [nudgeFloor], which is what "fades" means here.
     */
    fun nudgeStrength(weight: Double, ageCycles: Long): Double {
        if (ageCycles < 0) return 0.0
        val strength = nudgeActivation * weight * Math.pow(0.5, ageCycles / nudgeHalfLifeCycles)
        return if (strength < nudgeFloor) 0.0 else strength
    }

    /**
     * What one more step leaves: [activation] carried over an edge with [transmission], from a node
     * already [fromHops] steps out. The first step from a seed keeps the edge's full strength; each
     * later one also loses [hopDecay], so far-away things fade faster than near ones.
     */
    fun step(activation: Double, transmission: Double, fromHops: Int): Double =
        activation * transmission * (if (fromHops <= 0) 1.0 else hopDecay)

    /**
     * How pressing a deadline is at [nowMillis], 0 to 1, or 0 if it isn't a deadline worth showing.
     * Rises smoothly as [dueAt] approaches (halving every [halfDays] days out), is full when due, and
     * for a task past due stays full for [overdueFullUrgencyDays] then eases to a floor — an overdue
     * task is still owed. An [event] that has started is "happening now" until [eventGraceHours] pass,
     * then it is over and no longer urgent (it isn't owed anything).
     */
    fun urgency(dueAt: Long, nowMillis: Long, halfDays: Double, event: Boolean): Double {
        val daysUntil = (dueAt - nowMillis) / MILLIS_PER_DAY
        if (daysUntil > urgencyLookaheadDays) return 0.0
        if (daysUntil >= 0) return 1.0 / (1.0 + daysUntil / halfDays)
        if (event) return if (-daysUntil * 24.0 <= eventGraceHours) 1.0 else 0.0
        val daysOverdue = -daysUntil
        return if (daysOverdue <= overdueFullUrgencyDays) 1.0 else overdueUrgencyFloor
    }

    companion object {
        private const val MILLIS_PER_DAY = 86_400_000.0

        /**
         * The defaults with the staleness and "also due" windows overridden from the environment (`HORIZON_STALE_DAYS`,
         * `HORIZON_ALSO_DUE_HARD_DAYS`, `HORIZON_ALSO_DUE_SOFT_DAYS`, `HORIZON_ALSO_DUE_MAX_LINES`) and the board budgets (`HORIZON_BOARD_TOKENS`, `HORIZON_ZOOM_TOKENS`). A value that is not a
         * non-negative whole number is ignored.
         */
        fun fromEnvironment(env: Map<String, String> = System.getenv()): HorizonPolicy {
            fun days(name: String): Long? = env[name]?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
            val base = HorizonPolicy()
            return base.copy(
                staleEvidenceDays = days("HORIZON_STALE_DAYS") ?: base.staleEvidenceDays,
                alsoDueHardDays = days("HORIZON_ALSO_DUE_HARD_DAYS") ?: base.alsoDueHardDays,
                alsoDueSoftDays = days("HORIZON_ALSO_DUE_SOFT_DAYS") ?: base.alsoDueSoftDays,
                alsoDueMaxLines = days("HORIZON_ALSO_DUE_MAX_LINES")?.toInt()?.takeIf { it > 0 } ?: base.alsoDueMaxLines,
                boardTokenBudget = days("HORIZON_BOARD_TOKENS")?.toInt()?.takeIf { it > 0 } ?: base.boardTokenBudget,
                zoomTokenBudget = days("HORIZON_ZOOM_TOKENS")?.toInt()?.takeIf { it > 0 } ?: base.zoomTokenBudget,
            )
        }

        /** Independent contributions add but can't pass 1: 1 - the product of what each leaves unreached. */
        fun combine(contributions: Collection<Double>): Double =
            (1.0 - contributions.fold(1.0) { rest, c -> rest * (1.0 - c.coerceIn(0.0, 1.0)) }).coerceIn(0.0, 1.0)
    }
}
