package app.alfrd.engram.notion

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

class NotionIdentityConfigTest {

    private val containers = "11111111-1111-4111-8111-111111111111"
    private val items = "22222222-2222-4222-8222-222222222222"
    private val entities = "33333333-3333-4333-8333-333333333333"
    private val connections = "44444444-4444-4444-8444-444444444444"
    private val sessions = "55555555-5555-4555-8555-555555555555"
    private val persona = "66666666-6666-4666-8666-666666666666"

    private fun json(
        identityId: String = "jacob",
        tokenRef: String = "env:ALFRD_NOTION_TOKEN_JACOB",
        extraTop: String = "",
        sources: String = """
            "containers": "$containers", "items": "$items", "entities": "$entities",
            "connections": "$connections", "sessions": "$sessions", "persona": "$persona"
        """.trimIndent(),
        timezone: String = "\"timezone\": \"America/New_York\",",
    ) = """
        {
          "identityId": "$identityId",
          "storage": "notion",
          "tokenRef": "$tokenRef",
          $timezone
          $extraTop
          "dataSources": { $sources }
        }
    """.trimIndent()

    private fun rejected(text: String): NotionConfigException = assertThrows(NotionConfigException::class.java) { NotionIdentityConfig.parse(text) }

    // ── Ids ──────────────────────────────────────────────────────────────────

    @Test
    fun `an id is accepted bare, dashed, uppercase, as a collection reference, or inside a URL, and always comes out dashed lowercase`() {
        val want = "3f1d0721-d4e4-8125-9f60-d350bdbc0475"
        assertEquals(want, normalizeNotionId("3f1d0721d4e481259f60d350bdbc0475"))
        assertEquals(want, normalizeNotionId("3F1D0721-D4E4-8125-9F60-D350BDBC0475"))
        assertEquals(want, normalizeNotionId("collection://3f1d0721-d4e4-8125-9f60-d350bdbc0475"))
        assertEquals(want, normalizeNotionId("https://app.notion.com/p/3f1d0721d4e481259f60d350bdbc0475?pvs=204"))
        assertEquals(want, normalizeNotionId("  3f1d0721d4e481259f60d350bdbc0475  "))
    }

    @Test
    fun `something that is not an id is null, not a guess`() {
        assertNull(normalizeNotionId(null))
        assertNull(normalizeNotionId(""))
        assertNull(normalizeNotionId("not-an-id"))
        assertNull(normalizeNotionId("3f1d0721d4e481259f60d350bdbc047")) // 31 digits
        assertNull(normalizeNotionId("zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"))
    }

    // ── Parsing ──────────────────────────────────────────────────────────────

    @Test
    fun `a complete configuration parses, with ids normalized and the zone read`() {
        val config = NotionIdentityConfig.parse(json())
        assertEquals("jacob", config.identityId)
        assertEquals("env:ALFRD_NOTION_TOKEN_JACOB", config.tokenRef)
        assertEquals(ZoneId.of("America/New_York"), config.timezone)
        assertEquals(containers, config.dataSources.containers)
        assertEquals(persona, config.dataSources.personaPageId)
        assertEquals(setOf(containers, items, entities, connections, sessions), config.dataSources.writableDataSourceIds)
    }

    @Test
    fun `the persona page is never among the tables that may be written`() {
        val sources = NotionIdentityConfig.parse(json()).dataSources
        assertFalse(persona in sources.writableDataSourceIds)
    }

    @Test
    fun `ids may be given as URLs and collection references`() {
        val config = NotionIdentityConfig.parse(
            json(sources = """
                "containers": "collection://$containers", "items": "https://app.notion.com/p/${items.replace("-", "")}?pvs=204",
                "entities": "$entities", "connections": "$connections", "sessions": "$sessions", "persona": "$persona"
            """.trimIndent()),
        )
        assertEquals(containers, config.dataSources.containers)
        assertEquals(items, config.dataSources.items)
    }

    @Test
    fun `the time zone defaults to UTC`() {
        assertEquals(ZoneId.of("UTC"), NotionIdentityConfig.parse(json(timezone = "")).timezone)
    }

