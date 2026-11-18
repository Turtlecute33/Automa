package app.automa.chat

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * opencode through the Agent Client Protocol (`opencode acp`, JSON-RPC over stdio, DESIGN §13, research OC-1 to
 * OC-17). Permissions and question forms (`elicitation/create`, opencode 2.x) become cards. opencode 1.x has no
 * question tool in ACP: the model then asks in its text.
 */
class OpencodeDriver(dir: String, @Suppress("unused") private val guest: Guest) : Driver(dir) {
    private val rpc = Rpc(::write, ::request, ::notify)
    private var session = ""
    private var version = ""
    private var turns = 0
    private var last = ""

    /** The ids of the config options for the model, the effort and the mode (research OC-12). */
    private var ids = mapOf<Setting, String>()

    /** The tool calls, merged from their updates (research OC-7). */
    private val tools = HashMap<String, JSONObject>()

    /** The open agent requests, by card id. */
    private val requests = HashMap<String, Pair<Any, JSONObject>>()

    /** opencode 1.x opens a server on a loopback port with no password, unless this is set (research OC-2). */
    private val password = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    override fun command() = listOf("opencode", "acp")

    override fun environment() = mapOf("OPENCODE_SERVER_PASSWORD" to password)

    override suspend fun begin() {
        // Also after an error: the composer must not stay in "Starting" (review KM-M-30).
        try {
            val init = rpc.call(
                "initialize",
                JSONObject().put("protocolVersion", 1).put(
                    "clientCapabilities",
                    JSONObject().put("fs", JSONObject().put("readTextFile", false).put("writeTextFile", false)).put("terminal", false)
                        .put("elicitation", JSONObject().put("form", JSONObject())),
                ).put("clientInfo", JSONObject().put("name", "automa").put("version", "1")),
            )
            version = init.optJSONObject("agentInfo")?.str("version").orEmpty()
            try {
                val s = rpc.call("session/new", JSONObject().put("cwd", dir).put("mcpServers", JSONArray()))
                session = s.str("sessionId")
                config(s.optJSONArray("configOptions"))
                if (flow.value.model == null) update { it.copy(login = NO_MODEL) }
            } catch (e: Rpc.Error) {
                if ("model" in (e.message ?: "")) update { it.copy(login = NO_MODEL) } else fail(e)
            }
        } finally {
            update { it.copy(starting = false, session = session.ifEmpty { null }) }
        }
    }

    override suspend fun receive(json: JSONObject) = rpc.receive(json)

    private suspend fun notify(method: String, p: JSONObject) {
        when (method) {
            "session/update" -> onUpdate(p.optJSONObject("update") ?: return)
            "\$/cancel_request" -> {
                val id = p.opt("requestId") ?: return
                requests.entries.firstOrNull { it.value.first == id }?.key?.let { card ->
                    requests.remove(card)
                    done(card)
                    rpc.refuse(id, -32800, "cancelled")
                }
            }
        }
    }

    private fun onUpdate(u: JSONObject) {
        when (u.str("sessionUpdate")) {
            "agent_message_chunk" -> chunk("msg-${u.str("messageId")}", u, Entry::Text).also { last = (it as? Entry.Text)?.text.orEmpty() }
            "agent_thought_chunk" -> chunk("th-${u.str("messageId")}", u, Entry::Thought)
            "user_message_chunk" -> chunk("user-${u.str("messageId")}", u, Entry::User)
            "tool_call", "tool_call_update" -> {
                val id = u.str("toolCallId")
                val t = tools.getOrPut(id) { JSONObject() }
                u.keys().forEach { t.put(it, u.get(it)) }
                put("tool-$id") { tool("tool-$id", t) }
                // A finished tool gets no more updates: do not keep its data (file texts, review SEC-20).
                if (t.str("status") == "completed" || t.str("status") == "failed") tools.remove(id)
            }
            "available_commands_update" -> update { s ->
                s.copy(commands = u.optJSONArray("availableCommands").objects().map { Option(it.str("name"), it.str("name"), clean(it.str("description"))) })
            }
            "config_option_update" -> config(u.optJSONArray("configOptions"))
            "current_mode_update" -> update { it.copy(mode = u.str("currentModeId")) }
            "plan" -> put("plan") { Entry.Todos("plan", u.optJSONArray("entries").objects().map { todo(it.str("content"), it.str("status")) }) }
            "session_info_update" -> u.optJSONObject("_meta")?.optJSONObject("opencode/retry")?.let { retry ->
                note("Retry ${retry.optInt("attempt")}: ${retry.optJSONObject("error")?.str("message").orEmpty()}")
            }
        }
    }

    /** Adds a text chunk to the entry [key]; returns the new entry. */
    private fun chunk(key: String, u: JSONObject, make: (String, String) -> Entry): Entry? {
        val content = u.optJSONObject("content") ?: return null
        if (content.str("type") != "text") return null
        var made: Entry? = null
        put(key) { old ->
            val text = when (old) {
                is Entry.Text -> old.text
                is Entry.Thought -> old.text
                is Entry.User -> old.text
                else -> ""
            }.let { grow(it, clean(content.str("text"))) }
            make(key, text).also { made = it }
        }
        return made
    }

