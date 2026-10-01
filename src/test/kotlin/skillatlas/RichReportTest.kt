package skillatlas

import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RichReportTest {
    private val sha = "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39"
    private val repository = RepositoryMetadata("anthropics/skills", "Public repository for Agent Skills", "main")

    private fun render(result: ScanResult, columns: Int = 120): String = render(Presentation.SkillList(result), columns)

    private fun render(presentation: Presentation, columns: Int = 120): String = runBlocking {
        var snapshot = ""
        runMosaicTest { snapshot = setContentAndSnapshot { Report(presentation, columns) } }
        snapshot.lines().joinToString("\n") { it.trimEnd() }
    }

    @Test
    fun `renders the report layout from the spec`() {
        val result = ScanResult(
            repository, "main", sha,
            listOf(
                Skill(
                    "pdf-extract",
                    "Extract text and tables from PDF files. Use when the user asks to read or parse a PDF.",
                    "skills/pdf-extract",
                ),
                Skill("brand-guidelines", "Apply company brand colors and typography to documents.", "skills/brand-guidelines"),
                Skill("csv-tools", "", "skills/csv-tools", listOf("missing description")),
            ),
        )

        // The title's inverted block has its own leading space, inside the one-column margin.
        assertEquals(
            listOf(
                "  SKILL ATLAS",
                "",
                " Repository   anthropics/skills",
                " Description  Public repository for Agent Skills",
                " Commit       $sha  main",
                "",
                " 3 skills found",
                "",
                " ● pdf-extract",
                "   Extract text and tables from PDF files. Use when the user asks to read or parse a PDF.",
                "   skills/pdf-extract",
                "",
                " ● brand-guidelines",
                "   Apply company brand colors and typography to documents.",
                "   skills/brand-guidelines",
                "",
                " ● csv-tools  ⚠ missing description",
                "   (no description)",
                "   skills/csv-tools",
            ).joinToString("\n"),
            render(result),
        )
    }

    @Test
    fun `reports no skills and a missing description`() {
        val result = ScanResult(repository.copy(description = null), "main", sha, emptyList())

        val lines = render(result).lines()

        assertEquals(" Description  (none)", lines[3])
        assertEquals(" No skills found.", lines[6])
    }

    @Test
    fun `fits descriptions to the terminal width`() {
        val long = "Stop and check this skill before finishing any reply to a question about how to use Claude or a Claude product."
        val result = ScanResult(repository, "main", sha, listOf(Skill("a", long, "a")))

        val lines = render(result, columns = 50).lines()

        assertEquals("   Stop and check this skill before finishing…", lines[9])
        assertEquals(true, lines.all { it.length < 50 || it.contains(sha) })
    }

    @Test
    fun `shows the filter count and the matching skills`() {
        val result = ScanResult(
            repository, "main", sha,
            listOf(Skill("mps-tests", "Write MPS tests.", "skills/mps-tests"), Skill("pdf", "Extract PDF text.", "skills/pdf")),
        )

        val lines = render(Presentation.SkillList(result, "test")).lines()

        assertEquals(" 1 of 2 skills match \"test\"", lines[6])
        assertEquals(" ● mps-tests", lines[8])
        assertEquals("   Write MPS tests.", lines[9])
        assertEquals(false, lines.any { "pdf" in it })
    }

    @Test
    fun `shows one skill with similar skills and its file`() {
        val skill = Skill("mps-tests", "Write MPS tests.", "skills/mps-tests", alsoAt = listOf(".claude/skills/mps-tests"))
        val result = ScanResult(repository, "main", sha, listOf(skill), contents = mapOf(skill.path to "---\nname: mps-tests\n---\n"))
        val detail = Presentation.SkillDetail(result, skill, listOf(SimilarSkill("skills/run", "mps-run", 62)))

        assertEquals(
            listOf(
                " Skill        mps-tests",
                " Path         skills/mps-tests",
                " Also in      .claude/skills/mps-tests",
                " GitHub       https://github.com/anthropics/skills/blob/$sha/skills/mps-tests/SKILL.md",
                "",
                " Description",
                "   Write MPS tests.",
                "",
                " Similar skills",
                "   mps-run  ██████░░░░  62 %  skills/run",
                "",
                " SKILL.md",
                "   ---",
                "   name: mps-tests",
                "   ---",
            ),
            render(detail).lines().drop(6),
        )
    }

    @Test
    fun `draws the browse frame exactly as the screen lays it out`() = runBlocking {
        val skills = listOf(Skill("mps-tests", "Write MPS tests.", "skills/mps-tests"), Skill("pdf", "Extract PDF text.", "skills/pdf"))
        val state = BrowseState(
            ScanResult(repository, "main", sha, skills, contents = mapOf("skills/pdf" to "# PDF\n")),
            mapOf("skills/mps-tests" to listOf(SimilarSkill("skills/pdf", "pdf", 12)), "skills/pdf" to emptyList()),
        )
        var snapshot = ""
        runMosaicTest {
            snapshot = setContentAndSnapshot { Browser(state) }
            val size = this.state.size.value
            val expected = BrowseScreen.render(state, size.columns, size.rows - 1).lines.map { it.text.trimEnd() }
            assertEquals(expected, snapshot.lines().map { it.trimEnd() }.take(expected.size))
        }
        assertEquals(true, " SKILL ATLAS   anthropics/skills  3f2a9c1e8b7d main" in snapshot)
    }

    @Test
    fun `tags starred skills and shows star changes and the star list`() {
        val result = ScanResult(repository, "main", sha, listOf(Skill("pdf", "Read PDF files.", "skills/pdf", starred = true)))

        assertTrue(" ● pdf  ★ starred" in render(result), render(result))
        val skill = result.skills.single()
        assertEquals(" ★ Starred pdf (anthropics/skills:skills/pdf).", render(Presentation.StarChanged(result, skill, starred = true, changed = true)))
        assertEquals(" ☆ pdf isn't starred (anthropics/skills:skills/pdf).", render(Presentation.StarChanged(result, skill, starred = false, changed = false)))
        assertEquals(
            listOf(" 2 starred skills", "", " ★ mps-tests  JetBrains/MPS:.agents/skills/mps-tests", " ★ pdf        anthropics/skills:skills/pdf"),
            render(Presentation.StarList(listOf(Star("JetBrains/MPS", ".agents/skills/mps-tests", "mps-tests"), Star("anthropics/skills", "skills/pdf", "pdf")))).lines(),
        )
        assertEquals(" No starred skills yet.", render(Presentation.StarList(emptyList())))
    }
}
