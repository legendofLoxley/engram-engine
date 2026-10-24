package app.alfrd.engram.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What a name says about itself, by rule: kind, the name without designators (for linking only), and short forms a page defines. */
class EntityRulesTest {

    @Test
    fun `a name's own shape gives its kind, before any model label is trusted`() {
        assertEquals("school", EntityRules.kindByName("Beaumont MS"))
        assertEquals("school", EntityRules.kindByName("Jefferson HS"))
        assertEquals("school", EntityRules.kindByName("Clark Elementary"))
        assertEquals("school", EntityRules.kindByName("Beaumont Middle School"))
        assertEquals("school", EntityRules.kindByName("Faubion K–8"))
        assertEquals("school", EntityRules.kindByName("Beverly Cleary 6–8"), "a grade band after a name is that school's program, still a school")
        assertEquals("scenario", EntityRules.kindByName("SCENARIO A"))
        assertEquals("scenario", EntityRules.kindByName("Status quo"))
        assertNull(EntityRules.kindByName("Clark"), "a bare name says nothing about its kind")
        assertNull(EntityRules.kindByName("Portland Public Schools"))
    }

    @Test
    fun `a grade band is not a school`() {
        listOf("K–5", "K-5", "6–8", "9–12", "K–8", "K–8 and middle school", "K-5 students").forEach {
            assertEquals("grade band", EntityRules.kindByName(it), it)
            assertEquals("grade band", EntityRules.kindFor(it, "school"), "the model's label does not override a rule: $it")
        }
        assertEquals("school", EntityRules.kindFor("Clark", "school"), "with no rule, the model's label is used")
        assertEquals("other", EntityRules.kindFor("Clark", "pizza"), "...mapped onto the fixed kinds")
    }

    @Test
    fun `the loose name drops designators for linking only`() {
        assertEquals("clark", EntityRules.looseName("Clark Elementary"))
        assertEquals("clark", EntityRules.looseName("Clark"))
        assertEquals("beaumont", EntityRules.looseName("Beaumont MS"))
        assertEquals("beaumont", EntityRules.looseName("Beaumont Middle School"))
        assertEquals("beverly cleary", EntityRules.looseName("Beverly Cleary 6–8"))
        assertEquals("beverly cleary", EntityRules.looseName("Beverly Cleary"))
        assertEquals("elementary", EntityRules.looseName("Elementary"), "a name that is only a designator is left alone")
        assertEquals("mlk jr.", EntityRules.looseName("MLK Jr."))
    }

    @Test
    fun `a page that defines a short form gives an alias only when the letters are the initials`() {
        assertEquals(listOf("Portland Public Schools" to "PPS"), EntityRules.definedAliases("Portland Public Schools (PPS) has more buildings than enrollment supports."))
        assertEquals(listOf("Portland Public Schools" to "PPS"), EntityRules.definedAliases("This report is for Portland Public Schools (PPS)."), "the words before the name are not part of it")
        assertEquals(listOf("Save Portland Schools" to "SPS"), EntityRules.definedAliases("Members of Save Portland Schools (SPS) spoke."))
        assertEquals(listOf("Portland Public Schools" to "PPS"), EntityRules.definedAliases("Portland Public Schools’ (PPS) Scenario A closes 11 schools."), "a possessive is not part of the name")
        assertEquals(listOf("Portland Public Schools" to "PPS"), EntityRules.definedAliases("Portland Public Schools' (PPS) plan."))
        assertEquals(listOf("Board of Education" to "BOE"), EntityRules.definedAliases("The Board of Education (BOE) voted."), "small words do not count as initials")
        assertEquals(emptyList<Pair<String, String>>(), EntityRules.definedAliases("The report (DRAFT) is long and Clark Elementary (see page 4) is closed."), "letters that are not initials define nothing")
        assertEquals(emptyList<Pair<String, String>>(), EntityRules.definedAliases("Lunch (FREE) is served."))
    }
}
