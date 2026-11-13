package app.alfrd.engram.cognitive.pipeline.horizon

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The lists a kind can be a member of. Each used to be a hardcoded `listOf("Task", ...)` somewhere in the Horizon code; a kind is now in a
 * list only because its [ItemKindSpec] says so, and a new kind cannot be added without deciding every one of them (the registry is built from
 * an exhaustive `when`, and `ItemKindsTest` pins the membership of the four existing kinds).
 */
enum class KindList {
    /** HorizonSelector.datedScan: kinds whose open rows are scanned for a time they are bound to. */
    URGENCY_SCAN,
    /** HorizonSelector.openUnfiledItems: open rows, filed under no project, that make up the open baseline. */
    OPEN_UNFILED_SCAN,
    /** HorizonSelector.stateScan: kinds that carry `state`, scanned for waiting check-ins and active items. */
    STATE_SCAN,
    /** HorizonAssembler.alsoDueEntries: kinds that can be "also due" soon. */
    ALSO_DUE_SCAN,
    /** Kinds whose `status` lives on the item itself, so the assertion listing reads the item's status (the older copy on ASSERTS answers for the rest). */
    STATUS_ON_ITEM,
    /** HorizonStructureStore.listRecentItems(openOnly): the kinds an "open only" listing may return. */
    OPEN_ONLY_LISTING,
    /** Kinds that carry `state`, `status` and the time fields (HorizonStructureStore's former ITEM_STATE_TYPES). */
    CARRIES_STATE,
}

/**
 * Everything the Horizon needs to know about one [ItemKind], in one place.
 *
 * @property typeName the ArcadeDB vertex type
 * @property horizonLabel how the kind reads in the Horizon and in the Notion Kind select (`Fact`, `Task`, ...)
 * @property dueProperty the property holding the time the kind is bound to (`dueAt` for a task, `occursAt` for an event), or null
 * @property boundToMoment true when that time is a moment something happens (an event: it drops out after a grace period, and is never "overdue"), false when it is a deadline (a task)
 * @property lists the lists the kind is a member of
 */
data class ItemKindSpec(val kind: ItemKind, val typeName: String, val horizonLabel: String, val dueProperty: String?, val boundToMoment: Boolean, val lists: Set<KindList>) {
    /** The lowercase word the selector uses for the kind (`fact`, `task`, ...). */
    val word: String get() = horizonLabel.lowercase()
    val carriesState: Boolean get() = KindList.CARRIES_STATE in lists
    fun isIn(list: KindList): Boolean = list in lists
}

object ItemKinds {
    private val log = LoggerFactory.getLogger(ItemKinds::class.java)

    private fun spec(kind: ItemKind): ItemKindSpec = when (kind) {
        ItemKind.FACT -> ItemKindSpec(kind, kind.typeName, "Fact", null, false, emptySet())
        ItemKind.TASK -> ItemKindSpec(
            kind, kind.typeName, "Task", "dueAt", false,
            setOf(KindList.URGENCY_SCAN, KindList.OPEN_UNFILED_SCAN, KindList.STATE_SCAN, KindList.ALSO_DUE_SCAN, KindList.STATUS_ON_ITEM, KindList.OPEN_ONLY_LISTING, KindList.CARRIES_STATE),
        )
        ItemKind.EVENT -> ItemKindSpec(
            kind, kind.typeName, "Event", "occursAt", true,
            setOf(KindList.URGENCY_SCAN, KindList.STATE_SCAN, KindList.ALSO_DUE_SCAN, KindList.CARRIES_STATE),
        )
        ItemKind.QUESTION -> ItemKindSpec(
            kind, kind.typeName, "Question", null, false,
            setOf(KindList.OPEN_UNFILED_SCAN, KindList.STATE_SCAN, KindList.STATUS_ON_ITEM, KindList.OPEN_ONLY_LISTING, KindList.CARRIES_STATE),
        )
        // A goal is a task-shaped item (state, due date, evidence, staleness) with its own board section; it can be filed, focused and changed with the existing verbs.
        ItemKind.GOAL -> ItemKindSpec(
            kind, kind.typeName, "Goal", "dueAt", false,
            setOf(KindList.URGENCY_SCAN, KindList.OPEN_UNFILED_SCAN, KindList.STATE_SCAN, KindList.ALSO_DUE_SCAN, KindList.STATUS_ON_ITEM, KindList.OPEN_ONLY_LISTING, KindList.CARRIES_STATE),
        )
    }

    /** Every kind, in declaration order (the order the old hardcoded lists used). */
    val all: List<ItemKindSpec> = ItemKind.entries.map(::spec)

    private val byType: Map<String, ItemKindSpec> = all.associateBy { it.typeName }

    fun of(kind: ItemKind): ItemKindSpec = all.first { it.kind == kind }

    /** The members of [list], in registry order. */
    fun inList(list: KindList): List<ItemKindSpec> = all.filter { it.isIn(list) }

    fun typeNames(list: KindList): List<String> = inList(list).map { it.typeName }

    /** `['Task', 'Question']`, ready to splice into SQL (type names are constants of this registry, never user input). */
    fun sqlTypeList(list: KindList): String = typeNames(list).joinToString(", ", "[", "]") { "'$it'" }

    fun stateful(): List<ItemKindSpec> = inList(KindList.CARRIES_STATE)

    fun phraseTypeNames(): Set<String> = all.map { it.typeName }.toSet()

    fun isStateful(typeName: String?): Boolean = typeName != null && byType[typeName]?.carriesState == true

    /** The spec for a stored vertex type, or null when it is not an item kind this engine knows. Pure: never logs. */
    fun forType(typeName: String?): ItemKindSpec? = typeName?.let(byType::get)

    private val reported = ConcurrentHashMap.newKeySet<String>()

    /**
     * The kind a stored Phrase-family row reads as. A type the registry does not know (a Phrase subtype added without a registry entry) still reads as
     * a fact, so nothing breaks, but it is logged once per type name and the audit lists it (check A7). It is never silent.
     */
    fun kindOf(typeName: String?): ItemKind {
        forType(typeName)?.let { return it.kind }
        if (typeName != null && reported.add(typeName)) {
            log.warn("item-kinds: vertex type '{}' is not in the item kind registry; reading it as a fact. Add it to ItemKinds.", typeName)
        }
        return ItemKind.FACT
    }

    /** Label to kind, for the Notion Kind select; null for a label this engine has no use for (`Insight`). */
    fun fromLabel(label: String?): ItemKind? = all.firstOrNull { it.horizonLabel == label }?.kind
}
