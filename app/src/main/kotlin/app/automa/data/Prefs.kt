package app.automa.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.DocumentsContract
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small settings that are not secret. [load] reads them off the main thread; writes go to memory at once and to disk later. */
class Prefs(private val context: Context) {
    private lateinit var prefs: SharedPreferences

    private val _lock = MutableStateFlow(false)
    private val _login = MutableStateFlow<String?>(null)
    private val _fontSize = MutableStateFlow(DEFAULT_FONT)

    /** App lock with biometrics or the device credential (DESIGN §9). */
    val lock: StateFlow<Boolean> = _lock.asStateFlow()

    /** The GitHub login name after a login (DESIGN §6). The token is in [Secrets]. */
    val login: StateFlow<String?> = _login.asStateFlow()

    /** Terminal text size in sp. */
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    fun load() {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        // A value of another type (a changed file) must not open the app with no lock (review DS-L-19).
        _lock.value = runCatching { prefs.getBoolean(LOCK, false) }.getOrDefault(true)
        _login.value = prefs.getString(LOGIN, null)
        _fontSize.value = prefs.getInt(FONT, DEFAULT_FONT)
    }

    fun setLock(on: Boolean) {
        _lock.value = on
        prefs.edit { putBoolean(LOCK, on) }
        // The Files app reads the roots again: no home folder while the lock is on.
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri("${context.packageName}.home"), null)
    }

    /**
     * Sets the GitHub login [name] and its git name and e-mail address [gitUser] (the no-reply address) in one write.
     * The setup writes [gitUser] to /etc/gitconfig (review KM-M-13).
     */
    fun setLogin(name: String?, gitUser: Pair<String, String>? = null) {
        _login.value = name
        prefs.edit {
            putString(LOGIN, name)
            putString(GIT_NAME, gitUser?.first)
            putString(GIT_EMAIL, gitUser?.second)
        }
    }

    /** The git name and e-mail address of the GitHub login, or null. */
    fun gitUser(): Pair<String, String>? = runCatching {
        (prefs.getString(GIT_NAME, null) ?: return null) to (prefs.getString(GIT_EMAIL, null) ?: return null)
    }.getOrNull()

    fun setFontSize(sp: Int) {
        _fontSize.value = sp.coerceIn(MIN_FONT, MAX_FONT)
        prefs.edit { putInt(FONT, _fontSize.value) }
    }

    companion object {
        /** For the documents provider, which can run before [load]. */
        fun lockOn(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean(LOCK, false)

        private const val LOCK = "lock"
        private const val LOGIN = "github_login"
        private const val FONT = "font_size"
        private const val GIT_NAME = "git_name"
        private const val GIT_EMAIL = "git_email"
        const val DEFAULT_FONT = 12
        const val MIN_FONT = 7
        const val MAX_FONT = 28
    }
}
