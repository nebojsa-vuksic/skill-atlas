package skillatlas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GitHubUrlTest {
    private val expected = RepoCoordinates("owner", "repo")

    @Test
    fun `accepts every URL form from the spec`() {
        val forms = listOf(
            "https://github.com/owner/repo",
            "https://github.com/owner/repo/",
            "https://github.com/owner/repo.git",
            "http://github.com/owner/repo",
            "github.com/owner/repo",
            "git@github.com:owner/repo.git",
        )
        for (form in forms) assertEquals(expected, GitHubUrl.parse(form), form)
    }

    @Test
    fun `tolerates surrounding whitespace, host case and www`() {
        assertEquals(expected, GitHubUrl.parse("  HTTPS://GitHub.com/owner/repo  "))
        assertEquals(expected, GitHubUrl.parse("https://www.github.com/owner/repo"))
        assertEquals(expected, GitHubUrl.parse("git@github.com:owner/repo"))
    }

    @Test
    fun `keeps dots, dashes and underscores in names`() {
        assertEquals(RepoCoordinates("my-org", "my_repo.js"), GitHubUrl.parse("https://github.com/my-org/my_repo.js.git"))
    }

    @Test
    fun `names an owner with every owner URL form from the spec`() {
        val forms = listOf(
            "https://github.com/owner",
            "https://github.com/owner/",
            "http://github.com/owner",
            "github.com/owner",
            "  https://www.GitHub.com/owner  ",
            "https://github.com/orgs/owner",
            "github.com/orgs/owner/",
            "https://github.com/orgs/owner/repositories",
        )
        for (form in forms) assertEquals(ScanTarget.Owner("owner"), GitHubUrl.target(form), form)
        assertEquals(ScanTarget.Repository(expected), GitHubUrl.target("git@github.com:owner/repo.git"))
    }

    @Test
    fun `parse still rejects owner URLs, and orgs is never a repository owner`() {
        for (input in listOf("https://github.com/owner", "github.com/orgs/owner", "github.com/orgs/owner/repositories", "github.com/orgs")) {
            assertFailsWith<InvalidUrlException>(input) { GitHubUrl.parse(input) }
        }
        for (input in listOf("github.com/orgs", "github.com/orgs/owner/settings", "git@github.com:owner", "https://github.com/ow ner")) {
            assertFailsWith<InvalidUrlException>(input) { GitHubUrl.target(input) }
        }
    }

    @Test
    fun `rejects anything else with a usage error`() {
        val invalid = listOf(
            "",
            "not a url",
            "https://gitlab.com/owner/repo",
            "git@gitlab.com:owner/repo.git",
            "https://github.com.evil.com/owner/repo",
            "https://evil.com/github.com/owner/repo",
            "https://github.com/",
            "https://github.com/owner",
            "https://github.com/owner/repo/tree/main",
            "https://github.com//repo",
            "https://github.com/owner/repo?tab=readme",
            "https://github.com/owner/.git",
            "https://github.com/../repo",
        )
        for (input in invalid) {
            val error = assertFailsWith<InvalidUrlException>(input) { GitHubUrl.parse(input) }
            assertEquals(ExitCode.USAGE, error.exitCode)
            assertEquals("not a GitHub repository URL: $input", error.message)
        }
    }
}
