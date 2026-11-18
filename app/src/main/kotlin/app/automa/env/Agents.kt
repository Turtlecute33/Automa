package app.automa.env

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * A coding agent for a session. opencode comes with the setup; the user installs the others on demand. [launch] starts
 * it in a session: Codex runs with no background server (its daemon needs a full package, research F-10).
 */
enum class Agent(val label: String, val command: String, val launch: String = command) {
    Opencode("opencode", "opencode"),
    Claude("Claude Code", "claude"),
    Codex("Codex", "codex", "codex --no-daemon"),
    ;

    companion object {
        val ON_DEMAND = listOf(Claude, Codex)
    }
}

/** An agent download: the SHA-256 of the .zst file, and of the program when the publisher gives it. */
data class AgentRelease(val version: String, val zstSha256: String, val sha256: String?)

/**
 * Installs, updates and removes Claude Code and Codex in the guest (DESIGN §3). The user logs in inside each agent.
 * The caller holds the setup lock, on a ready guest system (review SEC-7).
 */
class Agents(private val env: Env) {
    /** The state of the install or update that runs now, or the error of the last one. */
    @Immutable
    data class Work(val agent: Agent, val text: String, val progress: Float? = null, val error: Exception? = null)

    private val _installed = MutableStateFlow<Map<Agent, String>>(emptyMap())

    /** The installed agents and their versions. */
    val installed: StateFlow<Map<Agent, String>> = _installed.asStateFlow()

    private val _work = MutableStateFlow<Work?>(null)
    val work: StateFlow<Work?> = _work.asStateFlow()

    private fun bin(agent: Agent) = env.file("/usr/local/bin/${agent.command}")
    private fun marker(agent: Agent) = env.file("/etc/automa-${agent.command}")

    /** Reads the installed versions. Not on the main thread. */
    fun check() {
        _installed.value = Agent.ON_DEMAND.mapNotNull { agent ->
            Tree.readSmall(marker(agent))?.trim()?.takeIf { bin(agent).isFile }?.let { agent to it }
        }.toMap()
    }

    /** After a reset: no guest system, no agents. */
    fun clear() {
        _installed.value = emptyMap()
        _work.value = null
    }

    /**
     * Installs [release] of [agent] as /usr/local/bin/<command>. The .zst file must have its SHA-256; zstd unpacks it
     * in the guest; a rename replaces the old program, so a failure keeps it and open sessions continue.
     */
    suspend fun install(agent: Agent, release: AgentRelease) {
        val zst = env.file("/tmp/automa-${agent.command}.zst")
        val part = env.file("/usr/local/bin/.automa-${agent.command}")
        try {
            _work.value = Work(agent, "Download ${agent.label} ${release.version}", 0f)
            Download.fetch(url(agent, release.version), zst, release.zstSha256, Pins.AGENT_MAX_BYTES) {
                _work.value = Work(agent, "Download ${agent.label} ${release.version}", it)
            }
            _work.value = Work(agent, "Unpack ${agent.label}")
            val output = ArrayDeque<String>()
            val code = env.run(listOf("zstd", "-d", "-q", "-f", "-o", "/usr/local/bin/.automa-${agent.command}", "/tmp/automa-${agent.command}.zst")) {
                synchronized(output) { output.addLast(it); while (output.size > 3) output.removeFirst() }
            }
            if (code != 0) throw IOException("zstd failed (exit code $code): ${synchronized(output) { output.joinToString(" ") }}")
            withContext(Dispatchers.IO) {
                if (release.sha256 != null && sha256(part) != release.sha256) throw IOException("The program is not the expected file (SHA-256)")
                // The privacy settings before the program: when they cannot be written, no Codex is installed that
                // runs without them.
                if (agent == Agent.Codex) codexConfig()
                // The guest programs run as the app user: owner-only permissions are sufficient.
                if (!part.setExecutable(true) || !part.renameTo(bin(agent))) throw IOException("Cannot save ${agent.label}")
                marker(agent).writeText("${release.version}\n")
            }
            _installed.value += agent to release.version
            _work.value = null
        } catch (e: CancellationException) {
            _work.value = null
            throw e
        } catch (e: Exception) {
            _work.value = Work(agent, "", error = e)
            throw e
        } finally {
            withContext(Dispatchers.IO) {
                zst.delete()
                part.delete()
            }
        }
    }

