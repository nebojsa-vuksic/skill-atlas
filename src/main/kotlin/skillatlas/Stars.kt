package skillatlas

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** One starred skill: its repository (`owner/name`), its directory path, and its name when it was starred (spec section 5.11). */
@Serializable
data class Star(val repository: String, val path: String, val name: String) {
    val id: String get() = skillId(repository, path)
}

@Serializable
private data class StarsFile(val stars: List<Star> = emptyList())

/**
 * The user's stars, in one JSON file that every view shares (spec section 5.11). Changes
 * read the file again and replace it atomically, so another view's change isn't lost.
 */
class StarStore(val file: Path) {
    private val lock = Any()

    /** The stars, in file order; none when there is no file. Throws [StarsFileException] when it can't be read. */
    fun read(): List<Star> = synchronized(lock) { load() }

    /** Like [read], but a file that can't be read is passed to [warn] and counts as no stars. */
    fun readOrWarn(warn: (String) -> Unit): List<Star> = try {
        read()
    } catch (e: StarsFileException) {
        warn("warning: ${e.message}")
        emptyList()
    }

    /** Stars [skill] of [repository] under its main path. Returns false when it was already starred. */
    fun star(repository: String, skill: Skill): Boolean = change { stars ->
        if (stars.any { it.matches(repository, skill) }) null else stars + Star(repository, skill.path, skill.name)
    }

    /** Removes every star that matches [skill], copies included. Returns false when it wasn't starred. */
    fun unstar(repository: String, skill: Skill): Boolean = change { stars ->
        stars.filterNot { it.matches(repository, skill) }.takeIf { it.size != stars.size }
    }

    /** Applies [update] to the current stars and saves the result; a null result means nothing changed. */
    private fun change(update: (List<Star>) -> List<Star>?): Boolean = synchronized(lock) {
        val updated = update(load()) ?: return false
        save(updated.sortedWith(compareBy<Star>({ it.repository.lowercase() }, { it.path })))
        true
    }

    private fun load(): List<Star> {
        val text = try {
            Files.readString(file)
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (e: IOException) {
            throw StarsFileException("could not read stars $file: ${reason(e)}", e)
        }
        return try {
            JSON.decodeFromString<StarsFile>(text).stars
        } catch (e: IllegalArgumentException) {
            // Includes SerializationException: broken JSON, or JSON without the stars' fields.
            throw StarsFileException("could not read stars $file: not valid stars JSON", e)
        }
    }

    private fun save(stars: List<Star>) {
        val directory = file.toAbsolutePath().parent
        try {
            Files.createDirectories(directory)
            // A temporary file in the same directory, then a rename, so the file is never half written.
            val temp = Files.createTempFile(directory, ".stars-", ".json.tmp")
            try {
                Files.writeString(temp, JSON.encodeToString(StarsFile(stars)) + "\n")
                Files.move(temp, file, REPLACE_EXISTING, ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (e: IOException) {
            throw StarsFileException("could not save stars $file: ${reason(e)}", e)
        }
    }

    /** Why [e] happened, without the path that the message already names. */
    private fun reason(e: IOException) = when (e) {
        is AccessDeniedException -> "permission denied"
        is FileAlreadyExistsException -> "${e.file} is not a directory"
        is FileSystemException -> e.reason ?: e.javaClass.simpleName
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private val JSON = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

        fun defaultLocation(
            environment: Map<String, String> = System.getenv(),
            home: String = System.getProperty("user.home"),
        ): Path {
            // As for XDG_STATE_HOME, a relative XDG_DATA_HOME is invalid and ignored.
            val dataHome = environment["XDG_DATA_HOME"]
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?.takeIf { it.isAbsolute }
                ?: Path.of(home, ".local", "share")
            return dataHome.resolve("skill-atlas").resolve("stars.json")
        }
    }
}

/** True when this star names [skill] of [repository]: the same repository, ignoring case, and its main path or a copy. */
fun Star.matches(repository: String, skill: Skill): Boolean =
    this.repository.equals(repository, ignoreCase = true) && (path == skill.path || path in skill.alsoAt)

/** This result with every skill's [Skill.starred] set from [stars] (spec section 5.11). */
fun ScanResult.withStars(stars: List<Star>): ScanResult {
    val paths = stars.filter { it.repository.equals(repository.fullName, ignoreCase = true) }.map { it.path }.toSet()
    fun starred(skill: Skill) = skill.path in paths || skill.alsoAt.any { it in paths }
    if (skills.all { it.starred == starred(it) }) return this
    return copy(skills = skills.map { it.copy(starred = starred(it)) })
}
