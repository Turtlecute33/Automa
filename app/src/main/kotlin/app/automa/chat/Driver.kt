package app.automa.chat

import app.automa.env.Env
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/** What the user selects in a running chat. */
enum class Setting { Model, Mode, Effort }

/** The guest files and programs that a driver can use. A fake in the tests. */
interface Guest {
    /**
     * The text of the guest file [path] when it is in the guest folder [within] (after links) and at most [max]
     * bytes, else null.
     */
    fun read(path: String, max: Int, within: String = "/"): String?

    /** Runs [command] in the guest folder [cwd] and returns its exit code. */
    suspend fun exit(command: List<String>, cwd: String): Int

    /** Adds [text] at the end of the guest file [path] (made when it is missing). False on an error. */
    fun append(path: String, text: String): Boolean
}

/** [Guest] on the real guest system. A path cannot leave the guest root (no `..` or link out). */
class EnvGuest(private val env: Env) : Guest {
    override fun read(path: String, max: Int, within: String): String? = runCatching {
        val root = File(env.rootfs, within.removePrefix("/")).canonicalFile
        val file = File(env.rootfs, path.removePrefix("/")).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || file.length() > max) return null
        file.readText()
    }.getOrNull()

    override fun append(path: String, text: String): Boolean = runCatching {
        val root = env.rootfs.canonicalFile
        val file = File(root, path.removePrefix("/")).canonicalFile
        if (!file.path.startsWith(root.path + File.separator)) return false
        file.parentFile?.mkdirs()
        file.appendText(text)
        true
    }.getOrDefault(false)

    override suspend fun exit(command: List<String>, cwd: String) = env.run(listOf("/bin/bash", "-lc", "exec \"\$@\"", "automa") + command, cwd)
}

