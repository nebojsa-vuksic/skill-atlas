package skillatlas

/** A GitHub repository identified by `owner/name`. */
data class RepoCoordinates(val owner: String, val name: String) {
    override fun toString() = "$owner/$name"
}

/** What a URL given to `scan` names: one repository, or every repository of an owner (spec sections 3.3 and 5.11). */
sealed interface ScanTarget {
    data class Repository(val coordinates: RepoCoordinates) : ScanTarget

    /** An organization or user account, e.g. `JetBrains` for `https://github.com/JetBrains`. */
    data class Owner(val login: String) : ScanTarget
}

/** Parses the repository and owner URL forms accepted by `skill-atlas scan` (spec section 3.3). */
object GitHubUrl {
    private val SEGMENT = Regex("[A-Za-z0-9._-]+")
    private const val SSH_PREFIX = "git@github.com:"
    private val HOSTS = setOf("github.com", "www.github.com")

    /** GitHub reserves this name, so `github.com/orgs/<name>` is an organization page, never a repository. */
    private const val ORGS = "orgs"

    /** A repository URL only; owner URLs are rejected like any other URL that isn't a repository. */
    fun parse(input: String): RepoCoordinates =
        (target(input) as? ScanTarget.Repository)?.coordinates ?: throw InvalidUrlException(input)

    fun target(input: String): ScanTarget {
        val url = input.trim()
        sshPath(url)?.let { path -> return repository(input, path.removeSuffix("/").split('/')) }
        val path = httpPath(url) ?: throw InvalidUrlException(input)
        val segments = path.removeSuffix("/").split('/')
        return when {
            segments.size == 1 && segments[0] != ORGS -> owner(input, segments[0])
            segments.size in 2..3 && segments[0] == ORGS && segments.getOrNull(2).let { it == null || it == "repositories" } ->
                owner(input, segments[1])
            else -> repository(input, segments)
        }
    }

    private fun repository(input: String, segments: List<String>): ScanTarget.Repository {
        if (segments.size != 2) throw InvalidUrlException(input)
        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        if (!isValidSegment(owner) || !isValidSegment(name) || owner == ORGS) throw InvalidUrlException(input)
        return ScanTarget.Repository(RepoCoordinates(owner, name))
    }

    private fun owner(input: String, login: String): ScanTarget.Owner {
        if (!isValidSegment(login)) throw InvalidUrlException(input)
        return ScanTarget.Owner(login)
    }

    private fun sshPath(url: String): String? =
        if (url.startsWith(SSH_PREFIX, ignoreCase = true)) url.substring(SSH_PREFIX.length) else null

    private fun httpPath(url: String): String? {
        val withoutScheme = when {
            url.startsWith("https://", ignoreCase = true) -> url.substring("https://".length)
            url.startsWith("http://", ignoreCase = true) -> url.substring("http://".length)
            else -> url
        }
        val slash = withoutScheme.indexOf('/')
        if (slash < 0) return null
        val host = withoutScheme.substring(0, slash).lowercase()
        return if (host in HOSTS) withoutScheme.substring(slash + 1) else null
    }

    private fun isValidSegment(segment: String) =
        SEGMENT.matches(segment) && segment != "." && segment != ".."
}
