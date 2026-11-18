package app.automa.env

import android.system.Os
import androidx.compose.runtime.Immutable
import app.automa.term.Links
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * Installs and updates the guest system (DESIGN §3). Each step can run again, so a failed or stopped setup
 * continues at the next start.
 */
class Setup(private val env: Env, private val cache: File, private val tff: Tff, private val agents: Agents) {
    enum class Step(val label: String) {
        Rootfs("Download Alpine Linux"),
        Configure("Write the settings"),
        Packages("Install the packages"),
        Opencode("Download opencode"),
    }

    @Immutable
    sealed interface State {
        data object Checking : State

        /** No guest system, or one from an older setup version ([update]). */
        data class Needed(val update: Boolean) : State
        data class Running(val step: Step, val progress: Float?, val log: List<String>) : State

        /** [blocked]: Android does not let PRoot use ptrace (GrapheneOS "Native code debugging" off). */
        data class Failed(val step: Step, val message: String, val log: List<String>, val blocked: Boolean = false) : State
        data object Ready : State
    }

    /** The in-app update (DESIGN §3): its progress or its result. */
    @Immutable
    sealed interface Update {
        data object Idle : Update
        data class Running(val text: String, val progress: Float?) : Update
        data class Done(val text: String) : Update
        data class Failed(val text: String) : Update
    }

    data class Release(val version: String, val sha256: String)

    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _update = MutableStateFlow<Update>(Update.Idle)
    val update: StateFlow<Update> = _update.asStateFlow()

    private val _opencode = MutableStateFlow<String?>(null)

    /** The installed opencode version, or null. */
    val opencode: StateFlow<String?> = _opencode.asStateFlow()

    private val marker get() = env.file("/etc/automa-setup")
    private val opencodeMarker get() = env.file("/etc/automa-opencode")

    /** Reads the setup version. Also deletes what a stopped reset left (the rootfs step deletes a stopped extract). */
    suspend fun check() {
        val version = withContext(Dispatchers.IO) {
            // A failed delete must not stop the start (review DS-L-19): the next check or reset deletes it again.
            runCatching { Tree.delete(old) }
            _opencode.value = installedOpencode()
            agents.check()
            Tree.readSmall(marker)?.trim()?.toIntOrNull()
        }
        _state.value = when (version) {
            Pins.SETUP_VERSION -> State.Ready
            null -> State.Needed(update = false)
            else -> State.Needed(update = true)
        }
    }

    private val running = Mutex()

    /** Runs the setup steps. A second call while one runs does nothing (a double tap). */
    suspend fun run() {
        if (!running.tryLock()) return
        try {
            env.foreground { steps() }
        } finally {
            running.unlock()
        }
    }

    private suspend fun steps() {
        val steps = withContext(Dispatchers.IO) {
            plan(rootfsReady = env.rootfs.isDirectory, opencode = installedOpencode())
        }
        var current = steps.first()
        val log = ArrayDeque<String>()
        fun show(progress: Float?) {
            _state.value = State.Running(current, progress, synchronized(log) { log.toList() })
        }
        val line: (String) -> Unit = { text ->
            synchronized(log) {
                log.addLast(text)
                while (log.size > LOG_LINES) log.removeFirst()
            }
            show(null)
        }
        try {
            for (step in steps) {
                current = step
                show(null)
                when (step) {
                    Step.Rootfs -> rootfs(::show)
                    Step.Configure -> configure()
                    Step.Packages -> packages(line)
                    Step.Opencode -> opencode(Pins.OPENCODE_VERSION, Pins.OPENCODE_SHA256, ::show)
                }
            }
            withContext(Dispatchers.IO) { marker.writeText("${Pins.SETUP_VERSION}\n") }
            _state.value = State.Ready
        } catch (e: CancellationException) {
            _state.value = State.Needed(update = env.rootfs.isDirectory)
            throw e
        } catch (e: Exception) {
            val lines = synchronized(log) { log.toList() }
            val blocked = lines.any { PTRACE in it }
            _state.value = State.Failed(current, if (blocked) BLOCKED else e.message ?: e.javaClass.simpleName, lines, blocked)
        }
    }

