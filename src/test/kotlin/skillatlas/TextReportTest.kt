package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals

class TextReportTest {
    private val sha = "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39"
    private val repository = RepositoryMetadata("anthropics/skills", "Public repository for Agent Skills", "main")

    @Test
    fun `renders the report from the spec`() {
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

        assertEquals(
            """
            Repository:  anthropics/skills
            Description: Public repository for Agent Skills
            Commit:      $sha (main)

            Found 3 skills:

              pdf-extract
                Extract text and tables from PDF files. Use when the user asks to read or parse a PDF.
                skills/pdf-extract

              brand-guidelines
                Apply company brand colors and typography to documents.
                skills/brand-guidelines

              csv-tools  [warning: missing description]
                (no description)
                skills/csv-tools

            """.trimIndent(),
            TextReport.render(result),
        )
    }

    @Test
    fun `reports when no skills are found and the repository has no description`() {
        val result = ScanResult(repository.copy(description = null), "master", sha, emptyList())

        assertEquals(
            """
            Repository:  anthropics/skills
            Description: (none)
            Commit:      $sha (master)

            No skills found.

            """.trimIndent(),
            TextReport.render(result),
        )
    }

    @Test
    fun `indents multi-line descriptions and uses the singular for one skill`() {
        val result = ScanResult(repository, "main", sha, listOf(Skill("a", "Line one.\nLine two.", "a")))

        val report = TextReport.render(result)

        assertEquals(true, report.contains("Found 1 skill:\n"))
        assertEquals(true, report.contains("    Line one.\n    Line two.\n    a\n"))
    }

    @Test
    fun `strips terminal control characters from repository content`() {
        val result = ScanResult(repository, "main", sha, listOf(Skill("evil\u001B[31m", "red\u0007", "x")))

        val report = TextReport.render(result)

        assertEquals(false, report.contains('\u001B'))
        assertEquals(false, report.contains('\u0007'))
        assertEquals(true, report.contains("  evil[31m\n    red\n"))
    }
}
