package app.alfrd.engram.cognitive.pipeline

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DateHintsTest {

    @Test
    fun `dates and times people actually write are noticed`() {
        listOf(
            "My sister's birthday is on the 22nd.",
            "The 3rd of October is her party",
            "Dinner on March 3rd",
            "Flight leaves Oct 14",
            "We land 14 Oct",
            "I have the dentist on Friday",
            "Call them tomorrow",
            "Tonight I need to pack",
            "The report is due next week",
            "Back in 3 days",
            "Deadline is 9/22",
            "This weekend we are hiking",
        ).forEach { assertTrue(DateHints.hasDate(it), "should notice a date in: $it") }
    }

    @Test
    fun `ordinary talk is not mistaken for a date`() {
        listOf(
            "Hi there!",
            "I may go to the shop",
            "My dog is called Newton",
            "I need to call the plumber",
            "What is the tallest mountain?",
            "I have 3 cats",
            "Sunny weather makes me happy",
        ).forEach { assertFalse(DateHints.hasDate(it), "should not see a date in: $it") }
    }
}