    /**
     * Updates the Alpine packages, and opencode to its newest stable release (DESIGN §3). The SHA-256 of the
     * archive comes from the GitHub release data. Only on a ready guest system; a second call while the setup,
     * a reset or an update runs does nothing. Open sessions continue (the old opencode file is replaced, not changed).
     */
    suspend fun update() {
        if (_state.value != State.Ready || !running.tryLock()) return
        var blocked = false
        try {
            env.foreground { updateSteps { blocked = true } }
        } catch (e: CancellationException) {
            _update.value = Update.Idle
            throw e
        } catch (e: Exception) {
            _update.value = Update.Failed(if (blocked) BLOCKED else e.message ?: e.javaClass.simpleName)
        } finally {
            running.unlock()
        }
    }

    private suspend fun updateSteps(onBlocked: () -> Unit) {
        _update.value = Update.Running("Update the packages", null)
        val code = env.run(listOf("apk", "upgrade", "-U")) { if (PTRACE in it) onBlocked() }
        if (code != 0) throw IOException("apk upgrade failed (exit code $code)")
        // The installed agents: each to its newest stable release (DESIGN §3). Before opencode: when the release list
        // has no 2.x release (only the first 30 releases come), the error must not also stop the agent updates.
        for ((agent, version) in agents.installed.value) {
            _update.value = Update.Running("Look for a new ${agent.label}", null)
            val newest = agents.latest(agent)
            if (newer(newest.version, version)) {
                _update.value = Update.Running("Update ${agent.label} to ${newest.version}", null)
                agents.install(agent, newest)
            }
        }
        _update.value = Update.Running("Look for a new opencode", null)
        val release = newest(Download.text(Pins.OPENCODE_RELEASES, RELEASE_MAX_BYTES))
            ?: throw IOException("Unexpected release data from GitHub")
        if (newer(release.version, _opencode.value)) {
            opencode(release.version, release.sha256) { _update.value = Update.Running("Download opencode ${release.version}", it) }
        }
        val done = listOf("opencode ${_opencode.value}") + agents.installed.value.map { (agent, version) -> "${agent.label} $version" }
        _update.value = Update.Done("Up to date: ${done.joinToString(", ")}")
    }

    /**
     * Runs [block] only on a ready guest system and not while the setup, a reset or an update runs (a write into a
     * missing guest would make an empty one, review SEC-7). Returns false when it did not run.
     */
    private suspend fun exclusive(block: suspend () -> Unit): Boolean {
        if (_state.value != State.Ready || !running.tryLock()) return false
        try {
            block()
        } finally {
            running.unlock()
        }
        return true
    }

    /** Loads the TFF model list (DESIGN §8). [Tff.status] shows the result. */
    suspend fun models() {
        exclusive { loadModels() }
    }

