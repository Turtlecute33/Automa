package app.automa.data

import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Environment variables for the guest that are secret (DESIGN §7): API keys and `GH_TOKEN`.
 * One JSON object, encrypted with [seal] and kept in [file]. Only the names are in memory for the UI.
 */
class Secrets(
    private val file: File,
    private val seal: (ByteArray) -> ByteArray,
    private val open: (ByteArray) -> ByteArray,
) {
    private val lock = Mutex()
    private val _names = MutableStateFlow<List<String>>(emptyList())

    /** The names, sorted. Values never leave [all]. */
    val names: StateFlow<List<String>> = _names.asStateFlow()

    suspend fun load() = lock.withLock { _names.value = readOrEmpty().keys.sorted() }

    /** All values, for a new guest process. A file that cannot be opened now gives no secrets. */
    suspend fun all(): Map<String, String> = lock.withLock { readOrEmpty() }

    /** Throws when the file cannot be opened, so that a short Keystore error does not erase the secrets (review SEC-6). */
    suspend fun put(name: String, value: String) = lock.withLock {
        require(validName(name)) { "Not a valid name" }
        require(validValue(value)) { "Not a valid value" }
        write(read() + (name to value))
    }

    suspend fun remove(name: String) = lock.withLock { write(read() - name) }

    private suspend fun read(): Map<String, String> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyMap()
        val json = JSONObject(open(AtomicFile(file).readFully()).decodeToString())
        json.keys().asSequence().associateWith { json.getString(it) }
    }

    private suspend fun readOrEmpty() = runCatching { read() }.getOrDefault(emptyMap())

    private suspend fun write(map: Map<String, String>) {
        withContext(Dispatchers.IO) {
            val bytes = seal(JSONObject(map).toString().encodeToByteArray())
            val atomic = AtomicFile(file)
            val out = atomic.startWrite()
            runCatching { out.write(bytes) }
                .onSuccess { atomic.finishWrite(out) }
                .onFailure { atomic.failWrite(out); throw it }
        }
        _names.value = map.keys.sorted()
    }

    companion object {
        const val GH_TOKEN = "GH_TOKEN"

        /** Names that the guest needs for itself, or that would change how PRoot and the linker work. */
        private val reserved = setOf("HOME", "PATH", "SHELL", "TERM", "USER", "LOGNAME", "LANG", "TMPDIR", "PWD", "COLORTERM")
        private val pattern = Regex("[A-Z_][A-Z0-9_]{0,63}")

        fun validName(name: String) =
            pattern.matches(name) && name !in reserved && !name.startsWith("LD_") && !name.startsWith("PROOT_")

        fun validValue(value: String) = value.isNotEmpty() && value.length <= 8192 && '\u0000' !in value
    }
}
