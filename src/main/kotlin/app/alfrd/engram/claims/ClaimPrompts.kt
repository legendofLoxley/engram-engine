package app.alfrd.engram.claims

import java.security.MessageDigest

/**
 * The two system prompts of the claim passes, and the name that identifies their exact wording in a run's record.
 *
 * The defaults live here, in code, reviewed and tested like the Director's prompts; [DEFAULT]'s version is the name a run records. A box can
 * try other wording without a rebuild by sending its own pair ([custom]); such a run records `custom-<first 16 hex of the SHA-256 of both prompts>`
 * as its prompt version, so the record still says which exact text produced it. A custom pair replaces only the wording: the tool, the
 * agreement rules, the verbatim-quote check and the model check are code and do not change. Prompts are not secrets, but a run's record never
 * carries their text, only the version.
 */
class PromptSet private constructor(val version: String, val pass1: String, val pass2: String) {

    val sha256: String get() = digest(pass1, pass2)

    companion object {
        const val DEFAULT_VERSION = "claims-v4"
        const val MAX_CHARS = 20_000

        private fun digest(a: String, b: String) =
            MessageDigest.getInstance("SHA-256").digest("$a\u0000$b".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        private val KINDS = ClaimKind.entries.joinToString(", ") { it.name.lowercase() }

        private val RULES = """
            A claim is one checkable assertion that the passage explicitly makes: a fact, a number with its units or date, a decision or a vote, a commitment or a deadline, a stated requirement, or an attributed position.
            Not claims: headings, titles, captions, labels, boilerplate, navigation text, rhetoric, questions, anything you would have to infer, and page furniture such as headers, footers, page numbers and "Updated <date>" stamps. If the passage does not state it, it is not a claim.

            For every claim give:
            - quote: the exact words of the passage that make the assertion, copied character for character from the passage, in one unbroken stretch. Never paraphrase, never join separate sentences, never correct typos.
              * Quote the whole sentence. Do not cut one sentence into fragments. Only if one sentence asserts independent things about different subjects, give each whole clause as its own claim.
              * Copy the words in the order they appear in the passage. Never reorder them and never add a word that is not there (do not write "13 schools close" unless those exact words stand together in the passage). If you cannot copy the assertion exactly, leave it out.
              * A list under a heading (schools, program moves, grade-level changes) is many assertions: give one claim per list item, and quote exactly that item as printed. Never give a whole list as one quote.
              * A number is only a claim together with what it counts. The quote must include the words that say what the number is about (for example "38 attendance boundary changes", never "38" alone). If the words and the number are not next to each other, quote from the first of them through the last, in one unbroken stretch, in the order they are printed (a card prints its title, then its number: quote the title through the number).
            - claimant: the person or organization the passage says made the assertion, as the passage names them. If the passage names nobody, leave it empty. Do not guess.
            - subject: always give one: a few words saying what the claim is about. A card, box, list or program name (for example PROGRAM MOVES) belongs here.
            - kind: one of $KINDS.
            - place: where in the passage the claim belongs, when that matters.
              * If the passage has scenario, option or party headings (for example SCENARIO A, SCENARIO B, STATUS QUO), the place is always that heading, exactly as it is written, never the name of a card, box, list or program inside it. When headings are nested, use the outermost one that names a scenario, option or party. A number or a list belongs to the heading it sits under, never to one above or beside it.
              * If the passage has no such headings but the claim sits under another heading, card or box title, give that title exactly as it is written.
              * Tables: a table is printed as lines of cells separated by " | ". For each value in a row, give one claim. The quote is the whole row, copied exactly as printed from its first cell to its last, with the " | " separators; never a single cell taken out of the middle of a row. The place is that value's column header, exactly as printed (for example SCENARIO A). The row label and the value are what the claim says.
              * Otherwise leave it empty.
            If the passage has no claims, report an empty list.
        """.trimIndent()

        private val SYSTEM_ONE = "You extract claims from a passage of a document.\n\n$RULES\n\nReport the claims in the order they appear in the passage."

        private val SYSTEM_TWO = """
            Your job is to read one passage of a document and list the assertions it explicitly makes.
            Work through the passage sentence by sentence, and for each sentence (or table row, or list item) decide whether it makes a checkable assertion.

            $RULES
        """.trimIndent()

        val DEFAULT: PromptSet = PromptSet(DEFAULT_VERSION, SYSTEM_ONE, SYSTEM_TWO)

        /** Wording supplied by the caller. Both prompts are required, non-blank and at most [MAX_CHARS] characters. */
        fun custom(pass1: String, pass2: String): PromptSet {
            require(pass1.isNotBlank() && pass2.isNotBlank()) { "both prompts are required" }
            require(pass1.length <= MAX_CHARS && pass2.length <= MAX_CHARS) { "a prompt is longer than $MAX_CHARS characters" }
            return PromptSet("custom-" + digest(pass1, pass2).take(16), pass1, pass2)
        }
    }
}