    /** A tool card from the merged tool call (research OC-7). */
    private fun tool(key: String, t: JSONObject): Entry {
        val input = t.optJSONObject("rawInput") ?: JSONObject()
        val name = t.str("name")
        if (name == "todowrite" || (name.isEmpty() && t.str("title") == "todowrite")) {
            input.optJSONArray("todos")?.let { todos -> return Entry.Todos(key, todos.objects().map { todo(it.str("content"), it.str("status")) }) }
        }
        val kind = when (t.str("kind")) {
            "execute" -> Kind.Command
            "edit" -> Kind.Edit
            "read" -> Kind.Read
            "search" -> Kind.Search
            "fetch" -> Kind.Web
            "think" -> Kind.Task
            else -> Kind.Other
        }
        val content = t.optJSONArray("content").objects()
        val output = content.filter { it.str("type") == "content" }.joinToString("\n") { it.optJSONObject("content")?.str("text").orEmpty() }
        val diffs = content.filter { it.str("type") == "diff" }.map {
            Diff.of(Tools.short(it.str("path"), dir), clean(it.str("oldText")), clean(it.str("newText"))).files.first()
        }
        val meta = t.optJSONObject("rawOutput")?.optJSONObject("metadata")
        val patch = diffs.ifEmpty {
            meta?.str("diff")?.takeIf { it.isNotBlank() }?.let { listOf(Diff.unified(Tools.short(input.str("filePath"), dir), clean(it)).files.first()) }
                ?: meta?.optJSONArray("files").objects().map { Diff.unified(Tools.short(it.str("filePath"), dir), clean(it.str("patch"))).files.first() }
        }
        val command = input.str("command")
        return Entry.Tool(
            key, kind,
            clean(t.str("title").ifEmpty { name.ifEmpty { "Tool" } }).take(200),
            if (command.isNotEmpty()) "$ " + clean(command) else if (kind == Kind.Edit) null else Tools.text(input).takeIf { input.length() > 0 },
            clean(output).takeLast(OUTPUT).ifEmpty { null },
            patch.takeIf { it.isNotEmpty() }?.let(::Diff),
            when (t.str("status")) {
                "completed" -> Status.Done
                "failed" -> Status.Failed
                else -> Status.Running
            },
        )
    }

    private fun todo(text: String, status: String) = Todo(clean(text), when (status) {
        "completed" -> Todo.State.Done
        "in_progress" -> Todo.State.Running
        else -> Todo.State.Pending
    })

    /** The model, effort and mode pickers from `configOptions` (research OC-4, OC-12). */
    private fun config(options: JSONArray?) {
        val all = options.objects()
        fun pick(category: String, id: String) = all.firstOrNull { it.str("category") == category } ?: all.firstOrNull { it.str("id") == id }
        val model = pick("model", "model")
        val effort = pick("thought_level", "effort")
        val mode = pick("mode", "mode")
        ids = listOfNotNull(model?.let { Setting.Model to it.str("id") }, effort?.let { Setting.Effort to it.str("id") }, mode?.let { Setting.Mode to it.str("id") }).toMap()
        fun list(o: JSONObject?) = o?.optJSONArray("options").objects().map { Option(it.str("value"), clean(it.str("name")), clean(it.str("description"))) }
        update {
            it.copy(
                models = list(model), model = model?.str("currentValue")?.takeIf { v -> v != "unknown/unknown" } ?: it.model,
                efforts = list(effort), effort = effort?.str("currentValue") ?: it.effort,
                modes = list(mode), mode = mode?.str("currentValue") ?: it.mode,
            )
        }
    }

    private suspend fun request(id: Any, method: String, p: JSONObject) {
        val key = cardId(id)
        // A second request with an open id would take the place of the first: an answer must go to the request that its
        // card shows (review DS-L-11, KM-H-02).
        if (key in requests) {
            rpc.refuse(id, -32600, "The request id is in use")
            return
        }
        val card: Ask = when (method) {
            "session/request_permission" -> {
                val call = p.optJSONObject("toolCall") ?: JSONObject()
                val t = tools.getOrPut(call.str("toolCallId")) { JSONObject() }
                call.keys().forEach { t.put(it, call.get(it)) }
                val shown = tool("tool-${call.str("toolCallId")}", t) as? Entry.Tool
                Ask.Permission(
                    key, "Allow ${shown?.title ?: "this tool"}?", shown?.input, shown?.diff,
                    p.optJSONArray("options").objects().map {
                        Choice(
                            it.str("optionId"), clean(it.str("name")),
                            when (it.str("kind")) {
                                "allow_once" -> Choice.Kind.Allow
                                "allow_always" -> Choice.Kind.Always
                                else -> Choice.Kind.Deny
                            },
                        )
                    },
                )
            }
            "elicitation/create" -> {
                val questions = if (p.str("mode", "form") == "form") Forms.questions(p.optJSONObject("requestedSchema") ?: JSONObject()) else emptyList()
                if (questions.isEmpty()) {
                    rpc.reply(id, JSONObject().put("action", "decline"))
                    return
                }
                Ask.Questions(key, questions)
            }
            // opencode 1.x writes an approved edit itself and also sends it here: write nothing (research OC-8).
            "fs/write_text_file" -> {
                rpc.reply(id, JSONObject.NULL)
                return
            }
            else -> {
                rpc.refuse(id, -32601, "Method not found")
                return
            }
        }
        requests[key] = id to p.put("method", method)
        ask(card)
    }

