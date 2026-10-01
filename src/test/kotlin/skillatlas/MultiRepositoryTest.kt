package skillatlas

import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Several repositories in one scan (spec section 5.10). */
class MultiRepositoryTest {
    private val sha = "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39"

    private fun result(name: String, vararg skills: Skill, contents: Map<String, String> = emptyMap()) =
        ScanResult(RepositoryMetadata(name, "About $name", "main"), "main", sha, skills.toList(), contents = contents)

    private val one = result(
        "acme/one",
        Skill("pdf-extract", "Extract text and tables from PDF files.", "skills/pdf"),
        Skill("commits", "How to write commit messages.", "skills/commits"),
    )
    private val two = result(
        "acme/two",
        Skill("pdf-forms", "Fill PDF forms and extract fields from PDF files.", "skills/pdf"),
        Skill("commits", "Commit message rules for this team.", "skills/team-commits"),
    )

    private fun scanner() = MultiScanner(Scanner(GitHubClient(), Git()))

    @Test
    fun `keeps URL order, scans a repository once, and isolates failures`() {
        val scanned = Collections.synchronizedList(mutableListOf<String>())
        val outcomes = scanner().scanAllWith(
            listOf("github.com/acme/one", "https://gitlab.com/x/y", "https://github.com/acme/two.git", "git@github.com:acme/one.git"),
        ) { url, _ ->
            scanned += url
            when (GitHubUrl.parse(url).toString()) {
                "acme/one" -> one
                else -> throw RepositoryNotFoundException(GitHubUrl.parse(url))
            }
        }.repositories

        assertEquals(listOf("acme/one", "https://gitlab.com/x/y", "acme/two"), outcomes.map { it.label })
        assertIs<RepositoryOutcome.Scanned>(outcomes[0])
        assertEquals(ExitCode.USAGE, (outcomes[1] as RepositoryOutcome.Failed).error.exitCode)
        assertEquals(ExitCode.REPOSITORY_NOT_FOUND, (outcomes[2] as RepositoryOutcome.Failed).error.exitCode)
        assertEquals(3, scanned.size, "the duplicate acme/one URL is not scanned again")
    }

    @Test
    fun `scans up to four repositories at the same time`() {
        val started = CountDownLatch(4)
        val release = CountDownLatch(1)
        val urls = (1..5).map { "github.com/acme/r$it" }
        val thread = Thread {
            scanner().scanAllWith(urls) { url, _ ->
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                result(GitHubUrl.parse(url).toString())
            }
        }
        thread.start()
        assertTrue(started.await(10, TimeUnit.SECONDS), "four scans should run at once")
        release.countDown()
        thread.join(10_000)
    }

    @Test
    fun `turns unexpected exceptions into failures with exit code 1`() {
        val outcome = scanner().scanAllWith(listOf("github.com/acme/one")) { _, _ -> error("boom") }.repositories.single()

        assertEquals("unexpected failure: boom", (outcome as RepositoryOutcome.Failed).error.message)
        assertEquals(ExitCode.INTERNAL_ERROR, outcome.error.exitCode)
    }

    private val acme = OwnerMetadata("acme", "Organization")

    private fun listed(name: String) = RepositoryMetadata(name, "About $name", "main")

    @Test
    fun `expands an owner in place, scans each repository once, and orders failures by URL`() {
        val discovered = Collections.synchronizedList(mutableListOf<String>())
        val scanned = Collections.synchronizedList(mutableListOf<Pair<String, RepositoryMetadata?>>())
        val scan = scanner().scanAllWith(
            listOf("github.com/acme/two", "https://github.com/acme", "github.com/orgs/ACME", "github.com/nobody"),
            discover = { login ->
                discovered += login
                if (login == "nobody") throw OwnerNotFoundException(login)
                OwnerDiscovery(acme, listOf(listed("acme/broken"), listed("acme/fixtures"), listed("acme/one"), listed("acme/two")), checked = 5, skipped = 1)
            },
        ) { url, metadata ->
            scanned += url to metadata
            when (val name = metadata?.fullName ?: GitHubUrl.parse(url).toString()) {
                "acme/one" -> one
                "acme/two" -> two
                "acme/fixtures" -> result("acme/fixtures")
                else -> throw BranchNotFoundException("main", RepoCoordinates("acme", name.substringAfter('/')))
            }
        }

        assertEquals(listOf("acme", "nobody"), discovered, "the same owner in another form is searched once")
        assertEquals(listOf("acme/two", "acme/broken", "acme/fixtures", "acme/one"), scan.repositories.map { it.label })
        assertEquals(listOf(null, "https://github.com/acme", "https://github.com/acme", "https://github.com/acme"), scan.repositories.map { it.from })
        assertEquals(4, scanned.size, "acme/two was given directly first, so the owner doesn't scan it again")
        assertEquals("https://github.com/acme/one" to listed("acme/one"), scanned.single { it.first.endsWith("/one") })
        assertEquals(listOf("acme/two", "acme/broken", "acme/one"), scan.reported.map { it.label }, "an owner's repository without skills isn't reported")
        assertEquals(4, scan.scanned.size + 1, "but it was scanned; only the broken one wasn't")

        val searched = scan.owners.first() as OwnerOutcome.Searched
        assertEquals(listOf("acme/one", "acme/two"), searched.withSkills, "acme/two counts for the owner too")
        assertEquals("Searched acme: 2 of 5 repositories have skills (1 fork or archived skipped)", searched.line)
        assertEquals(ExitCode.REPOSITORY_NOT_FOUND, (scan.owners[1] as OwnerOutcome.Failed).error.exitCode)
        assertEquals(listOf("acme/broken", "nobody"), scan.failures.map { it.label })

        val report = TextReport.render(Presentation.MultiList(scan))
        assertTrue(
            report.endsWith("\nSearched acme: 2 of 5 repositories have skills (1 fork or archived skipped)\nScanned 2 repositories: 4 skills, 2 failed\n"),
            report,
        )
    }

