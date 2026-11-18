package app.automa.chat

import app.automa.env.Env
import app.automa.env.Procs
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A guest program that speaks JSON, one object for each line, on stdin and stdout (DESIGN §13). No terminal and no
 * network port. Lines that are not JSON (for example from a login profile) are ignored. A line longer than [MAX_LINE]
 * is dropped, so that a very large tool output cannot use all the memory. stderr keeps only its last lines.
 */
class Pipe private constructor(private val process: Process, private val mark: String) {
    private val writer = process.outputStream.bufferedWriter()
    private val tail = ArrayDeque<String>()

    /** Sends [json] as one line. Not on the main thread. Throws when the program has ended. */
    @Synchronized
    fun send(json: JSONObject) {
        writer.write(json.toString())
        writer.write("\n")
        writer.flush()
    }

    /** Reads stdout until the program ends and gives each JSON object to [onJson]. Blocks; returns the exit code. */
    fun read(onJson: (JSONObject) -> Unit): Int {
        val errors = Thread({ lines(process.errorStream) { keep(it) } }, "automa-stderr").apply { start() }
        lines(process.inputStream) { line ->
            if (line.startsWith("{")) runCatching { JSONObject(line) }.getOrNull()?.let(onJson)
        }
        val code = process.waitFor()
        errors.join(1000)
        return code
    }

    /** The last lines of stderr, for an error text. */
    fun errors(): String = synchronized(tail) { tail.joinToString("\n") }

    /**
     * Stops the program: first the end of stdin (the agent can end cleanly), then after [GRACE_MS] all the processes
     * of the tree (research F-8). Not on the main thread.
     */
    fun stop() {
        // The end of stdin on its own thread: when the agent reads nothing, a send waits in the full pipe with the lock,
        // and the kill must still come. After the kill, the waiting write fails and the close ends.
        thread(name = "automa-stdin-close") { runCatching { synchronized(this@Pipe) { writer.close() } } }
        if (!process.waitFor(GRACE_MS, TimeUnit.MILLISECONDS)) Procs.child(mark)?.let(Procs::kill)
        process.destroy()
    }

    private fun keep(line: String) = synchronized(tail) {
        tail.addLast(line.take(500))
        while (tail.size > TAIL_LINES) tail.removeFirst()
    }

    companion object {
        const val MAX_LINE = 8 shl 20
        private const val TAIL_LINES = 40
        private const val GRACE_MS = 1500L

        /**
         * Starts [command] with a login shell in the guest folder [cwd], so that /etc/profile.d/automa.sh applies.
         * [extra] adds environment variables.
         */
        suspend fun start(env: Env, cwd: String, command: List<String>, extra: Map<String, String> = emptyMap()): Pipe {
            val mark = env.mark()
            // "$@" keeps each argument as it is: no shell quoting of the command.
            val process = env.start(listOf("/bin/bash", "-lc", "exec \"\$@\"", "automa") + command, cwd, mark, extra)
            return Pipe(process, mark)
        }

        /**
         * Splits [input] into UTF-8 lines. A line longer than [MAX_LINE] is dropped. A read error is the end: a stop
         * closes the streams (Process.destroy) while this thread reads, and an exception here would stop the app.
         */
        fun lines(input: InputStream, onLine: (String) -> Unit) {
            val buf = ByteArray(64 shl 10)
            val line = ByteArrayOutputStream()
            var skip = false
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (_: IOException) {
                    -1
                }
                if (n < 0) break
                var start = 0
                for (i in 0 until n) {
                    if (buf[i] != NEWLINE) continue
                    if (!skip && line.size() + i - start <= MAX_LINE) {
                        line.write(buf, start, i - start)
                        onLine(line.toString(Charsets.UTF_8).removeSuffix("\r"))
                    }
                    line.reset()
                    skip = false
                    start = i + 1
                }
                if (!skip) {
                    line.write(buf, start, n - start)
                    if (line.size() > MAX_LINE) {
                        skip = true
                        line.reset()
                    }
                }
            }
            if (!skip && line.size() > 0) onLine(line.toString(Charsets.UTF_8))
        }

        private const val NEWLINE = '\n'.code.toByte()
    }
}
