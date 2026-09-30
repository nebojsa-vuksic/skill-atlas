package skillatlas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.jakewharton.mosaic.LocalTerminalState
import com.jakewharton.mosaic.StaticEffect
import com.jakewharton.mosaic.layout.KeyEvent
import com.jakewharton.mosaic.layout.onKeyEvent
import com.jakewharton.mosaic.layout.padding
import com.jakewharton.mosaic.modifier.Modifier
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.ui.Color
import com.jakewharton.mosaic.ui.Column
import com.jakewharton.mosaic.ui.Row
import com.jakewharton.mosaic.ui.Text
import com.jakewharton.mosaic.ui.TextStyle
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

/** `skill-atlas shell`: the prompt and palette, with each command's output printed above them (spec section 5.9). */
class ShellView(private val session: ShellSession) {
    fun run() {
        val shell = ShellController(session)
        try {
            runMosaicWithOtherKeys(shell::onOtherKey) { ShellApp(shell) }
        } finally {
            session.close()
        }
    }
}

/** What the live area shows. */
sealed interface ShellMode {
    data object Prompt : ShellMode
    /** A `/scan` in progress; [id] tells two scans of the same URL apart. */
    data class Scanning(val url: String, val id: Long) : ShellMode
    data class Browsing(val state: BrowseState) : ShellMode
}

/** The shell's state across compositions: the prompt, what's running, and the output still to print. */
class ShellController(private val session: ShellSession) {
    val prompt = ShellState { session.repository?.result?.skills }
    var mode by mutableStateOf<ShellMode>(ShellMode.Prompt)
        private set
    var status by mutableStateOf("Starting")
        private set
    var quit by mutableStateOf(false)
        private set

    /** Blocks not yet printed, with ids so each is printed exactly once. */
    val pending = mutableStateListOf<Pair<Long, ShellBlock>>()
    private var nextId = 0L

    init {
        session.onWarning = { print(ShellBlock.Message(it, Look.WARNING)) }
    }

    fun print(vararg blocks: ShellBlock) = synchronized(pending) {
        for (block in blocks) pending.add(nextId++ to block)
    }

    /** Called once [printed] are in the scrollback. */
    fun printed(printed: List<Pair<Long, ShellBlock>>) = synchronized(pending) {
        pending.removeAll(printed)
    }

    fun onKey(event: KeyEvent): Boolean {
        when (mode) {
            // The browse view handles its own keys; the rest are swallowed so they don't reach the prompt.
            is ShellMode.Browsing -> Unit
            is ShellMode.Scanning -> if (event.ctrl && event.key.equals("c", ignoreCase = true)) interruptScan()
            ShellMode.Prompt -> when (val action = prompt.onKey(event.key, event.ctrl, event.alt)) {
                is ShellAction.Run -> run(action.line)
                ShellAction.Quit -> quit = true
                null -> Unit
            }
        }
        return true
    }

    /** A key Mosaic can't name: a character outside ASCII is typed into the prompt, anything else is ignored. */
    fun onOtherKey(event: KeyboardEvent) {
        val codepoint = event.codepoint
        val printable = Character.isValidCodePoint(codepoint) && !Character.isISOControl(codepoint) &&
            Character.getType(codepoint) != Character.PRIVATE_USE.toInt()
        if (mode == ShellMode.Prompt && printable && !event.ctrl && !event.alt) {
            prompt.onKey(String(Character.toChars(codepoint)))
        }
    }

    fun endBrowsing() {
        mode = ShellMode.Prompt
    }

    /**
     * Leaving the scanning mode disposes the effect that runs [scan], which cancels it and
     * interrupts its thread. The result of a scan that finishes anyway is dropped.
     */
    private fun interruptScan() {
        print(ShellBlock.Error(ScanInterruptedException().message.orEmpty()), ShellBlock.Message(""))
        mode = ShellMode.Prompt
    }

    private fun run(line: String) {
        print(ShellBlock.Echo(line))
        val outcome = try {
            session.execute(line)
        } catch (e: Exception) {
            ShellOutcome.Print(listOf(ShellBlock.Error("unexpected failure: ${e.message ?: e.javaClass.name}")))
        }
        when (outcome) {
            is ShellOutcome.Print -> print(*outcome.blocks.toTypedArray(), ShellBlock.Message(""))
            is ShellOutcome.Scan -> {
                status = "Starting"
                mode = ShellMode.Scanning(outcome.url, nextId)
            }
            is ShellOutcome.Browse -> mode = ShellMode.Browsing(outcome.state)
            ShellOutcome.Quit -> quit = true
        }
    }

