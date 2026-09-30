package skillatlas

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
data class ScanLogEntry(
    @SerialName("scanned_at") val scannedAt: String,
    val repository: String,
    val description: String?,
    val branch: String,
    val commit: String,
    @SerialName("skill_count") val skillCount: Int,
) {
    companion object {
        fun of(result: ScanResult, scannedAt: Instant) = ScanLogEntry(
            scannedAt = scannedAt.truncatedTo(ChronoUnit.SECONDS).toString(),
            repository = result.repository.fullName,
            description = result.repository.description,
            branch = result.branch,
            commit = result.commit,
            skillCount = result.skills.size,
        )
    }
}

/** Appends one JSON line per successful scan (spec section 6). */
class ScanLog(val file: Path) {
    fun append(entry: ScanLogEntry) {
        file.parent?.let { Files.createDirectories(it) }
        Files.writeString(file, Json.encodeToString(entry) + "\n", CREATE, APPEND, WRITE)
    }

    companion object {
        fun defaultLocation(
            environment: Map<String, String> = System.getenv(),
            home: String = System.getProperty("user.home"),
        ): Path {
            // The XDG spec says relative paths in XDG_STATE_HOME are invalid and must be ignored.
            val stateHome = environment["XDG_STATE_HOME"]
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?.takeIf { it.isAbsolute }
                ?: Path.of(home, ".local", "state")
            return stateHome.resolve("skill-atlas").resolve("scans.log")
        }
    }
}
