package app.alfrd.engram.cognitive.pipeline.memory

import app.alfrd.engram.cognitive.pipeline.horizon.ArcadeHorizonGraphStore
import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaAudit
import app.alfrd.engram.db.SchemaBootstrap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID

/**
 * The three paths that create a plain Phrase (conversational/onboarding ingest, environment signal, actor event) stamp `ownerEmail` with the
 * owning user's email, like every item store does. Ownership is still decided by the User -TRUSTS-> Source -ASSERTS-> item chain; the stamp is
 * additive, so the audit's A3 (no owner) and A4 (owner disagrees with the chain) stay clean for new rows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngestOwnerStampTest {

    private lateinit var manager: DatabaseManager
    private lateinit var client: DatabaseEngramClient
    private lateinit var graph: ArcadeHorizonGraphStore
    private val db get() = manager.getDatabase()
    private val me = "stamp-${UUID.randomUUID()}@example.test"
    private val other = "stamp-other-${UUID.randomUUID()}@example.test"

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager("./data/test-ingest-stamp-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(db)
        client = DatabaseEngramClient(db)
        graph = ArcadeHorizonGraphStore(db)
        db.transaction {
            for (e in listOf(me, other)) db.newVertex("User").set("uid", UUID.randomUUID().toString()).set("username", "u").set("email", e).set("tier", -1).set("createdAt", 1L).save()
        }
    }

    @AfterAll
    fun tearDown() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-ingest-stamp-") }?.forEach { it.deleteRecursively() }
    }

    private fun ownerOf(uid: String): String? =
        db.query("sql", "SELECT ownerEmail FROM Phrase WHERE uid = :u", mapOf("u" to uid)).use { rs -> if (rs.hasNext()) rs.next().getProperty<String?>("ownerEmail") else "<missing>" }

    @Test
    fun `conversational ingest stamps ownerEmail`() = runBlocking<Unit> {
        val uid = client.ingest(listOf(PhraseCandidate("I keep bees behind the garage", "conversation", PhraseCategory.CONTEXT)), me).single()
        assertEquals(me, ownerOf(uid))
        // read behaviour is unchanged: the user still reads it back through the chain
        assertTrue(client.queryPhrases(me, null, 50).any { it.text.contains("bees") })
        assertTrue(client.queryPhrases(other, null, 50).none { it.text.contains("bees") })
    }

    @Test
    fun `environment signal stamps ownerEmail`() = runBlocking<Unit> {
        val uid = graph.ingestEnvironmentSignal(me, 1, "weather", "Rain expected on Friday")!!
        assertEquals(me, ownerOf(uid))
    }

    @Test
    fun `actor event stamps ownerEmail`() = runBlocking<Unit> {
        val uid = graph.ingestActorEvent(me, 3, "hermes", "hermes_actor", "Booked the table", "evt-${UUID.randomUUID()}", "h1", null, null, "{}")!!
        assertEquals(me, ownerOf(uid))
    }

    @Test
    fun `audit A3 and A4 stay clean for stamped rows`() = runBlocking<Unit> {
        client.ingest(listOf(PhraseCandidate("Audit fact", "conversation", PhraseCategory.CONTEXT)), other)
        graph.ingestEnvironmentSignal(other, 1, "weather", "Audit signal")
        graph.ingestActorEvent(other, 2, "hermes", "hermes_actor", "Audit event", "evt-${UUID.randomUUID()}", "h2", null, null, "{}")
        val report = SchemaAudit(db, 5).run()
        // Other tests' rows are all stamped too (nothing in this database was ever written without a stamp), so the whole graph is clean here.
        assertEquals(0, report.finding("A3").count, report.finding("A3").examples.toString())
        assertEquals(0, report.finding("A4").count, report.finding("A4").examples.toString())
    }
}
