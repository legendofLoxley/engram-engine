package app.alfrd.engram.cognitive.pipeline.affect

/**
 * Session-level, slow-moving tone layer — per Alfrd Voice: Cognitive Architecture's "Affect:
 * Layered Emotional Intelligence" (v2: Mood). Governs conversational tone/warmth; deliberately
 * separate from [app.alfrd.engram.cognitive.pipeline.confidence.TopicConfidenceService] —
 * confidence governs what alfrd is epistemically willing to assume, mood governs how it sounds
 * saying it. Neither reads from nor writes to the other.
 */
enum class Mood { GUARDED, CAREFUL, NEUTRAL, WARM, PLAYFUL }

/**
 * @param overrideActive True once the user has explicitly set [mood] via direct instruction
 *   ("stop being so formal"). While true, automatic drift is suppressed — the explicit
 *   instruction stays in effect for the rest of the session until changed again.
 */
data class MoodState(val mood: Mood = Mood.NEUTRAL, val overrideActive: Boolean = false)
