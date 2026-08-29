package app.alfrd.engram.cognitive.pipeline.confidence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AffirmationClassifierTest {

    @Test
    fun `recognizes common affirmation phrases`() {
        assertTrue(AffirmationClassifier.isAffirmation("You nailed it!"))
        assertTrue(AffirmationClassifier.isAffirmation("That's exactly right"))
        assertTrue(AffirmationClassifier.isAffirmation("spot on"))
        assertTrue(AffirmationClassifier.isAffirmation("Yes, exactly, thanks"))
    }

    @Test
    fun `does not flag ordinary utterances as affirmation`() {
        assertFalse(AffirmationClassifier.isAffirmation("What time is it?"))
        assertFalse(AffirmationClassifier.isAffirmation("Remind me to call the vet"))
        assertFalse(AffirmationClassifier.isAffirmation("Actually, no I meant the dentist"))
    }

    @Test
    fun `is case-insensitive`() {
        assertTrue(AffirmationClassifier.isAffirmation("YOU NAILED IT"))
    }
}
