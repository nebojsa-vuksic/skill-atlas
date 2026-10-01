package skillatlas

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The stars file and how stars match skills (spec section 5.11). */
class StarStoreTest {
    @TempDir
    lateinit var dir: Path

    private val file: Path get() = dir.resolve("data/skill-atlas/stars.json")
    private val store: StarStore get() = StarStore(file)

    private val pdf = Skill("pdf", "Read PDF files.", "skills/pdf")
    private val tests = Skill("mps-tests", "Write MPS tests.", ".agents/skills/mps-tests", alsoAt = listOf(".claude/skills/mps-tests"))

    @Test
    fun `no file means no stars`() {
        assertEquals(emptyList(), store.read())
        assertFalse(file.exists())
    }

    @Test
    fun `starring saves the main path and name, sorted by repository then path`() {
        assertTrue(store.star("acme/skills", pdf))
        assertTrue(store.star("JetBrains/MPS", tests))
        assertTrue(store.star("acme/skills", Skill("docx", "", "skills/docx")))

        assertEquals(
            """
            {
                "stars": [
                    {
                        "repository": "acme/skills",
                        "path": "skills/docx",
                        "name": "docx"
                    },
                    {
                        "repository": "acme/skills",
                        "path": "skills/pdf",
                        "name": "pdf"
                    },
                    {
                        "repository": "JetBrains/MPS",
                        "path": ".agents/skills/mps-tests",
                        "name": "mps-tests"
                    }
                ]
            }

            """.trimIndent(),
            file.readText(),
        )
        assertEquals(listOf("acme/skills:skills/docx", "acme/skills:skills/pdf", "JetBrains/MPS:.agents/skills/mps-tests"), store.read().map { it.id })
    }

    @Test
    fun `starring a starred skill and unstarring an unstarred one change nothing`() {
        assertFalse(store.unstar("acme/skills", pdf))
        assertFalse(file.exists())

        store.star("acme/skills", pdf)
        val before = file.readText()
        assertFalse(store.star("ACME/Skills", pdf))
        assertEquals(before, file.readText())
    }

    @Test
    fun `unstarring removes every star that matches, copies included`() {
        // Starred when .claude was the main path; later an identical .agents copy took over.
        file.parent.createDirectories()
        file.writeText(
            """{"stars":[{"repository":"jetbrains/mps","path":".claude/skills/mps-tests","name":"mps-tests"},""" +
                """{"repository":"JetBrains/MPS","path":".agents/skills/mps-tests","name":"mps-tests"},""" +
                """{"repository":"acme/skills","path":"skills/pdf","name":"pdf"}]}""",
        )

        assertTrue(store.unstar("JetBrains/MPS", tests))

        assertEquals(listOf("acme/skills:skills/pdf"), store.read().map { it.id })
    }

    @Test
    fun `a skill is starred through its main path or a copy, ignoring the repository's case`() {
        val stars = listOf(Star("jetbrains/mps", ".claude/skills/mps-tests", "mps-tests"), Star("other/repo", "skills/pdf", "pdf"))
        val result = ScanResult(RepositoryMetadata("JetBrains/MPS", null, "master"), "master", "c".repeat(40), listOf(tests, pdf))

        val marked = result.withStars(stars)

        assertEquals(listOf(true, false), marked.skills.map { it.starred })
        assertEquals(listOf(false, false), marked.withStars(emptyList()).skills.map { it.starred })
        // Nothing to change: the same result comes back, so output without stars stays exactly as before.
        assertSame(result, result.withStars(emptyList()))
    }

    @Test
    fun `unknown keys are ignored`() {
        file.parent.createDirectories()
        file.writeText("""{"version":2,"stars":[{"repository":"acme/skills","path":"skills/pdf","name":"pdf","note":"x"}]}""")

        assertEquals(listOf(Star("acme/skills", "skills/pdf", "pdf")), store.read())
    }

    @Test
    fun `an invalid file is reported and never overwritten`() {
        file.parent.createDirectories()
        for (text in listOf("{not json", """{"stars":[{"repository":"acme/skills"}]}""")) {
            file.writeText(text)

            val read = assertFailsWith<StarsFileException> { store.read() }
            assertEquals("could not read stars $file: not valid stars JSON", read.message)
            assertEquals(ExitCode.INTERNAL_ERROR, read.exitCode)
            val change = assertFailsWith<StarsFileException> { store.star("acme/skills", pdf) }
            assertEquals(read.message, change.message)
            assertEquals(text, file.readText())
        }

        val warnings = mutableListOf<String>()
        assertEquals(emptyList(), store.readOrWarn { warnings += it })
        assertEquals(listOf("warning: could not read stars $file: not valid stars JSON"), warnings)
    }

    @Test
    fun `a file that can't be read or saved is an error`() {
        val blocked = dir.resolve("blocked").also { it.writeText("a file") }
        val inFile = blocked.resolve("stars.json")
        assertEquals("could not read stars $inFile: Not a directory", assertFailsWith<StarsFileException> { StarStore(inFile).read() }.message)

        val readOnly = dir.resolve("read-only").createDirectories()
        Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val store = StarStore(readOnly.resolve("stars.json"))
            assertEquals(emptyList(), store.read())
            val error = assertFailsWith<StarsFileException> { store.star("acme/skills", pdf) }
            assertEquals("could not save stars ${readOnly.resolve("stars.json")}: permission denied", error.message)
        } finally {
            Files.setPosixFilePermissions(readOnly, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun `saving replaces the file and leaves no temporary file behind`() {
        store.star("acme/skills", pdf)
        store.star("acme/skills", tests)
        store.unstar("acme/skills", pdf)

        assertEquals(listOf("stars.json"), file.parent.listDirectoryEntries().map { it.name })
        assertEquals(listOf("acme/skills:.agents/skills/mps-tests"), store.read().map { it.id })
    }

    @Test
    fun `the default location follows XDG_DATA_HOME`() {
        assertEquals(
            Path.of("/data/skill-atlas/stars.json"),
            StarStore.defaultLocation(mapOf("XDG_DATA_HOME" to "/data"), home = "/home/u"),
        )
        assertEquals(Path.of("/home/u/.local/share/skill-atlas/stars.json"), StarStore.defaultLocation(emptyMap(), home = "/home/u"))
        // A relative path is invalid by the XDG spec and ignored.
        assertEquals(
            Path.of("/home/u/.local/share/skill-atlas/stars.json"),
            StarStore.defaultLocation(mapOf("XDG_DATA_HOME" to "relative"), home = "/home/u"),
        )
    }
}
