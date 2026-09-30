package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PresentationTest {
    private val result = ScanResult(
        RepositoryMetadata("acme/skills", "Acme", "main"),
        "main",
        "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39",
        listOf(
            Skill("review", "Review pull requests.", ".agents/skills/review", listOf("duplicate name")),
            Skill("review", "Review documents.", ".claude/skills/review", listOf("duplicate name")),
            Skill("mps-tests", "Write MPS tests.", ".agents/skills/mps-tests", alsoAt = listOf(".claude/skills/mps-tests")),
        ),
    )

    @Test
    fun `finds a skill by path, by a copy's path, and by name ignoring case`() {
        assertEquals(".agents/skills/mps-tests", Presentation.detail(result, ".agents/skills/mps-tests").skill.path)
        assertEquals(".agents/skills/mps-tests", Presentation.detail(result, ".claude/skills/mps-tests/").skill.path)
        assertEquals(".agents/skills/mps-tests", Presentation.detail(result, "MPS-Tests").skill.path)
        assertEquals(".claude/skills/review", Presentation.detail(result, ".claude/skills/review").skill.path)
    }

    @Test
    fun `reports an unknown skill with exit code 6`() {
        val error = assertFailsWith<SkillNotFoundException> { Presentation.detail(result, "pdf") }
        assertEquals(ExitCode.SKILL_NOT_FOUND, error.exitCode)
        assertEquals("no skill 'pdf' in acme/skills", error.message)
    }

    @Test
    fun `reports an ambiguous name with exit code 2`() {
        val error = assertFailsWith<AmbiguousSkillException> { Presentation.detail(result, "review") }
        assertEquals(ExitCode.USAGE, error.exitCode)
        assertEquals(
            "skill name 'review' matches 2 skills: .agents/skills/review, .claude/skills/review; pass a path instead",
            error.message,
        )
    }

    @Test
    fun `links to the skill file on GitHub at the scanned commit`() {
        assertEquals(
            "https://github.com/acme/skills/blob/3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39/.agents/skills/mps-tests/SKILL.md",
            Presentation.detail(result, "mps-tests").githubUrl,
        )
        assertEquals("https://github.com/acme/skills/blob/3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39/SKILL.md", githubFileUrl(result, "."))
    }

    @Test
    fun `draws ten-cell similarity bars`() {
        assertEquals("░░░░░░░░░░", similarityBar(0))
        assertEquals("░░░░░░░░░░", similarityBar(4))
        assertEquals("█░░░░░░░░░", similarityBar(5))
        assertEquals("████░░░░░░", similarityBar(42))
        assertEquals("██████░░░░", similarityBar(62))
        assertEquals("██████████", similarityBar(100))
    }
}
