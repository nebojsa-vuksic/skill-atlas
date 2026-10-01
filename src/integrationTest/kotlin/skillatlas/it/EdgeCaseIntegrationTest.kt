package skillatlas.it

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * The edge cases from spec section 4.4, each modeled on the real repository where it was
 * found, run through the installed CLI and the web view.
 */
class EdgeCaseIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    private fun skill(name: String, description: String) = "---\nname: $name\ndescription: $description\n---\n\n# $name\n"

    /** Like JetBrains/MPS: every skill in `.agents` and `.claude`, some also shipped in a plugin. */
    private fun createMpsLikeRepository(): String {
        sandbox.api.repository("acme/mps", "Language workbench")
        val tests = skill("mps-tests", "Use when writing or modifying tests inside MPS models.")
        val generator = skill("mps-aspect-generator", "Use when defining or modifying MPS generators.")
        val commits = skill("commits", "How to write commit messages in this repository.")
        return sandbox.createRepository(
            "acme/mps",
            mapOf(
                ".agents/skills/mps-tests/SKILL.md" to tests,
                ".claude/skills/mps-tests/SKILL.md" to tests,
                ".agents/skills/mps-aspect-generator/SKILL.md" to generator,
                ".claude/skills/mps-aspect-generator/SKILL.md" to generator,
                "plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-aspect-generator/SKILL.md" to generator,
                ".agents/skills/commits/SKILL.md" to commits,
                ".claude/skills/commits/SKILL.md" to commits,
            ),
        )
    }

    @Test
    fun `merges identical copies in agents and claude and labels product copies`() {
        val commit = createMpsLikeRepository()

        val run = sandbox.run("scan", "https://github.com/acme/mps")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            """
            Repository:  acme/mps
            Description: Language workbench
            Commit:      $commit (main)

            Found 3 skills:

              commits
                How to write commit messages in this repository.
                .agents/skills/commits
                also in .claude/skills/commits

              mps-aspect-generator  [shipped in product]
                Use when defining or modifying MPS generators.
                .agents/skills/mps-aspect-generator
                also in .claude/skills/mps-aspect-generator
                also in plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-aspect-generator

              mps-tests
                Use when writing or modifying tests inside MPS models.
                .agents/skills/mps-tests
                also in .claude/skills/mps-tests

            """.trimIndent(),
            run.stdout,
        )
        assertTrue(""""skill_count":3""" in sandbox.scanLogLines().single(), sandbox.scanLogLines().toString())
    }

    @Test
    fun `lists skills from an unusual folder`() {
        sandbox.api.repository("acme/android", "Android Studio")
        val commit = sandbox.createRepository(
            "acme/android",
            mapOf(
                "agent/skills/writing-lint-checks/SKILL.md" to
                    skill("writing-lint-checks", "Helps to write a new lint check under `/tools/base/lint/libs/lint-checks`."),
                "tools/base/lint/README.md" to "Lint.\n",
            ),
        )

        val run = sandbox.run("scan", "https://github.com/acme/android")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            """
            Repository:  acme/android
            Description: Android Studio
            Commit:      $commit (main)

            Found 1 skill:

              writing-lint-checks
                Helps to write a new lint check under `/tools/base/lint/libs/lint-checks`.
                agent/skills/writing-lint-checks

            """.trimIndent(),
            run.stdout,
        )
    }

    /** Like JetBrains/koog: real skills in `.claude`, fixtures for the project's own tests. */
    private fun createKoogLikeRepository(): String {
        sandbox.api.repository("acme/koog", "Agent framework")
        return sandbox.createRepository(
            "acme/koog",
            mapOf(
                ".claude/skills/split-jvm-nonjvm/SKILL.md" to
                    skill("split-jvm-nonjvm", "Splits a class in commonMain into JVM and non-JVM parts."),
                "integration-tests/src/jvmTest/resources/skills/weather-retrieval/SKILL.md" to
                    skill("weather-retrieval", "Test fixture: retrieves the weather."),
                "integration-tests/src/jvmTest/resources/skills/arithmetic-evaluator/SKILL.md" to
                    skill("arithmetic-evaluator", "Test fixture: evaluates arithmetic."),
            ),
        )
    }

    @Test
    fun `ignores test fixtures`() {
        val commit = createKoogLikeRepository()

        val run = sandbox.run("scan", "https://github.com/acme/koog")

        assertEquals(0, run.exitCode, run.toString())
        assertEquals(
            """
            Repository:  acme/koog
            Description: Agent framework
            Commit:      $commit (main)

            Found 1 skill:

              split-jvm-nonjvm
                Splits a class in commonMain into JVM and non-JVM parts.
                .claude/skills/split-jvm-nonjvm

            Ignored 2 test fixtures (not skills):
              integration-tests/src/jvmTest/resources/skills/arithmetic-evaluator
              integration-tests/src/jvmTest/resources/skills/weather-retrieval

            """.trimIndent(),
            run.stdout,
        )
        assertTrue(""""skill_count":1""" in sandbox.scanLogLines().single(), sandbox.scanLogLines().toString())
    }

    @Test
    fun `flags different skills with the same name`() {
        sandbox.api.repository("acme/names", null)
        sandbox.createRepository(
            "acme/names",
            mapOf(
                ".agents/skills/review/SKILL.md" to skill("review", "Review pull requests."),
                ".claude/skills/review/SKILL.md" to skill("review", "Review documents."),
            ),
        )

        val run = sandbox.run("scan", "https://github.com/acme/names")

        assertEquals(0, run.exitCode, run.toString())
        assertTrue(
            run.stdout.endsWith(
                """
                Found 2 skills:

                  review  [warning: duplicate name]
                    Review pull requests.
                    .agents/skills/review

                  review  [warning: duplicate name]
                    Review documents.
                    .claude/skills/review

                """.trimIndent(),
            ),
            run.stdout,
        )
    }

    @Test
    fun `shows copies, product label and ignored fixtures in the terminal view`() {
        createMpsLikeRepository()

        val run = sandbox.runInTerminal("scan", "https://github.com/acme/mps")

        assertEquals(0, run.exitCode, run.toString())
        val screen = run.stdout.replace(Regex("""\u001B\[[0-?]*[ -/]*[@-~]|\u001B\][^\u0007\u001B]*(\u0007|\u001B\\)"""), "").replace("\r", "")
        val expected = listOf(
            " ● mps-aspect-generator  ◆ shipped in product",
            "   Use when defining or modifying MPS generators.",
            "   .agents/skills/mps-aspect-generator",
            "   also in .claude/skills/mps-aspect-generator",
            "   also in plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-aspect-generator",
        ).joinToString("\n")
        assertTrue(expected in screen.lines().joinToString("\n") { it.trimEnd() }, screen)
    }

    @Test
    fun `reports copies, product label and ignored fixtures through the web API`() {
        createKoogLikeRepository()
        sandbox.startWebView().use { web ->
            val response = web.get("/api/scan?url=github.com/acme/koog")

            assertEquals(200, response.status, response.toString())
            assertTrue(
                response.body.endsWith(
                    """"skills":[{"name":"split-jvm-nonjvm",""" +
                        """"description":"Splits a class in commonMain into JVM and non-JVM parts.",""" +
                        """"short_description":"Splits a class in commonMain into JVM and non-JVM parts.",""" +
                        """"path":".claude/skills/split-jvm-nonjvm","also_at":[],"shipped":false,"starred":false,"warnings":[],""" +
                        """"content":"---\nname: split-jvm-nonjvm\ndescription: Splits a class in commonMain into JVM and non-JVM parts.\n---\n\n# split-jvm-nonjvm\n",""" +
                        """"content_html":"<h1>split-jvm-nonjvm</h1>\n","similar":[]}],""" +
                        """"ignored":[""" +
                        """{"path":"integration-tests/src/jvmTest/resources/skills/arithmetic-evaluator","reason":"test data"},""" +
                        """{"path":"integration-tests/src/jvmTest/resources/skills/weather-retrieval","reason":"test data"}]}""",
                ),
                response.body,
            )
        }

        createMpsLikeRepository()
        sandbox.startWebView().use { web ->
            val body = web.get("/api/scan?url=github.com/acme/mps").body
            assertTrue(
                """"path":".agents/skills/mps-aspect-generator",""" +
                    """"also_at":[".claude/skills/mps-aspect-generator",""" +
                    """"plugins/mcp-tools/resources/jetbrains/mps/agents/mcp/skills/mps-aspect-generator"],""" +
                    """"shipped":true""" in body,
                body,
            )
        }
    }
}
