package app.alfrd.engram.cognitive.pipeline.agentwork

import app.alfrd.engram.notion.NotionApi
import app.alfrd.engram.notion.NotionApiException
import app.alfrd.engram.notion.NotionBlockRead
import app.alfrd.engram.notion.NotionGuardViolation
import app.alfrd.engram.notion.normalizeNotionId

/**
 * How much page content one cycle may read and stage. Every limit is in characters, because the
 * model's context is what is being protected; each one that bites is reported in the trace, not hidden.
 */
data class ContentLimits(
    /** The most text one page contributes. */
    val perPageChars: Int = 6_000,

    /** The most page text one task is given in total. */
    val perTaskChars: Int = 14_000,

    /** The most page text read fresh from Notion in one cycle. A page already read is reused at no cost. */
    val perCycleChars: Int = 60_000,

    /** The most top-level blocks read from one page. */
    val maxBlocksPerPage: Int = 300,

    /** The most children read under one block (one level only). */
    val maxChildrenPerBlock: Int = 60,
) {
    init {
        require(perPageChars > 0 && perTaskChars > 0 && perCycleChars > 0 && maxBlocksPerPage > 0 && maxChildrenPerBlock > 0) { "limits must be positive" }
    }
}

/** What reading one page's body gave. [text] is already within the per-page limit. */
data class PageContent(
    val text: String,
    /** True when a limit cut the page short (characters, or too many blocks). */
    val truncated: Boolean,
    val blocksRead: Int,
    /** Blocks of a type that carries no readable text here (images, code, embeds), or nested deeper than one level. */
    val blocksSkipped: Int,
)

/**
 * Reads the text of a page's blocks through the guarded client: paragraphs, headings, bulleted and
 * numbered lists, to-dos, callouts, quotes and toggles, with **one level of children** under any of them.
 * Anything else (an image, a code block, a table, a child page) is counted as skipped, not guessed at, and
 * nothing deeper than one level is read.
 *
 * Read-only by construction: it calls `listBlocks` and nothing else. It does not decide whether a page may
 * be read; that is [AllowedScope]'s job above it and the guard's job below it.
 */
class PageContentReader(private val api: NotionApi, private val limits: ContentLimits = ContentLimits()) {

    suspend fun read(pageId: String, maxChars: Int = limits.perPageChars): PageContent {
        val id = normalizeNotionId(pageId) ?: return PageContent("", truncated = false, blocksRead = 0, blocksSkipped = 0)
        val out = StringBuilder()
        var blocksRead = 0
        var skipped = 0
        var truncated = false
        var cursor: String? = null

        fun full(): Boolean = out.length > maxChars

        pages@ while (true) {
            val page = api.listBlocks(id, cursor, NotionApi.MAX_PAGE_SIZE)
            var number = 0
            for (block in page.blocks) {
                if (blocksRead >= limits.maxBlocksPerPage) { truncated = true; break@pages }
                number = if (block.type == "numbered_list_item") number + 1 else 0
                val line = render(block, number)
                if (line == null) {
                    // An empty text block is not worth a mention; a block of a type read here, nothing else, is counted.
                    if (block.type !in SUPPORTED) skipped++
                    continue
                }
                out.append(line).append('\n')
                blocksRead++
                if (block.hasChildren && block.type in NESTABLE) {
                    val children = try {
                        api.listBlocks(block.id, null, minOf(limits.maxChildrenPerBlock, NotionApi.MAX_PAGE_SIZE))
                    } catch (_: NotionApiException) {
                        skipped++
                        null
                    } catch (_: NotionGuardViolation) {
                        skipped++
                        null
                    }
                    if (children != null) {
                        var childNumber = 0
                        for (child in children.blocks.take(limits.maxChildrenPerBlock)) {
                            childNumber = if (child.type == "numbered_list_item") childNumber + 1 else 0
                            val childLine = render(child, childNumber)
                            if (childLine == null) { skipped++; continue }
                            out.append("  ").append(childLine).append('\n')
                            blocksRead++
                            if (child.hasChildren) skipped++ // a second level is not read
                        }
                        if (children.nextCursor != null || children.blocks.size > limits.maxChildrenPerBlock) truncated = true
                    }
                }
                if (full()) { truncated = true; break@pages }
            }
            cursor = page.nextCursor ?: break
        }

        var text = out.toString().trimEnd()
        if (text.length > maxChars) {
            val cut = text.lastIndexOf('\n', maxChars).let { if (it > maxChars / 2) it else maxChars }
            text = text.substring(0, cut).trimEnd()
            truncated = true
        }
        if (truncated) text += "\n[… the rest of this page was not read: size limit]"
        return PageContent(text, truncated, blocksRead, skipped)
    }

    private fun render(block: NotionBlockRead, number: Int): String? {
        val text = block.plainText.trim()
        if (text.isEmpty()) return null
        return when (block.type) {
            "paragraph" -> text
            "heading_1" -> "# $text"
            "heading_2" -> "## $text"
            "heading_3" -> "### $text"
            "bulleted_list_item" -> "- $text"
            "numbered_list_item" -> "$number. $text"
            "to_do" -> "[${if (block.checked == true) "x" else " "}] $text"
            "callout" -> "> (callout) $text"
            "quote" -> "> $text"
            "toggle" -> "▸ $text"
            else -> null
        }
    }

    private companion object {
        /** Block types whose text is read, and whose one level of children is read too. */
        val NESTABLE = setOf("paragraph", "bulleted_list_item", "numbered_list_item", "to_do", "callout", "quote", "toggle", "heading_1", "heading_2", "heading_3")
        val SUPPORTED = NESTABLE
    }
}
