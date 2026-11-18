package app.automa.git

import androidx.compose.runtime.Immutable
import app.automa.data.Prefs
import app.automa.data.Secrets
import app.automa.env.Env
import app.automa.env.Tree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException

@Immutable
data class Remote(val name: String, val private: Boolean, val description: String)

/**
 * GitHub login with the device flow of the GitHub CLI in the guest (DESIGN §6). The APK has no OAuth client ID.
 * The token goes into [Secrets] as `GH_TOKEN`; the gh config folder of the login is deleted after it.
 */
class GitHub(private val env: Env, private val secrets: Secrets, private val prefs: Prefs) {
    /** The state of a login, for the login sheet. */
    sealed interface Login {
        data object Idle : Login

        /** gh runs; [code] is the one-time code for github.com/login/device when gh has given it. */
        data class Waiting(val code: String?) : Login
        data class Failed(val error: Throwable) : Login
    }

    private val _login = MutableStateFlow<Login>(Login.Idle)
    val login: StateFlow<Login> = _login.asStateFlow()
    private var job: Job? = null

    /**
     * Starts the device flow in [scope] (the app scope). It continues when the sheet goes away, for example when the
     * app lock comes back while the user is in the browser. Only [cancel] stops it.
     */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _login.value = Login.Waiting(null)
        job = scope.launch {
            runCatching { deviceFlow { _login.value = Login.Waiting(it) } }
                .onSuccess { _login.value = Login.Idle }
                .onFailure { if (it !is CancellationException) _login.value = Login.Failed(it) }
        }
    }

    fun cancel() {
        job?.cancel()
        _login.value = Login.Idle
    }

    /**
     * The device flow. gh keeps the token in a temporary config folder until the account is read. Only then, and
     * with no cancel between them, the token goes into [Secrets] and the name into [Prefs] (review SEC-4).
     */
    private suspend fun deviceFlow(onCode: (String) -> Unit) = env.foreground {
        // A new GH_TOKEN would stop gh from a login: gh uses the variable and refuses to log in.
        val gh = listOf("/usr/bin/env", "-u", Secrets.GH_TOKEN, "GH_CONFIG_DIR=$LOGIN_DIR", "gh")
        try {
            cleanUp()
            var last = ""
            val code = env.run(gh + LOGIN) { line ->
                code(line)?.let(onCode)
                if (line.isNotBlank()) last = line.trim().take(200)
            }
            if (code != 0) throw IOException("The GitHub login did not complete: ${last.ifBlank { "exit code $code" }}")
            val token = env.output(gh + listOf("auth", "token"))?.lineSequence()?.lastOrNull()?.trim()
            if (token == null || !validToken(token)) throw IOException("gh gave no token")
            val user = env.output(gh + listOf("api", "user", "--jq", USER_FIELDS))?.lineSequence()?.lastOrNull()
                ?: throw IOException("Cannot read the GitHub account")
            val (login, id, name) = user.split('\t').let { Triple(it[0], it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }
            // The no-reply address keeps the private e-mail address out of commits. A failed write stops the login
            // (review KM-M-13).
            val git = name.ifBlank { login } to "$id+$login@users.noreply.github.com"
            for ((key, value) in listOf("user.name" to git.first, "user.email" to git.second)) {
                val code = env.run(listOf("git", "config", "--global", key, value))
                if (code != 0) throw IOException("Cannot save the git $key (exit code $code)")
            }
            withContext(NonCancellable) {
                secrets.put(Secrets.GH_TOKEN, token)
                // Also the git name and address: a reset deletes ~/.gitconfig, and the setup then writes them to
                // /etc/gitconfig.
                prefs.setLogin(login, git)
            }
        } finally {
            withContext(NonCancellable) { cleanUp() }
        }
    }

    /** Deletes the gh config folder of a login, also one that a stopped process left (review SEC-9). */
    suspend fun cleanUp() = withContext(Dispatchers.IO) { Tree.delete(env.file(LOGIN_DIR)) }

    /**
     * A stop of the app between the token write and the name write leaves a token with no login name: no screen shows
     * it, but each new process gets it. This deletes it (review KM-M-18). After [Prefs.load] and [Secrets.load].
     */
    suspend fun repair() {
        if (prefs.login.value == null && Secrets.GH_TOKEN in secrets.names.value) secrets.remove(Secrets.GH_TOKEN)
    }

    /** Deletes the token on this phone. It stays valid on GitHub until the user revokes it there. */
    suspend fun logout() {
        secrets.remove(Secrets.GH_TOKEN)
        prefs.setLogin(null)
    }

    /** The repositories of the account, newest push first. */
    suspend fun remotes(): List<Remote> {
        val out = env.output(listOf("gh", "repo", "list", "--limit", "200", "--json", "nameWithOwner,isPrivate,description"))
            ?: throw IOException("Cannot read the repositories")
        return parseRemotes(out)
    }

    companion object {
        private const val LOGIN_DIR = "/tmp/automa-gh-login"
        const val DEVICE_URL = "https://github.com/login/device"
        const val TOKENS_URL = "https://github.com/settings/applications"
        private val LOGIN = listOf(
            "auth", "login", "--hostname", "github.com", "--git-protocol", "https", "--web", "--skip-ssh-key",
            "--clipboard=false", "--scopes", "workflow",
        )
        private const val USER_FIELDS = "[.login, (.id|tostring), (.name // \"\")] | join(\"\\t\")"
        private val codePattern = Regex("one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})")

        fun code(line: String): String? = codePattern.find(line)?.groupValues?.get(1)

        fun validToken(token: String) = token.length in 20..255 && token.all { it.isLetterOrDigit() || it == '_' }

        fun parseRemotes(out: String): List<Remote> {
            val start = out.indexOf('[')
            if (start < 0) throw IOException("Cannot read the repositories")
            val array = JSONArray(out.substring(start))
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                Remote(o.getString("nameWithOwner"), o.optBoolean("isPrivate"), if (o.isNull("description")) "" else o.optString("description"))
            }
        }
    }
}
