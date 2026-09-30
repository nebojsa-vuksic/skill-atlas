package skillatlas

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.time.Clock

/** One piece of shell output, printed once into the scrollback (spec section 5.9). */
sealed interface ShellBlock {
    /** The input that was run, echoed as `❯ <line>`. */
    data class Echo(val line: String) : ShellBlock

    /** The rich report of `scan`, `scan --filter` or `scan --skill`. */
    data class Report(val presentation: Presentation) : ShellBlock

    /** Only the similar-skills table of one skill. */
    data class Similar(val detail: Presentation.SkillDetail) : ShellBlock

    data class Repository(val result: ScanResult) : ShellBlock

    data class Log(val entries: List<ScanLogEntry>) : ShellBlock

    data object Help : ShellBlock

    /** A line of text; [look] is [Look.WARNING] for notices such as "No repository yet". */
    data class Message(val text: String, val look: Look = Look.NORMAL) : ShellBlock

    /** Shown as `error: <message>` in red. */
    data class Error(val message: String) : ShellBlock
}

/** What running an input asks the shell to do. */
sealed interface ShellOutcome {
    data class Print(val blocks: List<ShellBlock>) : ShellOutcome

    /** Scan [url] in the background, then hand the result to [ShellSession.adopt]. */
    data class Scan(val url: String) : ShellOutcome

    data class Browse(val state: BrowseState) : ShellOutcome

    data object Quit : ShellOutcome
}

/** A scanned repository and its similar skills. */
class CurrentRepository(val result: ScanResult, val similar: Map<String, List<SimilarSkill>>)

/**
 * The shell's commands and the state they share: the current repository and the web view
 * (spec section 5.9). No terminal code, so every command can be tested directly.
 * [startWebView] starts `serve` on a port, passing its warnings on, and may throw
 * [java.io.IOException].
 */
class ShellSession(
    private val scanner: Scanner,
    private val scanLog: ScanLog,
    private val clock: Clock,
    private val startWebView: (port: Int, warn: (String) -> Unit) -> WebServer.Running,
) {
    var repository: CurrentRepository? = null
        private set

    /** Where warnings from the background web view go; they arrive on the server's threads. */
    var onWarning: (String) -> Unit = {}

    private var webView: WebServer.Running? = null

    /** Runs one input from the prompt. */
    fun execute(line: String): ShellOutcome {
        val input = line.trim()
        if (!input.startsWith("/")) return withRepository { filter(it, input) }
        val name = input.drop(1).substringBefore(' ')
        val argument = input.drop(1).substringAfter(' ', "").trim()
        val command = ShellCommands.find(name)
            ?: return print(ShellBlock.Error("unknown command /$name; type /help for the list"))
        if (command.needsRepository && repository == null) return print(NO_REPOSITORY)
        if (command.argument == ArgumentKind.REQUIRED && argument.isEmpty()) {
            return print(ShellBlock.Error("usage: ${command.label}"))
        }
        return when (command.name) {
            "scan" -> ShellOutcome.Scan(argument)
            "filter" -> withRepository { filter(it, argument) }
            "skill" -> withRepository { detail(it, argument) { detail -> ShellBlock.Report(detail) } }
            "similar" -> withRepository { detail(it, argument) { detail -> ShellBlock.Similar(detail) } }
            "browse" -> withRepository { ShellOutcome.Browse(BrowseState(it.result, it.similar)) }
            "repo" -> withRepository { print(ShellBlock.Repository(it.result)) }
            "serve" -> serve(argument)
            "log" -> print(log())
            "help" -> print(ShellBlock.Help)
            "quit" -> ShellOutcome.Quit
            else -> error("no handler for /${command.name}")
        }
    }

    /** Scans [url] and computes its similar skills. Blocking, and interruptible like `scan`. */
    fun scan(url: String, progress: (String) -> Unit): CurrentRepository {
        val result = scanner.scan(url, progress)
        return CurrentRepository(result, SkillSimilarity.compute(result.skills, result.contents))
    }

    /** Makes a finished scan the current repository, logs it, and returns its report. */
    fun adopt(scanned: CurrentRepository): List<ShellBlock> {
        repository = scanned
        val blocks = mutableListOf<ShellBlock>(ShellBlock.Report(Presentation.SkillList(scanned.result)))
        try {
            scanLog.append(ScanLogEntry.of(scanned.result, clock.instant()))
        } catch (e: Exception) {
            blocks.add(ShellBlock.Message("warning: could not write scan log ${scanLog.file}: ${e.message}", Look.WARNING))
        }
        return blocks
    }

    /** Stops the web view, if it runs. */
    fun close() {
        webView?.close()
        webView = null
    }

    private fun withRepository(action: (CurrentRepository) -> ShellOutcome): ShellOutcome =
        repository?.let(action) ?: print(NO_REPOSITORY)

    private fun filter(repository: CurrentRepository, words: String) =
        print(ShellBlock.Report(Presentation.SkillList(repository.result, words.ifBlank { null })))

    private fun detail(repository: CurrentRepository, selector: String, block: (Presentation.SkillDetail) -> ShellBlock) = try {
        print(block(Presentation.detail(repository.result, selector)))
    } catch (e: SkillAtlasException) {
        print(ShellBlock.Error(e.message.orEmpty()))
    }

    private fun serve(argument: String): ShellOutcome {
        val running = webView
        if (argument == "stop") {
            if (running == null) return print(ShellBlock.Message("The web view isn't running."))
            close()
            return print(ShellBlock.Message("Stopped the web view."))
        }
        val port = if (argument.isEmpty()) DEFAULT_PORT else argument.toIntOrNull()?.takeIf { it in 0..65535 }
            ?: return print(ShellBlock.Error("usage: /serve [port] or /serve stop"))
        if (running != null) return print(ShellBlock.Message("The web view is already running: ${running.url}"))
        val started = try {
            startWebView(port) { onWarning(it) }
        } catch (e: java.io.IOException) {
            return print(ShellBlock.Error("could not listen on 127.0.0.1:$port: ${e.message}"))
        }
        webView = started
        return print(ShellBlock.Message("Skill Atlas web view: ${started.url}"))
    }

    private fun log(): ShellBlock {
        val file = scanLog.file
        val lines = try {
            if (Files.exists(file)) Files.readAllLines(file) else emptyList()
        } catch (e: Exception) {
            return ShellBlock.Error("could not read scan log $file: ${e.message}")
        }
        val entries = lines.mapNotNull { line -> runCatching { LOG_JSON.decodeFromString<ScanLogEntry>(line) }.getOrNull() }
        if (entries.isEmpty()) return ShellBlock.Message("No scans logged yet.")
        return ShellBlock.Log(entries.takeLast(LOG_ENTRIES))
    }

    private fun print(vararg blocks: ShellBlock) = ShellOutcome.Print(blocks.toList())

    companion object {
        const val LOG_ENTRIES = 10
        val NO_REPOSITORY = ShellBlock.Message("No repository yet — run /scan <url> first.", Look.WARNING)
        private val LOG_JSON = Json { ignoreUnknownKeys = true }
    }
}
