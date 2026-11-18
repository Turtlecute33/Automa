package app.automa.ui.term

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowInsets
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/** A modifier key of the extra keys (as in Varco): off, on for the next key, or locked on. */
@Stable
class ModKey {
    enum class Mode { OFF, ONCE, LOCKED }

    var mode by mutableStateOf(Mode.OFF)

    /** Reads the key for one key press; a ONCE key turns off. */
    fun consume(): Boolean {
        val on = mode != Mode.OFF
        if (mode == Mode.ONCE) mode = Mode.OFF
        return on
    }

    fun tap() {
        mode = if (mode == Mode.OFF) Mode.ONCE else Mode.OFF
    }

    fun lock() {
        mode = if (mode == Mode.LOCKED) Mode.OFF else Mode.LOCKED
    }
}

/** Ctrl and Alt of the extra keys. */
@Stable
class Modifiers {
    val ctrl = ModKey()
    val alt = ModKey()

    /** KeyHandler modifier bits for one special key, consumed like a key press. */
    fun keyMod(): Int = (if (ctrl.consume()) KeyHandler.KEYMOD_CTRL else 0) or (if (alt.consume()) KeyHandler.KEYMOD_ALT else 0)
}

/** The view callbacks: keyboard on tap, text size on pinch, and the extra-key modifiers (DESIGN §5). */
class ViewClient(
    private val view: TerminalView,
    private val mods: Modifiers,
    private val onFontStep: (bigger: Boolean) -> Unit,
    private val onCopyMode: (Boolean) -> Unit,
) : TerminalViewClient {
    override fun onScale(scale: Float): Float {
        if (scale in 0.9f..1.1f) return scale
        onFontStep(scale > 1f)
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent) = showKeyboard()

    /** The insets controller waits for window focus, so this also works right after the view is made. */
    fun showKeyboard() {
        view.requestFocus()
        view.windowInsetsController?.show(WindowInsets.Type.ime())
    }

    override fun shouldBackButtonBeMappedToEscape() = false

    // Text input with no suggestions: keyboards send each character at once, not a word at the end.
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = onCopyMode(copyMode)
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun onLongPress(event: MotionEvent) = false
    override fun readControlKey() = mods.ctrl.consume()
    override fun readAltKey() = mods.alt.consume()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession) = false
    override fun onEmulatorSet() = Unit

    // The view logs can hold terminal text: no logs.
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
