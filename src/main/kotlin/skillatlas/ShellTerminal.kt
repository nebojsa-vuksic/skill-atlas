package skillatlas

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import com.jakewharton.mosaic.Mosaic
import com.jakewharton.mosaic.terminal.Event
import com.jakewharton.mosaic.terminal.KeyboardEvent
import com.jakewharton.mosaic.terminal.Terminal
import com.jakewharton.mosaic.tty.Tty
import com.jakewharton.mosaic.tty.terminal.asTerminalIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Runs [content] like Mosaic's `runMosaicBlocking`, except for keys Mosaic can't name.
 *
 * Mosaic 0.18 throws for any key outside printable ASCII and its named keys, which ends the
 * composition, and the terminal it bound can't be bound again in the same process. The shell
 * must survive a typed `é` or a pasted `—`, so this puts a filter in front of Mosaic's key
 * events: such keys go to [onOtherKey] instead, in the order they were typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun runMosaicWithOtherKeys(onOtherKey: (KeyboardEvent) -> Unit, content: @Composable () -> Unit) = runBlocking {
    val tty = checkNotNull(Tty.tryBind()) { "no terminal" }
    coroutineScope {
        val terminal = tty.asTerminalIn(this)
        terminal.use {
            val named = Channel<Event>(Channel.UNLIMITED)
            val forwarder = launch {
                for (event in terminal.events) {
                    if (event is KeyboardEvent && event.eventType == KeyboardEvent.EventTypePress && !hasKeyName(event.codepoint)) {
                        // Let Mosaic handle the keys typed before this one first, so the order is kept.
                        while (!named.isEmpty) delay(1)
                        onOtherKey(event)
                    } else {
                        named.send(event)
                    }
                }
            }
            val filtered = object : Terminal by terminal {
                override val events = named
            }

            val clock = BroadcastFrameClock()
            val rendering = LiveRendering(terminal.capabilities)
            val mosaic = Mosaic(coroutineContext + clock, onDraw = { print(rendering.render(it)) }, filtered)
            mosaic.setContent(content)
            val frames = launch {
                while (true) {
                    clock.sendFrame(System.nanoTime())
                    delay(1)
                }
            }
            try {
                mosaic.awaitComplete()
            } finally {
                frames.cancel()
                forwarder.cancel()
            }
        }
    }
}

/** The codepoints Mosaic 0.18's `toKeyEventOrNull` turns into keys; anything else makes it throw. */
private fun hasKeyName(codepoint: Int) =
    codepoint == 9 || codepoint == 13 || codepoint == 27 || codepoint in 32..127 || codepoint in 57348..57357 || codepoint in 57364..57398

/**
 * Mosaic's own ANSI rendering, which isn't public: static output is written once above the
 * live area, and the live area is redrawn in place.
 */
private class LiveRendering(private val capabilities: Terminal.Capabilities) {
    private var lastHeight = 0

    fun render(mosaic: Mosaic): String = buildString {
        if (capabilities.synchronizedOutput) append("$CSI?2026h")
        var staleLines = lastHeight
        if (staleLines > 0) append("$CSI${staleLines}F")

        mosaic.static()?.let { static ->
            if (staleLines > 0) {
                append(CLEAR_DISPLAY)
                staleLines = 0
            }
            append(static)
            append("\r\n")
        }

        val surface = mosaic.draw()
        for (row in 0 until surface.height) {
            if (staleLines-- > 0) append(CLEAR_LINE)
            surface.appendRowTo(this, row, capabilities.ansiLevel, capabilities.kittyUnderline)
            append("\r\n")
        }
        if (staleLines > 0) append(CLEAR_DISPLAY)

        if (capabilities.synchronizedOutput) append("$CSI?2026l")
        lastHeight = surface.height
    }

    private companion object {
        const val CSI = "\u001B["
        const val CLEAR_LINE = "${CSI}K"
        const val CLEAR_DISPLAY = "${CSI}J"
    }
}
