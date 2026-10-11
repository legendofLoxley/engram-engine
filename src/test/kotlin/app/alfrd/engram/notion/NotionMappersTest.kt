package app.alfrd.engram.notion

import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.FocusMode
import app.alfrd.engram.cognitive.pipeline.horizon.FocusTargetKind
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.OperationParser
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The mappers are exercised against the strict fake, so a property name or option the real template lacks fails here, not in production. */
class NotionMappersTest {

    private lateinit var notion: FakeNotionApi
    private lateinit var t: FakeTemplate
    private val zone: ZoneId = ZoneId.of("America/New_York")

    @BeforeEach
    fun setUp() {
        notion = FakeNotionApi()
        t = notion.installAlfrdTemplate()
    }

    private fun createContainer(kind: ContainerKind, name: String, parent: String? = null, due: Long? = null, tentative: Boolean = false) = runBlocking {
        notion.createPage(t.sources.containers, ContainerMapper.createProperties(kind, name, parent, due, tentative, zone))
    }

    private fun createItem(write: ItemWrite) = runBlocking { notion.createPage(t.sources.items, ItemMapper.createProperties(write, zone)) }

    private fun update(id: String, props: Map<String, NotionValue>) = runBlocking { notion.updatePage(id, props) }

    private fun item(id: String) = ItemMapper.fromPage(notion.page(id), zone)!!
    private fun container(id: String) = ContainerMapper.fromPage(notion.page(id), zone)!!

    private val friday = NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-09"), ZoneId.of("America/New_York"), DateOnlyMeans.END_OF_DAY)

    // ── Containers ───────────────────────────────────────────────────────────

    @Test
    fun `a container round trips through Notion, with its parent, due date and tentative flag`() {
        val work = createContainer(ContainerKind.DOMAIN, "Work")
        val demo = createContainer(ContainerKind.PROJECT, "  The  Meridian Demo ", parent = work.id, due = friday, tentative = true)
        val record = container(demo.id).record
        assertEquals(demo.id, record.uid)
        assertEquals(ContainerKind.PROJECT, record.kind)
        assertEquals("The  Meridian Demo", record.name)
        assertEquals(OperationParser.normalizeName("The Meridian Demo"), record.normalizedName)
        assertEquals("active", record.status)
        assertEquals(work.id, record.parentUid)
        assertEquals(friday, record.dueAt)
        assertTrue(record.tentative)
        assertNull(record.attention)
    }

    @Test
    fun `a name normalizes exactly as the engine's own container lookup does`() {
        val page = createContainer(ContainerKind.PROJECT, "Birthday   Dinner")
        assertEquals(OperationParser.normalizeName("Birthday   Dinner"), container(page.id).record.normalizedName)
        assertEquals("birthday dinner", container(page.id).record.normalizedName)
    }

    @Test
    fun `an inferred container is marked tentative and sourced to alfrd, a named one to the person`() {
        val inferred = createContainer(ContainerKind.PROJECT, "Guessed", tentative = true)
        val named = createContainer(ContainerKind.PROJECT, "Said")
        assertEquals("alfrd inferred", notion.page(inferred.id).select("Source"))
        assertEquals("Person said", notion.page(named.id).select("Source"))
    }

    @Test
    fun `a domain never reports a parent even if one is set by hand`() {
        val work = createContainer(ContainerKind.DOMAIN, "Work")
        val home = createContainer(ContainerKind.DOMAIN, "Home")
        update(home.id, ContainerMapper.fileProperties(work.id))
        assertNull(container(home.id).record.parentUid)
    }