/**
 * The protocol of one agent (DESIGN §13). [run] starts the program and reads its messages until it ends. All the
 * protocol state changes on one serial context: the messages and the user actions do not overlap. The protocol
 * parts have no Android types, so that the tests can drive them with scripted messages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class Driver(val dir: String) {
    protected val flow = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = flow.asStateFlow()

    private val serial = Dispatchers.Default.limitedParallelism(1)
    private var sink: (suspend (JSONObject) -> Unit)? = null
    @Volatile private var pipe: Pipe? = null
    private var keys = 0

    /** The agent program and its arguments. */
    protected abstract fun command(): List<String>

    /** Extra environment variables for the agent. */
    protected open fun environment(): Map<String, String> = emptyMap()

    /** Work before the start, for example a question to the user. Returns false to not start. */
    internal open suspend fun prepare(): Boolean = true

    /** The first messages after the start (a handshake). */
    internal abstract suspend fun begin()

    /** One message of the agent. */
    abstract suspend fun receive(json: JSONObject)

    /** Sends a user message. */
    abstract suspend fun send(text: String)

    /** Answers the request [ask]. */
    abstract suspend fun answer(ask: Ask, answer: Answer)

    /** Stops the turn that runs now. */
    abstract suspend fun interrupt()

    /** Changes a setting for the next turns. */
    abstract suspend fun select(setting: Setting, id: String)

    /** The command line that opens the same session in the agent's terminal UI, or null for a new session. */
    abstract fun terminal(): String?

    /** The agent ended: waiting calls must fail. */
    protected open fun closed() = Unit

    /** Starts the agent in [env] and reads until it ends. Runs in [scope] (the scope of the chat). */
    suspend fun run(env: Env, scope: CoroutineScope) {
        if (!withContext(serial) { prepare() }) return
        val pipe = try {
            // Only NonCancellable, no other dispatcher: then the new process comes back also when the chat closes during
            // the start, and the check below stops it. With a dispatcher change, withContext drops its result after a
            // cancel (review SEC-24).
            withContext(NonCancellable) { Pipe.start(env, dir, command(), environment()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            end("The agent did not start (${e.javaClass.simpleName})")
            return
        }
        this.pipe = pipe
        // A close during the start: the process must not stay (review SEC-24).
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable + Dispatchers.IO) { pipe.stop() }
            return
        }
        attach { json -> withContext(Dispatchers.IO) { pipe.send(json) } }
        scope.launch(serial) { safely { begin() } }
        val code = runInterruptible(Dispatchers.IO) { pipe.read { json -> scope.launch(serial) { safely { receive(json) } } } }
        withContext(serial) {
            closed()
            val errors = clean(pipe.errors()).lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
            end(if (code == 0) "The agent ended." else "The agent ended with code $code." + if (errors.isNotEmpty()) "\n$errors" else "")
        }
    }

    /** Where the messages to the agent go. [run] sets the pipe; the tests set a list. */
    fun attach(sink: suspend (JSONObject) -> Unit) {
        this.sink = sink
    }

    /** Stops the agent and its child processes. Not on the main thread. */
    fun stop() {
        pipe?.stop()
    }

    /** Writes [json] to the agent. Throws when the agent has not started or has ended. */
    protected suspend fun write(json: JSONObject) {
        (sink ?: throw IllegalStateException("The agent did not start"))(json)
    }

    protected fun update(change: (ChatState) -> ChatState) = flow.update(change)

    /** A new list key. */
    protected fun key(prefix: String) = "$prefix-${keys++}"

    protected fun add(entry: Entry) = update { it.copy(entries = limit(it.entries + entry)) }

    /** Replaces the entry with [key], or adds one when there is none. */
    protected fun put(key: String, entry: (Entry?) -> Entry) = update { s ->
        val i = s.entries.indexOfFirst { it.key == key }
        if (i < 0) s.copy(entries = limit(s.entries + entry(null))) else s.copy(entries = s.entries.toMutableList().also { it[i] = entry(it[i]) })
    }

    /** At most [MAX_ROWS] rows: the oldest go (review SEC-20). */
    private fun limit(entries: List<Entry>) = if (entries.size > MAX_ROWS) entries.drop(entries.size - MAX_ROWS + MAX_ROWS / 10) else entries

    /** The entry with [key], or null. */
    protected fun find(key: String): Entry? = flow.value.entries.lastOrNull { it.key == key }

    protected fun note(text: String, error: Boolean = false) = add(Entry.Note(key("note"), clean(text).take(2000), error))

    protected fun ask(ask: Ask) = update { it.copy(asks = it.asks + ask) }

    protected fun done(id: String) = update { s -> s.copy(asks = s.asks.filterNot { it.id == id }) }

    protected fun fail(e: Exception) = note(e.message ?: e.javaClass.simpleName, error = true)

    protected fun end(reason: String) = update { it.copy(ended = reason, busy = false, starting = false, asks = emptyList()) }

    /** A bad message must not stop the chat: it becomes an error line. */
    private suspend fun safely(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** Runs a user action on the serial context. An error becomes an error line. */
    protected suspend fun act(block: suspend () -> Unit) = withContext(serial) { safely(block) }
}

private const val MAX_ROWS = 2000

private val ID = Regex("[A-Za-z0-9_-]{1,128}")

/** An agent session id that can go into a shell command line (review SEC-25). */
fun safe(id: String) = ID.matches(id)

private val ANSI = Regex("""\u001B(\[[0-?]*[ -/]*[@-~]|\][^\u0007\u001B]*(\u0007|\u001B\\)|[@-Z\\-_])""")

/**
 * Text from the agent or a repository for the screen: no terminal escape sequences and no control characters.
 * Invisible format characters (bidi controls, zero-width characters) become visible marks, so that the screen shows
 * the real order of a command (review SEC-17).
 */
fun clean(text: String): String {
    val out = StringBuilder(text.length)
    for (c in ANSI.replace(text, "")) {
        when {
            c == '\n' || c == '\t' -> out.append(c)
            c.isISOControl() -> Unit
            Character.getType(c) == Character.FORMAT.toInt() || c == '\u2028' || c == '\u2029' ->
                out.append("<U+").append(c.code.toString(16).uppercase().padStart(4, '0')).append('>')
            else -> out.append(c)
        }
    }
    return out.toString()
}
