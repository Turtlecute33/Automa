package app.automa

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalRow
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.terminal.TextStyle
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun aWriteNeverWaitsForTheProgram() {
        // The program reads nothing (a hung program, or a pasted text while "sleep" runs). The main thread waited for
        // ever when the 4 KB queue was full: "App not responding", and Ctrl-C could not go out.
        val session = running()
        val writer = thread(isDaemon = true) {
            session.write(ByteArray(64 shl 10), 0, 64 shl 10)
            session.write(ByteArray(4 shl 20), 0, 4 shl 20)
            session.write(ByteArray(1), 0, 1)
        }
        writer.join(5000)
        assertFalse(writer.isAlive)
    }

    @Test
    fun termcapQueriesDoNotEchoTextIntoTheInput() {
        // XTGETTCAP: the reply goes to the program input (the shell prompt). Only hex names get a reply.
        feed("\u001BP+qrm -rf ~\u001B\\\u001BP+q\u0003\u0004\u0015\u001C\u001B\\\u001BP+q;;\u001B\\")
        assertEquals("", out.sent.toString())
        feed("\u001BP+q6B75\u001B\\")
        assertEquals("\u001BP1+r6B75=1B5B41\u001B\\", out.sent.toString())
    }

    @Test
    fun aSelectionAtTheRightEdgeStaysInTheRow() {
        // A long press in the sliver right of the last column gives column 80. On a row that grew (combining marks),
        // the column search read past the end of the row.
        feed("e\u0301\u0302".repeat(80))
        assertEquals("", term.screen.getSelectedText(80, 0, 80, 0))
    }

    @Test
    fun copiedTextHasNoHiddenControlCharacters() {
        // Line drawing "d" and "h" stored CR and LF, and CAN stored DEL: the copy had characters that the screen did
        // not show (a pasted LF can run a command).
        feed("echo safe\u001B(0h\u001B(Bid")
        feed("\r\nA\u001B[3\u0018B")
        val text = term.screen.getSelectedText(0, 0, 79, 1)
        assertFalse(text.any { it == '\r' || it == '\u007F' } || text.lines().size != 2, text)
        assertEquals(listOf("echo safe\u2424id", "AB"), text.lines())
    }

    @Test
    fun termcapPageKeysAreNotSwapped() {
        // "kN" (next page) is PgDn, ESC [ 6 ~; "kP" (previous page) is PgUp, ESC [ 5 ~. vim asks for them.
        feed("\u001BP+q6B4E;6B50\u001B\\")
        assertEquals("\u001BP1+r6B4E=1B5B367E\u001B\\\u001BP1+r6B50=1B5B357E\u001B\\", out.sent.toString())
    }

    @Test
    fun aColumnChangeMakesOnlyTheScreenRows() {
        // KM-Q-02, DS-L-65: a pinch or a rotation made all 3000 history rows at once, on the main thread.
        val big = TerminalEmulator(out, 80, 24, 10, 20, 3000, Client)
        val text = "a\r\nb".toByteArray()
        big.append(text, text.size)
        big.resize(100, 24, 10, 20)
        val lines = TerminalBuffer::class.java.getDeclaredField("mLines").apply { isAccessible = true }.get(big.screen) as Array<*>
        assertTrue(lines.count { it != null } <= 24)
        // The history still works: more lines than the ring buffer holds.
        val more = "x\r\n".repeat(3100).toByteArray()
        big.append(more, more.size)
        assertEquals(3000 - 24, big.screen.activeTranscriptRows)
        assertEquals("x", big.screen.getSelectedText(0, -1, 99, -1))
    }

    @Test
    fun aClearedRowGivesBackItsLargeText() {
        // KM-Q-01(b): combining marks make the text of a row grow. A clear kept the large array for the life of the row.
        val row = TerminalRow(80, 0)
        val size = row.mText.size
        for (col in 0 until 80) {
            row.setChar(col, 'e'.code, 0)
            repeat(15) { row.setChar(col, 0x301, 0) }
        }
        assertTrue(row.mText.size > size)
        row.clear(0)
        assertEquals(size, row.mText.size)
        assertEquals(80, row.spaceUsed)
    }

    @Test
    fun anApcWithNoEndDoesNotHideTheOutput() {
        // "ESC _" (APC) with no end hid all later output. CAN and SUB end it. An ESC that does not start the end (ESC \)
        // also ends it and starts its own sequence, as in xterm: here "ESC [ 1 m" (bold).
        feed("\u001B_Gjunk\u0018A")
        feed("\u001B_Gjunk\u001AB")
        feed("\u001B_Gjunk\u001B[1mC")
        // A complete APC (kitty graphics) shows nothing.
        feed("\u001B_Ga=T;QUJD\u001B\\D")
        assertEquals("ABCD", term.screen.getSelectedText(0, 0, 79, 0))
        assertTrue(TextStyle.decodeEffect(term.screen.getStyleAt(0, 2)) and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0)
    }

    @Test
    fun onlyEscBackslashEndsADeviceControlString() {
        // KM-N-06: a bare \ ended the DCS, and the rest showed as text. Sixel data has \ (0x5C is a sixel).
        feed("\u001BPq#0;2;0;0;0#0\\~-\\\\~\u001B\\A")
        // An ESC before another character stays in the string, as before (tmux passthrough doubles each ESC).
        feed("\u001BPtmux;\u001B\u001B[31m\u001B\\B")
        assertEquals("AB", term.screen.getSelectedText(0, 0, 79, 0))
        assertEquals(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.decodeForeColor(term.screen.getStyleAt(0, 1)))
        // A query still gets its reply.
        feed("\u001BP+q6B75\u001B\\")
        assertEquals("\u001BP1+r6B75=1B5B41\u001B\\", out.sent.toString())
    }

    @Test
    fun aFullResetLeavesTheAlternateScreenAndTheStyle() {
        // KM-N-07: RIS (ESC c) stayed on the alternate screen, kept bold, and REP repeated the character from before it.
        feed("\u001B[?1049h\u001B[1mX\u001Bc")
        assertFalse(term.isAlternateBufferActive)
        feed("A")
        assertEquals(0, TextStyle.decodeEffect(term.screen.getStyleAt(0, 0)))
        feed("\u001Bc\u001B[3b")
        assertEquals("", term.screen.getSelectedText(0, 0, 79, 0))
    }

    /** A private Int field of the emulator, for the margins. */
    private fun field(name: String) = TerminalEmulator::class.java.getDeclaredField(name).apply { isAccessible = true }.getInt(term)

    @Test
    fun anInvertedRegionIsIgnored() {
        // KM-N-08: xterm ignores a region whose top is not above its bottom. Upstream made another region from it and
        // moved the cursor.
        feed("\u001B[5;10r\u001B[3;7H\u001B[10;2r\u001B[30;40r\u001B[7;7r")
        assertEquals(listOf(4, 10, 2, 6), listOf(field("mTopMargin"), field("mBottomMargin"), term.cursorRow, term.cursorCol))
        feed("\u001B[7;8r")
        assertEquals(listOf(6, 8, 0, 0), listOf(field("mTopMargin"), field("mBottomMargin"), term.cursorRow, term.cursorCol))
        // The same for the left and right margins (DECSLRM).
        feed("\u001B[?69h\u001B[5;10s\u001B[3;7H\u001B[10;2s\u001B[90;99s\u001B[7;7s")
        assertEquals(listOf(4, 10, 2, 6), listOf(field("mLeftMargin"), field("mRightMargin"), term.cursorRow, term.cursorCol))
    }
}