    override suspend fun answer(ask: Ask, answer: Answer) = act {
        if (ask.id == PLAN) {
            done(PLAN)
            if ((answer as? Answer.Pick)?.choice?.id == "build") {
                ids[Setting.Mode]?.let { set(it, "build") }
                prompt("Do the plan.")
            }
            return@act
        }
        val (id, p) = requests.remove(ask.id) ?: return@act done(ask.id)
        done(ask.id)
        rpc.reply(id, result(p, answer))
    }

    private fun result(p: JSONObject, answer: Answer): JSONObject = when (p.str("method")) {
        "session/request_permission" -> JSONObject().put(
            "outcome",
            if (answer is Answer.Pick) JSONObject().put("outcome", "selected").put("optionId", answer.choice.id) else JSONObject().put("outcome", "cancelled"),
        )
        else -> if (answer is Answer.Replies) {
            JSONObject().put("action", "accept").put("content", Forms.content(Forms.questions(p.optJSONObject("requestedSchema") ?: JSONObject()), answer.replies))
        } else {
            JSONObject().put("action", "decline")
        }
    }

    override suspend fun send(text: String) = act { prompt(text) }

    private suspend fun prompt(text: String) {
        // opencode 1.x drops a text that starts with an unknown /command (research OC-14).
        val command = text.trim().takeIf { it.startsWith("/") }?.drop(1)?.substringBefore(' ')
        if (version.startsWith("1.") && command != null && command != "compact" && flow.value.commands.none { it.id == command }) {
            note("opencode has no command /$command. To send the text, do not start it with /.", error = true)
            return
        }
        add(Entry.User(key("user"), text))
        update { it.copy(busy = true) }
        last = ""
        try {
            val r = rpc.call(
                "session/prompt",
                JSONObject().put("sessionId", session).put("prompt", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            )
            val reason = r.str("stopReason")
            when (reason) {
                "cancelled" -> note("Stopped.")
                "max_tokens" -> note("The answer reached the length limit.", error = true)
                "refusal" -> note("The model refused to answer.", error = true)
            }
            turns++
            // Only a plan that came to its end: after a Stop, a Reject, the length limit or a refusal, the text is not
            // a full plan (Codex also asks only after a completed turn).
            if (flow.value.mode == "plan" && last.isNotBlank() && reason !in STOPPED) {
                ask(
                    Ask.Plan(
                        PLAN, last,
                        listOf(Choice("build", "Yes, switch to Build and do the plan", Choice.Kind.Allow), Choice("stay", "No, keep planning", Choice.Kind.Deny)),
                        feedback = false,
                    ),
                )
            }
        } catch (e: Rpc.Error) {
            if (e.code == -32000) update { it.copy(login = "The TFF key is missing or not valid. Add TFF_API_KEY in Settings → API keys, then start a new chat.") }
            else fail(e)
        } finally {
            update { it.copy(busy = false) }
        }
    }

    override suspend fun interrupt() = act {
        rpc.notify("session/cancel", JSONObject().put("sessionId", session))
        // The ACP spec: the client answers each open permission request with "cancelled" (research OC-11).
        requests.toList().forEach { (card, request) ->
            requests.remove(card)
            done(card)
            rpc.reply(request.first, result(request.second, Answer.Dismiss).let { r ->
                if (request.second.str("method") == "session/request_permission") r else JSONObject().put("action", "cancel")
            })
        }
    }

    override suspend fun select(setting: Setting, id: String) = act {
        ids[setting]?.let { set(it, id) }
    }

    private suspend fun set(configId: String, value: String) {
        val r = rpc.call("session/set_config_option", JSONObject().put("sessionId", session).put("configId", configId).put("value", value))
        config(r.optJSONArray("configOptions"))
    }

    override fun terminal() = if (turns > 0 && safe(session)) "opencode --session $session" else null

    override fun closed() = rpc.close()

    private companion object {
        const val PLAN = "plan"
        const val OUTPUT = 20_000
        val STOPPED = setOf("cancelled", "max_tokens", "refusal")
        const val NO_MODEL = "opencode has no model. Add TFF_API_KEY in Settings → API keys, then start a new chat."
    }
}
