package app.alfrd.engram.cognitive.pipeline

/**
 * Whether a message mentions a date or time — a cheap, deterministic check ("the 22nd", "March 3",
 * "Friday", "tomorrow", "next week", "in 3 days", "9/22"). It exists for one reason: the interpretation
 * model sometimes returns nothing for a plain statement of a dated thing ("My sister's birthday is on the
 * 22nd."), and a dated thing it skipped is the one kind of miss that silently costs the user a reminder.
 * A message with a date and nothing recorded is worth one more, narrower question to the model (see
 * [Interpreter.interpretDated]). False positives only cost that extra call; a false negative just means
 * no second look, exactly as before.
 */
internal object DateHints {
    private const val MONTH = "(?:jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"
    private const val WEEKDAY = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)"

    private val PATTERNS = listOf(
        Regex("\\b\\d{1,2}(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE), // the 22nd
        Regex("\\b$MONTH\\.?\\s+\\d{1,2}(?:st|nd|rd|th)?\\b", RegexOption.IGNORE_CASE), // March 3, oct 3rd
        Regex("\\b\\d{1,2}(?:st|nd|rd|th)?\\s+(?:of\\s+)?$MONTH\\b", RegexOption.IGNORE_CASE), // 3rd of October, 3 Oct
        Regex("\\b$WEEKDAY\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:tomorrow|tonight|next\\s+(?:week|month|year|weekend)|this\\s+(?:weekend|evening)|in\\s+\\d+\\s+(?:days?|weeks?|months?))\\b", RegexOption.IGNORE_CASE),
        Regex("\\b\\d{1,2}/\\d{1,2}(?:/\\d{2,4})?\\b"),
    )

    fun hasDate(text: String): Boolean = PATTERNS.any { it.containsMatchIn(text) }
}
