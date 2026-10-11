package app.alfrd.engram.notion

import java.security.MessageDigest

/**
 * Every Notion table set a process knows about, checked against each other: two sets must never
 * share a token or a table. A token that reaches one person's memory must not also reach the agent
 * worker's tables, and the other way round, so the registry refuses to be built from sets that
 * would let it.
 *
 * - set names are unique;
 * - no two sets use the same token *reference*;
 * - no table or readable page appears in two sets.
 *
 * Two different references can still resolve to the same secret (`env:A` and `env:B` holding one
 * token); [verifyDistinctTokens] checks that once secrets are available. Messages name sets, never
 * a reference's contents or a token.
 */
class NotionSetRegistry(bindings: List<NotionSetBinding>) {

    private val byName: Map<String, NotionSetBinding>

    init {
        val names = HashSet<String>()
        val tokenOwner = HashMap<String, String>()
        val idOwner = HashMap<String, String>()
        for (binding in bindings) {
            val set = binding.tableSet.name
            if (!names.add(set)) throw NotionConfigException("two table sets are both named '$set'")
            tokenOwner.put(binding.tokenRef, set)?.let { other ->
                throw NotionConfigException("table sets '$other' and '$set' share a token reference; each set needs its own token")
            }
            val ids = binding.tableSet.tables.map { it.dataSourceId } + binding.tableSet.readablePages.map { it.pageId }
            for (id in ids) {
                idOwner.put(id, set)?.let { other ->
                    throw NotionConfigException("table sets '$other' and '$set' both include $id; a table or page belongs to one set")
                }
            }
        }
        byName = bindings.associateBy { it.tableSet.name }
    }

    val sets: Collection<NotionSetBinding> get() = byName.values

    operator fun get(setName: String): NotionSetBinding? = byName[setName]

    /**
     * Resolves every set's token and fails if two sets resolve to the same one. The tokens are
     * compared by digest and not retained.
     */
    fun verifyDistinctTokens(resolver: SecretResolver) {
        val owner = HashMap<String, String>()
        for (binding in byName.values) {
            val digest = sha256(resolver.resolve(binding.tokenRef).reveal())
            owner.put(digest, binding.tableSet.name)?.let { other ->
                throw NotionConfigException("table sets '$other' and '${binding.tableSet.name}' resolve to the same token")
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
