package app.automa

import app.automa.chat.Pipe
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "the agent still runs")
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun aClosedStreamEndsTheLines() {
        // Process.destroy() closes the streams while the reader threads read: an IOException is the end, not an error.
        val input = object : InputStream() {
            private val data = "{\"a\":1}\n{\"b\"".toByteArray()
            private var done = false
            override fun read(): Int = throw IOException("Stream closed")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (done) throw IOException("Stream closed")
                done = true
                data.copyInto(b, off)
                return data.size
            }
        }
        val lines = mutableListOf<String>()
        Pipe.lines(input) { lines += it }
        assertEquals(listOf("{\"a\":1}", "{\"b\""), lines)
    }
}
