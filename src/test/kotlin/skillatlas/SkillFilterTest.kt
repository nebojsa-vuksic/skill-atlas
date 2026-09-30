package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Kotlin filter must behave exactly like the web view's (spec sections 5.5 and 5.7).
 * Expected values below were produced by running the functions in `web/app.js` on the same inputs.
 */
class SkillFilterTest {
    private val long = "Use when defining type rules, inference rules, checking rules, subtyping rules or quick fixes in a " +
        "language, or when writing a WhenConcrete test statement that checks them against the expected types."
    private val typesystem = Skill("mps-aspect-typesystem", long, "skills/typesystem")

    @Test
    fun `splits the query into lowercase words`() {
        assertEquals(listOf("test", "run"), SkillFilter.words("  Test  RUN "))
        assertEquals(emptyList(), SkillFilter.words("   "))
    }

    @Test
    fun `matches when every word is in the name or the full description`() {
        assertTrue(SkillFilter.matches(typesystem, listOf("typesystem", "whenconcrete")))
        assertTrue(SkillFilter.matches(typesystem, listOf("EXPECTED".lowercase())))
        assertFalse(SkillFilter.matches(typesystem, listOf("typesystem", "pdf")))
        assertTrue(SkillFilter.matches(typesystem, emptyList()))
    }

    @Test
    fun `keeps the original order`() {
        val skills = listOf(Skill("b-test", "", "b"), Skill("a", "", "a"), Skill("c-test", "", "c"))
        assertEquals(listOf("b-test", "c-test"), SkillFilter.filter(skills, listOf("test")).map { it.name })
    }

    @Test
    fun `merges overlapping and adjacent match ranges like the web view`() {
        assertEquals(listOf(0..6, 8..11), SkillFilter.matchRanges("testing tests", listOf("test", "sting")))
        assertEquals(listOf(0..2, 8..10), SkillFilter.matchRanges("PDF and pdf", listOf("pdf")))
        assertEquals(emptyList(), SkillFilter.matchRanges("anything", emptyList()))
    }

    @Test
    fun `uses the same description lines and snippets as the web view`() {
        val short = "Use when defining type rules, inference rules, checking rules, subtyping rules or quick fixes in a…"
        val cases = mapOf(
            listOf("test") to "…or when writing a WhenConcrete test statement that checks them against the…",
            listOf("rules") to short,
            listOf("expected", "type") to "…statement that checks them against the expected types.",
            listOf("zzz") to short,
            listOf("whenconcrete") to "…fixes in a language, or when writing a WhenConcrete test statement that checks them against…",
        )
        for ((words, expected) in cases) assertEquals(expected, SkillFilter.descriptionLine(typesystem, words), words.toString())
    }

    @Test
    fun `has no description line without a description`() {
        assertNull(SkillFilter.descriptionLine(Skill("a", "", "a"), listOf("a")))
    }

    @Test
    fun `leaves a short text whole in a snippet`() {
        assertEquals("short text with a match here", SkillFilter.snippet("short text with a match here", 18, 23))
    }

    @Test
    fun `separates repo qualifiers from words`() {
        assertEquals(SkillFilter.Query(listOf("test"), listOf("mps")), SkillFilter.parse("repo:MPS test"))
        assertEquals(SkillFilter.Query(emptyList(), listOf("mps", "koog")), SkillFilter.parse("repo:mps repo:koog"))
        assertEquals(SkillFilter.Query(listOf("test"), emptyList()), SkillFilter.parse("repo: test"), "an empty qualifier is ignored")
        assertEquals(listOf("test"), SkillFilter.words("repo:mps test"))
    }

    @Test
    fun `matches repositories by any qualifier, ignoring case`() {
        assertTrue(SkillFilter.parse("test").matchesRepository("JetBrains/MPS"))
        assertTrue(SkillFilter.parse("repo:mps").matchesRepository("JetBrains/MPS"))
        assertTrue(SkillFilter.parse("repo:koog repo:mps").matchesRepository("JetBrains/MPS"))
        assertTrue(SkillFilter.parse("repo:jetbrains/").matchesRepository("JetBrains/MPS"))
        assertFalse(SkillFilter.parse("repo:koog").matchesRepository("JetBrains/MPS"))
    }
}
