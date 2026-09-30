package skillatlas

/** Process exit codes (spec section 7). */
object ExitCode {
    const val OK = 0
    const val INTERNAL_ERROR = 1
    const val USAGE = 2
    const val REPOSITORY_NOT_FOUND = 3
    const val BRANCH_NOT_FOUND = 4
    const val NETWORK = 5
    const val SKILL_NOT_FOUND = 6
    const val INTERRUPTED = 130
}

/** A failure that ends the scan. Only the CLI layer turns these into messages and exit codes. */
sealed class SkillAtlasException(
    message: String,
    val exitCode: Int,
    cause: Throwable? = null,
) : Exception(message, cause)

class InvalidUrlException(input: String) :
    SkillAtlasException("not a GitHub repository URL: $input", ExitCode.USAGE)

class RepositoryNotFoundException(repository: RepoCoordinates) :
    SkillAtlasException(
        "repository $repository not found (is it private? set GITHUB_TOKEN)",
        ExitCode.REPOSITORY_NOT_FOUND,
    )

class RepositoryAccessDeniedException(repository: RepoCoordinates, reason: String) :
    SkillAtlasException("access to repository $repository denied: $reason", ExitCode.REPOSITORY_NOT_FOUND)

class BranchNotFoundException(branch: String, repository: RepoCoordinates) :
    SkillAtlasException(
        "branch '$branch' not found in $repository (is the repository empty?)",
        ExitCode.BRANCH_NOT_FOUND,
    )

class RateLimitException(tokenProvided: Boolean) :
    SkillAtlasException(
        if (tokenProvided) {
            "GitHub API rate limit exceeded; try again later"
        } else {
            "GitHub API rate limit exceeded; set GITHUB_TOKEN to raise the limit"
        },
        ExitCode.NETWORK,
    )

class NetworkException(message: String, cause: Throwable? = null) :
    SkillAtlasException(message, ExitCode.NETWORK, cause)

class GitNotFoundException(cause: Throwable? = null) :
    SkillAtlasException("git executable not found on PATH; install git and try again", ExitCode.INTERNAL_ERROR, cause)

class ScanInterruptedException :
    SkillAtlasException("scan interrupted", ExitCode.INTERRUPTED)

class SkillNotFoundException(selector: String, repository: String) :
    SkillAtlasException("no skill '$selector' in $repository", ExitCode.SKILL_NOT_FOUND)

class AmbiguousSkillException(selector: String, paths: List<String>) :
    SkillAtlasException(
        "skill name '$selector' matches ${paths.size} skills: ${paths.joinToString(", ")}; pass a path instead",
        ExitCode.USAGE,
    )

class NotATerminalException(command: String) :
    SkillAtlasException("$command needs an interactive terminal; use \"skill-atlas scan\" instead", ExitCode.USAGE)

class UnexpectedFailureException(cause: Throwable) :
    SkillAtlasException("unexpected failure: ${cause.message ?: cause.javaClass.name}", ExitCode.INTERNAL_ERROR, cause)
