package app.alfrd.engram.notion

import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerRecord
import app.alfrd.engram.cognitive.pipeline.horizon.FocusMode
import app.alfrd.engram.cognitive.pipeline.horizon.FocusRecord
import app.alfrd.engram.cognitive.pipeline.horizon.FocusTargetKind
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind
import app.alfrd.engram.cognitive.pipeline.horizon.ItemRecord
import app.alfrd.engram.cognitive.pipeline.horizon.OperationParser
import app.alfrd.engram.cognitive.pipeline.horizon.defaultZoomFor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where an item's words came from, as the `Source` column records it. Anything else a person or a direct edit put there reads as null. */
enum class ItemSource(val label: String) {
    PERSON_SAID(NotionSchema.SOURCE_PERSON_SAID),
    ALFRD_INFERRED(NotionSchema.SOURCE_ALFRD_INFERRED),
    ALFRD_ASKED(NotionSchema.SOURCE_ALFRD_ASKED);

    companion object {
        fun fromLabel(label: String?): ItemSource? = entries.firstOrNull { it.label == label }
    }
}

enum class ItemStatus { NONE, OPEN, RESOLVED, SUPERSEDED }

data class NotionFocus(val zoom: FocusZoom)

data class NotionContainer(val record: ContainerRecord, val focus: NotionFocus?, val lastEdited: Instant)

/**
 * One Items row, read. Ids are Notion page ids. Turning it into the engine's [ItemRecord] needs two
 * answers only the whole read knows (is that container a project? what is that entity called?), so
 * [toRecord] takes them as functions.
 */
data class NotionItem(
    val id: String,
    val kind: ItemKind,
    val title: String,
    /** The verbatim words, or null when the column is empty. */
    val said: String?,
    val status: ItemStatus,
    val dueAt: Long?,
    val containerId: String?,
    val aboutIds: List<String>,
    val attention: AttentionDirective?,
    val source: ItemSource?,
    val sourceLabel: String?,
    val tentative: Boolean,
    val sessionId: String?,
    val opKey: String?,
    val supersedes: List<String>,
    val supersededBy: List<String>,
    val relatedIds: List<String>,
    val focus: NotionFocus?,
    val createdAt: Long,
    val lastEdited: Instant,
    /** True when any relation came back partial; the lists above are then not the whole story. */
    val relationsTruncated: Boolean,
) {
    /** Superseded by status or by a row that points at it, so a direct edit in Notion counts the same as the adapter's own write. */
    val superseded: Boolean get() = status == ItemStatus.SUPERSEDED || supersededBy.isNotEmpty()

    fun toRecord(isProject: (String) -> Boolean, entityName: (String) -> String?): ItemRecord = ItemRecord(
        uid = id,
        kind = kind,
        text = title,
        status = when {
            kind == ItemKind.FACT -> null
            status == ItemStatus.RESOLVED -> "resolved"
            else -> "open"
        },
        dueAt = dueAt,
        projectUid = containerId?.takeIf(isProject),
        createdAt = createdAt,
        about = aboutIds.mapNotNull(entityName),
        attention = attention,
        sourceQuote = said?.takeIf { it != title },
        askedBy = if (kind == ItemKind.QUESTION) (if (source == ItemSource.ALFRD_ASKED) "alfrd" else "user") else null,
    )
}

/** What to write for a new item. [said] is the person's verbatim words; when null the item's own text is stored as them. */
data class ItemWrite(
    val kind: ItemKind,
    val text: String,
    val said: String? = null,
    val source: ItemSource = ItemSource.PERSON_SAID,
    val tentative: Boolean = false,
    val dueAt: Long? = null,
    val containerId: String? = null,
    val sessionId: String? = null,
    val opKey: String? = null,
    val aboutIds: List<String> = emptyList(),
    /** The row this one corrects. The old row is marked Superseded separately; see [ItemMapper.markSuperseded]. */
    val supersedesId: String? = null,
)

