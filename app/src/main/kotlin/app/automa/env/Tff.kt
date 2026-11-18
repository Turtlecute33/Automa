package app.automa.env

import androidx.compose.runtime.Immutable
import app.automa.data.Secrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Tokens for Friends (TFF), the only AI provider of opencode in Automa (DESIGN §8). opencode cannot read the model
 * list of a custom provider, so Automa gets it from the gateway with `TFF_API_KEY` and writes it to the managed
 * opencode config `/etc/opencode/opencode.json`. The key stays in [Secrets]; opencode gets it from the environment.
 */
class Tff(private val env: Env, private val secrets: Secrets) {
    @Immutable
    sealed interface Status {
        data object NoKey : Status
        data object Loading : Status
        data class Loaded(val count: Int) : Status
        data class Failed(val error: Exception) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.NoKey)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val file get() = env.file(CONFIG)

    /** Reads the number of models in the managed config. */
    suspend fun check() {
        val count = withContext(Dispatchers.IO) { Tree.readSmall(file, MAX_BYTES)?.let { runCatching { count(it) }.getOrNull() } }
        _status.value = if (count != null) Status.Loaded(count) else Status.NoKey
    }

    /**
     * Gets the model list and writes the managed config. The caller holds the setup lock and the guest system
     * exists (a write into a missing rootfs would make an empty one, review SEC-7). Throws on failure.
     */
    suspend fun load() {
        val key = secrets.all()[KEY]
        if (key == null) {
            _status.value = Status.NoKey
            return
        }
        _status.value = Status.Loading
        try {
            val json = Download.text("$BASE_URL/models", MAX_BYTES, mapOf("Authorization" to "Bearer $key"))
            // The JSON is parsed off the main thread (review KM-S-06).
            val ids = withContext(Dispatchers.Default) { models(json) }
            if (ids.isEmpty()) throw IOException("TFF sent no models")
            withContext(Dispatchers.IO) {
                val part = File(file.path + ".part")
                file.parentFile?.mkdirs()
                part.writeText(config(ids))
                if (!part.renameTo(file)) throw IOException("Cannot save the model list")
            }
            _status.value = Status.Loaded(ids.size)
        } catch (e: CancellationException) {
            // NonCancellable: in a cancelled job, check() alone throws at once and the state stays Loading.
            withContext(NonCancellable) { check() }
            throw e
        } catch (e: Exception) {
            _status.value = Status.Failed(e)
            throw e
        }
    }

    companion object {
        const val KEY = "TFF_API_KEY"
        const val PROVIDER = "tff"
        const val BASE_URL = "https://gateway.trytokenfactory.dev/v1"
        const val CONFIG = "/etc/opencode/opencode.json"
        private const val MAX_BYTES = 1 shl 20
        private const val MAX_MODELS = 300

        /**
         * A model id goes into the config and onto the terminal: only plain characters, so that the gateway cannot
         * send terminal control sequences or JSON.
         */
        private val ID = Regex("[A-Za-z0-9._:/@+-]{1,128}")

        /** The model ids from an OpenAI `/models` answer: sorted, no duplicates, only valid ids. */
        fun models(json: String): List<String> {
            val data = JSONObject(json).optJSONArray("data") ?: throw IOException("Unexpected answer from TFF")
            return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id")?.takeIf(ID::matches) }
                .distinct().sorted().take(MAX_MODELS)
        }

        /** The managed opencode config: the TFF provider with [ids]. opencode has its OpenAI-compatible SDK built in. */
        fun config(ids: List<String>): String {
            val models = JSONObject()
            ids.forEach { models.put(it, JSONObject()) }
            val tff = JSONObject()
                .put("name", "Tokens for Friends")
                .put("npm", "@ai-sdk/openai-compatible")
                .put("env", JSONArray().put(KEY))
                .put("options", JSONObject().put("baseURL", BASE_URL))
                .put("models", models)
            return JSONObject().put("provider", JSONObject().put(PROVIDER, tff)).toString(2) + "\n"
        }

        /** The number of TFF models in a managed config [text]. */
        fun count(text: String): Int =
            JSONObject(text).getJSONObject("provider").getJSONObject(PROVIDER).getJSONObject("models").length()
    }
}
