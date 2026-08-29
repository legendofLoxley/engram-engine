package app.alfrd.engram.cognitive.pipeline.confidence

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TopicResolverTest {

    @Test
    fun `picks the longest keyword from an utterance`() {
        assertEquals("newton", TopicResolver.resolve("my dog's name is Newton"))
    }

    @Test
    fun `returns null for a trivial greeting`() {
        assertNull(TopicResolver.resolve("Hey"))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(TopicResolver.resolve(""))
        assertNull(TopicResolver.resolve("   "))
    }

    @Test
    fun `is case-insensitive`() {
        assertEquals("kotlin", TopicResolver.resolve("I love KOTLIN"))
    }
}
