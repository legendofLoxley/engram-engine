package app.alfrd.engram.director.goals

import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.WorkState
import kotlin.math.exp
import kotlin.math.ln

/**
 * Evidence for an open item, chosen by code. A planner is not asked to name the closed history that bears on an open item (it cannot know what is there); for each open
 * item a step covers, code retrieves a bounded set of related closed items of the same project and hands them to the step as labelled evidence with refs.
 *
 * What counts as evidence: the owner's own closed items (done or dropped) and the owner's notes in the goal's project; never what the Director wrote, never goals.
 * How a candidate is ranked (all three signals are plain and the same on every run):
 *  - **shared meaningful words** with the open item, each weighted by how rare it is in the project's evidence (a word in every item says nothing);
 *  - **shared concepts** (the `about` links the two items have in common);
 *  - **recency**, a small bonus that only breaks ties between items that are related at all.
 * An item that shares no word and no concept is not evidence, however recent, and neither is one whose relevance is below the minimum ([GoalRunnerConfig.minEvidenceScore]).
 * Words that appear in more than a quarter of the project's items are ignored (they connect everything to everything).
 */
object GoalEvidence {
    private val STOP = setOf(
        "this", "that", "with", "from", "have", "will", "been", "were", "your", "their", "there", "what", "when", "which", "while", "about", "after", "before",
        "into", "than", "then", "them", "they", "also", "just", "more", "some", "such", "only", "over", "each", "other", "should", "would", "could", "need",
        "needs", "done", "task", "status", "priority", "start", "date", "item", "items", "open", "closed",
    )

    /** The meaningful words of [text]: four letters or more, not a stop word, not a number, and not in [ignore] (the words too common in the project to tell items apart). */
    fun words(text: String, ignore: Set<String> = emptySet()): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in STOP && it !in ignore && !it.all(Char::isDigit) }.toSet()

    /** Words that appear in more than a quarter of the project's items say nothing about any one of them (the project's own name, mostly). Only judged once the project has [MIN_ITEMS_FOR_COMMON] items; a tiny project has no "common". */
    fun commonWords(projectItems: List<ItemRecord>): Set<String> {
        if (projectItems.size < MIN_ITEMS_FOR_COMMON) return emptySet()
        val df = HashMap<String, Int>()
        projectItems.forEach { i -> words(i.text).forEach { df.merge(it, 1, Int::plus) } }
        return df.filterValues { it * 4 > projectItems.size }.keys
    }

    const val MIN_ITEMS_FOR_COMMON = 20

    /** What may serve as evidence from [projectItems]: the owner's closed items and notes. */
    fun pool(projectItems: List<ItemRecord>): List<ItemRecord> = projectItems.filter { it.directorGoalUid == null && it.kind != ItemKind.GOAL && isClosedOrNote(it) }

    fun isOpen(i: ItemRecord): Boolean = i.kind != ItemKind.GOAL && i.kind != ItemKind.FACT && i.workState.let { it == WorkState.OPEN || it == WorkState.ACTIVE || it == WorkState.WAITING }

    private fun isClosedOrNote(i: ItemRecord): Boolean = i.kind == ItemKind.FACT || i.workState == WorkState.DONE || i.workState == WorkState.DROPPED

    /** How many pool items contain each word, for weighting. */
    fun documentFrequency(pool: List<ItemRecord>, ignore: Set<String> = emptySet()): Map<String, Int> {
        val df = HashMap<String, Int>()
        pool.forEach { p -> words(p.text, ignore).forEach { df.merge(it, 1, Int::plus) } }
        return df
    }

    /** A candidate and how it scored. [relevance] is shared words and shared concepts only (what the minimum is judged on); [score] adds the recency tie-break. */
    data class Scored(val item: ItemRecord, val relevance: Double, val score: Double)

    /**
     * Up to [limit] items of [pool] that bear on [item], best first, each with at least [minRelevance] of relevance. Pure: the same inputs give the same list.
     * Nothing qualifying gives an empty list, which the step is told plainly ("no evidence found").
     */
    fun related(item: ItemRecord, pool: List<ItemRecord>, df: Map<String, Int>, now: Long, limit: Int, ignore: Set<String> = emptySet(), minRelevance: Double = 0.0): List<Scored> {
        val mine = words(item.text, ignore)
        val myAbout = item.about.map { it.lowercase() }.toSet()
        if (mine.isEmpty() && myAbout.isEmpty()) return emptyList()
        return pool.asSequence().filter { it.uid != item.uid }.mapNotNull { candidate ->
            val shared = mine.intersect(words(candidate.text, ignore))
            val sharedAbout = myAbout.intersect(candidate.about.map { it.lowercase() }.toSet())
            if (shared.isEmpty() && sharedAbout.isEmpty()) return@mapNotNull null
            val relevance = 3.0 * shared.sumOf { 1.0 / ln(2.0 + (df[it] ?: 1)) } + 1.5 * sharedAbout.size
            if (relevance < minRelevance) return@mapNotNull null
            val ageDays = ((now - candidate.evidenceAt).coerceAtLeast(0)) / 86_400_000.0
            Scored(candidate, relevance, relevance + 0.5 * exp(-ageDays / 180.0))
        }.sortedWith(compareByDescending<Scored> { it.score }.thenByDescending { it.item.evidenceAt }.thenBy { it.item.uid }).take(limit).toList()
    }
}
