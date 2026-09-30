package skillatlas

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class SkillCatalogTest {
    @TempDir
    lateinit var root: Path

    private fun write(directory: String, content: String) {
        root.resolve(directory).createDirectories().resolve("SKILL.md").writeText(content)
    }

    private fun catalog() = SkillCatalog.build(root, SkillScanner.discover(root), rootName = "repo")

    private fun skill(name: String, description: String = "About $name.") =
        "---\nname: $name\ndescription: $description\n---\n"

    @Test
    fun `classifies locations by their parent folders`() {
        val cases = mapOf(
            ".agents/skills/mps-tests" to SkillLocation.AGENT_CONFIG,
            ".claude/skills/tests" to SkillLocation.AGENT_CONFIG,
            "agent/skills/writing-lint-checks" to SkillLocation.REPOSITORY,
            "skills/pdf" to SkillLocation.REPOSITORY,
            "." to SkillLocation.REPOSITORY,
            "plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-aspect-generator" to SkillLocation.PRODUCT,
            "app/src/main/resources/skills/x" to SkillLocation.PRODUCT,
            "integration-tests/src/jvmTest/resources/skills/weather-retrieval" to SkillLocation.TEST_DATA,
            "src/test/resources/skills/x" to SkillLocation.TEST_DATA,
            "lib/src/testFixtures/skills/x" to SkillLocation.TEST_DATA,
            "tests/skills/x" to SkillLocation.TEST_DATA,
            "docs/Fixtures/x" to SkillLocation.TEST_DATA,
            "web/__tests__/x" to SkillLocation.TEST_DATA,
            "testdata/x" to SkillLocation.TEST_DATA,
            "integration-tests/skills/x" to SkillLocation.REPOSITORY,
            "srcTest/x" to SkillLocation.REPOSITORY,
        )
        for ((directory, expected) in cases) assertEquals(expected, SkillLocation.of(directory), directory)
    }

    @Test
    fun `merges identical copies with the agent folder as the main path`() {
        val content = skill("mps-tests")
        write(".claude/skills/mps-tests", content)
        write(".agents/skills/mps-tests", content)
        write("plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-tests", content)

        assertEquals(
            Catalog(
                listOf(
                    Skill(
                        "mps-tests", "About mps-tests.", ".agents/skills/mps-tests",
                        alsoAt = listOf(".claude/skills/mps-tests", "plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-tests"),
                        shipped = true,
                    ),
                ),
                ignored = emptyList(),
            ),
            catalog(),
        )
    }

    @Test
    fun `prefers repository folders over product copies`() {
        write("agent/skills/lint", skill("lint"))
        write("plugin/src/main/resources/skills/lint", skill("lint"))

        assertEquals(
            listOf(Skill("lint", "About lint.", "agent/skills/lint", alsoAt = listOf("plugin/src/main/resources/skills/lint"), shipped = true)),
            catalog().skills,
        )
    }

    @Test
    fun `keeps a skill that only ships with the product`() {
        write("plugins/tools/resources/skills/generator", skill("generator"))

        assertEquals(
            listOf(Skill("generator", "About generator.", "plugins/tools/resources/skills/generator", shipped = true)),
            catalog().skills,
        )
    }

    @Test
    fun `ignores test data even when it is identical to a real skill`() {
        write(".claude/skills/weather", skill("weather"))
        write("integration-tests/src/jvmTest/resources/skills/weather", skill("weather"))
        write("integration-tests/src/jvmTest/resources/skills/arithmetic", skill("arithmetic"))

        assertEquals(
            Catalog(
                listOf(Skill("weather", "About weather.", ".claude/skills/weather")),
                listOf(
                    IgnoredSkill("integration-tests/src/jvmTest/resources/skills/arithmetic", SkillCatalog.TEST_DATA),
                    IgnoredSkill("integration-tests/src/jvmTest/resources/skills/weather", SkillCatalog.TEST_DATA),
                ),
            ),
            catalog(),
        )
    }

    @Test
    fun `flags different skills that share a name`() {
        write(".agents/skills/review", skill("review", "Review pull requests."))
        write(".claude/skills/review", skill("review", "Review documents."))

        assertEquals(
            listOf(
                Skill("review", "Review pull requests.", ".agents/skills/review", listOf(SkillCatalog.DUPLICATE_NAME)),
                Skill("review", "Review documents.", ".claude/skills/review", listOf(SkillCatalog.DUPLICATE_NAME)),
            ),
            catalog().skills,
        )
    }

    @Test
    fun `does not merge copies that differ by a single byte`() {
        write(".agents/skills/a", skill("a"))
        write(".claude/skills/a", skill("a") + "\n")

        assertEquals(2, catalog().skills.size)
    }

    @Test
    fun `never merges files that are too large to read`() {
        val large = skill("big") + "x".repeat(SkillParser.MAX_FILE_SIZE.toInt())
        write(".agents/skills/big", large)
        write(".claude/skills/big", large)

        assertEquals(listOf(".agents/skills/big", ".claude/skills/big"), catalog().skills.map { it.path })
    }
}
