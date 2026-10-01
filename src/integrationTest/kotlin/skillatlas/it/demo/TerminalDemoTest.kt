package skillatlas.it.demo

import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir
import skillatlas.it.LAUNCHER
import skillatlas.it.Sandbox

/**
 * The terminal demos of spec section 13.3: each runs its VHS tape (`/tapes/<demo>.tape`)
 * against the stub API and fixture repositories, and compares the tape's key moments.
 * Runs on CI only (`./gradlew demoTest`).
 */
@Tag("demo")
class TerminalDemoTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var sandbox: Sandbox

    @BeforeTest
    fun setUp() {
        sandbox = Sandbox(dir)
        DemoFixtures.install(sandbox)
    }

    @AfterTest
    fun tearDown() = sandbox.close()

    @Test
    fun cli() = TerminalDemo.run("cli", sandbox, LAUNCHER)

    @Test
    fun browse() = TerminalDemo.run("browse", sandbox, LAUNCHER)

    @Test
    fun shell() = TerminalDemo.run("shell", sandbox, LAUNCHER)

    @Test
    fun org() = TerminalDemo.run("org", sandbox, LAUNCHER)
}