    /** Installs the pinned release of [agent] (DESIGN §3). [Agents.work] shows the progress and the error. */
    suspend fun install(agent: Agent) {
        exclusive {
            try {
                env.foreground { agents.install(agent, Agents.pinned(agent)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Deletes [agent]. Its login and settings stay in the home folder. */
    suspend fun remove(agent: Agent) {
        exclusive { runCatching { agents.remove(agent) } }
    }

    /** A TFF error does not stop the setup: [Tff.status] shows it, and the keys screen can load the list again. */
    private suspend fun loadModels() {
        try {
            tff.load()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    /**
     * The opencode version from its marker, or null when the program is missing: then Update installs it again (a
     * marker with no program kept the state Ready, and Update saw no newer version).
     */
    private fun installedOpencode() =
        Tree.readSmall(opencodeMarker)?.trim()?.takeIf { env.file("/usr/local/bin/opencode").isFile }

    /**
     * Deletes the guest system, the repositories in it included. The secrets stay. The state changes first, so that
     * the home screen opens no new session or chat; then [stop] ends the open ones and waits for them, and only then
     * the files go (review DS-L-07, KM-M-11). A rename comes first, so that a stop during the delete cannot leave a
     * guest system with no programs that counts as ready (review SEC-7).
     */
    suspend fun reset(stop: suspend () -> Unit) = running.withLock {
        _state.value = State.Needed(update = false)
        try {
            stop()
            withContext(Dispatchers.IO) {
                Tree.delete(old)
                if (env.rootfs.exists() && !env.rootfs.renameTo(old)) throw IOException("Cannot delete the environment")
            }
        } catch (e: Exception) {
            // Nothing is deleted: the state of the guest system again.
            withContext(NonCancellable) { check() }
            throw e
        }
        _opencode.value = null
        agents.clear()
        withContext(Dispatchers.IO) { Tree.delete(old) }
    }

    private val old get() = File(env.rootfs.path + ".old")

    private suspend fun rootfs(progress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(cache, "alpine.tar.gz")
        Download.fetch(Pins.ALPINE_URL, archive, Pins.ALPINE_SHA256, Pins.ALPINE_MAX_BYTES, progress)
        progress(null)
        val part = File(env.rootfs.path + ".part")
        Tree.delete(part)
        try {
            GZIPInputStream(archive.inputStream().buffered()).use { Tar.extract(it, part) }
            if (!part.renameTo(env.rootfs)) throw IOException("Cannot save the guest system")
        } finally {
            archive.delete()
            Tree.delete(part)
        }
    }

    private suspend fun configure() = withContext(Dispatchers.IO) {
        write("/etc/profile.d/automa.sh", PROFILE)
        write("/etc/gitconfig", GITCONFIG)
        // The user can change these files: write them only when they are missing, or still the old Automa default.
        val config = env.file(OPENCODE_CONFIG_PATH)
        if (!config.exists() || Tree.readSmall(config)?.trim() == OLD_OPENCODE_CONFIG) write(OPENCODE_CONFIG_PATH, OPENCODE_CONFIG)
        // opencode 2.x keeps the theme in cli.json and moves an old tui.json there itself: only a new guest gets cli.json.
        if (!env.file(CLI_CONFIG_PATH).exists() && !env.file(TUI_CONFIG_PATH).exists()) write(CLI_CONFIG_PATH, CLI_CONFIG)
        // No shared background server of opencode 2.x: it would run detached, outside the session (DESIGN §3).
        if (!env.file(SERVICE_PATH).exists()) write(SERVICE_PATH, SERVICE)
        write(XDG_OPEN, XDG_OPEN_SCRIPT)
        if (!env.file(XDG_OPEN).setExecutable(true)) throw IOException("Cannot write $XDG_OPEN")
        env.file(Links.DIR).mkdirs()
        env.repos.mkdirs()
        // The sticky bit, which Tar does not keep: Claude Code refuses a world-writable /tmp without it (DESIGN §3).
        for (dir in listOf("/tmp", "/var/tmp")) env.file(dir).run { mkdirs(); Os.chmod(path, TMP_MODE) }
        loadModels()
    }

    private suspend fun packages(line: (String) -> Unit) {
        for (command in listOf(listOf("apk", "upgrade"), listOf("apk", "add") + Pins.PACKAGES)) {
            line("$ ${command.joinToString(" ")}")
            val code = env.run(command, line = line)
            if (code != 0) throw IOException("${command.take(2).joinToString(" ")} failed (exit code $code)")
        }
    }

    /**
     * Installs opencode [version] as /usr/local/bin/opencode. It extracts to a temporary folder and then renames,
     * so that a failure keeps the old program and a running opencode keeps its file.
     */
    private suspend fun opencode(version: String, sha256: String, progress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val archive = File(cache, "opencode.tar.gz")
        val bin = env.file("/usr/local/bin")
        val part = File(bin, ".automa-part")
        try {
            Download.fetch(Pins.opencodeUrl(version), archive, sha256, Pins.OPENCODE_MAX_BYTES, progress)
            progress(null)
            Tree.delete(part)
            val count = GZIPInputStream(archive.inputStream().buffered()).use { Tar.extract(it, part, only = setOf("opencode")) }
            if (count != 1) throw IOException("The opencode archive has no program")
            if (!File(part, "opencode").renameTo(File(bin, "opencode"))) throw IOException("Cannot save opencode")
            opencodeMarker.writeText("$version\n")
            _opencode.value = version
        } finally {
            archive.delete()
            Tree.delete(part)
        }
    }

    private fun write(guestPath: String, text: String) {
        val file = env.file(guestPath)
        file.parentFile?.mkdirs()
        file.delete()
        file.writeText(text)
    }

    companion object {
        private const val LOG_LINES = 200
        private const val RELEASE_MAX_BYTES = 8 shl 20

        /** 01777: all can write, only the owner of a file can delete it. */
        private const val TMP_MODE = 0b1_111_111_111

        /** The PRoot error when the system blocks ptrace. */
        private const val PTRACE = "ptrace(TRACEME): Operation not permitted"
        private const val BLOCKED = "Android does not let PRoot run Linux programs (ptrace is blocked). On GrapheneOS, " +
            "open App info and set \"Native code debugging\" to Allowed. Do not restrict dynamic code loading: opencode needs it."

        private val VERSION = Regex("""(\d{1,6})\.(\d{1,6})\.(\d{1,6})""")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /**
         * The steps for a guest system in this state. Configure and Packages always run (an update needs them).
         * opencode only when it is missing or older than the pinned one: the setup does not undo an in-app update.
         */
        fun plan(rootfsReady: Boolean, opencode: String?): List<Step> = buildList {
            if (!rootfsReady) add(Step.Rootfs)
            add(Step.Configure)
            add(Step.Packages)
            if (newer(Pins.OPENCODE_VERSION, opencode)) add(Step.Opencode)
        }

        /** True when version [a] (x.y.z) is newer than [b], or when [b] is no version (nothing installed). */
        fun newer(a: String, b: String?): Boolean {
            val x = VERSION.matchEntire(a)?.groupValues?.drop(1)?.map(String::toInt) ?: return false
            val y = b?.let { VERSION.matchEntire(it) }?.groupValues?.drop(1)?.map(String::toInt) ?: return true
            for (i in 0..2) if (x[i] != y[i]) return x[i] > y[i]
            return false
        }

        fun isVersion(text: String) = VERSION.matches(text)

        /**
         * The version and the SHA-256 of [asset] from the GitHub release data [json] (tag [prefix] + x.y.z). Null when
         * it is not a stable release with a plain x.y.z version and the asset with a SHA-256 digest.
         */
        fun release(json: String, asset: String = Pins.OPENCODE_ASSET, prefix: String = "v"): Release? =
            runCatching { release(JSONObject(json), asset, prefix) }.getOrNull()

        /**
         * The newest stable opencode release in the release list [json] with the major version of the pin (the 2.x
         * line, DESIGN §3). Null when there is none.
         */
        fun newest(json: String, major: String = Pins.OPENCODE_VERSION.substringBefore('.')): Release? = runCatching {
            val list = JSONArray(json)
            (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let { release(it, Pins.OPENCODE_ASSET, "v") } }
                .filter { it.version.substringBefore('.') == major }
                .reduceOrNull { a, b -> if (newer(b.version, a.version)) b else a }
        }.getOrNull()

        private fun release(root: JSONObject, asset: String, prefix: String): Release? = runCatching {
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
            val tag = root.getString("tag_name")
            if (!tag.startsWith(prefix)) return null
            val version = tag.removePrefix(prefix).takeIf(VERSION::matches) ?: return null
            val assets = root.getJSONArray("assets")
            (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.optString("name") == asset }
                ?.optString("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                ?.takeIf(SHA256::matches)?.let { Release(version, it) }
        }.getOrNull()

        private val PROFILE = """
            |# Written by Automa at each setup. Put your changes in ~/.profile.
            |# Privacy defaults for opencode (Automa DESIGN §8).
            |export OPENCODE_DISABLE_AUTOUPDATE=1
            |export OPENCODE_DISABLE_MODELS_FETCH=1
            |export OPENCODE_DISABLE_FREE_MODELS=1
            |# A cloned repository cannot start its own opencode plugins or MCP commands (review SEC-5).
            |# To use the opencode.json and .opencode/ of your own projects, put this in ~/.profile:
            |#   unset OPENCODE_DISABLE_PROJECT_CONFIG
            |export OPENCODE_DISABLE_PROJECT_CONFIG=1
            |# Claude Code (DESIGN §8): no telemetry, error reports, feedback or self-update; the ripgrep of Alpine.
            |export CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1
            |export USE_BUILTIN_RIPGREP=0
            |# Links that programs open (logins) go to Automa, which asks before it opens the browser (DESIGN §5).
            |export BROWSER=$XDG_OPEN
            |if [ -n "${'$'}BASH_VERSION" ]; then
            |  # Codex with no background server: its daemon needs the full Codex package (research F-10).
            |  alias codex='codex --no-daemon'
            |  PS1='\[\e[1;32m\]\w\[\e[0m\] \$ '
            |fi
            |""".trimMargin()

        private const val XDG_OPEN = "/usr/local/bin/xdg-open"

        /**
         * Gives an https link to Automa (DESIGN §5): a file in [Links.DIR], written with a rename. Automa shows the host
         * and opens the browser only after a tap.
         */
        private val XDG_OPEN_SCRIPT = """
            |#!/bin/sh
            |# Written by Automa at each setup. Automa asks before it opens the link in the Android browser.
            |case "${'$'}1" in
            |  https://*) ;;
            |  *) echo "xdg-open: Automa opens only https links" >&2; exit 1 ;;
            |esac
            |dir=${Links.DIR}
            |mkdir -p "${'$'}dir" && printf '%s\n' "${'$'}1" > "${'$'}dir/.link.${'$'}${'$'}" && mv -f "${'$'}dir/.link.${'$'}${'$'}" "${'$'}dir/${Links.FILE}"
            |echo "Automa: tap Open to open the link in the browser." >&2
            |""".trimMargin()

        /** git asks gh for the GitHub password; gh gives GH_TOKEN (DESIGN §6). */
        private val GITCONFIG = """
            |# Written by Automa at each setup. Put your changes in ~/.gitconfig.
            |[credential "https://github.com"]
            |	helper =
            |	helper = !/usr/bin/gh auth git-credential
            |[credential "https://gist.github.com"]
            |	helper =
            |	helper = !/usr/bin/gh auth git-credential
            |[init]
            |	defaultBranch = main
            |""".trimMargin()

        private const val OPENCODE_CONFIG_PATH = "/root/.config/opencode/opencode.json"
        private const val TUI_CONFIG_PATH = "/root/.config/opencode/tui.json"
        private const val CLI_CONFIG_PATH = "/root/.config/opencode/cli.json"
        private const val SERVICE_PATH = "/root/.config/opencode/service.json"

        /**
         * opencode defaults (DESIGN §8): only the TFF provider (Automa writes it to the managed config, [Tff]), no share
         * links, no update check, and no OpenCode Zen provider (its free default model would send the first prompt to
         * opencode.ai before the user selects a provider).
         */
        private val OPENCODE_CONFIG = """
            |{
            |  "${'$'}schema": "https://opencode.ai/config.json",
            |  "share": "disabled",
            |  "autoupdate": false,
            |  "enabled_providers": ["${Tff.PROVIDER}"],
            |  "disabled_providers": ["opencode"]
            |}
            |""".trimMargin()

        /** The default of setup version 2. A file with this text gets [OPENCODE_CONFIG]; a changed file stays. */
        private val OLD_OPENCODE_CONFIG = """
            |{
            |  "${'$'}schema": "https://opencode.ai/config.json",
            |  "share": "disabled",
            |  "autoupdate": false,
            |  "disabled_providers": ["opencode"]
            |}
            """.trimMargin()

        /** The opencode theme that matches the terminal colours (Catppuccin Mocha, DESIGN §5), opencode 2.x format. */
        private val CLI_CONFIG = """
            |{
            |  "theme": { "name": "catppuccin" }
            |}
            |""".trimMargin()

        /** opencode 2.x: a private server for each start, which ends with it, not the detached background service. */
        private val SERVICE = """
            |{
            |  "disabled": true
            |}
            |""".trimMargin()
    }
}