    @Test
    fun `an unknown key is rejected so a misspelling cannot silently leave a table unconfigured`() {
        val e = rejected(json(extraTop = "\"tokenref\": \"env:X\","))
        assertTrue(e.message!!.contains("tokenref"))
        val sources = rejected(json(sources = """
            "containers": "$containers", "items": "$items", "entitys": "$entities",
            "connections": "$connections", "sessions": "$sessions", "persona": "$persona"
        """.trimIndent()))
        assertTrue(sources.message!!.contains("entitys"))
    }

    @Test
    fun `a missing table is rejected and named`() {
        val e = rejected(json(sources = """
            "containers": "$containers", "items": "$items", "entities": "$entities", "connections": "$connections", "persona": "$persona"
        """.trimIndent()))
        assertTrue(e.message!!.contains("sessions"))
    }

    @Test
    fun `two roles pointing at the same Notion id are rejected`() {
        val e = rejected(json(sources = """
            "containers": "$containers", "items": "$containers", "entities": "$entities",
            "connections": "$connections", "sessions": "$sessions", "persona": "$persona"
        """.trimIndent()))
        assertTrue(e.message!!.contains("containers") && e.message!!.contains("items"))
    }

    @Test
    fun `an id that is not a Notion id is rejected and the field is named`() {
        val e = rejected(json(sources = """
            "containers": "oops", "items": "$items", "entities": "$entities",
            "connections": "$connections", "sessions": "$sessions", "persona": "$persona"
        """.trimIndent()))
        assertTrue(e.message!!.contains("dataSources.containers"))
    }

    @Test
    fun `a bad identity id, zone, storage value or non-object is rejected`() {
        rejected(json(identityId = "has spaces"))
        rejected(json(timezone = "\"timezone\": \"Mars/Olympus\","))
        rejected(json().replace("\"storage\": \"notion\"", "\"storage\": \"arcadedb\""))
        rejected("[1,2,3]")
        rejected("not json")
    }

    // ── Token references ─────────────────────────────────────────────────────

    @Test
    fun `a token pasted where a reference belongs is rejected, and the error does not repeat it`() {
        val pasted = "ntn_1234567890abcdefSECRETSECRETSECRET"
        val e = rejected(json(tokenRef = pasted))
        assertFalse(e.message!!.contains(pasted), "the rejection repeated what was pasted")
        assertTrue(e.message!!.contains("env:"))
    }

    @Test
    fun `a reference with nothing after the prefix is rejected`() {
        rejected(json(tokenRef = "env:"))
        rejected(json(tokenRef = "file:"))
    }

    @Test
    fun `an env reference resolves to the token and a missing one fails without printing a value`() {
        val resolver = EnvAndFileSecretResolver(env = mapOf("ALFRD_NOTION_TOKEN_JACOB" to "  ntn_abc  \n"))
        assertEquals("ntn_abc", resolver.resolve("env:ALFRD_NOTION_TOKEN_JACOB").reveal())
        val e = assertThrows(NotionConfigException::class.java) { resolver.resolve("env:NOT_SET") }
        assertTrue(e.message!!.contains("env:NOT_SET"))
    }

    @Test
    fun `a file reference reads the file and drops the trailing newline, and an unreadable one fails`() {
        val resolver = EnvAndFileSecretResolver(env = emptyMap(), readFile = { path -> if (path == "/run/secrets/notion") "ntn_from_file\n" else null })
        assertEquals("ntn_from_file", resolver.resolve("file:/run/secrets/notion").reveal())
        assertThrows(NotionConfigException::class.java) { resolver.resolve("file:/nope") }
    }

    @Test
    fun `a blank value, or any other prefix, does not resolve`() {
        val resolver = EnvAndFileSecretResolver(env = mapOf("EMPTY" to "   "))
        assertThrows(NotionConfigException::class.java) { resolver.resolve("env:EMPTY") }
        assertThrows(NotionConfigException::class.java) { resolver.resolve("literal:ntn_x") }
    }

    @Test
    fun `a token never prints its value`() {
        val token = NotionToken("ntn_supersecretvalue")
        assertFalse(token.toString().contains("supersecret"))
        assertEquals("NotionToken(***)", "$token")
        assertThrows(IllegalArgumentException::class.java) { NotionToken("   ") }
    }

    @Test
    fun `a configuration prints a reference to the token, never a token`() {
        val config = NotionIdentityConfig.parse(json())
        assertTrue(config.toString().contains("env:ALFRD_NOTION_TOKEN_JACOB"))
    }
}
