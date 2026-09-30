package skillatlas

/** A GitHub repository identified by `owner/name`. */
data class RepoCoordinates(val owner: String, val name: String) {
    override fun toString() = "$owner/$name"
}

/** Parses the repository URL forms accepted by `skill-atlas scan` (spec section 3.3). */
object GitHubUrl {
    private val SEGMENT = Regex("[A-Za-z0-9._-]+")
    private const val SSH_PREFIX = "git@github.com:"
    private val HOSTS = setOf("github.com", "www.github.com")

    fun parse(input: String): RepoCoordinates {
        val url = input.trim()
        val path = sshPath(url) ?: httpPath(url) ?: throw InvalidUrlException(input)
        val segments = path.removeSuffix("/").split('/')
        if (segments.size != 2) throw InvalidUrlException(input)

        val owner = segments[0]
        val name = segments[1].removeSuffix(".git")
        if (!isValidSegment(owner) || !isValidSegment(name)) throw InvalidUrlException(input)
        return RepoCoordinates(owner, name)
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
