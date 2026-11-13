package app.alfrd.engram.cognitive.pipeline.horizon

import app.alfrd.engram.db.DatabaseManager
import app.alfrd.engram.db.SchemaAudit
import app.alfrd.engram.db.SchemaBootstrap
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ItemKindsTest {

    private lateinit var manager: DatabaseManager

    @BeforeAll
    fun setUp() {
        manager = DatabaseManager("./data/test-item-kinds-${System.currentTimeMillis()}")
        SchemaBootstrap.bootstrap(manager.getDatabase())
    }

    @AfterAll
    fun tearDown() {
        manager.close()
        File("./data").listFiles()?.filter { it.name.startsWith("test-item-kinds-") }?.forEach { it.deleteRecursively() }
    }

    /**
     * What the hardcoded lists said on master 795edf9, one row per list. A new kind has no row here until someone decides, so adding an
     * [ItemKind] fails [every kind has an explicit answer for every list] instead of being silently left out of a scan.
     */
    private val expected: Map<KindList, Set<ItemKind>> = mapOf(
        KindList.URGENCY_SCAN to setOf(ItemKind.TASK, ItemKind.EVENT, ItemKind.GOAL),                              // HorizonSelector.datedScan: scan("Task"), scan("Event")
        KindList.OPEN_UNFILED_SCAN to setOf(ItemKind.TASK, ItemKind.QUESTION, ItemKind.GOAL),                      // HorizonSelector.openUnfiledItems: listOf("Task", "Question")
        KindList.STATE_SCAN to setOf(ItemKind.TASK, ItemKind.EVENT, ItemKind.QUESTION, ItemKind.GOAL),             // HorizonSelector.stateScan
        KindList.ALSO_DUE_SCAN to setOf(ItemKind.TASK, ItemKind.EVENT, ItemKind.GOAL),                             // HorizonAssembler.alsoDueEntries: listOf("Task", "Event")
        KindList.STATUS_ON_ITEM to setOf(ItemKind.TASK, ItemKind.QUESTION, ItemKind.GOAL),                         // HorizonAssembler: IN ['Task', 'Question'] (Event reads the assertion's copy)
        KindList.OPEN_ONLY_LISTING to setOf(ItemKind.TASK, ItemKind.QUESTION, ItemKind.GOAL),                      // HorizonStructureStore.listRecentItems(openOnly)
        KindList.CARRIES_STATE to setOf(ItemKind.TASK, ItemKind.EVENT, ItemKind.QUESTION, ItemKind.GOAL),          // HorizonStructureStore ITEM_STATE_TYPES
    )

    @Test
    fun `every kind has an explicit answer for every list that used to be hardcoded`() {
        assertEquals(KindList.entries.toSet(), expected.keys, "a list was added to KindList without recording what it contained")
        for (kind in ItemKind.entries) {
            val spec = ItemKinds.of(kind)
            for (list in KindList.entries) {
                assertEquals(kind in expected.getValue(list), spec.isIn(list), "$kind in $list")
            }
        }
        for ((list, kinds) in expected) assertEquals(kinds, ItemKinds.inList(list).map { it.kind }.toSet(), "members of $list")
    }

    @Test
    fun `list order is the order the hardcoded lists used`() {
        assertEquals(listOf("Task", "Event", "Goal"), ItemKinds.typeNames(KindList.URGENCY_SCAN))
        assertEquals(listOf("Task", "Question", "Goal"), ItemKinds.typeNames(KindList.OPEN_UNFILED_SCAN))
        assertEquals(listOf("Task", "Event", "Question", "Goal"), ItemKinds.typeNames(KindList.STATE_SCAN))
        assertEquals("['Task', 'Question', 'Goal']", ItemKinds.sqlTypeList(KindList.STATUS_ON_ITEM))
    }

    @Test
    fun `the five kinds keep their type names, labels and due properties`() {
        assertEquals(
            listOf(Triple("Phrase", "Fact", null), Triple("Task", "Task", "dueAt"), Triple("Event", "Event", "occursAt"), Triple("Question", "Question", null), Triple("Goal", "Goal", "dueAt")),
            ItemKinds.all.map { Triple(it.typeName, it.horizonLabel, it.dueProperty) },
        )
        assertEquals(setOf("Phrase", "Task", "Event", "Question", "Goal"), ItemKinds.phraseTypeNames())
        for (kind in ItemKind.entries) assertEquals(kind, ItemKinds.fromLabel(app.alfrd.engram.notion.NotionSchema.kindLabel(kind)))
        assertEquals(null, ItemKinds.fromLabel("Insight"))
    }

    @Test
    fun `the registry matches the schema`() {
        val schema = manager.getDatabase().schema
        for (spec in ItemKinds.all) {
            assertTrue(schema.existsType(spec.typeName), "${spec.typeName} exists")
            val type = schema.getType(spec.typeName)
            assertTrue(spec.typeName == "Phrase" || type.isSubTypeOf("Phrase"), "${spec.typeName} is a Phrase")
            if (spec.carriesState) for (p in listOf("state", "status", "dueKind", "nudgeAt", "waitingOn", "lastEvidenceAt")) assertTrue(type.existsProperty(p), "${spec.typeName}.$p")
            spec.dueProperty?.let { assertTrue(type.existsPolymorphicProperty(it), "${spec.typeName}.$it") }
        }
        // and the other way: every Phrase subtype in the schema is a registered kind
        val subtypes = schema.types.filter { it.name != "Phrase" && it.isSubTypeOf("Phrase") }.map { it.name }.toSet()
        assertEquals(ItemKinds.phraseTypeNames() - "Phrase", subtypes)
    }

    /** Kind names appear as string literals only where the registry, the schema or an external vocabulary needs them. */
    @Test
    fun `no hardcoded item kind list is left outside the registry`() {
        val allowed = setOf(
            "ItemKinds.kt",                 // the registry
            "SchemaBootstrap.kt",           // the schema itself
            "HorizonStructureStore.kt",     // the ItemKind enum's type names
            "ChangeMarker.kt",              // the marker line: one count per type, deliberately its own fixed list (changing it changes the tick's prompt)
            "AgentWorkSchema.kt",           // the Notion agent-work vocabulary, not item kinds
        )
        val pattern = Regex("""["'](Task|Event|Question)["']""")
        val offenders = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" && it.name !in allowed }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (pattern.containsMatchIn(line) && !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*")) "${f.name}:${i + 1}: ${line.trim().take(100)}" else null } }.toList()
        assertTrue(offenders.isEmpty(), "kind literals outside the registry:\n" + offenders.joinToString("\n"))
    }

    // ── an unknown Phrase subtype is no longer silently a fact ──────────────

    @Test
    fun `an unknown Phrase subtype still reads as a fact, is logged once, and shows up in the audit`() {
        val own = DatabaseManager("./data/test-item-kinds-unknown-${System.nanoTime()}")
        try { unknownSubtype(own) } finally { own.close() }
    }

    private fun unknownSubtype(own: DatabaseManager) {
        SchemaBootstrap.bootstrap(own.getDatabase())
        val db = own.getDatabase()
        val typeName = "Goal${UUID.randomUUID().toString().take(6).replace("-", "")}"
        db.transaction { db.schema.createVertexType(typeName).addSuperType("Phrase") }
        val email = "kinds-${UUID.randomUUID()}@example.test"
        val uid = UUID.randomUUID().toString()
        db.transaction {
            val user = db.newVertex("User").set("uid", UUID.randomUUID().toString()).set("email", email).set("username", "k"); user.save()
            val src = db.newVertex("Source").set("uid", UUID.randomUUID().toString()).set("name", "conversation:$email").set("type", "conversation"); src.save()
            user.newEdge("TRUSTS", src, false).save()
            val goal = db.newVertex(typeName).set("uid", uid).set("text", "ship the thing").set("ownerEmail", email).set("createdAt", 1L); goal.save()
            src.newEdge("ASSERTS", goal, false).save()
        }
        val log = LoggerFactory.getLogger(ItemKinds::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        log.addAppender(appender)
        try {
            val store = ArcadeHorizonStructureStore(db)
            val item = runBlocking { store.getItem(email, uid) }
            assertEquals(ItemKind.FACT, item?.kind)
            runBlocking { store.getItem(email, uid) }
            val warnings = appender.list.filter { it.level == Level.WARN && it.formattedMessage.contains(typeName) }
            assertEquals(1, warnings.size, "logged once per type name, not once per read")
        } finally { log.detachAppender(appender) }
        val finding = SchemaAudit(db).run().finding("A7")
        assertEquals(1L, finding.count)
        assertEquals(listOf(uid), finding.examples)
        assertEquals(mapOf(typeName to 1L), finding.breakdown)
    }
}