    @Test
    fun `phrases the Searched line for every count`() {
        fun line(checked: Int, withSkills: Int, skipped: Int) = OwnerOutcome.Searched(
            "github.com/acme", OwnerDiscovery(acme, emptyList(), checked, skipped), List(withSkills) { "acme/r$it" },
        ).line

        assertEquals("Searched acme: 1 of 1 repository has skills", line(1, 1, 0))
        assertEquals("Searched acme: 0 of 1 repository has skills (1 fork or archived skipped)", line(1, 0, 1))
        assertEquals("Searched acme: 1 of 3 repositories has skills (2 forks or archived skipped)", line(3, 1, 2))
        assertEquals("Searched acme: 0 of 0 repositories have skills", line(0, 0, 0))
        assertEquals("Searched acme: 2 of 3 repositories have skills", line(3, 2, 0))
    }

    @Test
    fun `computes similar skills across repositories with ids`() {
        val similar = crossRepositorySimilarity(listOf(one, two))

        assertEquals(setOf("acme/one:skills/pdf", "acme/one:skills/commits", "acme/two:skills/pdf", "acme/two:skills/team-commits"), similar.keys)
        assertEquals("acme/two:skills/pdf", similar.getValue("acme/one:skills/pdf").first().path)
        assertEquals("pdf-forms", similar.getValue("acme/one:skills/pdf").first().name)
    }

    @Test
    fun `summarizes several repositories`() {
        val scanned = listOf(RepositoryOutcome.Scanned("one", one), RepositoryOutcome.Scanned("two", two))
        val failed = RepositoryOutcome.Failed("three", "acme/three", RepositoryNotFoundException(RepoCoordinates("acme", "three")))

        assertEquals("Scanned 2 repositories: 4 skills", Presentation.MultiList(scanned).summary)
        assertEquals("Scanned 2 repositories: 2 of 4 skills match \"pdf\", 1 failed", Presentation.MultiList(scanned + failed, "pdf").summary)
        assertEquals("Scanned 0 repositories: 0 skills, 1 failed", Presentation.MultiList(listOf(failed)).summary)
    }

    @Test
    fun `renders several repositories with separators and the summary`() {
        val report = TextReport.render(
            Presentation.MultiList(listOf(RepositoryOutcome.Scanned("one", one), RepositoryOutcome.Scanned("two", two)), "repo:two pdf"),
        )

        assertEquals(
            """
            Repository:  acme/one
            Description: About acme/one
            Commit:      $sha (main)

            Found 2 skills, none match "repo:two pdf".

            ${"─".repeat(80)}

            Repository:  acme/two
            Description: About acme/two
            Commit:      $sha (main)

            Found 2 skills, 1 match "repo:two pdf":

              pdf-forms
                Fill PDF forms and extract fields from PDF files.
                skills/pdf

            Scanned 2 repositories: 1 of 4 skills match "repo:two pdf"

            """.trimIndent(),
            report,
        )
    }

    @Test
    fun `finds a skill across repositories`() {
        val results = listOf(one, two)

        val qualified = Presentation.detail(results, "acme/two:skills/pdf")
        assertEquals("pdf-forms", qualified.skill.name)
        assertEquals("acme/one:skills/pdf", qualified.similar.first().path)
        assertEquals(results, qualified.results)

        assertEquals("pdf-extract", Presentation.detail(results, "PDF-extract").skill.name)
        assertFailsWith<SkillNotFoundException> { Presentation.detail(results, "acme/one:skills/nope") }
        val missing = assertFailsWith<SkillNotFoundException> { Presentation.detail(results, "nope") }
        assertEquals("no skill 'nope' in acme/one, acme/two", missing.message)
    }

    @Test
    fun `reports names and paths found in several repositories as ambiguous`() {
        val byName = assertFailsWith<AmbiguousSkillException> { Presentation.detail(listOf(one, two), "commits") }
        assertEquals(
            "skill name 'commits' matches 2 skills: acme/one:skills/commits, acme/two:skills/team-commits; pass a path instead",
            byName.message,
        )
        val byPath = assertFailsWith<AmbiguousSkillException> { Presentation.detail(listOf(one, two), "skills/pdf") }
        assertTrue("acme/one:skills/pdf, acme/two:skills/pdf" in byPath.message.orEmpty(), byPath.message)
    }

    @Test
    fun `caches scan results for ten minutes`() {
        val cache = ScanCache()
        val now = Instant.parse("2026-09-30T10:00:00Z")
        assertNull(cache.get("github.com/acme/one", now))

        cache.put(one, now)

        assertEquals(one, cache.get("https://github.com/ACME/one.git", now.plus(Duration.ofMinutes(9))))
        assertNull(cache.get("github.com/acme/one", now.plus(Duration.ofMinutes(10))))
        assertNull(cache.get("not a url", now))
    }
}
