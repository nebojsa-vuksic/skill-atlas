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
import com.github.ajalt.clikt.parameters.options.versionOption
import java.io.PrintStream
import java.time.Clock
import kotlin.system.exitProcess

val VERSION: String = SkillAtlasCli::class.java.`package`?.implementationVersion ?: "dev"

fun main(args: Array<String>) {
    val token = System.getenv("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
    val scanner = Scanner(
        github = GitHubClient(token = token),
        git = Git(token = token),
        progress = System.err::println,
    )
    val cli = SkillAtlasCli(scanner, ScanLog(ScanLog.defaultLocation()))
    exitProcess(cli.run(args.toList()))
}

/** Parses arguments, runs the command, and turns every outcome into an exit code (spec section 7). */
class SkillAtlasCli(
    private val scanner: Scanner,
    private val scanLog: ScanLog,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun run(args: List<String>): Int {
        val command = RootCommand().subcommands(ScanCommand())
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

    private inner class ScanCommand : CoreCliktCommand(name = "scan") {
        private val url by argument(
            name = "github-project-url",
            help = "Repository to scan, e.g. https://github.com/owner/repo",
        )

        override fun help(context: Context) =
            "Scan the default branch of a GitHub repository and list its skills."

        override fun run() {
            val result = try {
                scanner.scan(url)
            } catch (e: SkillAtlasException) {
                err.println("error: ${e.message}")
                throw ProgramResult(e.exitCode)
            } catch (e: Exception) {
                err.println("error: unexpected failure: ${e.message ?: e.javaClass.name}")
                throw ProgramResult(ExitCode.INTERNAL_ERROR)
            }

            out.print(TextReport.render(result))
            out.flush()

            try {
                scanLog.append(ScanLogEntry.of(result, clock.instant()))
            } catch (e: Exception) {
                err.println("warning: could not write scan log ${scanLog.file}: ${e.message}")
            }
        }
    }
}
