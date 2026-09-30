package skillatlas

import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.readLines
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.io.TempDir

class ScanLogTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `uses XDG_STATE_HOME when it is an absolute path`() {
        assertEquals(
            Path.of("/state/skill-atlas/scans.log"),
            ScanLog.defaultLocation(mapOf("XDG_STATE_HOME" to "/state"), home = "/home/u"),
        )
    }

    @Test
    fun `falls back to the home directory`() {
        val fallback = Path.of("/home/u/.local/state/skill-atlas/scans.log")
        assertEquals(fallback, ScanLog.defaultLocation(emptyMap(), home = "/home/u"))
        assertEquals(fallback, ScanLog.defaultLocation(mapOf("XDG_STATE_HOME" to ""), home = "/home/u"))
        assertEquals(fallback, ScanLog.defaultLocation(mapOf("XDG_STATE_HOME" to "relative"), home = "/home/u"))
    }

    @Test
    fun `creates the directory and appends one JSON line per scan`() {
        val log = ScanLog(dir.resolve("nested/skill-atlas/scans.log"))
        val result = ScanResult(
            RepositoryMetadata("anthropics/skills", "Public repository for Agent Skills", "main"),
            "main",
            "3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39",
            List(3) { Skill("s$it", "d", "s$it") },
        )

        log.append(ScanLogEntry.of(result, Instant.parse("2026-09-30T10:28:00.123Z")))
        log.append(ScanLogEntry.of(result.copy(repository = result.repository.copy(description = null)), Instant.EPOCH))

        assertEquals(
            listOf(
                """{"scanned_at":"2026-09-30T10:28:00Z","repository":"anthropics/skills","description":"Public repository for Agent Skills","branch":"main","commit":"3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39","skill_count":3}""",
                """{"scanned_at":"1970-01-01T00:00:00Z","repository":"anthropics/skills","description":null,"branch":"main","commit":"3f2a9c1e8b7d6a5f4e3d2c1b0a9f8e7d6c5b4a39","skill_count":3}""",
            ),
            log.file.readLines(),
        )
    }
}
