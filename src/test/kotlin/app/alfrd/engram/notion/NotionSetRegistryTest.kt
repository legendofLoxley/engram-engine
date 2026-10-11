package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The memory sets and the agent-worker set must never share a token or a table. */
class NotionSetRegistryTest {

    private fun id(n: Int) = "%08x-abcd-4abc-8abc-%012x".format(n, n)

    private fun person(identity: String, base: Int, tokenRef: String = "env:ALFRD_NOTION_TOKEN_${identity.uppercase()}") =
        NotionIdentityConfig(identity, tokenRef, NotionDataSources(id(base), id(base + 1), id(base + 2), id(base + 3), id(base + 4), id(base + 5)))

    private fun worker(base: Int = 100, tokenRef: String = "env:ALFRD_WORKER_NOTION_TOKEN", name: String = "agent-worker") =
        NotionTableSetConfig(
            tokenRef,
            NotionTableSet(name, listOf(NotionTable("cycles", id(base)), NotionTable("claims", id(base + 1), TableAccess.READ_ONLY)), listOf(NotionReadablePage("home", id(base + 2)))),
        )

    private fun rejected(vararg bindings: NotionSetBinding): NotionConfigException =
        assertThrows(NotionConfigException::class.java) { NotionSetRegistry(bindings.toList()) }

    @Test
    fun `memory sets and the worker set with their own tokens and tables coexist`() {
        val registry = NotionSetRegistry(listOf(person("jacob", 1), person("lee", 20), worker()))
        assertEquals(3, registry.sets.size)
        assertNotNull(registry["agent-worker"])
        assertEquals("env:ALFRD_NOTION_TOKEN_JACOB", registry["alfrd-memory.jacob"]!!.tokenRef)
        assertNull(registry["alfrd-memory.nobody"])
    }

    @Test
    fun `the worker set and a memory set may not share a token reference`() {
        val e = rejected(person("jacob", 1, tokenRef = "env:SHARED"), worker(tokenRef = "env:SHARED"))
        assertTrue(e.message!!.contains("alfrd-memory.jacob") && e.message!!.contains("agent-worker"), e.message)
        assertTrue(e.message!!.contains("share a token"), e.message)
    }

    @Test
    fun `two people may not share a token reference either`() {
        rejected(person("jacob", 1, tokenRef = "env:SHARED"), person("lee", 20, tokenRef = "env:SHARED"))
    }

    @Test
    fun `a refusal about a shared token reference does not echo the reference`() {
        val e = rejected(person("jacob", 1, tokenRef = "file:/run/secrets/very-specific-name"), worker(tokenRef = "file:/run/secrets/very-specific-name"))
        assertFalse(e.message!!.contains("very-specific-name"))
    }

    @Test
    fun `no table may belong to two sets`() {
        val overlapping = worker(base = 3) // its tables are ids 3 and 4, which are in jacob's set
        val e = rejected(person("jacob", 1), overlapping)
        assertTrue(e.message!!.contains("belongs to one set"), e.message)
    }

    @Test
    fun `no readable page may belong to two sets`() {
        val sharesPersona = NotionTableSetConfig("env:OTHER", NotionTableSet("other", listOf(NotionTable("t", id(50))), listOf(NotionReadablePage("p", id(6)))))
        rejected(person("jacob", 1), sharesPersona)
    }

    @Test
    fun `a set name can be used once`() {
        rejected(worker(base = 100, tokenRef = "env:A"), worker(base = 200, tokenRef = "env:B"))
    }

    @Test
    fun `an empty registry is valid`() {
        assertEquals(0, NotionSetRegistry(emptyList()).sets.size)
    }

    // ── Different references, same secret ───────────────────────────────────

    @Test
    fun `two references that resolve to the same token are caught once secrets are available`() {
        val registry = NotionSetRegistry(listOf(person("jacob", 1, tokenRef = "env:A"), worker(tokenRef = "env:B")))
        val sameSecret = EnvAndFileSecretResolver(env = mapOf("A" to "ntn_same_secret_value", "B" to "ntn_same_secret_value"))
        val e = assertThrows(NotionConfigException::class.java) { registry.verifyDistinctTokens(sameSecret) }
        assertTrue(e.message!!.contains("resolve to the same token"))
        assertFalse(e.message!!.contains("same_secret"), "the refusal echoed the token")
    }

    @Test
    fun `distinct secrets pass`() {
        val registry = NotionSetRegistry(listOf(person("jacob", 1, tokenRef = "env:A"), worker(tokenRef = "env:B")))
        registry.verifyDistinctTokens(EnvAndFileSecretResolver(env = mapOf("A" to "ntn_one", "B" to "ntn_two")))
    }

    @Test
    fun `a reference that does not resolve is reported as a configuration problem`() {
        val registry = NotionSetRegistry(listOf(worker(tokenRef = "env:MISSING")))
        assertThrows(NotionConfigException::class.java) { registry.verifyDistinctTokens(EnvAndFileSecretResolver(env = emptyMap())) }
    }
}
