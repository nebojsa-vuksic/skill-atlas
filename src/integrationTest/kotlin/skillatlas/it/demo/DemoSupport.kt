package skillatlas.it.demo

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.options.ScreenshotAnimations
import com.microsoft.playwright.options.ColorScheme
import com.microsoft.playwright.options.ScreenshotCaret
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.abs
import kotlin.test.fail
import skillatlas.it.Sandbox

/** Where demo tests read and write (spec section 13.2), passed in by Gradle. */
object DemoPaths {
    val repository: Path = Path.of(checkNotNull(System.getProperty("skillAtlas.repoRoot")) { "skillAtlas.repoRoot not set" })
    val output: Path = repository.resolve("build/demo")
    val baselines: Path = repository.resolve("src/integrationTest/baselines")

    /** `-PupdateScreenshots`: write key moments as the new baselines instead of comparing them (spec section 13.5). */
    val updating: Boolean = System.getProperty("skillAtlas.updateScreenshots") == "true"

    fun outputOf(demo: String): Path = output.resolve(demo).also { it.createDirectories() }
}

/**
 * Compares key-moment screenshots with their baselines (spec section 13.4). Every moment is
 * checked before the test fails, so one run shows all differences.
 */
class ScreenshotCheck(private val demo: String) {
    private val failures = mutableListOf<String>()
    private val out = DemoPaths.outputOf(demo)

    fun check(moment: String, actual: Path) {
        val baseline = DemoPaths.baselines.resolve(demo).resolve("$moment.png")
        if (DemoPaths.updating) {
            baseline.parent.createDirectories()
            Files.copy(actual, baseline, StandardCopyOption.REPLACE_EXISTING)
            return
        }
        if (!baseline.exists()) {
            failures += "no baseline for $demo/$moment; run the Update screenshots workflow"
            return
        }
        val expected = ImageIO.read(baseline.toFile())
        val got = ImageIO.read(actual.toFile())
        val expectedCopy = out.resolve("expected").also { it.createDirectories() }.resolve("$moment.png")
        val diffFile = out.resolve("diff").also { it.createDirectories() }.resolve("$moment.png")
        if (expected.width != got.width || expected.height != got.height) {
            Files.copy(baseline, expectedCopy, StandardCopyOption.REPLACE_EXISTING)
            failures += "$demo/$moment: size ${got.width}×${got.height} differs from the baseline's ${expected.width}×${expected.height}"
            return
        }
        val (differing, diff) = compare(expected, got)
        val share = differing.toDouble() / (got.width.toLong() * got.height)
        if (share > MAX_DIFFERING_SHARE) {
            Files.copy(baseline, expectedCopy, StandardCopyOption.REPLACE_EXISTING)
            ImageIO.write(diff, "png", diffFile.toFile())
            failures += "$demo/$moment: %.3f %% of pixels differ (limit %.1f %%)\n  actual:   %s\n  expected: %s\n  diff:     %s"
                .format(share * 100, MAX_DIFFERING_SHARE * 100, actual, expectedCopy, diffFile)
        }
    }

    /** Fails the test if any moment differed. */
    fun verify() {
        if (failures.isNotEmpty()) fail("Screenshots of demo '$demo' changed (spec section 13.4):\n" + failures.joinToString("\n"))
    }

    companion object {
        const val CHANNEL_TOLERANCE = 16
        const val MAX_DIFFERING_SHARE = 0.001

        /** The number of differing pixels, and an image with them in red over the faded actual image. */
        fun compare(expected: BufferedImage, actual: BufferedImage): Pair<Int, BufferedImage> {
            val diff = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_RGB)
            var differing = 0
            for (y in 0 until actual.height) {
                for (x in 0 until actual.width) {
                    val a = expected.getRGB(x, y)
                    val b = actual.getRGB(x, y)
                    val differs = (0..16 step 8).any { shift -> abs(((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)) > CHANNEL_TOLERANCE }
                    if (differs) {
                        differing++
                        diff.setRGB(x, y, 0xFF0000)
                    } else {
                        // The actual image at 30 %, over white.
                        val faded = (0..16 step 8).fold(0) { rgb, shift ->
                            val channel = (b shr shift) and 0xFF
                            rgb or ((255 - (255 - channel) * 3 / 10) shl shift)
                        }
                        diff.setRGB(x, y, faded)
                    }
                }
            }
            return differing to diff
        }
    }
}

