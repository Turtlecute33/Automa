package app.automa.term

import android.os.FileObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Links that guest programs open with `xdg-open` or `$BROWSER`, for example the login of Claude Code or Codex
 * (DESIGN §5). The guest has no browser: the script writes the link to [FILE] in [DIR], and the terminal screen asks
 * before it opens the Android browser. Only https, at most [MAX] characters, no spaces or control characters.
 */
class Links(private val dir: File) {
    private val _link = MutableStateFlow<String?>(null)

    /** The last link that a program asked to open, until the user opens or dismisses it. */
    val link: StateFlow<String?> = _link.asStateFlow()

    private var watching = false

    // A rename of the finished file is one event (the script writes a temporary file first).
    private val observer = object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == FILE) read()
        }
    }

    /** Starts to watch [DIR]. Only on a ready guest: the setup makes the folder (review SEC-7). Main thread. */
    fun start() {
        if (watching || !dir.isDirectory) return
        observer.startWatching()
        watching = true
    }

    fun clear() {
        _link.value = null
    }

    private fun read() {
        val file = File(dir, FILE)
        val bytes = runCatching { file.inputStream().use { it.readNBytes(MAX + 2) } }.getOrNull()
        file.delete()
        parse(bytes?.decodeToString() ?: return)?.let { _link.value = it }
    }

    companion object {
        const val DIR = "/run/automa"
        const val FILE = "link"
        private const val MAX = 4096

        /** The link in [text], or null when it is not one https link that the user can check. */
        fun parse(text: String): String? = text.removeSuffix("\n").takeIf { link ->
            link.length in 9..MAX && link.startsWith("https://") && link.none { it.isWhitespace() || it.isISOControl() }
        }
    }
}
