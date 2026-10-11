package app.alfrd.engram.notion

import app.alfrd.engram.cognitive.pipeline.horizon.AttentionDirective
import app.alfrd.engram.cognitive.pipeline.horizon.ContainerKind
import app.alfrd.engram.cognitive.pipeline.horizon.FocusZoom
import app.alfrd.engram.cognitive.pipeline.horizon.ItemKind

/**
 * Property names and select options of the alfrd memory template (schema v0.2 plus the columns the
 * adapter needs: Items `Op key`, `Focus`, `Zoom`; Sessions `Key`; Containers `Tentative`, `Focus`,
 * `Zoom`). Everything the adapter reads or writes is named here, so a template change is one edit.
 *
 * `Override` means **attention** (pin or suppress) on Containers and Items alike. **Focus** (what
 * the person is working on) is the separate `Focus` checkbox plus `Zoom`, on a Container or an Item.
 */
object NotionSchema {

    object Containers {
        const val NAME = "Name"
        const val KIND = "Kind"
        const val PARENT = "Parent"
        const val STATUS = "Status"
        const val DUE = "Due"
        const val OVERRIDE = "Override"
        const val FOCUS = "Focus"
        const val ZOOM = "Zoom"
        const val TENTATIVE = "Tentative"
        const val SOURCE = "Source"
    }

    object Items {
        const val ITEM = "Item"
        const val KIND = "Kind"
        const val CONTAINER = "Container"
        const val ABOUT = "About"
        const val SESSION = "Session"
        const val SAID = "Said"
        const val DATE = "Date"
        const val SOURCE = "Source"
        const val TENTATIVE = "Tentative"
        const val OVERRIDE = "Override"
        const val STATUS = "Status"
        const val SUPERSEDES = "Supersedes"
        const val SUPERSEDED_BY = "Superseded by"
        const val RELATED = "Related"
        const val OP_KEY = "Op key"
        const val FOCUS = "Focus"
        const val ZOOM = "Zoom"
    }

    object Sessions {
        const val SESSION = "Session"
        const val SOURCE = "Source"
        const val DATE = "Date"
        const val CHANNEL = "Channel"
        const val KEY = "Key"
    }

    object Entities {
        const val NAME = "Name"
        const val SOURCE = "Source"
        const val TENTATIVE = "Tentative"
    }

    // ── Select options ───────────────────────────────────────────────────────

    const val SOURCE_PERSON_SAID = "Person said"
    const val SOURCE_ALFRD_INFERRED = "alfrd inferred"
    const val SOURCE_ALFRD_ASKED = "alfrd asked"

    const val STATUS_OPEN = "Open"
    const val STATUS_RESOLVED = "Resolved"
    const val STATUS_SUPERSEDED = "Superseded"

    const val CONTAINER_ACTIVE = "Active"
    const val CONTAINER_PAUSED = "Paused"
    const val CONTAINER_DONE = "Done"

    const val OVERRIDE_PINNED = "Pinned"
    const val OVERRIDE_SUPPRESSED = "Suppressed"

    const val SESSION_SOURCE_ALFRD = "alfrd"
    const val CHANNEL_TEXT = "Text"

    fun kindLabel(kind: ContainerKind): String = when (kind) {
        ContainerKind.DOMAIN -> "Domain"
        ContainerKind.PROJECT -> "Project"
    }

    fun containerKind(label: String?): ContainerKind? = ContainerKind.entries.firstOrNull { kindLabel(it) == label }

    fun kindLabel(kind: ItemKind): String = when (kind) {
        ItemKind.FACT -> "Fact"
        ItemKind.TASK -> "Task"
        ItemKind.EVENT -> "Event"
        ItemKind.QUESTION -> "Question"
    }

    /** Null for `Insight`, or anything else this engine has no use for; such rows are not part of the Horizon. */
    fun itemKind(label: String?): ItemKind? = ItemKind.entries.firstOrNull { kindLabel(it) == label }

    fun attentionLabel(attention: AttentionDirective?): String? = when (attention) {
        AttentionDirective.PINNED -> OVERRIDE_PINNED
        AttentionDirective.SUPPRESSED -> OVERRIDE_SUPPRESSED
        null -> null
    }

    fun attention(label: String?): AttentionDirective? = when (label) {
        OVERRIDE_PINNED -> AttentionDirective.PINNED
        OVERRIDE_SUPPRESSED -> AttentionDirective.SUPPRESSED
        else -> null
    }

    fun zoomLabel(zoom: FocusZoom): String = when (zoom) {
        FocusZoom.OVERVIEW -> "Zoomed out"
        FocusZoom.BINOCULARS -> "Binoculars"
        FocusZoom.TELESCOPE -> "Telescope"
    }

    fun zoom(label: String?): FocusZoom? = FocusZoom.entries.firstOrNull { zoomLabel(it) == label }
}
