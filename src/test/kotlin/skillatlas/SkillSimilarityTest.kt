package skillatlas

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

class SkillSimilarityTest {
    private fun file(name: String, description: String, body: String) =
        "---\nname: $name\ndescription: $description\n---\n\n$body"

    /** Skills with the given descriptions and no files, each at `skills/<name>`. */
    private fun skills(vararg descriptions: Pair<String, String>) =
        descriptions.map { (name, description) -> Skill(name, description, "skills/$name") }

    private val fixtures = listOf(
        Triple("commits", "How to write good commit messages.", "# Commits\n\nWrite the subject in the imperative mood.\n"),
        Triple("docx", "Edit Word documents and extract their text.", "# Word\n\nUnzip the document, then edit the XML.\n"),
        Triple(
            "mps-aspect-typesystem", "Use when defining typesystem rules for MPS languages.",
            "# Typesystem\n\nWrite inference rules and checking rules.\n",
        ),
        Triple(
            "mps-run-configurations", "Create run configurations that run MPS tests.",
            "# Run configurations\n\nPick the test module, then run it.\n",
        ),
        Triple(
            "mps-tests", "Use when writing or modifying tests for MPS languages.",
            "# MPS tests\n\nRun the tests from the test module with Gradle.\n",
        ),
        Triple("pdf-extract", "Extract text and tables from PDF files.", "# PDF\n\nUse pdftotext, then check every table.\n"),
    )
    private val fixtureSkills = fixtures.map { (name, description) -> Skill(name, description, "skills/$name") }
    private val fixtureContents = fixtures.associate { (name, description, body) -> "skills/$name" to file(name, description, body) }

    @Test
    fun `splits into lowercase words and drops stopwords, short words and digits`() {
        assertEquals(
            listOf("mps", "test", "run", "gradle", "v2x", "file", "test"),
            SkillSimilarity.words("Use MPS-tests when you run them with Gradle 42 2026 v2x x files, and the Tests!"),
        )
    }

    @Test
    fun `removes a trailing s only from longer words that do not end in ss`() {
        assertEquals(
            listOf("test", "tool", "class", "access", "docs", "pdfs", "file"),
            SkillSimilarity.words("tests tools class access docs pdfs files"),
        )
    }

    @Test
    fun `gives exact scores in order of similarity`() {
        assertEquals(
            mapOf(
                "skills/commits" to emptyList(),
                "skills/docx" to listOf(SimilarSkill("skills/pdf-extract", "pdf-extract", 17)),
                "skills/mps-aspect-typesystem" to listOf(
                    SimilarSkill("skills/mps-tests", "mps-tests", 22),
                    SimilarSkill("skills/mps-run-configurations", "mps-run-configurations", 12),
                ),
                "skills/mps-run-configurations" to listOf(
                    SimilarSkill("skills/mps-tests", "mps-tests", 41),
                    SimilarSkill("skills/mps-aspect-typesystem", "mps-aspect-typesystem", 12),
                ),
                "skills/mps-tests" to listOf(
                    SimilarSkill("skills/mps-run-configurations", "mps-run-configurations", 41),
                    SimilarSkill("skills/mps-aspect-typesystem", "mps-aspect-typesystem", 22),
                ),
                "skills/pdf-extract" to listOf(SimilarSkill("skills/docx", "docx", 17)),
            ),
            SkillSimilarity.compute(fixtureSkills, fixtureContents),
        )
    }

    @Test
    fun `lists at most five similar skills`() {
        val filler = listOf("kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango", "uniform", "victor")
        val words = listOf("alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf")
        val skills = listOf(Skill("hub", words.joinToString(" "), "skills/hub")) +
            words.mapIndexed { i, word -> Skill(word, "About $word and ${filler.take(2 * i).joinToString(" ")}", "skills/$word") }

        assertEquals(
            listOf(
                SimilarSkill("skills/alpha", "alpha", 31),
                SimilarSkill("skills/bravo", "bravo", 30),
                SimilarSkill("skills/charlie", "charlie", 28),
                SimilarSkill("skills/delta", "delta", 26),
                SimilarSkill("skills/echo", "echo", 24),
            ),
            SkillSimilarity.compute(skills, emptyMap()).getValue("skills/hub"),
        )
    }

