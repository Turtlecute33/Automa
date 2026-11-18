package app.automa

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vendored terminal emulator with untrusted output: a file that a program prints (for example `cat` of a file in a
 * cloned repository) must not crash or freeze the app. The emulator runs on the main thread, so an exception kills the
 * app and all its sessions. No process, no Android views.
 */
class TerminalTest {
    private class Out : TerminalOutput() {
        val sent = ByteArrayOutputStream()
        override fun write(data: ByteArray, offset: Int, count: Int) = sent.write(data, offset, count)
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private object Client : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) = Unit
        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private val out = Out()
    private val term = TerminalEmulator(out, 80, 24, 10, 20, 100, Client)

    private fun feed(text: String) {
        val bytes = text.toByteArray()
        term.append(bytes, bytes.size)
    }

    @Test
    fun oscColorResetWithBadIndexesIsIgnored() {
        for (args in listOf("999", "-1", "1;", "x", "1;x;2", ";;", "300;1")) feed("\u001B]104;$args\u0007")
        // A valid list still resets each color.
        term.mColors.mCurrentColors[1] = 42
        term.mColors.mCurrentColors[2] = 42
        feed("\u001B]104;1;2\u0007")
        assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[1], term.mColors.mCurrentColors[1])
        assertEquals(TerminalColors.COLOR_SCHEME.mDefaultColors[2], term.mColors.mCurrentColors[2])
    }

    @Test
    fun backspaceLeftOfTheLeftMarginStaysOnTheScreen() {
        for (margins in listOf("2;4", "10;20")) {
            feed("\u001Bc\u001B[?69h\u001B[${margins}s\b")
            assertEquals(0, term.cursorCol)
            feed("\u001BH\u001B[P\u001B[@\u001B[X\u001B[K")
        }
    }

    @Test
    fun rectangleAttributesStayInTheRegion() {
        // Origin mode adds the margins: the rectangle went past the last row or column.
        feed("\u001B[?6h\u001B[10;24r\u001B[1;1;24;80;1\$r\u001B[1;1;24;80;1\$t")
        feed("\u001Bc\u001B[?69h\u001B[?6h\u001B[10;80s\u001B[1;1;24;80;1\$r")
        // Rectangle mode (DECSACE 2): only the cell of a 1x1 rectangle changes.
        feed("\u001Bc\u001B[2*x\u001B[1;1;1;1;1\$r")
        val bold = { row: Int, col: Int -> TextStyle.decodeEffect(term.screen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0 }
        assertEquals(listOf(true, false, false), listOf(bold(0, 0), bold(0, 1), bold(1, 0)))
    }

    @Test
    fun hugeRepeatCountsStayFast() {
        val start = System.nanoTime()
        // 4 KB of scroll-up inside DECSLRM margins (each scroll copies the region), and of REP.
        feed("\u001B[?69h\u001B[2;80s" + "\u001B[9999S".repeat(585))
        feed("\u001Bc" + "A" + "\u001B[9999b".repeat(585))
        // A combining mark: REP has no graphic character to repeat.
        feed("\u001Bc" + "e\u0301" + "\u001B[9999b".repeat(585))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 3000, "$ms ms")
    }

    /** A session as if its process runs (no real process: nothing reads its input). */
    private fun running() = TerminalSession("/bin/false", "/", arrayOf(), arrayOf(), 100, Client).also {
        TerminalSession::class.java.getDeclaredField("mShellPid").apply { isAccessible = true }.setInt(it, 1)
    }

    @Test
    fun anEmptyWriteIsIgnored() {
        // An empty paste: output can arm it with "ESC ] 52 ; c ; BEL", and the paste removes an ESC-only clip.
        running().write(ByteArray(0), 0, 0)
    }
}