    /** Runs the scan of [scanning], from an effect that lives as long as that mode. */
    suspend fun scan(scanning: ShellMode.Scanning) {
        val blocks = try {
            val scanned = inBackground { session.scan(scanning.url) { status = it.removeSuffix("...") } }
            currentCoroutineContext().ensureActive()
            session.adopt(scanned)
        } catch (e: SkillAtlasException) {
            listOf(ShellBlock.Error(e.message.orEmpty()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            listOf(ShellBlock.Error("unexpected failure: ${e.message ?: e.javaClass.name}"))
        }
        print(*blocks.toTypedArray(), ShellBlock.Message(""))
        mode = ShellMode.Prompt
    }

    /**
     * Runs [block] on its own thread. Cancelling interrupts the thread and returns at once,
     * so the prompt comes back even if the blocking work takes a moment to notice.
     */
    private suspend fun <T> inBackground(block: () -> T): T {
        val result = CompletableDeferred<T>()
        val worker = thread(isDaemon = true, name = "skill-atlas-shell-scan") {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        try {
            return result.await()
        } catch (e: CancellationException) {
            worker.interrupt()
            throw e
        }
    }
}

@Composable
internal fun ShellApp(shell: ShellController) {
    // Keeps the composition, and so the program, alive until the user quits.
    LaunchedEffect(Unit) { snapshotFlow { shell.quit }.first { it } }
    val columns = LocalTerminalState.current.size.columns

    Column(modifier = Modifier.onKeyEvent { shell.onKey(it) }) {
        // Each block is printed once into the scrollback, then dropped.
        val pending = shell.pending.toList()
        for ((id, block) in pending) {
            key(id) { StaticEffect { ShellBlockView(block, columns) } }
        }
        SideEffect { shell.printed(pending) }

        if (!shell.quit) {
            when (val mode = shell.mode) {
                ShellMode.Prompt -> PromptArea(shell.prompt, columns)
                is ShellMode.Scanning -> {
                    LaunchedEffect(mode) { shell.scan(mode) }
                    StatusLine(shell.status)
                }
                is ShellMode.Browsing -> {
                    LaunchedEffect(mode) {
                        snapshotFlow { mode.state.quit }.first { it }
                        shell.endBrowsing()
                    }
                    if (!mode.state.quit) Browser(mode.state)
                }
            }
        }
    }
}

/** The live prompt and palette. */
@Composable
internal fun PromptArea(state: ShellState, columns: Int) {
    Column {
        for (line in ShellScreen.render(state, columns)) {
            Row {
                if (line.isEmpty()) Text(" ")
                for (span in line) LookText(span)
            }
        }
    }
}

/** One block of output, as it appears in the scrollback. */
@Composable
internal fun ShellBlockView(block: ShellBlock, columns: Int) {
    when (block) {
        is ShellBlock.Echo -> Text("${ShellScreen.PROMPT}${sanitize(block.line)}", textStyle = TextStyle.Dim)
        is ShellBlock.Report -> Report(block.presentation, columns)
        is ShellBlock.Similar -> Column(modifier = Modifier.padding(horizontal = 1)) {
            Row {
                Text("Similar to ", textStyle = TextStyle.Dim)
                Text(sanitize(block.detail.skill.name), color = Color.Cyan, textStyle = TextStyle.Bold)
                Text("  ${sanitize(block.detail.skill.path)}", textStyle = TextStyle.Dim)
            }
            SimilarRows(block.detail.similar)
        }
        is ShellBlock.Repository -> Column(modifier = Modifier.padding(horizontal = 1)) {
            RepositoryHeader(block.result)
            Text("")
            SkillCount(block.result.skills.size)
            if (block.result.ignored.isNotEmpty()) Text(ignoredHeading(block.result.ignored.size), textStyle = TextStyle.Dim)
        }
        is ShellBlock.Log -> Column(modifier = Modifier.padding(horizontal = 1)) {
            val width = block.entries.maxOf { sanitize(it.repository).length }
            val branchWidth = block.entries.maxOf { sanitize(it.branch).length }
            for (entry in block.entries) {
                Row {
                    Text("${entry.scannedAt}  ", textStyle = TextStyle.Dim)
                    Text(sanitize(entry.repository).padEnd(width), color = Color.Cyan, textStyle = TextStyle.Bold)
                    Text("  ")
                    Text(entry.commit.take(12), color = Color.Yellow)
                    Text("  ")
                    Text(sanitize(entry.branch).padEnd(branchWidth), color = Color.Magenta)
                    Text("  ${entry.skillCount} ${if (entry.skillCount == 1) "skill" else "skills"}")
                }
            }
        }
        ShellBlock.Help -> Column(modifier = Modifier.padding(horizontal = 1)) {
            Text("Commands", textStyle = TextStyle.Bold)
            val width = ShellCommands.ALL.maxOf { it.label.length }
            for (command in ShellCommands.ALL) {
                Row {
                    Text("  ${command.label.padEnd(width)}  ", color = Color.Cyan)
                    Text(command.description, textStyle = TextStyle.Dim)
                }
            }
            Text("")
            Text("Keys", textStyle = TextStyle.Bold)
            for ((keys, effect) in HELP_KEYS) {
                Row {
                    Text("  ${keys.padEnd(HELP_KEYS.maxOf { it.first.length })}  ", color = Color.Cyan)
                    Text(effect, textStyle = TextStyle.Dim)
                }
            }
            Text("")
            Text("Text without a / filters the current repository's skills.", textStyle = TextStyle.Dim)
        }
        is ShellBlock.Message -> Column(modifier = Modifier.padding(horizontal = 1)) {
            if (block.look == Look.WARNING) Text(block.text, color = Color.Yellow) else Text(block.text)
        }
        is ShellBlock.Error -> Column(modifier = Modifier.padding(horizontal = 1)) {
            Text("error: ${sanitize(block.message)}", color = Color.Red)
        }
    }
}

private val HELP_KEYS = listOf(
    "/" to "open the command palette",
    "↑ ↓" to "move in the palette, or walk the history",
    "tab" to "complete the selected command or skill",
    "enter" to "run the input or the selected command",
    "esc" to "close the palette",
    "ctrl-c" to "clear the input, or quit when it's empty; cancel a scan",
    "ctrl-d" to "quit",
)
