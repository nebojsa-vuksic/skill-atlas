package skillatlas

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.CoreCliktCommand
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import com.github.ajalt.clikt.parameters.types.int
import com.jakewharton.mosaic.tty.Tty
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.time.Clock
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/** Default port for `skill-atlas serve`. */
const val DEFAULT_PORT = 8421

val VERSION: String = SkillAtlasCli::class.java.`package`?.implementationVersion ?: "dev"

fun main(args: Array<String>) {
    // Descriptions may contain any Unicode, and the report uses "…", "●" and "⚠".
    System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, Charsets.UTF_8))

    val environment = System.getenv()
    fun setting(name: String) = environment[name]?.takeIf { it.isNotBlank() }

    val token = setting("GITHUB_TOKEN")
    // Test hooks that point the CLI at a local API stub and local repositories (spec section 11.2).
    val apiUrl = setting("SKILL_ATLAS_GITHUB_API_URL")?.trimEnd('/') ?: "https://api.github.com"
    val gitBaseUrl = setting("SKILL_ATLAS_GIT_BASE_URL")?.trimEnd('/') ?: "https://github.com"
    val scanner = Scanner(
        github = GitHubClient(apiBaseUrl = apiUrl, token = token),
        git = Git(token = token),
        cloneUrl = { "$gitBaseUrl/${it.owner}/${it.name}.git" },
    )
    val terminal = terminalStatus()
    val view = if (terminal.stdout) RichScanView() else PlainScanView(System.out, System.err)
    val cli = SkillAtlasCli(scanner, ScanLog(ScanLog.defaultLocation()), view, interactive = terminal.stdin && terminal.stdout)
    exitProcess(cli.run(args.toList()))
}

private class TerminalStatus(val stdin: Boolean, val stdout: Boolean)

/** Whether stdin and stdout are interactive terminals rather than pipes or files (spec sections 5 and 5.8). */
private fun terminalStatus(): TerminalStatus = try {
    Tty.tryBind()?.use { TerminalStatus(it.isStdinTty(), it.isStdoutTty()) } ?: TerminalStatus(false, false)
} catch (_: Exception) {
    TerminalStatus(false, false)
}

