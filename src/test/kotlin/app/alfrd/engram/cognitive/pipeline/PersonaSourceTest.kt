package app.alfrd.engram.cognitive.pipeline

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultPersonaSource] — the retrieved conditioner source for persona and
 * self-description. Both invariants from the task's acceptance criteria are asserted directly:
 * the self-description must never deny cross-session memory the system actually has, and must
 * never claim a modality it isn't running on.
 */
class PersonaSourceTest {

    private val source = DefaultPersonaSource()

    // ── persona reuses the existing modality-correct identity prompts ─────────

    @Test
    fun `voice persona equals the existing voice identity prompt`() {
        assertEquals(VOICE_IDENTITY_SYSTEM_PROMPT, source.describe(Modality.VOICE).persona)
    }

    @Test
    fun `text persona equals the existing text identity prompt`() {
        assertEquals(TEXT_IDENTITY_SYSTEM_PROMPT, source.describe(Modality.TEXT).persona)
    }

    // ── self-description never denies memory the system has ──────────────────

    // "no memory of past conversations" legitimately appears as part of the forbidding clause
    // ("never claim to... have no memory of past conversations") — so check for an affirmative
    // denial ("you have no memory" / "don't remember"), not a blind substring, mirroring how
    // IdentitySystemPromptTest checks TEXT_IDENTITY_SYSTEM_PROMPT's negated "can hear" clause.

    @Test
    fun `voice self-description asserts persistent cross-session memory`() {
        val desc = source.describe(Modality.VOICE).selfDescription.lowercase()
        assertTrue(desc.contains("persistent memory") || desc.contains("memory of this user"), "got: $desc")
        assertFalse(desc.contains("you have no memory"), "must not deny memory it has, got: $desc")
        assertFalse(desc.contains("don't remember") || desc.contains("do not remember"), "got: $desc")
    }

    @Test
    fun `text self-description asserts persistent cross-session memory`() {
        val desc = source.describe(Modality.TEXT).selfDescription.lowercase()
        assertTrue(desc.contains("persistent memory") || desc.contains("memory of this user"), "got: $desc")
        assertFalse(desc.contains("you have no memory"), "must not deny memory it has, got: $desc")
    }

    // ── self-description never claims the wrong modality ──────────────────────

    @Test
    fun `voice self-description claims it can hear and never claims text-only`() {
        val desc = source.describe(Modality.VOICE).selfDescription.lowercase()
        assertTrue(desc.contains("can hear"), "got: $desc")
        assertFalse(desc.contains("cannot hear"), "got: $desc")
    }

    @Test
    fun `text self-description claims it cannot hear or speak aloud`() {
        val desc = source.describe(Modality.TEXT).selfDescription.lowercase()
        assertTrue(desc.contains("cannot hear or speak"), "got: $desc")
        assertFalse(desc.contains("you can hear"), "must not claim it can hear on text, got: $desc")
    }

    // ── source is genuinely per-modality, not a single hardcoded string ───────

    @Test
    fun `self-description differs by modality`() {
        val voice = source.describe(Modality.VOICE).selfDescription
        val text = source.describe(Modality.TEXT).selfDescription
        assertNotEquals(voice, text)
    }
}
