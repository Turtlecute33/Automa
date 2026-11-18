package app.automa

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