object ContainerMapper {
    fun fromPage(page: NotionPage, zone: ZoneId): NotionContainer? {
        if (page.archived) return null
        val kind = NotionSchema.containerKind(page.select(NotionSchema.Containers.KIND)) ?: return null
        val name = page.title(NotionSchema.Containers.NAME).trim().ifEmpty { return null }
        val record = ContainerRecord(
            uid = page.id,
            kind = kind,
            name = name,
            normalizedName = OperationParser.normalizeName(name),
            status = when (page.select(NotionSchema.Containers.STATUS)) {
                NotionSchema.CONTAINER_DONE -> "done"
                NotionSchema.CONTAINER_PAUSED -> "paused"
                else -> "active"
            },
            dueAt = NotionDates.toMillis(page.date(NotionSchema.Containers.DUE), zone, DateOnlyMeans.END_OF_DAY),
            parentUid = if (kind == ContainerKind.PROJECT) page.relation(NotionSchema.Containers.PARENT).firstOrNull() else null,
            createdAt = page.createdTime.toEpochMilli(),
            attention = NotionSchema.attention(page.select(NotionSchema.Containers.OVERRIDE)),
            tentative = page.checkbox(NotionSchema.Containers.TENTATIVE),
        )
        val focus = if (page.checkbox(NotionSchema.Containers.FOCUS)) {
            NotionFocus(NotionSchema.zoom(page.select(NotionSchema.Containers.ZOOM)) ?: defaultZoomFor(kind.focusKind()))
        } else null
        return NotionContainer(record, focus, page.lastEditedTime)
    }

    fun createProperties(kind: ContainerKind, name: String, parentDomainId: String?, dueAt: Long?, tentative: Boolean, zone: ZoneId): Map<String, NotionValue> = buildMap {
        put(NotionSchema.Containers.NAME, NotionValue.Title(fitTitle(name)))
        put(NotionSchema.Containers.KIND, NotionValue.Select(NotionSchema.kindLabel(kind)))
        put(NotionSchema.Containers.STATUS, NotionValue.Select(NotionSchema.CONTAINER_ACTIVE))
        put(NotionSchema.Containers.SOURCE, NotionValue.Select(if (tentative) NotionSchema.SOURCE_ALFRD_INFERRED else NotionSchema.SOURCE_PERSON_SAID))
        put(NotionSchema.Containers.TENTATIVE, NotionValue.Checkbox(tentative))
        if (kind == ContainerKind.PROJECT && parentDomainId != null) put(NotionSchema.Containers.PARENT, NotionValue.Relation(listOf(parentDomainId)))
        if (dueAt != null) put(NotionSchema.Containers.DUE, NotionDates.toValue(dueAt, zone, DateOnlyMeans.END_OF_DAY))
    }

    fun fileProperties(parentDomainId: String): Map<String, NotionValue> = mapOf(NotionSchema.Containers.PARENT to NotionValue.Relation(listOf(parentDomainId)))

    fun attentionProperties(attention: AttentionDirective?): Map<String, NotionValue> =
        mapOf(NotionSchema.Containers.OVERRIDE to NotionValue.Select(NotionSchema.attentionLabel(attention)))

    /** A project is Done or Active again; the engine resolves nothing else about a container. */
    fun resolveProperties(resolved: Boolean): Map<String, NotionValue> =
        mapOf(NotionSchema.Containers.STATUS to NotionValue.Select(if (resolved) NotionSchema.CONTAINER_DONE else NotionSchema.CONTAINER_ACTIVE))

    private fun ContainerKind.focusKind(): FocusTargetKind = if (this == ContainerKind.DOMAIN) FocusTargetKind.DOMAIN else FocusTargetKind.PROJECT
}

/** What setting an item's date means when it already has one. */
sealed interface DueDecision {
    data object NoChange : DueDecision

    /** The row had no date; filling it in is not a change to anything the person said. */
    data class FillInPlace(val properties: Map<String, NotionValue>) : DueDecision

    /** The row has a different date (or loses it). That is a correction, and a correction never overwrites. */
    data object Supersede : DueDecision
}

/** What marking an item resolved (or open again) does to the row. */
sealed interface ResolveDecision {
    data object NoChange : ResolveDecision
    data class Apply(val properties: Map<String, NotionValue>) : ResolveDecision

    /** A fact has no lifecycle, and a superseded row cannot be reopened. */
    data object NotApplicable : ResolveDecision
}

