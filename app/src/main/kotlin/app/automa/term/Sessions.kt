package app.automa.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.Immutable
import app.automa.env.Agent
import app.automa.env.Env
import app.automa.env.Procs
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.concurrent.thread

/** One terminal session for the UI. [exit] is set when the process ended with an error. */
@Immutable
data class Tab(val id: Int, val title: String, val dir: String, val exit: Int? = null)

/**
 * The open terminal sessions (DESIGN §4). Main thread only, like the Termux session callbacks.
 * [SessionService] keeps the process in the foreground while a session is open.
 */
class Sessions(private val context: Context, private val env: Env) : TerminalSessionClient {
    private class Entry(val tab: Tab, val session: TerminalSession)

    init {
        Look.apply()
    }

    private val entries = mutableListOf<Entry>()
    private val _tabs = MutableStateFlow<List<Tab>>(emptyList())
    val tabs: StateFlow<List<Tab>> = _tabs.asStateFlow()
    private var nextId = 1

    /** The view that shows a session now. It gets the screen updates. */
    var view: TerminalView? = null

    /**
     * Opens a session in the guest folder [dir]: [agent] and then a shell in the same folder, or only a shell.
     * [launch] replaces the agent command line (a chat opens its session with a resume, DESIGN §13).
     * The process starts when a view gives the session its size.
     */
    suspend fun open(dir: String, agent: Agent?, launch: String? = null): Int {
        val environment = env.environment().map { (k, v) -> "$k=$v" }.toTypedArray()
        val program = if (agent != null) listOf("/bin/bash", "-lc", "${launch ?: agent.launch}; exec bash -l") else listOf("/bin/bash", "-l")
        val argv = env.argv(dir, program).toTypedArray()
        val session = TerminalSession(env.proot, context.cacheDir.path, argv, environment, TRANSCRIPT_ROWS, this)
        val name = if (dir == Env.HOME) "~" else dir.substringAfterLast('/')
        val tab = Tab(nextId++, if (agent != null) "$name · ${agent.label}" else name, dir)
        entries += Entry(tab, session)
        publish()
        return tab.id
    }

    fun session(id: Int): TerminalSession? = entries.firstOrNull { it.tab.id == id }?.session

    /** Stops the process of the session and removes it. */
    fun close(id: Int) {
        val entry = entries.firstOrNull { it.tab.id == id } ?: return
        entries.remove(entry)
        // Not finishIfRunning(): it kills only PRoot, and its programs would continue (research F-8). The tree is read
        // from /proc, so off the main thread.
        if (entry.session.isRunning) {
            val pid = entry.session.pid
            thread(name = "automa-stop") { Procs.kill(pid) }
        }
        publish()
    }

    fun closeAll() = entries.map { it.tab.id }.forEach(::close)

    private fun publish() {
        _tabs.value = entries.map { it.tab }
        SessionService.sync(context)
    }

    override fun onTextChanged(changedSession: TerminalSession) {
        view?.takeIf { it.currentSession === changedSession }?.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        val index = entries.indexOfFirst { it.session === finishedSession }
        if (index < 0) return
        val code = finishedSession.exitStatus
        // An error stays on the screen, so that the user can read it.
        if (code == 0) entries.removeAt(index) else entries[index] = Entry(entries[index].tab.copy(exit = code), finishedSession)
        publish()
    }

    /**
     * A copy from the selection, or from a program with OSC 52 (the opencode copy uses it). Only the session on the
     * screen can set the clipboard, so that a session in the background cannot change it (review SEC-8).
     */
    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val shown = view?.takeIf { it.isAttachedToWindow && it.hasWindowFocus() }?.currentSession
        if (shown !== session) return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
        val text = clip.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    override fun onColorsChanged(session: TerminalSession) {
        view?.takeIf { it.currentSession === session }?.invalidate()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null

    // The emulator logs can hold terminal text: no logs.
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit

    private companion object {
        const val TRANSCRIPT_ROWS = 3000
    }
}
