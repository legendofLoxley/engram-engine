package app.alfrd.engram.db

/**
 * What a name says about itself, by rule, before any model's label is trusted. Nothing here is a guess: each rule is a pattern in the name
 * as printed, reviewed and tested ([app.alfrd.engram.db.EntityRulesTest]).
 *
 * - **Kind.** A name ending in a school designator (MS, HS, Middle School, Elementary, K-8) is a school; a bare grade band (K–5, 6–8) is a grade
 *   band, not a school; SCENARIO A and STATUS QUO are scenarios. A grade band after a name ("Beverly Cleary 6–8") makes it a school's grade
 *   program: still a school, but a different thing from the school without it.
 * - **Loose name.** The name with its designators taken off, used only to *link* things that may be the same ("Clark" and "Clark Elementary").
 *   It is never the identity of anything: "Beverly Cleary" and "Beverly Cleary 6–8" stay two Concepts.
 * - **Defined aliases.** A page that defines a short form, "Portland Public Schools (PPS)", is evidence that the two are one name; accepted only
 *   when the short form's letters are the initials of the long form, so a parenthesis that is something else is passed over.
 */
object EntityRules {

    private const val BAND = "(?:K|[0-9]{1,2})\\s*[–-]\\s*(?:K|[0-9]{1,2})"
    private val BAND_ONLY = Regex("^$BAND(?:\\s+(?:and|&|or)\\s+.*|\\s+(?:students?|grades?|schools?|programs?))?$", RegexOption.IGNORE_CASE)
    private val BAND_AFTER_NAME = Regex("\\s+$BAND$", RegexOption.IGNORE_CASE)
    private val SCHOOL_DESIGNATOR = Regex("\\s+(?:MS|HS|Middle School|High School|Elementary|Elementary School|K[–-]8|K[–-]8 School)$", RegexOption.IGNORE_CASE)
    private val SCENARIO = Regex("^(?:SCENARIO\\s+[A-Z]|STATUS QUO|OPTION\\s+[A-Z0-9])$", RegexOption.IGNORE_CASE)

    /** The kind a name's own shape gives, or null when it says nothing. */
    fun kindByName(name: String): String? {
        val n = name.trim().replace(Regex("\\s+"), " ")
        return when {
            n.isEmpty() -> null
            SCENARIO.matches(n) -> "scenario"
            BAND_ONLY.matches(n) -> "grade band"
            SCHOOL_DESIGNATOR.containsMatchIn(n) && SCHOOL_DESIGNATOR.replace(n, "").isNotBlank() -> "school"
            BAND_AFTER_NAME.containsMatchIn(n) && BAND_AFTER_NAME.replace(n, "").isNotBlank() -> "school"
            else -> null
        }
    }

    /** The kind to file [name] under: the rule's when it has one, else the model's label mapped onto the fixed kinds. */
    fun kindFor(name: String, modelKind: String?): String = kindByName(name) ?: ConceptStore.kindOf(modelKind)

    /** [name] without its designators (a school's MS/HS/Elementary, a grade band), for linking only; compared without case. */
    fun looseName(name: String): String {
        var n = name.trim().replace(Regex("\\s+"), " ")
        var changed = true
        while (changed) {
            changed = false
            for (r in listOf(SCHOOL_DESIGNATOR, BAND_AFTER_NAME)) {
                val next = r.replace(n, "")
                if (next != n && next.isNotBlank()) { n = next; changed = true }
            }
        }
        return ConceptStore.normalizedName(n)
    }

    /** A person's or place's own name never takes the issuer as parent; these kinds do (a school belongs to the body that issued the document). */
    val KINDS_WITH_ISSUER_PARENT = setOf("school", "program", "policy")

    private val DEFINED = Regex("((?:[\\p{L}][\\p{L}'’.-]*\\s+){1,8})\\(([A-Z][A-Za-z0-9&.-]{1,9})\\)")
    private val SMALL_WORDS = setOf("of", "the", "for", "and", "&", "a", "an", "in", "on")

    /** (long name, short form) pairs a text defines as "Long Name (SHORT)", where SHORT's letters are the initials of the words of Long Name. */
    fun definedAliases(text: String): List<Pair<String, String>> = DEFINED.findAll(text).mapNotNull { m ->
        val words = m.groupValues[1].trim().split(Regex("\\s+"))
        val short = m.groupValues[2]
        val letters = short.filter { it.isLetter() }.uppercase()
        if (letters.length < 2) return@mapNotNull null
        // The shortest run of words ending at the parenthesis whose words start with exactly these letters (small words like "of" may or may not be counted: PPS, BOE).
        for (take in 1..words.size) {
            val run = words.takeLast(take)
            if (run.first().lowercase() in SMALL_WORDS || !run.first()[0].isUpperCase()) continue
            val withSmall = run.map { it[0].uppercaseChar() }.joinToString("")
            val withoutSmall = run.filter { it.lowercase() !in SMALL_WORDS }.map { it[0].uppercaseChar() }.joinToString("")
            // A possessive before the parenthesis ("Portland Public Schools' (PPS)") is not part of the name.
            if (letters == withoutSmall || letters == withSmall) return@mapNotNull run.joinToString(" ").trimEnd('\'', '’') to short
        }
        null
    }.distinct().toList()
}
