package app.alfrd.engram.db

/**
 * Names and inputs for email in the graph (design: "Email in the graph", first slice).
 *
 * ```
 * User -TRUSTS-> Email (a Source subtype) -CONTAINS-> Chunk <-QUOTES- item
 *                  |-SENT_BY-> Email Address          (one)
 *                  `-SENT_TO(kind = to|cc|bcc)-> Email Address (any number)
 * ```
 * **The old type string stays.** An Email row still has `type = "email_message"` beside its vertex type. The vertex type says what the row *is*
 * (so the scans that go by exact vertex type have to list it, see SchemaAudit, FixturePurge and HorizonSelector); the string is the source kind that
 * is half of a Source's identity (owner, type, locator) everywhere else (`SourceStore.findSource`, `currentByLocator`, `markCurrent`, `versionsOf`,
 * `DebugSourceView`, the Actor-source filter in HorizonAssembler) and the only thing that marks the rows written before the subtype existed.
 * Dropping it would mean rewriting those rows and every reader at once.
 *
 * Everything here is a fact the message states about itself. Nothing says whose an address is; that is a judged edge, added later, with a
 * confidence and a source.
 */
object EmailGraph {
    const val EMAIL = "Email"
    const val ADDRESS = "EmailAddress"
    const val SENT_BY = "SENT_BY"
    const val SENT_TO = "SENT_TO"

    /** The most recipients one message is stored with, so a mailing-list blast cannot write thousands of edges in one transaction. */
    const val MAX_RECIPIENTS = 500

    val KINDS = listOf("to", "cc", "bcc")

    /** A name and an address as a header printed them. */
    data class Party(val address: String, val name: String? = null)

    /** What the message says about itself, apart from its text. [from] is the sender; [to], [cc] and [bcc] are the recipients. */
    data class Facts(
        val subject: String? = null,
        val sentAt: Long? = null,
        val gmailMessageId: String? = null,
        val gmailThreadId: String? = null,
        val from: Party? = null,
        val to: List<Party> = emptyList(),
        val cc: List<Party> = emptyList(),
        val bcc: List<Party> = emptyList(),
    ) {
        fun recipients(): List<Pair<String, Party>> = to.map { "to" to it } + cc.map { "cc" to it } + bcc.map { "bcc" to it }
    }

    /** The address in the form it is stored and found by: trimmed and lower-cased, or null when it is not `local@domain`. */
    fun normalize(raw: String?): String? {
        val a = raw?.trim()?.trim('<', '>')?.trim()?.lowercase() ?: return null
        if (a.isEmpty() || a.length > 320 || a.any { it.isWhitespace() || it == '<' || it == '>' || it == ',' || it == ';' }) return null
        val at = a.indexOf('@')
        if (at <= 0 || at != a.lastIndexOf('@') || at == a.length - 1) return null
        return a
    }

    /** What follows the @ of a normalized address. */
    fun domainOf(address: String): String = address.substringAfter('@')
}
