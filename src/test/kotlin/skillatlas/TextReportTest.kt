package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `collapses multi-line descriptions and uses the singular for one skill`() {
        val result = ScanResult(repository, "main", sha, listOf(Skill("a", "Line one.\n\n  Line two.", "a")))

        val report = TextReport.render(result)

        assertTrue(report.contains("Found 1 skill:\n"), report)
        assertTrue(report.contains("    Line one. Line two.\n    a\n"), report)
    }

    @Test
    fun `shortens long descriptions at a word boundary`() {
        val long = "Stop and check this skill before finishing any reply to a question about how to use Claude or a Claude product."
        val result = ScanResult(repository, "main", sha, listOf(Skill("a", long, "a")))

        val report = TextReport.render(result)

        assertTrue(
            report.contains("    Stop and check this skill before finishing any reply to a question about how to use Claude or a…\n"),
            report,
        )
    }

    @Test
    fun `strips terminal control characters from repository content`() {
        val result = ScanResult(repository, "main", sha, listOf(Skill("evil\u001B[31m", "red\u0007", "x")))

        val report = TextReport.render(result)

        assertTrue('\u001B' !in report && '\u0007' !in report, report)
        assertTrue(report.contains("  evil[31m\n    red\n"), report)
    }

    @Test
    fun `shows copies, the product label and ignored test fixtures`() {
        val result = ScanResult(
            repository, "main", sha,
            listOf(
                Skill(
                    "mps-tests", "Write MPS tests.", ".agents/skills/mps-tests", listOf("duplicate name"),
                    alsoAt = listOf(".claude/skills/mps-tests", "plugins/p/resources/skills/mps-tests"),
                    shipped = true,
                ),
            ),
            listOf(IgnoredSkill("src/jvmTest/resources/skills/weather", "test data")),
        )

        assertEquals(
            """
            Repository:  anthropics/skills
            Description: Public repository for Agent Skills
            Commit:      $sha (main)

            Found 1 skill:

              mps-tests  [shipped in product]  [warning: duplicate name]
                Write MPS tests.
                .agents/skills/mps-tests
                also in .claude/skills/mps-tests
                also in plugins/p/resources/skills/mps-tests

            Ignored 1 test fixture (not skills):
              src/jvmTest/resources/skills/weather

            """.trimIndent(),
            TextReport.render(result),
        )
    }

    @Test
    fun `lists ignored fixtures even when no skills are found`() {
        val result = ScanResult(repository, "main", sha, emptyList(), listOf(IgnoredSkill("tests/a", "test data"), IgnoredSkill("tests/b", "test data")))

        assertTrue(TextReport.render(result).endsWith("No skills found.\n\nIgnored 2 test fixtures (not skills):\n  tests/a\n  tests/b\n"))
    }

    private val filterResult = ScanResult(
        repository, "main", sha,
        listOf(
            Skill("mps-run-configurations", "Create run configurations that run MPS tests.", "skills/run", shipped = true),
            Skill(
                "mps-aspect-typesystem",
                "Use when defining type rules, inference rules, checking rules, subtyping rules or quick fixes in a " +
                    "language, or when writing a WhenConcrete test statement that checks them against the expected types.",
                "skills/typesystem",
            ),
            Skill("pdf-extract", "Extract text from PDFs.", "skills/pdf"),
        ),
    )

    @Test
    fun `lists only the skills that match the filter`() {
        assertEquals(
            """
            Repository:  anthropics/skills
            Description: Public repository for Agent Skills
            Commit:      $sha (main)

            Found 3 skills, 2 match "test":

              mps-run-configurations  [shipped in product]
                Create run configurations that run MPS tests.
                skills/run

              mps-aspect-typesystem
                …or when writing a WhenConcrete test statement that checks them against the…
                skills/typesystem

            """.trimIndent(),
            TextReport.render(Presentation.SkillList(filterResult, "test")),
        )
    }

    @Test
    fun `says so when nothing matches the filter`() {
        assertTrue(
            TextReport.render(Presentation.SkillList(filterResult, "nothing here"))
                .endsWith("Found 3 skills, none match \"nothing here\".\n"),
        )
    }

    @Test
    fun `prints one skill with similar skills and its exact file`() {
        val file = "---\nname: mps-tests\ndescription: Write MPS tests.\n---\n\n# MPS tests\n\nRun them.\n"
        val skill = Skill("mps-tests", "Write MPS tests.", ".agents/skills/mps-tests", alsoAt = listOf(".claude/skills/mps-tests", "p/resources/mps-tests"), shipped = true)
        val result = ScanResult(repository, "main", sha, listOf(skill), contents = mapOf(skill.path to file))
        val detail = Presentation.SkillDetail(
            result, skill,
            listOf(SimilarSkill("skills/run", "mps-run-configurations", 62), SimilarSkill("skills/typesystem", "mps-typesystem", 7)),
        )

        assertEquals(
            """
            Repository:  anthropics/skills
            Description: Public repository for Agent Skills
            Commit:      $sha (main)

            Skill:       mps-tests  [shipped in product]
            Path:        .agents/skills/mps-tests
            Also in:     .claude/skills/mps-tests
                         p/resources/mps-tests
            GitHub:      https://github.com/anthropics/skills/blob/$sha/.agents/skills/mps-tests/SKILL.md

            Description:
              Write MPS tests.

            Similar skills:
              mps-run-configurations  ██████░░░░  62 %  skills/run
              mps-typesystem          █░░░░░░░░░   7 %  skills/typesystem

            SKILL.md:
              ---
              name: mps-tests
              description: Write MPS tests.
              ---

              # MPS tests

              Run them.

            """.trimIndent(),
            TextReport.render(detail),
        )
    }

    @Test
    fun `prints a skill without copies, similar skills or content`() {
        val skill = Skill("lonely", "", "skills/lonely", listOf("missing description"))
        val detail = Presentation.SkillDetail(ScanResult(repository, "main", sha, listOf(skill)), skill, emptyList())

        val report = TextReport.render(detail)

        assertTrue("Also in" !in report, report)
        assertTrue("Skill:       lonely  [warning: missing description]\n" in report, report)
        assertTrue("Description:\n  (no description)\n" in report, report)
        assertTrue("Similar skills:\n  No similar skills found.\n" in report, report)
        assertTrue(report.endsWith("SKILL.md:\n  (content not available)\n"), report)
    }
}
