package app.automa.term

import android.content.Context
import android.graphics.Typeface
import app.automa.R
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle

/**
 * The terminal look (DESIGN §5), the same as Varco: Catppuccin Mocha colours and JetBrains Mono. The app theme
 * and the opencode theme (`catppuccin`) use the same colours, so the app looks like one surface.
 */
object Look {
    const val FOREGROUND = 0xFFCDD6F4.toInt()
    const val BACKGROUND = 0xFF1E1E2E.toInt()
    private const val CURSOR = 0xFFF5E0DC.toInt()

    /** The 16 ANSI colours: black, red, green, yellow, blue, magenta, cyan, white, then the bright ones. */
    val ANSI = intArrayOf(
        0x45475a, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xbac2de,
        0x585b70, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xa6adc8,
    ).map { it or 0xFF000000.toInt() }.toIntArray()

    /** Sets the default colours of the terminal engine (one shared scheme), before the first session starts. */
    fun apply() {
        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        ANSI.copyInto(colors, 0)
        colors[TextStyle.COLOR_INDEX_FOREGROUND] = FOREGROUND
        colors[TextStyle.COLOR_INDEX_BACKGROUND] = BACKGROUND
        colors[TextStyle.COLOR_INDEX_CURSOR] = CURSOR
    }

    /** The font after [font] read it once. A new view sets it before its first layout. */
    @Volatile var cached: Typeface? = null
        private set

    /** JetBrains Mono. Reads a file the first time: not on the main thread. */
    fun font(context: Context): Typeface = cached ?: context.resources.getFont(R.font.jetbrains_mono).also { cached = it }
}
