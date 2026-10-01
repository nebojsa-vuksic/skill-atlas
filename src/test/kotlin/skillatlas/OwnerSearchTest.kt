package skillatlas

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Finding an owner's repositories with skill files through the GitHub API (spec section 5.12, steps 1 to 4). */
class OwnerSearchTest {
    private lateinit var api: HttpServer
    private val responses = Collections.synchronizedMap(mutableMapOf<String, Pair<Int, String>>())
    private val requests = Collections.synchronizedList(mutableListOf<String>())

    @BeforeTest
    fun setUp() {
        api = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        api.createContext("/") { exchange ->
            val path = exchange.requestURI.rawPath + (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            requests += path
            val (status, body) = responses[path] ?: (404 to """{"message":"Not Found"}""")
            if (status == 403) exchange.responseHeaders.add("x-ratelimit-remaining", "0")
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        api.start()
    }

    @AfterTest
    fun tearDown() = api.stop(0)

    private fun search() = OwnerSearch(GitHubClient(apiBaseUrl = "http://127.0.0.1:${api.address.port}"))

    private fun repository(fullName: String, branch: String = "main", fork: Boolean = false, archived: Boolean = false) =
        """{"full_name":"$fullName","description":null,"default_branch":"$branch","fork":$fork,"archived":$archived}"""

    private fun organization(login: String, vararg pages: List<String>) {
        responses["/users/$login"] = 200 to """{"login":"$login","type":"Organization"}"""
        pages.forEachIndexed { i, page ->
            responses["/orgs/$login/repos?type=all&sort=full_name&per_page=100&page=${i + 1}"] = 200 to page.joinToString(",", "[", "]")
        }
    }

    private fun tree(fullName: String, vararg paths: String, branch: String = "main", truncated: Boolean = false) {
        val entries = paths.joinToString(",") { """{"path":"$it","type":"${if (it.endsWith("/")) "tree" else "blob"}"}""" }
        responses["/repos/$fullName/git/trees/$branch?recursive=1"] = 200 to """{"tree":[$entries],"truncated":$truncated}"""
    }

    @Test
    fun `keeps repositories whose tree may hold skills, sorted, and skips forks and archived ones`() {
        organization(
            "acme",
            listOf(
                repository("acme/zeta"), repository("acme/Alpha"), repository("acme/fork", fork = true),
                repository("acme/old", archived = true), repository("acme/docs"), repository("acme/empty"),
                repository("acme/huge"), repository("acme/broken"), repository("acme/lower", branch = "release/1.0"),
                repository("acme/dir"),
            ),
        )
        tree("acme/zeta", "README.md", "skills/z/SKILL.md")
        tree("acme/Alpha", "SKILL.md")
        tree("acme/docs", "README.md", "docs/skill.md", "NOT-SKILL.md")
        responses["/repos/acme/empty/git/trees/main?recursive=1"] = 409 to """{"message":"Git Repository is empty."}"""
        tree("acme/huge", "README.md", truncated = true)
        responses["/repos/acme/broken/git/trees/main?recursive=1"] = 500 to "oops"
        tree("acme/lower", "a/SKILL.md", branch = "release%2F1.0")
        tree("acme/dir", "SKILL.md/")

        val progress = mutableListOf<String>()
        val discovery = search().discover("acme", progress::add)

        assertEquals(
            listOf("acme/Alpha", "acme/broken", "acme/huge", "acme/lower", "acme/zeta"),
            discovery.candidates.map { it.fullName },
            "SKILL.md files, a truncated tree and an unexpected failure are scanned; no SKILL.md, an empty repository and a directory named SKILL.md aren't",
        )
        assertEquals(8, discovery.checked)
        assertEquals(2, discovery.skipped)
        assertEquals("Organization", discovery.owner.type)
        assertEquals(listOf("Listing repositories of acme...", "Checking 8 repositories of acme for skill files..."), progress)
        assertTrue(requests.none { "/acme/fork/" in it || "/acme/old/" in it }, requests.toString())
    }

    @Test
    fun `lists page by page until a page comes back short`() {
        organization("many", (1..100).map { repository("many/r$it", archived = true) }, listOf(repository("many/last")))
        tree("many/last", "SKILL.md")

        val discovery = search().discover("many")

        assertEquals(listOf("many/last"), discovery.candidates.map { it.fullName })
        assertEquals(1 to 100, discovery.checked to discovery.skipped)
        assertEquals(2, requests.count { it.startsWith("/orgs/many/repos?") })
    }

    @Test
    fun `lists a user's own repositories`() {
        responses["/users/jane"] = 200 to """{"login":"Jane","type":"User"}"""
        responses["/users/Jane/repos?type=owner&sort=full_name&per_page=100&page=1"] = 200 to "[${repository("Jane/notes")}]"
        tree("Jane/notes", "SKILL.md")

        val progress = mutableListOf<String>()
        val discovery = search().discover("jane", progress::add)

        assertEquals(listOf("Jane/notes"), discovery.candidates.map { it.fullName })
        assertEquals("Checking 1 repository of Jane for skill files...", progress.last(), "the login as GitHub spells it")
    }

    @Test
    fun `an unknown owner, and a rate limit while checking, fail the owner`() {
        val unknown = assertFailsWith<OwnerNotFoundException> { search().discover("nobody") }
        assertEquals("organization or user nobody not found", unknown.message)
        assertEquals(ExitCode.REPOSITORY_NOT_FOUND, unknown.exitCode)

        organization("limited", listOf(repository("limited/a"), repository("limited/b")))
        tree("limited/b", "SKILL.md")
        responses["/repos/limited/a/git/trees/main?recursive=1"] = 403 to """{"message":"API rate limit exceeded"}"""
        val limited = assertFailsWith<RateLimitException> { search().discover("limited") }
        assertEquals(ExitCode.NETWORK, limited.exitCode)
    }

    @Test
    fun `an owner without repositories has nothing to check`() {
        organization("void", emptyList())

        val discovery = search().discover("void")

        assertEquals(emptyList(), discovery.candidates)
        assertEquals(0 to 0, discovery.checked to discovery.skipped)
    }
}
