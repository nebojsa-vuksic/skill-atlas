package skillatlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class RepositoryMetadata(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String,
    /** Only read from an owner's repository list, where forks and archived repositories are skipped (spec section 5.12). */
    val fork: Boolean = false,
    val archived: Boolean = false,
)

/** An organization or user account (spec section 5.12). */
@Serializable
data class OwnerMetadata(val login: String, val type: String) {
    val isOrganization: Boolean get() = type == "Organization"
}

/** What a repository's tree says about skill files, without cloning it (spec section 5.12, step 4). */
enum class TreeCheck(val mayHaveSkills: Boolean) {
    SKILL_FILES(true),
    /** Too large for one response, so only a clone can tell. */
    TRUNCATED(true),
    /** No `SKILL.md`, an empty repository, or no such branch. */
    NO_SKILL_FILES(false),
    /** Anything unexpected; the clone then reports the real error. */
    FAILED(true),
}

/** Reads repository and owner metadata from the GitHub REST API (spec sections 4.1, step 2, and 5.12). */
class GitHubClient(
    private val apiBaseUrl: String = "https://api.github.com",
    private val token: String? = null,
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchRepository(repository: RepoCoordinates): RepositoryMetadata {
        val response = get("/repos/${repository.owner}/${repository.name}")
        return when (response.statusCode()) {
            200 -> decode(response, "$repository")
            404 -> throw RepositoryNotFoundException(repository)
            else -> throw failure(response, "$repository") { RepositoryAccessDeniedException(repository, it) }
        }
    }

    fun fetchOwner(login: String): OwnerMetadata {
        val response = get("/users/$login")
        return when (response.statusCode()) {
            200 -> decode(response, login)
            404 -> throw OwnerNotFoundException(login)
            else -> throw failure(response, login) { NetworkException("the GitHub API denied access to $login: $it") }
        }
    }

    /** Every repository of [owner], 100 per page, until a page comes back short. */
    fun listRepositories(owner: OwnerMetadata): List<RepositoryMetadata> {
        val path = if (owner.isOrganization) "/orgs/${owner.login}/repos?type=all" else "/users/${owner.login}/repos?type=owner"
        val repositories = mutableListOf<RepositoryMetadata>()
        var page = 1
        while (true) {
            val response = get("$path&sort=full_name&per_page=$PAGE_SIZE&page=$page")
            val entries = when (response.statusCode()) {
                200 -> decode<List<RepositoryMetadata>>(response, owner.login)
                404 -> throw OwnerNotFoundException(owner.login)
                else -> throw failure(response, owner.login) { NetworkException("the GitHub API denied access to ${owner.login}: $it") }
            }
            repositories += entries
            if (entries.size < PAGE_SIZE) return repositories
            page++
        }
    }

    /** Looks for `SKILL.md` in the tree of [branch]. Only a rate limit is an error, since it would fail every other check too. */
    fun checkSkillFiles(repository: RepoCoordinates, branch: String): TreeCheck {
        val ref = URLEncoder.encode(branch, Charsets.UTF_8).replace("+", "%20")
        val response = try {
            get("/repos/${repository.owner}/${repository.name}/git/trees/$ref?recursive=1")
        } catch (_: NetworkException) {
            return TreeCheck.FAILED
        }
        return when (response.statusCode()) {
            200 -> {
                val tree = try {
                    json.decodeFromString<Tree>(response.body())
                } catch (_: SerializationException) {
                    return TreeCheck.FAILED
                }
                when {
                    tree.tree.any { it.type == "blob" && (it.path == SKILL_FILE || it.path.endsWith("/$SKILL_FILE")) } -> TreeCheck.SKILL_FILES
                    tree.truncated -> TreeCheck.TRUNCATED
                    else -> TreeCheck.NO_SKILL_FILES
                }
            }
            404, 409 -> TreeCheck.NO_SKILL_FILES
            else -> if (isRateLimited(response)) throw RateLimitException(tokenProvided = token != null) else TreeCheck.FAILED
        }
    }

    @Serializable
    private data class Tree(val tree: List<Entry> = emptyList(), val truncated: Boolean = false) {
        @Serializable
        data class Entry(val path: String, val type: String)
    }

    private fun get(path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$apiBaseUrl$path"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "skill-atlas")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .GET()
            .build()

        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw NetworkException("could not reach the GitHub API: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NetworkException("interrupted while calling the GitHub API", e)
        }
    }

    private inline fun <reified T> decode(response: HttpResponse<String>, subject: String): T = try {
        json.decodeFromString<T>(response.body())
    } catch (e: SerializationException) {
        throw NetworkException("unexpected response from the GitHub API for $subject", e)
    }

    /** The error for any status other than 200 and 404; [denied] builds the one for a rejected token or a 403. */
    private fun failure(response: HttpResponse<String>, subject: String, denied: (String) -> SkillAtlasException): SkillAtlasException =
        when (response.statusCode()) {
            401 -> denied("GitHub rejected GITHUB_TOKEN")
            403, 429 -> if (isRateLimited(response)) RateLimitException(tokenProvided = token != null) else denied("HTTP ${response.statusCode()}")
            else -> NetworkException("the GitHub API returned HTTP ${response.statusCode()} for $subject")
        }

    private fun isRateLimited(response: HttpResponse<String>) =
        response.statusCode() == 429 ||
            response.headers().firstValue("x-ratelimit-remaining").orElse(null) == "0" ||
            response.body().contains("rate limit", ignoreCase = true)

    private companion object {
        const val PAGE_SIZE = 100
        const val SKILL_FILE = "SKILL.md"
    }
}
