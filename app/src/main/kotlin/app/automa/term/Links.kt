package app.automa.term

import android.os.FileObserver
import app.automa.env.Tree
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

    // A rename of the finished file is one event (the script writes a temporary file first).
    private val observer = object : FileObserver(dir, MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path == FILE) read()
        }
    }

    /**
     * Starts to watch [DIR], again at each call: a reset deletes the folder, and the watch goes with it (review
     * KM-S-05). Then reads a link that came while there was no watch. Only on a ready guest: the setup makes the folder
     * (review SEC-7). Not on the main thread.
     */
    @Synchronized
    fun start() {
        if (!dir.isDirectory) return
        observer.stopWatching()
        observer.startWatching()
        read()
    }

    fun clear() {
        _link.value = null
    }

    @Synchronized
    private fun read() {
        val file = File(dir, FILE)
        // Not a FIFO: its open would stop the observer thread of the app (review DS-L-19).
        val text = Tree.readSmall(file, MAX + 1)
        file.delete()
        parse(text ?: return)?.let { _link.value = it }
    }

    companion object {
        const val DIR = "/run/automa"
        const val FILE = "link"
        private const val MAX = 4096

        /** The link in [text], or null when it is not one https link that the user can check. */
        fun parse(text: String): String? = text.removeSuffix("\n").takeIf { link -> link.length in 9..MAX && checked(link) }

        /**
         * An https link whose host is plain ASCII (letters, digits, dots, hyphens; punycode for other scripts) with an
         * optional port, and with no space or control character. Then the host on the screen is the host that the
         * browser opens: Android shows no host for `https:///evil.example` or `https://\evil.example`, but the browser
         * opens evil.example; and a host in look-alike letters reads as another host. Also for chat links.
         */
        fun checked(link: String) = PLAIN.matches(link) && link.none { it.isWhitespace() || it.isISOControl() }

        private val PLAIN = Regex("""https://[A-Za-z0-9.-]+(:[0-9]{1,5})?([/?#].*)?""")
    }
}