    @Test
    fun `Paused and Done map to states that are not active, so they leave the live view`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        update(demo.id, mapOf("Status" to NotionValue.Select("Paused")))
        assertEquals("paused", container(demo.id).record.status)
        update(demo.id, ContainerMapper.resolveProperties(resolved = true))
        assertEquals("done", container(demo.id).record.status)
        update(demo.id, ContainerMapper.resolveProperties(resolved = false))
        assertEquals("active", container(demo.id).record.status)
    }

    @Test
    fun `a row with no usable kind or name is not a container, and an archived one is skipped`() {
        val noKind = runBlocking { notion.createPage(t.sources.containers, mapOf("Name" to NotionValue.Title("No kind"))) }
        assertNull(ContainerMapper.fromPage(noKind, zone))
        val noName = runBlocking { notion.createPage(t.sources.containers, mapOf("Name" to NotionValue.Title("  "), "Kind" to NotionValue.Select("Project"))) }
        assertNull(ContainerMapper.fromPage(noName, zone))
        assertNull(ContainerMapper.fromPage(noKind.copy(archived = true), zone))
    }

    // ── Attention is Override; focus is the Focus checkbox and Zoom ─────────

    @Test
    fun `pinning for attention does not set focus, and focusing does not set attention`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        update(demo.id, ContainerMapper.attentionProperties(AttentionDirective.PINNED))
        assertEquals(AttentionDirective.PINNED, container(demo.id).record.attention)
        assertNull(container(demo.id).focus)

        update(demo.id, ContainerMapper.attentionProperties(null))
        update(demo.id, FocusMapper.setProperties(FocusZoom.BINOCULARS))
        assertNull(container(demo.id).record.attention)
        assertEquals(FocusZoom.BINOCULARS, container(demo.id).focus?.zoom)
    }

    @Test
    fun `a focus with no zoom chosen takes the default for what it is on`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        update(demo.id, mapOf("Focus" to NotionValue.Checkbox(true)))
        assertEquals(FocusZoom.BINOCULARS, container(demo.id).focus?.zoom)
        val task = createItem(ItemWrite(ItemKind.TASK, "Send the deck"))
        update(task.id, mapOf("Focus" to NotionValue.Checkbox(true)))
        assertEquals(FocusZoom.TELESCOPE, item(task.id).focus?.zoom)
    }

    @Test
    fun `suppress is an attention value on an item the same as on a container`() {
        val task = createItem(ItemWrite(ItemKind.TASK, "Fix the firewall"))
        update(task.id, ItemMapper.attentionProperties(AttentionDirective.SUPPRESSED))
        assertEquals(AttentionDirective.SUPPRESSED, item(task.id).attention)
        update(task.id, ItemMapper.attentionProperties(null))
        assertNull(item(task.id).attention)
    }

    @Test
    fun `at most one focus is honoured, the most recently edited, and the rest are listed to clear`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        val dinner = createContainer(ContainerKind.PROJECT, "Dinner")
        val task = createItem(ItemWrite(ItemKind.TASK, "Look at firewall rules"))
        notion.now = Instant.parse("2026-10-06T12:10:00Z")
        update(demo.id, FocusMapper.setProperties(FocusZoom.BINOCULARS))
        notion.now = Instant.parse("2026-10-06T12:20:00Z")
        update(task.id, FocusMapper.setProperties(FocusZoom.TELESCOPE))
        notion.now = Instant.parse("2026-10-06T12:30:00Z")
        update(dinner.id, FocusMapper.setProperties(FocusZoom.OVERVIEW))

        val candidates = FocusMapper.candidates(
            listOf(container(demo.id), container(dinner.id)),
            listOf(item(task.id)),
        )
        val resolution = FocusMapper.resolve(candidates)
        assertEquals(dinner.id, resolution.current?.pageId)
        assertEquals(setOf(demo.id, task.id), resolution.extras.map { it.pageId }.toSet())
    }

    @Test
    fun `setting a new focus clears the previous one across containers and items`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        val task = createItem(ItemWrite(ItemKind.TASK, "Look at firewall rules"))
        update(task.id, FocusMapper.setProperties(FocusZoom.TELESCOPE))
        val before = FocusMapper.candidates(emptyList(), listOf(item(task.id)))

        FocusMapper.planSet(demo.id, FocusZoom.BINOCULARS, before).forEach { update(it.pageId, it.properties) }

        assertEquals(FocusZoom.BINOCULARS, container(demo.id).focus?.zoom)
        assertNull(item(task.id).focus)
        assertNull(notion.page(task.id).select("Zoom"))
    }

    @Test
    fun `focusing what is already focused at that zoom writes nothing, and releasing clears only what holds focus`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        update(demo.id, FocusMapper.setProperties(FocusZoom.BINOCULARS))
        val held = FocusMapper.candidates(listOf(container(demo.id)), emptyList())
        assertEquals(emptyList<PageUpdate>(), FocusMapper.planSet(demo.id, FocusZoom.BINOCULARS, held))
        assertEquals(1, FocusMapper.planSet(demo.id, FocusZoom.TELESCOPE, held).size)
        assertEquals(listOf(demo.id), FocusMapper.planRelease(null, held).map { it.pageId })
        assertEquals(emptyList<PageUpdate>(), FocusMapper.planRelease("someone-else", held))
    }

    @Test
    fun `a superseded item cannot hold the focus`() {
        val old = createItem(ItemWrite(ItemKind.TASK, "Delivery Friday"))
        update(old.id, FocusMapper.setProperties(FocusZoom.TELESCOPE))
        createItem(ItemWrite(ItemKind.TASK, "Delivery Monday", supersedesId = old.id))
        update(old.id, ItemMapper.markSuperseded())
        assertTrue(FocusMapper.candidates(emptyList(), listOf(item(old.id))).isEmpty())
    }

    @Test
    fun `a focus becomes the engine's pinned focus record`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        update(demo.id, FocusMapper.setProperties(FocusZoom.BINOCULARS))
        val candidate = FocusMapper.candidates(listOf(container(demo.id)), emptyList()).single()
        val record = FocusMapper.toRecord(candidate, setCycleSeq = 7)
        assertEquals(demo.id, record.targetUid)
        assertEquals(FocusTargetKind.PROJECT, record.targetKind)
        assertEquals(FocusMode.PINNED, record.mode)
        assertEquals(7L, record.setCycleSeq)
    }

    // ── Items ────────────────────────────────────────────────────────────────

    @Test
    fun `a recorded item carries its source, the verbatim words, its session and its container`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        val session = runBlocking { notion.createPage(t.sources.sessions, SessionMapper.createProperties(LocalDate.parse("2026-10-06"))) }
        val written = createItem(ItemWrite(
            kind = ItemKind.TASK,
            text = "Send the Meridian deck",
            said = "I need to send the Meridian deck by Friday",
            dueAt = friday,
            containerId = demo.id,
            sessionId = session.id,
            opKey = "req-1:0",
        ))
        val read = item(written.id)
        assertEquals(ItemKind.TASK, read.kind)
        assertEquals("Send the Meridian deck", read.title)
        assertEquals("I need to send the Meridian deck by Friday", read.said)
        assertEquals(ItemSource.PERSON_SAID, read.source)
        assertEquals(ItemStatus.OPEN, read.status)
        assertEquals(friday, read.dueAt)
        assertEquals(demo.id, read.containerId)
        assertEquals(session.id, read.sessionId)
        assertEquals("req-1:0", read.opKey)
        assertFalse(read.tentative)
        // The relations are visible from the other side too, so a read of the session or container finds the item.
        assertEquals(listOf(written.id), notion.page(session.id).relation("Items"))
        assertEquals(listOf(written.id), notion.page(demo.id).relation("Items"))
    }

    @Test
    fun `when no verbatim words are given the item's own text is stored as them, so Said is never empty`() {
        val written = createItem(ItemWrite(ItemKind.FACT, "My sister is allergic to shellfish"))
        assertEquals("My sister is allergic to shellfish", item(written.id).said)
    }

    @Test
    fun `a fact has no status or date, a task has both, and a dated fact's date is not written`() {
        val fact = createItem(ItemWrite(ItemKind.FACT, "Dogs are great", dueAt = friday))
        assertEquals(ItemStatus.NONE, item(fact.id).status)
        assertNull(item(fact.id).dueAt)
        val task = createItem(ItemWrite(ItemKind.TASK, "Water the plants"))
        assertEquals(ItemStatus.OPEN, item(task.id).status)
    }

    @Test
    fun `an event's date reads as the start of its day and a task's as the end of it`() {
        val day = LocalDate.parse("2026-10-09")
        val event = createItem(ItemWrite(ItemKind.EVENT, "Sister's birthday", dueAt = NotionDates.dateOnlyMoment(day, zone, DateOnlyMeans.START_OF_DAY)))
        val task = createItem(ItemWrite(ItemKind.TASK, "Send the deck", dueAt = NotionDates.dateOnlyMoment(day, zone, DateOnlyMeans.END_OF_DAY)))
        assertEquals("2026-10-09", notion.page(event.id).date("Date")?.start)
        assertEquals("2026-10-09", notion.page(task.id).date("Date")?.start)
        assertEquals(NotionDates.dateOnlyMoment(day, zone, DateOnlyMeans.START_OF_DAY), item(event.id).dueAt)
        assertEquals(NotionDates.dateOnlyMoment(day, zone, DateOnlyMeans.END_OF_DAY), item(task.id).dueAt)
    }

    @Test
    fun `an inferred item is tentative and sourced to alfrd, and a question alfrd asked reads as asked by alfrd`() {
        val inferred = createItem(ItemWrite(ItemKind.FACT, "Probably prefers mornings", source = ItemSource.ALFRD_INFERRED, tentative = true))
        assertEquals("alfrd inferred", notion.page(inferred.id).select("Source"))
        assertTrue(item(inferred.id).tentative)

        val asked = createItem(ItemWrite(ItemKind.QUESTION, "What have you been up to?", source = ItemSource.ALFRD_ASKED))
        val theirs = createItem(ItemWrite(ItemKind.QUESTION, "Who is coming to dinner?"))
        val resolveEntity = { _: String -> null as String? }
        assertEquals("alfrd", item(asked.id).toRecord({ false }, resolveEntity).askedBy)
        assertEquals("user", item(theirs.id).toRecord({ false }, resolveEntity).askedBy)
        assertNull(item(inferred.id).toRecord({ false }, resolveEntity).askedBy)
    }

    @Test
    fun `an Insight row is not part of the Horizon`() {
        val insight = runBlocking { notion.createPage(t.sources.items, mapOf("Item" to NotionValue.Title("A reflection"), "Kind" to NotionValue.Select("Insight"))) }
        assertNull(ItemMapper.fromPage(insight, zone))
    }

    @Test
    fun `an item record has a project only when its container is one, and entity names come from the entity rows`() {
        val work = createContainer(ContainerKind.DOMAIN, "Work")
        val demo = createContainer(ContainerKind.PROJECT, "Demo", parent = work.id)
        val sister = runBlocking { notion.createPage(t.sources.entities, EntityMapper.createProperties("sister", tentative = false)) }
        val inProject = createItem(ItemWrite(ItemKind.TASK, "Send the deck", containerId = demo.id, aboutIds = listOf(sister.id)))
        val inDomain = createItem(ItemWrite(ItemKind.TASK, "Plan the quarter", containerId = work.id))

        val names = mapOf(sister.id to "sister")
        val projectIds = setOf(demo.id)
        assertEquals(demo.id, item(inProject.id).toRecord(projectIds::contains, names::get).projectUid)
        assertEquals(listOf("sister"), item(inProject.id).toRecord(projectIds::contains, names::get).about)
        assertNull(item(inDomain.id).toRecord(projectIds::contains, names::get).projectUid)
        assertEquals(work.id, item(inDomain.id).containerId)
    }

    @Test
    fun `the person's words are only the source quote when they differ from the label`() {
        val same = createItem(ItemWrite(ItemKind.FACT, "Dogs are great"))
        val different = createItem(ItemWrite(ItemKind.TASK, "Send the deck", said = "ugh I really should send that deck out"))
        val noEntity = { _: String -> null as String? }
        assertNull(item(same.id).toRecord({ false }, noEntity).sourceQuote)
        assertEquals("ugh I really should send that deck out", item(different.id).toRecord({ false }, noEntity).sourceQuote)
    }

    @Test
    fun `an item longer than a title can hold keeps its full words in Said`() {
        val words = "w".repeat(6_000)
        val written = createItem(ItemWrite(ItemKind.FACT, words))
        assertEquals(2000, notion.page(written.id).title("Item").length)
        assertEquals(words, item(written.id).said)
    }

    // ── Corrections and resolution ──────────────────────────────────────────

    @Test
    fun `a correction is a new row that supersedes the old one, which is marked Superseded and never overwritten`() {
        val old = createItem(ItemWrite(ItemKind.TASK, "Delivery is Friday", said = "Delivery is Friday", dueAt = friday))
        val mondayMs = NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-12"), zone, DateOnlyMeans.END_OF_DAY)
        val new = createItem(ItemWrite(ItemKind.TASK, "Delivery is Monday", said = "I meant Monday", dueAt = mondayMs, supersedesId = old.id))
        update(old.id, ItemMapper.markSuperseded())

        val oldRead = item(old.id)
        assertEquals(ItemStatus.SUPERSEDED, oldRead.status)
        assertTrue(oldRead.superseded)
        assertEquals(listOf(new.id), oldRead.supersededBy)
        assertEquals("Delivery is Friday", oldRead.title)
        assertEquals(friday, oldRead.dueAt)

        val newRead = item(new.id)
        assertEquals(listOf(old.id), newRead.supersedes)
        assertFalse(newRead.superseded)
        assertEquals("I meant Monday", newRead.said)
        assertEquals(mondayMs, newRead.dueAt)
    }

    @Test
    fun `a row someone superseded by hand in Notion, setting only Supersedes, still reads as superseded`() {
        val old = createItem(ItemWrite(ItemKind.FACT, "Dog is Newton"))
        val new = createItem(ItemWrite(ItemKind.FACT, "Dog is Norton"))
        update(new.id, mapOf("Supersedes" to NotionValue.Relation(listOf(old.id))))
        assertTrue(item(old.id).superseded)
        assertEquals(ItemStatus.NONE, item(old.id).status)
    }

    @Test
    fun `resolving a superseded row never downgrades Superseded to Resolved`() {
        val old = createItem(ItemWrite(ItemKind.TASK, "Delivery is Friday"))
        update(old.id, ItemMapper.markSuperseded())
        assertEquals(ResolveDecision.NoChange, ItemMapper.decideResolve(item(old.id), resolved = true))
        assertEquals(ResolveDecision.NotApplicable, ItemMapper.decideResolve(item(old.id), resolved = false))
        assertEquals(ItemStatus.SUPERSEDED, item(old.id).status)
    }

    @Test
    fun `resolve and reopen change the status, repeat themselves harmlessly, and do not apply to facts`() {
        val task = createItem(ItemWrite(ItemKind.TASK, "Water the plants"))
        val decision = ItemMapper.decideResolve(item(task.id), resolved = true)
        assertTrue(decision is ResolveDecision.Apply)
        update(task.id, (decision as ResolveDecision.Apply).properties)
        assertEquals(ItemStatus.RESOLVED, item(task.id).status)
        assertEquals(ResolveDecision.NoChange, ItemMapper.decideResolve(item(task.id), resolved = true))
        assertTrue(ItemMapper.decideResolve(item(task.id), resolved = false) is ResolveDecision.Apply)

        val fact = createItem(ItemWrite(ItemKind.FACT, "Dogs are great"))
        assertEquals(ResolveDecision.NotApplicable, ItemMapper.decideResolve(item(fact.id), resolved = true))
    }

    @Test
    fun `an empty date may be filled in place, but changing or clearing an existing date is a correction that must supersede`() {
        val undated = createItem(ItemWrite(ItemKind.TASK, "Send the deck"))
        val fill = ItemMapper.decideDue(item(undated.id), friday, zone)
        assertTrue(fill is DueDecision.FillInPlace)
        update(undated.id, (fill as DueDecision.FillInPlace).properties)
        assertEquals(friday, item(undated.id).dueAt)

        assertEquals(DueDecision.NoChange, ItemMapper.decideDue(item(undated.id), friday, zone))
        val monday = NotionDates.dateOnlyMoment(LocalDate.parse("2026-10-12"), zone, DateOnlyMeans.END_OF_DAY)
        assertEquals(DueDecision.Supersede, ItemMapper.decideDue(item(undated.id), monday, zone))
        assertEquals(DueDecision.Supersede, ItemMapper.decideDue(item(undated.id), null, zone))
        assertEquals(friday, item(undated.id).dueAt) // deciding never writes
    }

    @Test
    fun `a fact cannot be given a date in place`() {
        val fact = createItem(ItemWrite(ItemKind.FACT, "Dogs are great"))
        assertEquals(DueDecision.Supersede, ItemMapper.decideDue(item(fact.id), friday, zone))
    }

    // ── Links ────────────────────────────────────────────────────────────────

    @Test
    fun `filing moves an item, and about and related lists grow without duplicates`() {
        val demo = createContainer(ContainerKind.PROJECT, "Demo")
        val dinner = createContainer(ContainerKind.PROJECT, "Dinner")
        val a = createItem(ItemWrite(ItemKind.TASK, "a", containerId = demo.id))
        val b = createItem(ItemWrite(ItemKind.TASK, "b"))
        val sister = runBlocking { notion.createPage(t.sources.entities, EntityMapper.createProperties("sister", tentative = true)) }

        update(a.id, ItemMapper.fileProperties(dinner.id))
        assertEquals(dinner.id, item(a.id).containerId)
        assertEquals(emptyList<String>(), notion.page(demo.id).relation("Items"))

        update(a.id, ItemMapper.aboutProperties(item(a.id), sister.id))
        update(a.id, ItemMapper.aboutProperties(item(a.id), sister.id))
        assertEquals(listOf(sister.id), item(a.id).aboutIds)
        assertEquals(listOf(a.id), notion.page(sister.id).relation("Items about"))

        update(a.id, ItemMapper.relatedProperties(item(a.id), b.id))
        update(a.id, ItemMapper.relatedProperties(item(a.id), b.id))
        assertEquals(listOf(b.id), item(a.id).relatedIds)
    }

    // ── Sessions and entities ────────────────────────────────────────────────

    @Test
    fun `a session is one per day, found by its key`() {
        val day = LocalDate.parse("2026-10-06")
        assertEquals("day:2026-10-06", SessionMapper.keyForDay(day))
        val session = runBlocking { notion.createPage(t.sources.sessions, SessionMapper.createProperties(day)) }
        val found = runBlocking { notion.query(t.sources.sessions, SessionMapper.keyFilter(day)).pages }
        assertEquals(listOf(session.id), found.map { it.id })
        assertEquals(emptyList<NotionPage>(), runBlocking { notion.query(t.sources.sessions, SessionMapper.keyFilter(day.plusDays(1))).pages })
        assertEquals("day:2026-10-06", SessionMapper.fromPage(session).key)
        assertEquals("alfrd", session.select("Source"))
    }

    @Test
    fun `the day a moment belongs to is the day in the person's zone, not in UTC`() {
        val lateEvening = Instant.parse("2026-10-07T02:30:00Z") // still the 6th in New York
        assertEquals(LocalDate.parse("2026-10-06"), SessionMapper.dayOf(lateEvening, zone))
    }

    @Test
    fun `last active is the latest edit to any session`() {
        notion.now = Instant.parse("2026-10-05T09:00:00Z")
        val monday = runBlocking { notion.createPage(t.sources.sessions, SessionMapper.createProperties(LocalDate.parse("2026-10-05"))) }
        notion.now = Instant.parse("2026-10-06T09:00:00Z")
        val tuesday = runBlocking { notion.createPage(t.sources.sessions, SessionMapper.createProperties(LocalDate.parse("2026-10-06"))) }
        notion.now = Instant.parse("2026-10-06T17:45:00Z")
        runBlocking { notion.appendBlocks(tuesday.id, listOf(NotionBlock.Paragraph(listOf(RichSegment("hello"))))) }
        assertEquals(Instant.parse("2026-10-06T17:45:00Z").toEpochMilli(), SessionMapper.lastActiveAt(listOf(notion.page(monday.id), notion.page(tuesday.id))))
        assertNull(SessionMapper.lastActiveAt(emptyList()))
    }

    @Test
    fun `an entity the model named is tentative, one the person named is not, and names are matched normalized`() {
        val tentative = runBlocking { notion.createPage(t.sources.entities, EntityMapper.createProperties("Sister", tentative = true)) }
        assertEquals("alfrd inferred", tentative.select("Source"))
        assertTrue(tentative.checkbox("Tentative"))
        assertEquals("Sister", EntityMapper.name(tentative))
        assertEquals("sister", EntityMapper.normalizedName("  SISTER "))
        assertNotNull(tentative.id)
    }

    @Test
    fun `a partial relation is flagged so a caller does not trust it as complete`() {
        val page = notion.page(createItem(ItemWrite(ItemKind.TASK, "x")).id)
        val truncated = page.copy(properties = page.properties + ("Related" to NotionValue.Relation(listOf("a"), hasMore = true)))
        assertTrue(ItemMapper.fromPage(truncated, zone)!!.relationsTruncated)
        assertFalse(ItemMapper.fromPage(page, zone)!!.relationsTruncated)
    }
}
