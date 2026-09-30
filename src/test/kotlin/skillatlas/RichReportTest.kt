package skillatlas

import com.jakewharton.mosaic.testing.runMosaicTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class RichReportTest {
    private val sha = "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39"
    private val repository = RepositoryMetadata("anthropics/skills", "Public repository for Agent Skills", "main")

    private fun render(result: ScanResult, columns: Int = 120): String = runBlocking {
        var snapshot = ""
        runMosaicTest { snapshot = setContentAndSnapshot { Report(result, columns) } }
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
}