object ItemMapper {
    fun fromPage(page: NotionPage, zone: ZoneId): NotionItem? {
        if (page.archived) return null
        val kind = NotionSchema.itemKind(page.select(NotionSchema.Items.KIND)) ?: return null
        val sourceLabel = page.select(NotionSchema.Items.SOURCE)
        val dated = kind == ItemKind.TASK || kind == ItemKind.EVENT
        val means = if (kind == ItemKind.EVENT) DateOnlyMeans.START_OF_DAY else DateOnlyMeans.END_OF_DAY
        val relationNames = listOf(
            NotionSchema.Items.CONTAINER, NotionSchema.Items.ABOUT, NotionSchema.Items.SESSION,
            NotionSchema.Items.SUPERSEDES, NotionSchema.Items.SUPERSEDED_BY, NotionSchema.Items.RELATED,
        )
        return NotionItem(
            id = page.id,
            kind = kind,
            title = page.title(NotionSchema.Items.ITEM),
            said = page.text(NotionSchema.Items.SAID).takeIf { it.isNotBlank() },
            status = when (page.select(NotionSchema.Items.STATUS)) {
                NotionSchema.STATUS_OPEN -> ItemStatus.OPEN
                NotionSchema.STATUS_RESOLVED -> ItemStatus.RESOLVED
                NotionSchema.STATUS_SUPERSEDED -> ItemStatus.SUPERSEDED
                else -> ItemStatus.NONE
            },
            dueAt = if (dated) NotionDates.toMillis(page.date(NotionSchema.Items.DATE), zone, means) else null,
            containerId = page.relation(NotionSchema.Items.CONTAINER).firstOrNull(),
            aboutIds = page.relation(NotionSchema.Items.ABOUT),
            attention = NotionSchema.attention(page.select(NotionSchema.Items.OVERRIDE)),
            source = ItemSource.fromLabel(sourceLabel),
            sourceLabel = sourceLabel,
            tentative = page.checkbox(NotionSchema.Items.TENTATIVE),
            sessionId = page.relation(NotionSchema.Items.SESSION).firstOrNull(),
            opKey = page.text(NotionSchema.Items.OP_KEY).takeIf { it.isNotBlank() },
            supersedes = page.relation(NotionSchema.Items.SUPERSEDES),
            supersededBy = page.relation(NotionSchema.Items.SUPERSEDED_BY),
            relatedIds = page.relation(NotionSchema.Items.RELATED),
            focus = if (page.checkbox(NotionSchema.Items.FOCUS)) {
                NotionFocus(NotionSchema.zoom(page.select(NotionSchema.Items.ZOOM)) ?: defaultZoomFor(FocusTargetKind.ITEM))
            } else null,
            createdAt = page.createdTime.toEpochMilli(),
            lastEdited = page.lastEditedTime,
            relationsTruncated = relationNames.any(page::relationHasMore),
        )
    }

    fun createProperties(write: ItemWrite, zone: ZoneId): Map<String, NotionValue> = buildMap {
        put(NotionSchema.Items.ITEM, NotionValue.Title(fitTitle(write.text)))
        put(NotionSchema.Items.KIND, NotionValue.Select(NotionSchema.kindLabel(write.kind)))
        put(NotionSchema.Items.SAID, NotionValue.RichText(write.said?.takeIf { it.isNotBlank() } ?: write.text))
        put(NotionSchema.Items.SOURCE, NotionValue.Select(write.source.label))
        put(NotionSchema.Items.TENTATIVE, NotionValue.Checkbox(write.tentative))
        if (write.kind != ItemKind.FACT) put(NotionSchema.Items.STATUS, NotionValue.Select(NotionSchema.STATUS_OPEN))
        if (write.dueAt != null && (write.kind == ItemKind.TASK || write.kind == ItemKind.EVENT)) {
            put(NotionSchema.Items.DATE, NotionDates.toValue(write.dueAt, zone, meansFor(write.kind)))
        }
        write.containerId?.let { put(NotionSchema.Items.CONTAINER, NotionValue.Relation(listOf(it))) }
        write.sessionId?.let { put(NotionSchema.Items.SESSION, NotionValue.Relation(listOf(it))) }
        if (write.aboutIds.isNotEmpty()) put(NotionSchema.Items.ABOUT, NotionValue.Relation(write.aboutIds.distinct()))
        write.opKey?.let { put(NotionSchema.Items.OP_KEY, NotionValue.RichText(it)) }
        write.supersedesId?.let { put(NotionSchema.Items.SUPERSEDES, NotionValue.Relation(listOf(it))) }
    }

