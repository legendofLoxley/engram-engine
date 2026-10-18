package app.alfrd.engram.cognitive.pipeline.horizon

/** How an item's words are compared for identity: case, spacing and surrounding blanks do not make a different item. */
internal object ItemWords {
    private val SPACES = Regex("\\s+")
    fun normalize(text: String): String = text.lowercase().replace(SPACES, " ").trim()
}
