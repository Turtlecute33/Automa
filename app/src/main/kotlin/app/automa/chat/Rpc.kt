package app.automa.chat

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import java.io.IOException

/**
 * JSON-RPC 2.0 over JSON lines (Codex app-server and the Agent Client Protocol, DESIGN §13). Requests from the agent
 * to the app go to [onRequest], notifications to [onNotify]. Not thread-safe: use it on the driver's serial context.
 */
class Rpc(
    private val write: suspend (JSONObject) -> Unit,
    private val onRequest: suspend (id: Any, method: String, params: JSONObject) -> Unit,
    private val onNotify: suspend (method: String, params: JSONObject) -> Unit,
) {
    class Error(val code: Int, message: String) : IOException(message)

    private var next = 1L
    private val pending = HashMap<Long, CompletableDeferred<JSONObject>>()

    /** Sends a request and waits for its result. Throws [Error] for an error answer. */
    suspend fun call(method: String, params: JSONObject = JSONObject()): JSONObject {
        val id = next++
        val result = CompletableDeferred<JSONObject>()
        pending[id] = result
        try {
            write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params))
            return result.await()
        } finally {
            pending.remove(id)
        }
    }

    suspend fun notify(method: String, params: JSONObject = JSONObject()) =
        write(JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params))

    /** The answer to a request of the agent. [result] can be [JSONObject.NULL]. */
    suspend fun reply(id: Any, result: Any) = write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result))

    suspend fun refuse(id: Any, code: Int, message: String) =
        write(JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", code).put("message", message)))

    /** Gives one message to the waiting call or to the handlers. */
    suspend fun receive(json: JSONObject) {
        val method = json.str("method")
        val id = json.opt("id")
        when {
            method.isNotEmpty() && id != null && id != JSONObject.NULL -> onRequest(id, method, json.optJSONObject("params") ?: JSONObject())
            method.isNotEmpty() -> onNotify(method, json.optJSONObject("params") ?: JSONObject())
            id is Number -> {
                val call = pending[id.toLong()] ?: return
                val error = json.optJSONObject("error")
                if (error != null) {
                    call.completeExceptionally(Error(error.optInt("code"), error.str("message").take(500)))
                } else {
                    call.complete(json.optJSONObject("result") ?: JSONObject())
                }
            }
        }
    }

    /** The agent ended: the waiting calls fail. */
    fun close() {
        pending.values.forEach { it.completeExceptionally(IOException("The agent ended")) }
        pending.clear()
    }
}
