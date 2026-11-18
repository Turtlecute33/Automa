package app.automa.git

import androidx.compose.runtime.Immutable
import app.automa.env.Env
import app.automa.env.Tree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URI

@Immutable
data class Repo(val name: String, val branch: String?)

/** The repositories in `/root/repos` (DESIGN §2) and the clone command. */
class Repos(private val env: Env) {
    private val _list = MutableStateFlow<List<Repo>>(emptyList())
    val list: StateFlow<List<Repo>> = _list.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        _list.value = env.repos.listFiles().orEmpty()
            .filter { it.isDirectory && File(it, ".git").exists() }
            .map { Repo(it.name, branch(it)) }
            .sortedBy { it.name.lowercase() }
    }

    /** Clones [input] (`owner/repo` or an https URL) into `/root/repos/<name>`. Returns the name. */
    suspend fun clone(input: String, line: (String) -> Unit): String = env.foreground {
        val source = source(input) ?: throw IOException("Type owner/repo or an https link")
        if (withContext(Dispatchers.IO) { File(env.repos, source.name).exists() }) {
            throw IOException("A repository with the name ${source.name} is already there")
        }
        env.repos.mkdirs()
        var last = ""
        val code = env.run(source.program, Env.REPOS) { last = it; line(it) }
        refresh()
        if (code != 0) throw IOException("The clone failed: ${last.ifBlank { "exit code $code" }}")
        source.name
    }

    /** [name] is a folder in the list; the shell can make names that a clone here does not (review SEC-7). */
    suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        require(name.isNotEmpty() && '/' !in name && name != "." && name != "..")
        Tree.delete(File(env.repos, name))
        refresh()
    }

    private fun branch(dir: File): String? {
        val head = Tree.readSmall(File(dir, ".git/HEAD"))?.trim() ?: return null
        return if (head.startsWith("ref: refs/heads/")) head.removePrefix("ref: refs/heads/") else head.take(7)
    }

    /** The guest command for a clone, and the folder name. */
    data class Source(val program: List<String>, val name: String)

    companion object {
        private val short = Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}/([A-Za-z0-9._-]{1,100})")

        /**
         * `owner/repo` is a GitHub repository. git asks gh for the token only when GitHub asks for a login, so
         * public repositories need no login (`gh repo clone` needs one for all).
         */
        fun source(input: String): Source? {
            val text = input.trim()
            short.matchEntire(text)?.let { match ->
                val name = match.groupValues[1].removeSuffix(".git")
                val url = "https://github.com/${text.removeSuffix(".git")}.git"
                return if (validName(name)) Source(listOf("git", "clone", "--", url, "${Env.REPOS}/$name"), name) else null
            }
            val uri = runCatching { URI(text) }.getOrNull() ?: return null
            // No user info: a password in the link would go into .git/config.
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            val name = uri.path.orEmpty().trimEnd('/').substringAfterLast('/').removeSuffix(".git")
            if (!validName(name)) return null
            return Source(listOf("git", "clone", "--", text, "${Env.REPOS}/$name"), name)
        }

        fun validName(name: String) = name.isNotEmpty() && name.length <= 100 && name != "." && name != ".." &&
            name.all { it.isLetterOrDigit() || it in "._-" } && !name.startsWith("-")
    }
}