    /** What to set on the old row once its correction exists. Notion fills the old row's `Superseded by` itself, from the new row's `Supersedes`. */
    fun markSuperseded(): Map<String, NotionValue> = mapOf(NotionSchema.Items.STATUS to NotionValue.Select(NotionSchema.STATUS_SUPERSEDED))

    /** Never downgrades Superseded to Resolved: a superseded row is already closed, and stays what it is. */
    fun decideResolve(current: NotionItem, resolved: Boolean): ResolveDecision {
        if (current.kind == ItemKind.FACT) return ResolveDecision.NotApplicable
        if (current.superseded) return if (resolved) ResolveDecision.NoChange else ResolveDecision.NotApplicable
        val target = if (resolved) ItemStatus.RESOLVED else ItemStatus.OPEN
        if (current.status == target) return ResolveDecision.NoChange
        val label = if (resolved) NotionSchema.STATUS_RESOLVED else NotionSchema.STATUS_OPEN
        return ResolveDecision.Apply(mapOf(NotionSchema.Items.STATUS to NotionValue.Select(label)))
    }

    /** Filling an empty date in place is fine; changing or clearing an existing one is a correction and must supersede. */
    fun decideDue(current: NotionItem, newDueAt: Long?, zone: ZoneId): DueDecision {
        val existing = current.dueAt
        return when {
            existing == newDueAt -> DueDecision.NoChange
            existing == null && newDueAt != null && (current.kind == ItemKind.TASK || current.kind == ItemKind.EVENT) ->
                DueDecision.FillInPlace(mapOf(NotionSchema.Items.DATE to NotionDates.toValue(newDueAt, zone, meansFor(current.kind))))
            else -> DueDecision.Supersede
        }
    }

    fun attentionProperties(attention: AttentionDirective?): Map<String, NotionValue> =
        mapOf(NotionSchema.Items.OVERRIDE to NotionValue.Select(NotionSchema.attentionLabel(attention)))

    /** Moves the item: an item has one container. */
    fun fileProperties(containerId: String): Map<String, NotionValue> = mapOf(NotionSchema.Items.CONTAINER to NotionValue.Relation(listOf(containerId)))

    /** The whole new list: the old ids, then [entityId] if it is not already there. Idempotent. */
    fun aboutProperties(current: NotionItem, entityId: String): Map<String, NotionValue> =
        mapOf(NotionSchema.Items.ABOUT to NotionValue.Relation((current.aboutIds + entityId).distinct()))

    fun relatedProperties(current: NotionItem, otherId: String): Map<String, NotionValue> =
        mapOf(NotionSchema.Items.RELATED to NotionValue.Relation((current.relatedIds + otherId).distinct()))

    private fun meansFor(kind: ItemKind) = if (kind == ItemKind.EVENT) DateOnlyMeans.START_OF_DAY else DateOnlyMeans.END_OF_DAY
}

/** A page that currently holds the focus: a container or an item. */
data class FocusCandidate(
    val pageId: String,
    val isItem: Boolean,
    val targetKind: FocusTargetKind,
    val zoom: FocusZoom,
    val lastEdited: Instant,
)

data class FocusResolution(val current: FocusCandidate?, val extras: List<FocusCandidate>)

data class PageUpdate(val pageId: String, val properties: Map<String, NotionValue>)

/**
 * Focus is the `Focus` checkbox plus `Zoom`, on a container or an item, and the engine allows at most
 * one pinned focus. Notion cannot enforce that (a person can tick two boxes by hand), so the rule is
 * applied on read: the most recently edited wins, and the rest are listed so they can be cleared.
 */
object FocusMapper {
    fun setProperties(zoom: FocusZoom): Map<String, NotionValue> =
        mapOf(NotionSchema.Containers.FOCUS to NotionValue.Checkbox(true), NotionSchema.Containers.ZOOM to NotionValue.Select(NotionSchema.zoomLabel(zoom)))

    fun clearProperties(): Map<String, NotionValue> =
        mapOf(NotionSchema.Containers.FOCUS to NotionValue.Checkbox(false), NotionSchema.Containers.ZOOM to NotionValue.Select(null))

