package app.automa

import app.automa.chat.Pipe
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFalse

/** The chat transport with a real host process (no guest, no PRoot). */
class PipeTest {
    private fun pipe(process: Process): Pipe =
        Pipe::class.java.getDeclaredConstructor(Process::class.java, String::class.java)
            .apply { isAccessible = true }.newInstance(process, "AUTOMA_RUN=test")

    @Test
    fun stopEndsAnAgentThatReadsNoInput() {
        // The agent reads nothing, so a large message waits in the full stdin pipe and keeps the send lock.
        val process = ProcessBuilder("sleep", "60").start()
        try {
            val pipe = pipe(process)
            thread(isDaemon = true) { runCatching { pipe.send(JSONObject().put("text", "x".repeat(1 shl 20))) } }
            Thread.sleep(300)
            val stop = thread(isDaemon = true) { pipe.stop() }
            stop.join(10_000)
            assertFalse(stop.isAlive, "stop() waits for ever")
            assertFalse(process.isAlive)
        } finally {
            process.destroyForcibly()
        }
    }
}
