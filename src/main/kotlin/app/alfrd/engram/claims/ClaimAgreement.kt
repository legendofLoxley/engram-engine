package app.alfrd.engram.claims

import app.alfrd.engram.db.ChunkRecord

/**
 * Compares what two independent passes reported for one chunk. Pure: no model, no graph.
 *
 * **A place that is a heading is checked.** When a claim's place is a line of the chunk (a card or scenario title) the passage must lie
 * under that heading, up to the next heading some claim names; a count that sits under SCENARIO A cannot be filed under SCENARIO B.
 * A place that is not a line of the chunk (a table column) cannot be checked this way and is only compared between the passes.
 *
 * **A quote must be verbatim.** A claim whose quote is not in its chunk is rejected and kept as a disagreement; a quote that differs
 * from the chunk only in the spacing or line breaks inside it is replaced by the chunk's own text for that passage, so what is stored
 * is always a passage of the chunk exactly as read.
 *
 * **Agreement is on three things:** the quote, the canonical claimant, and the place (the chunk is the location; when both passes
 * also name a place in it, those must match too). The subject, the kind and the wording of the claim are not compared: the
 * established claim keeps pass 1's. Only claims both passes reported are established; everything else is retained, whole, as a
 * disagreement. Nothing here is a score.
 */
object ClaimAgreement {
    const val REJECTED = "quote is not in the chunk"
    const val PLACE_TOO_LONG = "place is too long to be a place"
    /** A place is a heading, a column or a box title: a few words. Longer is the model copying text into the field. */
    const val MAX_PLACE_CHARS = 120
    const val OUT_OF_SECTION = "quote is outside the section its place names"
    const val SPLIT_CLAIMANT = "both passes name more than one claimant for this passage"


    /** The collapsed-spacing form of a quote, for comparing two quotes. Case is kept: "No" and "NO" are different words. */
    fun quoteKey(quote: String): String = quote.trim().replace(Regex("\\s+"), " ")

    /**
     * The passage of [chunkText] that [quote] names, exactly as it is written there, or null when the chunk has no such passage.
     * Spacing and line breaks inside the quote may differ from the chunk's; nothing else may.
     */
    fun locate(chunkText: String, quote: String): String? {
        val words = quote.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val pattern = Regex(words.joinToString("\\s+") { Regex.escape(it) })
        return pattern.find(chunkText)?.value
    }

    private data class Resolved(val version: ClaimVersion, val kind: ClaimKind)

    private fun resolve(c: PassClaim, located: String, resolver: ClaimantResolver): Resolved {
        val kind = ClaimKind.parse(c.kind) ?: ClaimKind.FACT
        return Resolved(
            ClaimVersion(
                quote = located, claimant = resolver.canonical(c.claimant), claimantAsWritten = c.claimant?.trim()?.takeIf { it.isNotEmpty() },
                subject = c.subject.trim(), kind = kind.name, place = c.place?.trim()?.takeIf { it.isNotEmpty() },
            ),
            kind,
        )
    }

    private fun matches(a: ClaimVersion, b: ClaimVersion): Boolean =
        quoteKey(a.quote) == quoteKey(b.quote) && ClaimantResolver.key(a.claimant) == ClaimantResolver.key(b.claimant) &&
            (a.place == null || b.place == null || quoteKey(a.place).lowercase() == quoteKey(b.place).lowercase())

    /**
     * Where each place that is also a heading sits in the chunk: the offset of the line that is exactly that place (case and spacing aside).
     * A place that is not a line of the chunk (a column of a table, a box with no title) has no entry and is not checked.
     */
    private fun headings(text: String, places: Collection<String>): List<Pair<String, Int>> {
        val wanted = places.map { quoteKey(it).lowercase() }.toSet()
        val out = LinkedHashMap<String, Int>()
        var offset = 0
        text.split('\n').forEach { line ->
            val k = quoteKey(line).lowercase()
            if (k in wanted && k !in out) out[k] = offset
            offset += line.length + 1
        }
        return out.entries.map { it.key to it.value }.sortedBy { it.second }
    }

    /** True unless [place] is a heading of the chunk and the passage does not lie in that heading's section (up to the next heading some claim names). */
    private fun inSection(text: String, heads: List<Pair<String, Int>>, place: String?, located: String): Boolean {
        val key = place?.let { quoteKey(it).lowercase() } ?: return true
        val i = heads.indexOfFirst { it.first == key }
        if (i < 0) return true
        val from = heads[i].second; val to = heads.getOrNull(i + 1)?.second ?: text.length
        var at = text.indexOf(located)
        while (at >= 0) {
            if (at in from until to) return true
            at = text.indexOf(located, at + 1)
        }
        return false
    }