    fun candidates(containers: List<NotionContainer>, items: List<NotionItem>): List<FocusCandidate> =
        containers.mapNotNull { c ->
            c.focus?.let {
                FocusCandidate(c.record.uid, false, if (c.record.kind == ContainerKind.DOMAIN) FocusTargetKind.DOMAIN else FocusTargetKind.PROJECT, it.zoom, c.lastEdited)
            }
        } + items.filter { !it.superseded }.mapNotNull { i ->
            i.focus?.let { FocusCandidate(i.id, true, FocusTargetKind.ITEM, it.zoom, i.lastEdited) }
        }

    fun resolve(candidates: List<FocusCandidate>): FocusResolution {
        val ordered = candidates.sortedWith(compareByDescending<FocusCandidate> { it.lastEdited }.thenBy { it.pageId })
        return FocusResolution(ordered.firstOrNull(), ordered.drop(1))
    }

    /** The writes that make [targetId] the one focus at [zoom]: set it (unless it already is exactly that), and clear every other page that holds focus. */
    fun planSet(targetId: String, zoom: FocusZoom, candidates: List<FocusCandidate>): List<PageUpdate> {
        val updates = ArrayList<PageUpdate>()
        val already = candidates.firstOrNull { it.pageId == targetId }
        if (already == null || already.zoom != zoom) updates += PageUpdate(targetId, setProperties(zoom))
        candidates.filter { it.pageId != targetId }.forEach { updates += PageUpdate(it.pageId, clearProperties()) }
        return updates
    }

    /** Clears [targetId] if given, otherwise every page holding focus. */
    fun planRelease(targetId: String?, candidates: List<FocusCandidate>): List<PageUpdate> =
        candidates.filter { targetId == null || it.pageId == targetId }.map { PageUpdate(it.pageId, clearProperties()) }

    fun toRecord(candidate: FocusCandidate, setCycleSeq: Long): FocusRecord =
        FocusRecord(candidate.pageId, candidate.targetKind, candidate.zoom, FocusMode.PINNED, setCycleSeq, 1.0)
}

data class NotionSession(val id: String, val key: String?, val lastEdited: Instant)

/** One Session per identity per day, found by its `Key`. The ledger lives in the page body. */
object SessionMapper {
    fun keyForDay(day: LocalDate): String = "day:$day"

    fun dayOf(at: Instant, zone: ZoneId): LocalDate = at.atZone(zone).toLocalDate()

    fun createProperties(day: LocalDate, channel: String = NotionSchema.CHANNEL_TEXT): Map<String, NotionValue> = mapOf(
        NotionSchema.Sessions.SESSION to NotionValue.Title("$day · alfrd"),
        NotionSchema.Sessions.SOURCE to NotionValue.Select(NotionSchema.SESSION_SOURCE_ALFRD),
        NotionSchema.Sessions.DATE to NotionValue.DateValue(day.toString()),
        NotionSchema.Sessions.CHANNEL to NotionValue.Select(channel),
        NotionSchema.Sessions.KEY to NotionValue.RichText(keyForDay(day)),
    )

    fun keyFilter(day: LocalDate): NotionFilter = NotionFilter.TextEquals(NotionSchema.Sessions.KEY, keyForDay(day))

    fun fromPage(page: NotionPage): NotionSession =
        NotionSession(page.id, page.text(NotionSchema.Sessions.KEY).takeIf { it.isNotBlank() }, page.lastEditedTime)

    /** When the person was last active: the latest edit to any Session page. Appending to a page's body is expected to move its `last_edited_time`; Notion reports that at minute precision. */
    fun lastActiveAt(sessions: List<NotionPage>): Long? = sessions.maxOfOrNull { it.lastEditedTime }?.toEpochMilli()
}

object EntityMapper {
    fun createProperties(name: String, tentative: Boolean): Map<String, NotionValue> = mapOf(
        NotionSchema.Entities.NAME to NotionValue.Title(fitTitle(name)),
        NotionSchema.Entities.SOURCE to NotionValue.Select(if (tentative) NotionSchema.SOURCE_ALFRD_INFERRED else NotionSchema.SOURCE_PERSON_SAID),
        NotionSchema.Entities.TENTATIVE to NotionValue.Checkbox(tentative),
    )

    fun name(page: NotionPage): String = page.title(NotionSchema.Entities.NAME).trim()

    fun normalizedName(name: String): String = OperationParser.normalizeName(name)
}
