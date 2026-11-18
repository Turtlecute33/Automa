package app.automa.env

import android.content.Context
import app.automa.chat.Pipe
import app.automa.data.Secrets
import app.automa.term.SessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The guest system and how to start a program in it with PRoot (DESIGN §3). Paths in [argv] that start with
 * `/` and are not the PRoot program are guest paths.
 */
class Env(private val context: Context, private val secrets: Secrets) {
    private val lib: String = context.applicationInfo.nativeLibraryDir
    val rootfs = File(context.noBackupFilesDir, "rootfs")
    private val tmp = File(context.cacheDir, "proot")

    /** The guest home folder `/root`, as a host path. */
    val home = File(rootfs, "root")
    val repos = File(home, "repos")

    val proot: String get() = "$lib/libproot.so"

    /** A host file in the guest: [guestPath] starts with `/`. */
    fun file(guestPath: String) = File(rootfs, guestPath.removePrefix("/"))

    /** The PRoot command line that runs the guest [program] in the guest folder [cwd]. argv[0] is [proot]. */
    fun argv(cwd: String, program: List<String>): List<String> = listOf(
        proot, "--kill-on-exit", "--link2symlink", "-L", "-0", "-r", rootfs.path,
        "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", cwd,
        // Only the guest variables go on: the PRoot variables point to host paths.
        "/usr/bin/env", "-u", "LD_LIBRARY_PATH", "-u", "PROOT_LOADER", "-u", "PROOT_TMP_DIR",
    ) + program

    /**
     * The process environment for [argv]: the guest variables, the secrets (here, not in argv, DESIGN §3) and the
     * PRoot variables. Also writes the DNS servers of the network that is active now.
     */
    suspend fun environment(): Map<String, String> {
        val secret = secrets.all()
        return withContext(Dispatchers.IO) {
            tmp.mkdirs()
            Dns.write(context, file("/etc/resolv.conf"))
            GUEST + secret + mapOf(
                "LD_LIBRARY_PATH" to lib,
                "PROOT_LOADER" to "$lib/libproot-loader.so",
                "PROOT_TMP_DIR" to tmp.path,
            )
        }
    }

    /**
     * Runs [program] with no terminal and gives each output line (stdout and stderr) to [line], on a background
     * thread. Returns the exit code. A cancel stops the process and its children.
     */
    suspend fun run(program: List<String>, cwd: String = HOME, line: (String) -> Unit = {}): Int {
        val mark = mark()
        return coroutineScope {
            // As in Driver.run: a cancel during the start must not lose the process, so that the finally stops it.
            val process = withContext(NonCancellable) { start(program, cwd, mark, NO_PROMPT, mergeErrors = true) }
                .also { it.outputStream.close() }
            val reader = launch(Dispatchers.IO) {
                // Pipe.lines: a line has a length limit (a git server can send one very long "remote:" line). Each line
                // also splits at a carriage return, as readLine does (progress text).
                runCatching { Pipe.lines(process.inputStream) { it.split('\r').forEach(line) } }
            }
            try {
                runInterruptible(Dispatchers.IO) { process.waitFor() }.also { reader.join() }
            } finally {
                // Nothing when the process has ended. Else a cancel: stop PRoot and all guest programs (research F-8),
                // and wait for them, so that the caller can delete what they wrote (review KM-M-12).
                if (process.isAlive) {
                    withContext(NonCancellable + Dispatchers.IO) { Procs.child(mark)?.let { Procs.stop(listOf(it), STOP_MS) } }
                }
                process.destroy()
            }
        }
    }

    /**
     * Starts [program] in the guest with pipes and no terminal. [mark] (from [mark]) goes into the guest environment,
     * so that [Procs.child] finds the process for a stop. [extra] adds variables.
     */
    suspend fun start(program: List<String>, cwd: String, mark: String, extra: Map<String, String> = emptyMap(), mergeErrors: Boolean = false): Process {
        val env = environment() + extra
        // No cancel between the start and the return: a lost Process would leave the program running (review SEC-24).
        // The dispatcher change still drops the result after a cancel, so the callers also wrap the call in
        // withContext(NonCancellable).
        return withContext(NonCancellable + Dispatchers.IO) {
            ProcessBuilder(argv(cwd, listOf(mark) + program)).redirectErrorStream(mergeErrors)
                .apply { environment().run { clear(); putAll(env) } }
                .start()
        }
    }

    /** A new mark for [start]: env sets it in the guest, and it is in the PRoot command line. */
    fun mark() = "AUTOMA_RUN=${UUID.randomUUID()}"

    /**
     * Runs [block] with the app in the foreground (SessionService.keep), so that the job keeps its network and is not
     * frozen when the user goes to another app (research F-9). Only for jobs that the user starts on the screen.
     */
    suspend fun <T> foreground(block: suspend () -> T): T = SessionService.keep(context, block)

    /** Runs [program] and returns its output, or null when it fails. */
    suspend fun output(program: List<String>, cwd: String = HOME): String? {
        val out = StringBuilder()
        val code = run(program, cwd) { synchronized(out) { out.appendLine(it) } }
        return if (code == 0) out.toString().trimEnd() else null
    }

    companion object {
        const val HOME = "/root"
        const val REPOS = "/root/repos"
        private const val STOP_MS = 3000L

        /** The base guest environment. The opencode privacy flags are in /etc/profile.d/automa.sh. */
        val GUEST = mapOf(
            "HOME" to HOME,
            "USER" to "root",
            "LOGNAME" to "root",
            "SHELL" to "/bin/bash",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "xterm-256color",
            "COLORTERM" to "truecolor",
            "LANG" to "C.UTF-8",
            "TMPDIR" to "/tmp",
            // No update checks by gh (DESIGN §8).
            "GH_NO_UPDATE_NOTIFIER" to "1",
            "GH_NO_EXTENSION_UPDATE_NOTIFIER" to "1",
        )

        /** A process with no terminal cannot answer a question: git and gh fail at once instead. */
        private val NO_PROMPT = mapOf("GIT_TERMINAL_PROMPT" to "0", "GH_PROMPT_DISABLED" to "1")
    }
}