/**
 * The voice-over lines of one demo (spec section 13.6). With Kokoro set up (`demo/.venv`,
 * `demo/.kokoro`) every line is rendered up front; without it, lengths are estimated and the
 * video gets captions only.
 */
class Narration(private val out: Path, private val prefix: String, lines: Collection<String>) {
    private class Rendered(val file: String?, val duration: Double)

    private val rendered: Map<String, Rendered> = render(lines.distinct())
    private val spoken = mutableListOf<String>()
    private var started = System.nanoTime()

    fun start() {
        started = System.nanoTime()
    }

    /** Notes the line at the current time and waits until it has been spoken. */
    fun say(page: Page, text: String) {
        val line = checkNotNull(rendered[text]) { "narration: \"$text\" wasn't listed up front" }
        val at = (System.nanoTime() - started) / 1e9
        spoken += """{"at": %.3f, "duration": %.3f, "text": %s, "file": %s}"""
            .format(at, line.duration, json(text), line.file?.let(::json) ?: "null")
        page.waitForTimeout(line.duration * 1000 + 400)
    }

    fun save() {
        out.resolve("$prefix.narration.json").writeText("[\n  " + spoken.joinToString(",\n  ") + "\n]\n")
    }

    private fun render(texts: List<String>): Map<String, Rendered> {
        val demo = DemoPaths.repository.resolve("demo")
        val python = demo.resolve(".venv/bin/python")
        val model = demo.resolve(".kokoro/kokoro-v1.0.onnx")
        if (!python.exists() || !model.exists() || texts.isEmpty()) {
            return texts.associateWith { Rendered(null, it.split(' ').size / 2.6) }
        }
        val voice = out.resolve("voice").also { it.createDirectories() }
        val request = texts.mapIndexed { i, text ->
            """{"text": ${json(text)}, "file": ${json(voice.resolve("%02d.wav".format(i + 1)).toString())}}"""
        }.joinToString(",", "[", "]")
        val process = ProcessBuilder(
            python.toString(), demo.resolve("lib/kokoro_say.py").toString(), model.toString(),
            demo.resolve(".kokoro/voices-v1.0.bin").toString(), System.getenv("DEMO_VOICE") ?: "af_heart", "1.05",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        process.outputStream.use { it.write(request.toByteArray()) }
        val response = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "kokoro_say.py failed" }
        val durations = Regex(""""duration":\s*([\d.]+)""").findAll(response).map { it.groupValues[1].toDouble() }.toList()
        return texts.mapIndexed { i, text -> text to Rendered(voice.resolve("%02d.wav".format(i + 1)).toString(), durations[i]) }.toMap()
    }

