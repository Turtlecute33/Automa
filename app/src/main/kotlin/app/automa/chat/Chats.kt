package app.automa.chat

import android.content.Context
import androidx.compose.runtime.Immutable
import app.automa.env.Agent
import app.automa.env.Env
import app.automa.term.SessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.concurrent.thread

/** One open chat for the UI. */
@Immutable
data class ChatTab(val id: Int, val title: String, val dir: String, val agent: Agent)

/**
 * The open chats (DESIGN §13). Each agent runs in the app scope, so that it continues when the user goes to another
 * screen; [SessionService] keeps the app in the foreground while a chat is open. Main thread only.
 */
class Chats(private val context: Context, private val env: Env, private val scope: CoroutineScope) {
    private class Item(val tab: ChatTab, val driver: Driver, val job: Job)

    private val items = mutableListOf<Item>()
    private val _tabs = MutableStateFlow<List<ChatTab>>(emptyList())
    val tabs: StateFlow<List<ChatTab>> = _tabs.asStateFlow()
    private var nextId = 1

    /** Starts [agent] in the guest folder [dir] and returns the chat id. */
    fun open(dir: String, agent: Agent): Int {
        val guest = EnvGuest(env)
        val driver = when (agent) {
            Agent.Claude -> ClaudeDriver(dir, guest)
            Agent.Codex -> CodexDriver(dir, guest)
            Agent.Opencode -> OpencodeDriver(dir, guest)
        }
        val tab = ChatTab(nextId++, "${dir.substringAfterLast('/')} · ${agent.label}", dir, agent)
        val job = scope.launch { driver.run(env, this) }
        items += Item(tab, driver, job)
        publish()
        return tab.id
    }

    fun driver(id: Int): Driver? = items.firstOrNull { it.tab.id == id }?.driver

    /** Stops the agent of the chat and removes the chat. */
    fun close(id: Int) {
        val item = items.firstOrNull { it.tab.id == id } ?: return
        items.remove(item)
        item.job.cancel()
        // The process tree is read from /proc: off the main thread (research F-8).
        thread(name = "automa-chat-stop") { item.driver.stop() }
        publish()
    }

    /** Closes the chat and opens a new one with the same agent and folder (for example after a login). */
    fun restart(id: Int): Int? {
        val tab = items.firstOrNull { it.tab.id == id }?.tab ?: return null
        close(id)
        return open(tab.dir, tab.agent)
    }

    fun closeAll() = items.map { it.tab.id }.forEach(::close)

    /** Closes all chats and waits until their processes have ended (before a reset, review SEC-16). */
    suspend fun stopAll() {
        val all = items.toList()
        items.clear()
        publish()
        all.forEach { it.job.cancel() }
        withContext(Dispatchers.IO) { all.forEach { it.driver.stop() } }
    }

    private fun publish() {
        _tabs.value = items.map { it.tab }
        SessionService.sync(context)
    }
}
