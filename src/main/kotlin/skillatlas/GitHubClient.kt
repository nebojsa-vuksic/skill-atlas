package skillatlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

@Serializable
data class RepositoryMetadata(
    @SerialName("full_name") val fullName: String,
    val description: String? = null,
    @SerialName("default_branch") val defaultBranch: String,
)

/** Reads repository metadata from the GitHub REST API (spec section 4.1, step 2). */
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
        val request = HttpRequest.newBuilder(URI.create("$apiBaseUrl/repos/${repository.owner}/${repository.name}"))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "skill-atlas")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .GET()
            .build()

        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw NetworkException("could not reach the GitHub API: ${e.message ?: e.javaClass.simpleName}", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw NetworkException("interrupted while calling the GitHub API", e)
        }

        return when (response.statusCode()) {
            200 -> try {
                json.decodeFromString<RepositoryMetadata>(response.body())
            } catch (e: SerializationException) {
                throw NetworkException("unexpected response from the GitHub API for $repository", e)
            }
            404 -> throw RepositoryNotFoundException(repository)
            401 -> throw RepositoryAccessDeniedException(repository, "GitHub rejected GITHUB_TOKEN")
            403, 429 ->
                if (isRateLimited(response)) {
                    throw RateLimitException(tokenProvided = token != null)
                } else {
                    throw RepositoryAccessDeniedException(repository, "HTTP ${response.statusCode()}")
                }
            else -> throw NetworkException("the GitHub API returned HTTP ${response.statusCode()} for $repository")
        }
    }

    private fun isRateLimited(response: HttpResponse<String>) =
        response.statusCode() == 429 ||
            response.headers().firstValue("x-ratelimit-remaining").orElse(null) == "0" ||
            response.body().contains("rate limit", ignoreCase = true)
}