    /** Deletes the program. The login and the settings of the agent stay in the home folder. */
    suspend fun remove(agent: Agent) = withContext(Dispatchers.IO) {
        if (bin(agent).exists() && !bin(agent).delete()) throw IOException("Cannot delete ${agent.label}")
        marker(agent).delete()
        _installed.value -= agent
        _work.value = null
    }

    /** The newest stable release of [agent], with the SHA-256 from the publisher (as for opencode, review SEC-9). */
    suspend fun latest(agent: Agent): AgentRelease = when (agent) {
        Agent.Claude -> {
            val version = Download.text(Pins.CLAUDE_STABLE, 64).trim()
            if (!Setup.isVersion(version)) throw IOException("Unexpected version data from Anthropic")
            AgentRelease(
                version,
                zstSha256 = checksum(Download.text(Pins.claudeZstManifest(version), MANIFEST_MAX_BYTES)),
                sha256 = checksum(Download.text(Pins.claudeManifest(version), MANIFEST_MAX_BYTES)),
            )
        }
        Agent.Codex -> Setup.release(Download.text(Pins.CODEX_LATEST, RELEASE_MAX_BYTES), Pins.CODEX_ASSET, "rust-v")
            ?.let { AgentRelease(it.version, it.sha256, null) }
            ?: throw IOException("Unexpected release data from GitHub")
        Agent.Opencode -> throw IllegalArgumentException("opencode comes with the setup")
    }

    /** No metrics, no feedback upload and no update check (Automa updates Codex). Only when the user has no file. */
    private fun codexConfig() {
        val file = env.file("/root/.codex/config.toml")
        if (file.exists()) return
        file.parentFile?.mkdirs()
        file.writeText(CODEX_CONFIG)
    }

    companion object {
        private const val MANIFEST_MAX_BYTES = 1 shl 20
        private const val RELEASE_MAX_BYTES = 4 shl 20
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** The pinned release of [agent], for the install. */
        fun pinned(agent: Agent): AgentRelease = when (agent) {
            Agent.Claude -> AgentRelease(Pins.CLAUDE_VERSION, Pins.CLAUDE_ZST_SHA256, Pins.CLAUDE_SHA256)
            Agent.Codex -> AgentRelease(Pins.CODEX_VERSION, Pins.CODEX_ZST_SHA256, null)
            Agent.Opencode -> throw IllegalArgumentException("opencode comes with the setup")
        }

        fun url(agent: Agent, version: String) = when (agent) {
            Agent.Claude -> Pins.claudeUrl(version)
            Agent.Codex -> Pins.codexUrl(version)
            Agent.Opencode -> throw IllegalArgumentException("opencode comes with the setup")
        }

        /** The SHA-256 of the musl arm64 file in a Claude Code manifest. */
        fun checksum(manifest: String): String =
            runCatching {
                JSONObject(manifest).getJSONObject("platforms").getJSONObject(Pins.CLAUDE_PLATFORM).getString("checksum")
            }.getOrNull()?.takeIf(SHA256::matches) ?: throw IOException("Unexpected manifest from Anthropic")

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(256 shl 10)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return Download.hex(digest.digest())
        }

        private val CODEX_CONFIG = """
            |# Written by Automa when it installed Codex (DESIGN §8). You can change this file.
            |# No update check: Settings → Update in Automa updates Codex.
            |check_for_update_on_startup = false
            |# The Linux sandbox of Codex (bubblewrap) cannot run under PRoot. The Android app sandbox is the limit, the
            |# same as for opencode and Claude Code in Automa. Codex still asks before it runs commands.
            |sandbox_mode = "danger-full-access"
            |
            |# No Statsig metrics and no feedback upload.
            |[analytics]
            |enabled = false
            |
            |[feedback]
            |enabled = false
            |""".trimMargin()
    }
}