    @Test
    fun `drops skills below a similarity of 0_05`() {
        val first = listOf("kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango")
        val second = listOf("amber", "coral", "ivory", "jade", "lemon", "mango", "olive", "peach", "ruby", "tulip")
        fun pair(unique: Int) = SkillSimilarity.compute(
            skills(
                "a1" to "shared ${first.take(unique).joinToString(" ")}",
                "b1" to "shared ${second.take(unique).joinToString(" ")}",
            ),
            emptyMap(),
        )

        // One shared word among 9 unique ones is a similarity of 0.053; among 10 it is 0.048.
        assertEquals(listOf(SimilarSkill("skills/b1", "b1", 5)), pair(9).getValue("skills/a1"))
        assertEquals(emptyList(), pair(10).getValue("skills/a1"))
    }

    @Test
    fun `breaks ties by path`() {
        val skills = skills("z1" to "Render charts.", "b1" to "Render charts.", "a1" to "Render charts.")

        assertEquals(
            listOf(SimilarSkill("skills/b1", "b1", 100), SimilarSkill("skills/z1", "z1", 100)),
            SkillSimilarity.compute(skills, emptyMap()).getValue("skills/a1"),
        )
    }

    @Test
    fun `finds nothing for skills without words`() {
        val skills = skills("ab" to "", "cd" to "The and of 42.")

        assertEquals(mapOf("skills/ab" to emptyList(), "skills/cd" to emptyList()), SkillSimilarity.compute(skills, emptyMap()))
        assertEquals(emptyMap(), SkillSimilarity.compute(emptyList(), emptyMap()))
    }

    @Test
    fun `reads only the first 20,000 characters of the body`() {
        val skills = skills("a1" to "", "b1" to "")
        fun similar(padding: Int) = SkillSimilarity.compute(
            skills,
            mapOf(
                "skills/a1" to file("a1", "", " ".repeat(padding) + "typesystem"),
                "skills/b1" to file("b1", "", "typesystem"),
            ),
        ).getValue("skills/a1")

        assertEquals(listOf(SimilarSkill("skills/b1", "b1", 100)), similar(SkillSimilarity.MAX_BODY_LENGTH - 10))
        assertEquals(emptyList(), similar(SkillSimilarity.MAX_BODY_LENGTH - 9))
    }

    @Test
    fun `gives identical output on every run`() {
        val first = SkillSimilarity.compute(fixtureSkills, fixtureContents)
        repeat(5) {
            assertEquals(first, SkillSimilarity.compute(fixtureSkills, fixtureContents))
            assertEquals(first, SkillSimilarity.compute(fixtureSkills.reversed(), fixtureContents))
        }
    }

    @Test
    fun `handles 500 skills in under a second`() {
        val random = Random(42)
        val vocabulary = List(5_000) { i -> "word" + i.toString(36) }
        val skills = List(500) { i ->
            Skill("skill-$i", List(20) { vocabulary[random.nextInt(vocabulary.size)] }.joinToString(" "), "skills/skill-$i")
        }
        val contents = skills.associate { skill ->
            val body = generateSequence { vocabulary[random.nextInt(vocabulary.size)] }.take(3_000).joinToString(" ")
            skill.path to file(skill.name, skill.description, body)
        }

        val (similar, duration) = measureTimedValue { SkillSimilarity.compute(skills, contents) }

        assertEquals(500, similar.size)
        assertTrue(similar.values.all { it.size <= SkillSimilarity.MAX_SIMILAR })
        assertTrue(duration < 1.seconds, "took $duration")
    }
}