    /** [rejected] counts the claims whose quote was not in the chunk (they are also in the returned disagreements). */
    data class Compared(val outcome: ChunkOutcome, val rejected: Int, val reported: Int)

    fun compare(chunk: ChunkRecord, passOne: List<PassClaim>, passTwo: List<PassClaim>, resolver: ClaimantResolver): Compared {
        val disagreements = ArrayList<Disagreement>()
        var rejected = 0

        val heads = headings(chunk.text, (passOne + passTwo).mapNotNull { it.place?.takeIf { p -> p.isNotBlank() } })

        fun prepare(claims: List<PassClaim>, first: Boolean): List<Resolved> {
            val seen = HashSet<String>()
            val out = ArrayList<Resolved>()
            claims.forEach { c ->
                val located = locate(chunk.text, c.quote)
                if (located == null) {
                    rejected++
                    val v = resolve(c, c.quote.trim(), resolver).version
                    disagreements += Disagreement(chunk.uid, REJECTED, if (first) v else null, if (first) null else v)
                    return@forEach
                }
                val r = resolve(c, located, resolver)
                if ((r.version.place?.length ?: 0) > MAX_PLACE_CHARS) {
                    rejected++
                    disagreements += Disagreement(chunk.uid, PLACE_TOO_LONG, if (first) r.version.copy(place = r.version.place!!.take(MAX_PLACE_CHARS) + "…") else null, if (first) null else r.version.copy(place = r.version.place!!.take(MAX_PLACE_CHARS) + "…"))
                    return@forEach
                }
                if (!inSection(chunk.text, heads, r.version.place, located)) {
                    rejected++
                    disagreements += Disagreement(chunk.uid, OUT_OF_SECTION, if (first) r.version else null, if (first) null else r.version)
                    return@forEach
                }
                // The same claim reported twice by one pass is one claim.
                if (seen.add("${quoteKey(located)}|${ClaimantResolver.key(r.version.claimant)}|${r.version.place?.lowercase().orEmpty()}")) out += r
            }
            return out
        }

        val a = prepare(passOne, true)
        val b = prepare(passTwo, false)
        val usedB = BooleanArray(b.size)
        val agreed = ArrayList<AgreedClaim>()
        val leftoverA = ArrayList<Resolved>()
        a.forEach { x ->
            val j = b.indices.firstOrNull { !usedB[it] && matches(x.version, b[it].version) }
            if (j == null) leftoverA += x else {
                usedB[j] = true
                val v = x.version
                agreed += AgreedClaim(chunk.uid, v.quote, v.claimant, v.claimantAsWritten, v.subject, x.kind, v.place ?: b[j].version.place)
            }
        }
        val leftoverB = b.filterIndexed { i, _ -> !usedB[i] }.toMutableList()

        // Both passes agree on a passage but name more than one claimant for it: the commit plumbing cannot hold two claimants for one
        // passage in one chunk, and an attribution that wavers is not one to establish. All of them are retained instead.
        agreed.groupBy { quoteKey(it.quote) + "|" + quoteKey(it.place.orEmpty()).lowercase() }.filterValues { g -> g.map { ClaimantResolver.key(it.claimant) }.distinct().size > 1 }.values.forEach { g ->
            agreed.removeAll(g.toSet())
            g.forEach { c ->
                val v = ClaimVersion(c.quote, c.claimant, c.claimantAsWritten, c.subject, c.kind.name, c.place)
                disagreements += Disagreement(chunk.uid, SPLIT_CLAIMANT, v, v)
            }
        }

        // A leftover of one pass and a leftover of the other over the same passage are one disagreement (they differ in claimant or
        // place); anything else is a claim only one pass reported.
        leftoverA.forEach { x ->
            val j = leftoverB.indexOfFirst { quoteKey(it.version.quote) == quoteKey(x.version.quote) }
            if (j >= 0) {
                val y = leftoverB.removeAt(j)
                val what = if (ClaimantResolver.key(x.version.claimant) != ClaimantResolver.key(y.version.claimant)) "claimant" else "place"
                disagreements += Disagreement(chunk.uid, "passes differ on the $what", x.version, y.version)
            } else {
                disagreements += Disagreement(chunk.uid, "only pass 1 reported it", x.version, null)
            }
        }
        leftoverB.forEach { y -> disagreements += Disagreement(chunk.uid, "only pass 2 reported it", null, y.version) }

        // Distinct claims either pass reported with a usable quote: agreed ones, and each disagreement (a differing pair counts once).
        val reported = agreed.size + disagreements.count { it.reason != REJECTED && it.reason != OUT_OF_SECTION && it.reason != PLACE_TOO_LONG }
        return Compared(ChunkOutcome(chunk.uid, agreed, disagreements), rejected, reported)
    }
}