/** Parses arguments, runs the command, and turns every outcome into an exit code (spec section 7). */
class SkillAtlasCli(
    private val scanner: Scanner,
    private val scanLog: ScanLog,
    private val view: ScanView,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val clock: Clock = Clock.systemUTC(),
    /** True when stdin and stdout are both terminals, which `browse` needs. */
    private val interactive: Boolean = false,
) {
    private val multiScanner = MultiScanner(scanner)

    fun run(args: List<String>): Int {
        val command = RootCommand().subcommands(ScanCommand(), BrowseCommand(), ServeCommand())
        return try {
            command.parse(args)
            ExitCode.OK
        } catch (e: ProgramResult) {
            e.statusCode
        } catch (e: PrintHelpMessage) {
            // Help that was asked for goes to stdout; help shown because a subcommand is missing is a usage error.
            val help = e.context?.command?.getFormattedHelp() ?: command.getFormattedHelp()
            (if (e.error) err else out).println(help)
            if (e.error) ExitCode.USAGE else ExitCode.OK
        } catch (e: PrintMessage) {
            (if (e.printError) err else out).println(e.message)
            if (e.printError) ExitCode.USAGE else ExitCode.OK
        } catch (e: CliktError) {
            // Usage errors: unknown options, a missing URL argument, and so on.
            err.println(command.getFormattedHelp(e))
            ExitCode.USAGE
        }
    }

    private class RootCommand : CoreCliktCommand(name = "skill-atlas") {
        init {
            versionOption(VERSION, names = setOf("-V", "--version"), message = { "skill-atlas $it" })
        }

        override fun help(context: Context) = "List the agent skills defined in a GitHub repository."

        override fun run() = Unit
    }

    private inner class ServeCommand : CoreCliktCommand(name = "serve") {
        private val port by option("-p", "--port", metavar = "<port>", help = "Port to listen on; 0 picks a free one")
            .int()
            .default(DEFAULT_PORT)
            .check("must be between 0 and 65535") { it in 0..65535 }

        override fun help(context: Context) = "Serve the local web view on 127.0.0.1."

        override fun run() {
            val server = try {
                WebServer(scanner, scanLog, clock, err::println).start(port)
            } catch (e: IOException) {
                err.println("error: could not listen on 127.0.0.1:$port: ${e.message}")
                throw ProgramResult(ExitCode.INTERNAL_ERROR)
            }
            out.println("Skill Atlas web view: ${server.url}")
            out.println("Press Ctrl-C to stop.")
            out.flush()
            // Serve until the process is stopped.
            CountDownLatch(1).await()
        }
    }

    private inner class BrowseCommand : CoreCliktCommand(name = "browse") {
        private val url by argument(
            name = "github-project-url",
            help = "Repository to scan, e.g. https://github.com/owner/repo",
        )

        override fun help(context: Context) =
            "Scan a GitHub repository, then browse its skills interactively in the terminal."

        override fun run() {
            try {
                if (!interactive) throw NotATerminalException()
                BrowseView(onScanned = ::appendToScanLog).run { progress -> scanner.scan(url, progress) }
            } catch (e: SkillAtlasException) {
                err.println("error: ${e.message}")
                throw ProgramResult(e.exitCode)
            } catch (e: Exception) {
                err.println("error: unexpected failure: ${e.message ?: e.javaClass.name}")
                throw ProgramResult(ExitCode.INTERNAL_ERROR)
            }
        }
    }

    private inner class ScanCommand : CoreCliktCommand(name = "scan") {
        private val urls by argument(
            name = "github-project-url",
            help = "Repositories to scan, e.g. https://github.com/owner/repo; give several to scan them together",
        ).multiple(required = true)

        private val filter by option("-f", "--filter", metavar = "<words>", help = "List only skills whose name or description has every word; repo:<text> narrows repositories")
        private val skill by option("-s", "--skill", metavar = "<name-or-path>", help = "Show one skill's details, similar skills and content")

        override fun help(context: Context) =
            "Scan the default branch of GitHub repositories and list their skills."

        override fun run() {
            if (filter != null && skill != null) {
                err.println("error: --filter and --skill can't be used together")
                throw ProgramResult(ExitCode.USAGE)
            }
            if (urls.size == 1) scanOne(urls.single()) else scanMany(urls)
        }

        private fun scanOne(url: String) {
            val result = try {
                view.show { progress -> present(scanner.scan(url, progress)) }.results.single()
            } catch (e: SkillAtlasException) {
                err.println("error: ${e.message}")
                throw ProgramResult(e.exitCode)
            } catch (e: Exception) {
                err.println("error: unexpected failure: ${e.message ?: e.javaClass.name}")
                throw ProgramResult(ExitCode.INTERNAL_ERROR)
            }

            appendToScanLog(result)
        }

        private fun present(result: ScanResult): Presentation {
            val selector = skill
            if (selector == null) return Presentation.SkillList(result, filter?.takeIf { it.isNotBlank() })
            return try {
                Presentation.detail(result, selector)
            } catch (e: SkillAtlasException) {
                // The scan itself succeeded, so it still counts for the scan log.
                appendToScanLog(result)
                throw e
            }
        }

        /** Several repositories (spec section 5.10): each failure is reported after the others, and the first one sets the exit code. */
        private fun scanMany(urls: List<String>) {
            var outcomes: List<RepositoryOutcome> = emptyList()
            fun finish(extraError: SkillAtlasException? = null): Nothing? {
                outcomes.filterIsInstance<RepositoryOutcome.Scanned>().forEach { appendToScanLog(it.result) }
                val failures = outcomes.filterIsInstance<RepositoryOutcome.Failed>()
                for (failure in failures) err.println("error: ${failure.label}: ${failure.error.message}")
                if (extraError != null) err.println("error: ${extraError.message}")
                val code = failures.firstOrNull()?.error?.exitCode ?: extraError?.exitCode
                if (code != null) throw ProgramResult(code)
                return null
            }

            try {
                view.show { progress ->
                    outcomes = multiScanner.scanAll(urls, progress)
                    presentMany(outcomes)
                }
            } catch (e: SkillAtlasException) {
                finish(extraError = e)
            } catch (e: Exception) {
                err.println("error: unexpected failure: ${e.message ?: e.javaClass.name}")
                throw ProgramResult(ExitCode.INTERNAL_ERROR)
            }
            finish()
        }

        private fun presentMany(outcomes: List<RepositoryOutcome>): Presentation {
            val selector = skill ?: return Presentation.MultiList(outcomes, filter?.takeIf { it.isNotBlank() })
            val results = outcomes.filterIsInstance<RepositoryOutcome.Scanned>().map { it.result }
            // With nothing scanned there is no skill to show; the summary and the errors say why.
            if (results.isEmpty()) return Presentation.MultiList(outcomes)
            return Presentation.detail(results, selector)
        }
    }

    private fun appendToScanLog(result: ScanResult) {
        try {
            scanLog.append(ScanLogEntry.of(result, clock.instant()))
        } catch (e: Exception) {
            err.println("warning: could not write scan log ${scanLog.file}: ${e.message}")
        }
    }
}
