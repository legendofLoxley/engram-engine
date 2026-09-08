package app.alfrd.engram.cognitive.pipeline.horizon

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Direct unit coverage for [BoundedText.of]'s truncation boundary, isolated from the graph/assembler
 * machinery. Found via empirical reproduction (a `jshell` session against plain `java.lang.String`,
 * whose UTF-16 semantics are identical to Kotlin's `String`): the original implementation truncated
 * with `String.take(maxLength)`, a raw UTF-16-code-unit cut. When `maxLength` code units land in the
 * middle of a surrogate pair (e.g. an emoji, which is 2 UTF-16 units per Unicode code point), the cut
 * leaves a lone high surrogate — not valid UTF-16 — which does not round-trip through UTF-8 encoding:
 * `String(original.toByteArray(UTF_8), UTF_8) != original`, the lone surrogate becomes a replacement
 * character on the way out. This is exactly the "multibyte characters" hazard the serialized-output
 * budget must cover, since [BoundedText] is what bounds every inlined text field in a [ContextHorizon].
 */
class HorizonModelTest {

    @Test
    fun `truncating at a surrogate-pair boundary does not split the pair, and the result round-trips through UTF-8`() {
        val maxLength = HorizonLimits.MAX_ITEM_TEXT_LENGTH
        // 279 ASCII chars + one emoji (a 2-code-unit surrogate pair straddling code units 279/280,
        // the exact truncation boundary for maxLength=280) + more trailing text past the cap.
        val fullText = "a".repeat(maxLength - 1) + "😀" + "trailing text past the cap"
        require(Character.isHighSurrogate(fullText[maxLength - 1])) { "test setup: emoji must straddle the cut point" }

        val bounded = BoundedText.of(fullText, maxLength)

        assertTrue(bounded.truncated)
        // No lone surrogate anywhere in the result.
        for (i in bounded.text.indices) {
            val c = bounded.text[i]
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < bounded.text.length && Character.isLowSurrogate(bounded.text[i + 1]), "lone high surrogate at index $i")
            }
            if (Character.isLowSurrogate(c)) {
                assertTrue(i > 0 && Character.isHighSurrogate(bounded.text[i - 1]), "lone low surrogate at index $i")
            }
        }
        // The real-world consequence: encoding to UTF-8 and decoding back must reproduce the exact
        // same string. This is what actually broke before the fix.
        val roundTripped = String(bounded.text.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        assertEquals(bounded.text, roundTripped, "truncated text must round-trip through UTF-8 encoding without corruption")
    }

    @Test
    fun `truncation still applies the maxLength cap when no surrogate pair is involved`() {
        val bounded = BoundedText.of("x".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 50), HorizonLimits.MAX_ITEM_TEXT_LENGTH)
        assertTrue(bounded.truncated)
        assertEquals(HorizonLimits.MAX_ITEM_TEXT_LENGTH + 1, bounded.text.length) // cap + ellipsis
    }

    @Test
    fun `text at or under the cap is never marked truncated`() {
        val exact = BoundedText.of("y".repeat(HorizonLimits.MAX_ITEM_TEXT_LENGTH), HorizonLimits.MAX_ITEM_TEXT_LENGTH)
        assertTrue(!exact.truncated)
        assertEquals(HorizonLimits.MAX_ITEM_TEXT_LENGTH, exact.text.length)
    }

    /**
     * The byte budget [ArcadeHorizonAssembler.assemble] enforces must scale with the caller's own
     * `budget.maxItems`, not stay pinned to [HorizonBudget.DEFAULT]'s — a caller requesting a larger
     * item budget needs a correspondingly larger ceiling, or a legitimate larger result would be
     * wrongly rejected/truncated as if it were oversized.
     */
    @Test
    fun `serializedByteBudget scales with maxItems rather than a fixed default`() {
        val defaultBudget = HorizonLimits.serializedByteBudget(HorizonBudget.DEFAULT.maxItems)
        val largerBudget = HorizonLimits.serializedByteBudget(HorizonBudget.DEFAULT.maxItems * 4)
        val smallerBudget = HorizonLimits.serializedByteBudget(1)

        assertEquals(HorizonLimits.MAX_SERIALIZED_HORIZON_BYTES, defaultBudget)
        assertTrue(largerBudget > defaultBudget, "a larger maxItems must get a larger byte ceiling")
        assertTrue(smallerBudget < defaultBudget, "a smaller maxItems must get a smaller byte ceiling")
    }
}
