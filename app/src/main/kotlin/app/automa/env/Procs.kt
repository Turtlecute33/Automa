package app.automa.env

import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * The app's own processes, from /proc (research F-8). PRoot ignores SIGTERM, and after a SIGKILL its tracees continue
 * as orphans, so a stop must reach each process of the tree. ProcessHandle is in the SDK stubs but not in the runtime
 * (NoClassDefFoundError on API 37), so this reads /proc. Android shows only the processes of this app user there.
 */
object Procs {
    /**
     * Stops [root] and all its descendants with SIGKILL. The tree is read first, while it is still connected. A pid
     * of 0 or less is not a process: a terminal tab that closes before its first layout has pid 0, and kill(0) would
     * stop the process group of the app itself.
     */
    fun kill(root: Int) {
        if (root <= 0) return
        tree(root).asReversed().forEach { runCatching { Os.kill(it, OsConstants.SIGKILL) } }
    }

    /** The PID of a direct child of this app whose command line contains [marker], or null. */
    fun child(marker: String): Int? {
        val me = Os.getpid()
        return pids().firstOrNull { pid ->
            parent(read("/proc/$pid/stat")) == me && read("/proc/$pid/cmdline").contains(marker)
        }
    }

    /** [root] and its descendants, root first. */
    fun tree(root: Int): List<Int> {
        val children = HashMap<Int, MutableList<Int>>()
        for (pid in pids()) parent(read("/proc/$pid/stat"))?.let { children.getOrPut(it) { mutableListOf() } += pid }
        val out = ArrayList<Int>()
        val todo = ArrayDeque(listOf(root))
        while (todo.isNotEmpty()) {
            val pid = todo.removeFirst()
            out += pid
            children[pid]?.let(todo::addAll)
        }
        return out
    }

    /** The parent PID in a /proc/<pid>/stat line: "pid (comm) state ppid …". comm can hold spaces and ")". */
    fun parent(stat: String): Int? = stat.substringAfterLast(')').trim().split(' ').getOrNull(1)?.toIntOrNull()

    private fun pids() = File("/proc").list().orEmpty().mapNotNull { it.toIntOrNull() }

    private fun read(path: String) = runCatching { File(path).readText() }.getOrDefault("")
}