    private fun json(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

/**
 * One web view demo (spec sections 13.1 and 13.3): a pinned browser context that records a
 * video, narrates, and screenshots key moments for comparison.
 */
class WebDemo(val name: String, browser: Browser, lines: Collection<String>) : AutoCloseable {
    private val out = DemoPaths.outputOf(name)
    private val checks = ScreenshotCheck(name)
    private val narration = Narration(out, "web", lines)
    private val context: BrowserContext = browser.newContext(
        Browser.NewContextOptions()
            .setViewportSize(WIDTH, HEIGHT)
            .setDeviceScaleFactor(1.0)
            .setColorScheme(ColorScheme.LIGHT)
            .setLocale("en-US")
            .setTimezoneId("UTC")
            // Only so the pinned fonts can be injected; the page's CSP stays in force in real use.
            .setBypassCSP(true)
            .setRecordVideoDir(out.resolve("video"))
            .setRecordVideoSize(WIDTH, HEIGHT),
    ).also { it.addInitScript(FONT_SCRIPT) }
    val page: Page = context.newPage().also { narration.start() }

    fun say(text: String) = narration.say(page, text)

    /** Screenshots a key moment once fonts have loaded, and compares it with its baseline. */
    fun moment(moment: String) {
        page.evaluate("() => document.fonts.ready")
        val file = out.resolve("screenshots").also { it.createDirectories() }.resolve("$moment.png")
        page.screenshot(
            Page.ScreenshotOptions().setPath(file).setAnimations(ScreenshotAnimations.DISABLED).setCaret(ScreenshotCaret.HIDE),
        )
        checks.check(moment, file)
    }

    fun idle() = page.waitForSelector("body[data-state=idle]")

    fun skill(name: String) = page.locator("[role=option]").filter(
        com.microsoft.playwright.Locator.FilterOptions().setHas(page.locator(".skill-name").getByText(name, com.microsoft.playwright.Locator.GetByTextOptions().setExact(true))),
    )

    fun type(selector: String, text: String, delay: Double = 55.0) =
        page.locator(selector).pressSequentially(text, com.microsoft.playwright.Locator.PressSequentiallyOptions().setDelay(delay))

    override fun close() {
        narration.save()
        val video = checkNotNull(page.video()) { "the context records video" }
        context.close()
        val target = out.resolve("web.webm")
        target.deleteIfExists()
        video.saveAs(target)
        video.delete()
        checks.verify()
    }

    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 800

        /** Inter and JetBrains Mono from the test resources, forced onto every page (spec section 13.1). */
        private val FONT_SCRIPT: String by lazy {
            fun font(file: String) = Base64.getEncoder().encodeToString(DemoFixtures::class.java.getResourceAsStream("/fonts/$file")!!.readBytes())
            val faces = listOf(
                Triple("Inter", "Inter-Regular.woff2", "font-weight:400;font-style:normal"),
                Triple("Inter", "Inter-SemiBold.woff2", "font-weight:600;font-style:normal"),
                Triple("Inter", "Inter-Bold.woff2", "font-weight:700;font-style:normal"),
                Triple("Inter", "Inter-Italic.woff2", "font-weight:400;font-style:italic"),
                Triple("JetBrains Mono", "JetBrainsMono-Regular.ttf", "font-weight:400;font-style:normal"),
                Triple("JetBrains Mono", "JetBrainsMono-Bold.ttf", "font-weight:700;font-style:normal"),
            ).joinToString("") { (family, file, style) ->
                val format = if (file.endsWith(".woff2")) "font/woff2" else "font/ttf"
                "@font-face{font-family:'$family';src:url(data:$format;base64,${font(file)});$style}"
            }
            val css = faces +
                "body,input,button,textarea{font-family:'Inter',sans-serif !important}" +
                "code,pre,input#url,.skill-path,.commit,.similar-path,.raw{font-family:'JetBrains Mono',monospace !important}" +
                "*,*::before,*::after{transition:none !important;caret-color:transparent !important}"
            """
            (() => {
              const add = () => {
                const style = document.createElement("style");
                style.textContent = ${"\""}${css.replace("\"", "\\\"")}${"\""};
                document.head.appendChild(style);
              };
              if (document.head) add(); else document.addEventListener("DOMContentLoaded", add);
            })();
            """.trimIndent()
        }
    }
}

/**
 * One terminal demo (spec sections 13.1 and 13.3): runs a VHS tape against the sandbox's stub
 * API and fixture repositories, then compares the tape's `Screenshot` key moments.
 */
object TerminalDemo {
    fun run(name: String, sandbox: Sandbox, launcher: Path) {
        val out = DemoPaths.outputOf(name)
        val tape = out.resolve("terminal.tape")
        tape.writeText(checkNotNull(DemoFixtures::class.java.getResource("/tapes/$name.tape")) { "no tape for $name" }.readText())
        out.resolve("screenshots").createDirectories()

        val environment = System.getenv().toMutableMap()
        environment.remove("GITHUB_TOKEN")
        environment.putAll(sandbox.cliEnvironment().filterKeys { it != "PATH" && it != "HOME" })
        environment["PATH"] = launcher.parent.toString() + File.pathSeparator + System.getenv("PATH")

        val process = ProcessBuilder("vhs", "--output", "terminal.webm", "terminal.tape")
            .directory(out.toFile())
            .redirectErrorStream(true)
            .redirectOutput(out.resolve("vhs.log").toFile())
            .apply { environment().apply { clear(); putAll(environment) } }
            .start()
        check(process.waitFor(5, TimeUnit.MINUTES)) { "vhs timed out on $name; see ${out.resolve("vhs.log")}" }
        check(process.exitValue() == 0) { "vhs failed on $name:\n" + out.resolve("vhs.log").readText().takeLast(2000) }

        val checks = ScreenshotCheck(name)
        val moments = Regex("""^Screenshot screenshots/(.+)\.png$""", RegexOption.MULTILINE).findAll(tape.readText()).map { it.groupValues[1] }.toList()
        check(moments.isNotEmpty()) { "the $name tape takes no screenshots" }
        for (moment in moments) checks.check(moment, out.resolve("screenshots/$moment.png"))
        checks.verify()
    }
}
