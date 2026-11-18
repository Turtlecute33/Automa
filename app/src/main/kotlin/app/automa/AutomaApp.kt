package app.automa

import android.app.Application
import android.content.Context
import android.os.StrictMode
import app.automa.chat.Chats
import app.automa.data.Prefs
import app.automa.data.Secrets
import app.automa.data.Vault
import app.automa.env.Agents
import app.automa.env.Env
import app.automa.env.Setup
import app.automa.env.Tff
import app.automa.git.GitHub
import app.automa.git.Repos
import app.automa.term.Links
import app.automa.term.Look
import app.automa.term.Sessions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** App-wide objects (no DI framework). Heavy parts start on first use, off the main thread. */
class AutomaApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs by lazy { Prefs(this) }
    private val vault by lazy { Vault() }
    val secrets by lazy { Secrets(File(noBackupFilesDir, "secrets.bin"), vault::encrypt, vault::decrypt) }
    val env by lazy { Env(this, secrets) }
    val tff by lazy { Tff(env, secrets) }
    val agents by lazy { Agents(env) }
    val setup by lazy { Setup(env, cacheDir, tff, agents, prefs) }
    val links by lazy { Links(env.file(Links.DIR)) }
    val sessions by lazy { Sessions(this, env) }
    val chats by lazy { Chats(this, env, scope) }
    val repos by lazy { Repos(env) }
    val github by lazy { GitHub(env, secrets, prefs) }

    private val _ready = MutableStateFlow(false)

    /** True after the settings and the setup state are read; the splash screen stays until then. */
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        super.onCreate()
        scope.launch(Dispatchers.IO) {
            // Each step on its own: a failed step must not keep the splash screen for ever or stop the app. A guest
            // program can make a folder with no permissions where a step deletes (review DS-L-19, KM-L-05).
            listOf<suspend () -> Unit>(
                { prefs.load() },
                { secrets.load() },
                { setup.check() },
                { tff.check() },
                { Look.font(this@AutomaApp) },
                { github.cleanUp() },
            ).forEach { step -> runCatching { step() } }
            _ready.value = true
        }
    }
}

val Context.app: AutomaApp get() = applicationContext as AutomaApp
