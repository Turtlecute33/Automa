package app.automa.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Claude Code in stream-json mode with the control protocol of the Agent SDK (DESIGN §13, research CC-1 to CC-26).
 * Each permission, each AskUserQuestion and each plan approval comes as a `can_use_tool` request and becomes a card.
 */
class ClaudeDriver(dir: String, private val guest: Guest) : Driver(dir) {
    private val session = UUID.randomUUID().toString()
    private var turns = 0
    private var trusted = false
    private val pending = HashMap<String, CompletableDeferred<JSONObject>>()

    /** The open `can_use_tool` requests, by request id. */
    private val requests = HashMap<String, JSONObject>()
    private var models = JSONArray()

    /** Streamed text and thinking: the entry keys of a message, in order, and how many a full block replaced. */
    private var message = ""
    private val streamed = HashMap<String, MutableList<String>>()
    private val used = HashMap<String, Int>()

    override fun command(): List<String> = buildList {
        addAll(
            listOf(
                "claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json", "--verbose",
                "--include-partial-messages", "--permission-prompt-tool", "stdio", "--permission-mode", "default",
                "--session-id", session,
            ),
        )
        // -p skips the trust question: a folder that the user did not trust in Claude Code gets no project settings,
        // hooks or MCP servers, so that a cloned repository cannot start programs (research CC-25).
        if (!trusted) addAll(listOf("--setting-sources", "user", "--strict-mcp-config"))
    }

    override fun environment() = mapOf("CLAUDE_CODE_SDK_READS_SESSION_STATE" to "1", "CLAUDE_CODE_STARTUP_FAILURE_RESULTS" to "1")

    override suspend fun prepare(): Boolean {
        trusted = trusted(guest.read("/root/.claude.json", 8 shl 20), dir)
        return true
    }

    override suspend fun begin() = coroutineScope {
        launch {
            // In the home folder: a repository's .claude settings must not load for this check (review SEC-15).
            if (guest.exit(listOf("claude", "auth", "status"), HOME) != 0) update { it.copy(login = LOGIN) }
        }
        if (!trusted) {
            note("The project settings of this folder are off (CLAUDE.md, hooks, MCP servers): Claude Code does not trust it yet. " +
                "To trust it, open the terminal and answer the trust question, then start a new chat.")
        }
        try {
            val answer = withTimeout(START_MS) { control(JSONObject().put("subtype", "initialize")) }
            models = answer.optJSONArray("models") ?: JSONArray()
            val commands = answer.optJSONArray("commands").objects().map { Option(it.str("name"), it.str("name"), clean(it.str("description"))) }
            update { s ->
                val model = s.model ?: models.objects().firstOrNull { it.str("value") == "default" }?.str("value")
                s.copy(
                    starting = false, models = modelOptions(models), model = model, modes = modes(model), mode = s.mode ?: "default",
                    efforts = efforts(model), commands = commands,
                )
            }
        } catch (e: Exception) {
            update { it.copy(starting = false) }
            if (e is IOException) fail(e) else note("Claude Code did not answer at the start.", error = true)
        }
    }

    override suspend fun receive(json: JSONObject) {
        when (json.str("type")) {
            "stream_event" -> stream(json.optJSONObject("event") ?: return, json.isNull("parent_tool_use_id"))
            "assistant" -> assistant(json)
            "user" -> toolResults(json)
            "result" -> result(json)
            "system" -> system(json)
            "conversation_reset" -> update { it.copy(entries = emptyList()) }.also { note("New conversation") }
            "rate_limit_event" -> json.optJSONObject("rate_limit_info")?.let { info ->
                when (info.str("status")) {
                    "allowed_warning" -> note("You are near a usage limit of your plan.")
                    "rejected" -> note("You reached a usage limit of your plan.", error = true)
                }
            }
            "control_request" -> request(json.str("request_id"), json.optJSONObject("request") ?: JSONObject())
            "control_cancel_request" -> json.str("request_id").let { requests.remove(it); done(it) }
            "control_response" -> json.optJSONObject("response")?.let { r ->
                val call = pending.remove(r.str("request_id")) ?: return
                if (r.str("subtype") == "success") call.complete(r.optJSONObject("response") ?: JSONObject())
                else call.completeExceptionally(IOException(clean(r.str("error")).take(300)))
            }
        }
    }

    private fun stream(event: JSONObject, main: Boolean) {
        if (!main) return
        when (event.str("type")) {
            "message_start" -> message = event.optJSONObject("message")?.str("id").orEmpty()
            "content_block_start" -> {
                val block = event.optJSONObject("content_block") ?: return
                val type = block.str("type")
                if (type == "text" || type == "thinking") {
                    val key = "$type-$message-${event.optInt("index")}"
                    streamed.getOrPut("$type-$message") { mutableListOf() } += key
                    put(key) { if (type == "text") Entry.Text(key, "") else Entry.Thought(key, "") }
                }
            }
            "content_block_delta" -> {
                val delta = event.optJSONObject("delta") ?: return
                val key = "${if (delta.str("type") == "thinking_delta") "thinking" else "text"}-$message-${event.optInt("index")}"
                when (delta.str("type")) {
                    "text_delta" -> put(key) { old -> Entry.Text(key, grow((old as? Entry.Text)?.text ?: "", clean(delta.str("text")))) }
                    "thinking_delta" -> put(key) { old -> Entry.Thought(key, grow((old as? Entry.Thought)?.text ?: "", clean(delta.str("thinking")))) }
                }
            }
        }
    }

    private fun assistant(json: JSONObject) {
        val msg = json.optJSONObject("message") ?: return
        val id = msg.str("id")
        val sub = !json.isNull("parent_tool_use_id")
        if (json.str("error") == "authentication_failed") update { it.copy(login = LOGIN) }
        for (block in msg.optJSONArray("content").objects()) {
            when (val type = block.str("type")) {
                "text", "thinking" -> {
                    if (sub) continue
                    val text = clean(block.str(type)).takeLast(MAX_TEXT)
                    val list = streamed["$type-$id"].orEmpty()
                    val n = used["$type-$id"] ?: 0
                    used["$type-$id"] = n + 1
                    val key = list.getOrNull(n) ?: "$type-$id-x$n"
                    if (text.isBlank() && type == "thinking") continue
                    put(key) { if (type == "text") Entry.Text(key, text) else Entry.Thought(key, text) }
                }
                "tool_use" -> toolUse(block, sub)
            }
        }
    }

    private fun toolUse(block: JSONObject, sub: Boolean) {
        val id = block.str("id")
        val name = block.str("name")
        val input = block.optJSONObject("input") ?: JSONObject()
        if (name == "TodoWrite") {
            val todos = input.optJSONArray("todos").objects().map {
                Todo(clean(it.str("content")), when (it.str("status")) {
                    "completed" -> Todo.State.Done
                    "in_progress" -> Todo.State.Running
                    else -> Todo.State.Pending
                })
            }
            put("tool-$id") { Entry.Todos("tool-$id", todos) }
            return
        }
        val (kind, title) = Tools.claude(name, input, dir)
        put("tool-$id") { old ->
            val status = (old as? Entry.Tool)?.status ?: Status.Running
            Entry.Tool("tool-$id", kind, (if (sub) "↳ " else "") + title, Tools.claudeInput(name, input), (old as? Entry.Tool)?.output, Tools.claudeDiff(name, input, guest, dir), status)
        }
    }

    private fun toolResults(json: JSONObject) {
        val structured = json.optJSONObject("tool_use_result")
        for (block in json.optJSONObject("message")?.optJSONArray("content").objects()) {
            if (block.str("type") != "tool_result") continue
            val key = "tool-${block.str("tool_use_id")}"
            val output = when (val content = block.opt("content")) {
                is String -> content
                is JSONArray -> content.objects().filter { it.str("type") == "text" }.joinToString("\n") { it.str("text") }
                else -> ""
            }
            val tool = find(key) as? Entry.Tool ?: continue
            val patch = structured?.let { Tools.claudePatch(it, dir) }
            put(key) {
                tool.copy(
                    output = clean(output).takeLast(OUTPUT),
                    status = if (block.optBoolean("is_error")) Status.Failed else Status.Done,
                    diff = patch ?: tool.diff,
                )
            }
        }
    }

    private fun result(json: JSONObject) {
        turns++
        update { it.copy(busy = false, starting = false) }
        val failed = json.optBoolean("is_error") || json.str("subtype") != "success"
        if (!failed) return
        val text = json.str("result").ifEmpty { json.optJSONArray("errors").strings().joinToString("\n") }
        when {
            "/login" in text -> update { it.copy(login = LOGIN) }
            json.str("terminal_reason").startsWith("aborted") -> note("Stopped.")
            else -> note(text.ifEmpty { "The turn ended with an error." }, error = true)
        }
    }

    private fun system(json: JSONObject) {
        when (json.str("subtype")) {
            "init" -> update { s ->
                val model = modelId(json.str("model")) ?: s.model
                val hidden = json.optJSONArray("terminal_slash_commands").strings().toSet()
                val commands = s.commands.ifEmpty {
                    json.optJSONArray("slash_commands").strings().filter { it !in hidden }.map { Option(it, it) }
                }
                s.copy(
                    model = model,
                    mode = json.str("permissionMode").ifEmpty { s.mode },
                    modes = modes(model),
                    efforts = efforts(model),
                    effort = json.str("effort").ifEmpty { s.effort },
                    commands = commands,
                    session = json.str("session_id").ifEmpty { s.session },
                )
            }
            "status" -> {
                json.str("permissionMode").takeIf { it.isNotEmpty() }?.let { mode -> update { it.copy(mode = mode) } }
                if (json.str("status") == "compacting") note("Compacting the conversation…")
            }
            "session_state_changed" -> when (json.str("state")) {
                "running" -> update { it.copy(busy = true) }
                "idle" -> update { it.copy(busy = false) }
            }
            "api_retry" -> note("Server error, retry ${json.optInt("attempt")} of ${json.optInt("max_retries")}")
            "compact_boundary" -> note("The conversation was compacted.")
            "permission_denied" -> note("Denied: ${json.str("tool_name")}. ${json.str("message")}".trim())
            "local_command_output" -> add(Entry.Text(key("out"), cut(clean(json.str("content")), MAX_TEXT)))
            "informational" -> note(json.str("content"), error = json.str("level") == "warning")
            "task_notification" -> json.str("summary").takeIf { it.isNotBlank() }?.let { note(it) }
        }
    }

    private suspend fun request(id: String, request: JSONObject) {
        when (request.str("subtype")) {
            "can_use_tool" -> {
                requests[id] = request
                ask(ask(id, request))
            }
            "elicitation" -> write(response(id, JSONObject().put("action", "decline")))
            // A dialog kind that the app did not declare: no answer (research CC-1).
            "request_user_dialog" -> Unit
            else -> write(
                JSONObject().put("type", "control_response").put(
                    "response",
                    JSONObject().put("subtype", "error").put("request_id", id).put("error", "Not supported by Automa"),
                ),
            )
        }
    }

    /** The card for a `can_use_tool` request (research CC-13, CC-16, CC-17). */
    private fun ask(id: String, r: JSONObject): Ask {
        val tool = r.str("tool_name")
        val input = r.optJSONObject("input") ?: JSONObject()
        return when (tool) {
            "AskUserQuestion" -> Ask.Questions(id, input.optJSONArray("questions").objects().map { q ->
                Question(
                    id = q.str("question"),
                    header = clean(q.str("header")),
                    text = clean(q.str("question")),
                    options = q.optJSONArray("options").objects().map { Question.Option(clean(it.str("label")), clean(it.str("description"))) },
                    multi = q.optBoolean("multiSelect"),
                    other = true,
                )
            })
            "ExitPlanMode" -> Ask.Plan(
                id,
                clean(input.str("plan").ifEmpty { input.str("planFilePath").takeIf { it.isNotEmpty() }?.let { guest.read(it, 1 shl 20) }.orEmpty() }),
                listOf(
                    Choice("acceptEdits", "Yes, and accept edits", Choice.Kind.Allow),
                    Choice("default", "Yes, and ask before edits", Choice.Kind.Always),
                    Choice("deny", "No, keep planning", Choice.Kind.Deny),
                ),
            )
            else -> {
                val suggestions = if (r.optBoolean("suppress_always_allow_rule")) emptyList() else r.optJSONArray("permission_suggestions").objects()
                val no = r.optBoolean("default_to_no")
                val reason = clean(r.str("decision_reason")).takeIf { it.isNotBlank() }
                Ask.Permission(
                    id = id,
                    title = clean(r.str("title").ifEmpty { "Allow ${r.str("display_name").ifEmpty { tool }}?" }),
                    detail = listOfNotNull(Tools.claudeInput(tool, input), reason).joinToString("\n\n"),
                    diff = Tools.claudeDiff(tool, input, guest, dir),
                    choices = listOf(Choice("allow", "Yes", Choice.Kind.Allow)) +
                        suggestions.mapIndexed { i, s -> Choice("s$i", suggestionLabel(s), Choice.Kind.Always) } +
                        Choice("deny", "No", Choice.Kind.Deny),
                    feedback = true,
                    // The diff shows one change, but Yes changes all the places (review KM-M-24).
                    message = if (tool == "Edit" && input.optBoolean("replace_all")) "This edit changes all the places in the file that have the old text." else null,
                    caution = no,
                ).let { if (no) it.copy(choices = it.choices.takeLast(1) + it.choices.dropLast(1)) else it }
            }
        }
    }

    override suspend fun answer(ask: Ask, answer: Answer) = act {
        val r = requests.remove(ask.id) ?: return@act done(ask.id)
        done(ask.id)
        val input = r.optJSONObject("input") ?: JSONObject()
        val toolUse = r.str("tool_use_id")
        write(response(ask.id, permission(ask, answer, input, r.optJSONArray("permission_suggestions")).put("toolUseID", toolUse)))
    }

    override suspend fun send(text: String) = act {
        add(Entry.User(key("user"), text))
        update { it.copy(busy = true) }
        write(
            JSONObject().put("type", "user").put("session_id", "").put("parent_tool_use_id", JSONObject.NULL).put(
                "message",
                JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))),
            ),
        )
    }

    override suspend fun interrupt() = act {
        control(JSONObject().put("subtype", "interrupt"))
    }

    override suspend fun select(setting: Setting, id: String) = act {
        when (setting) {
            Setting.Model -> {
                control(JSONObject().put("subtype", "set_model").put("model", id))
                update { it.copy(model = id, modes = modes(id), efforts = efforts(id)) }
            }
            Setting.Mode -> {
                control(JSONObject().put("subtype", "set_permission_mode").put("mode", id))
                update { it.copy(mode = id) }
            }
            Setting.Effort -> {
                control(JSONObject().put("subtype", "apply_flag_settings").put("settings", JSONObject().put("effortLevel", id)))
                update { it.copy(effort = id) }
            }
        }
    }

    override fun terminal() = if (turns > 0) "claude --resume $session" else null

    override fun closed() {
        pending.values.forEach { it.completeExceptionally(IOException("Claude Code ended")) }
        pending.clear()
        requests.clear()
    }

    /** Sends a control request and waits for its answer. */
    private suspend fun control(request: JSONObject): JSONObject {
        val id = UUID.randomUUID().toString().take(13)
        val call = CompletableDeferred<JSONObject>()
        pending[id] = call
        write(JSONObject().put("type", "control_request").put("request_id", id).put("request", request))
        return call.await()
    }

    private fun modelId(resolved: String): String? = models.objects().firstOrNull {
        it.str("resolvedModel") == resolved || it.str("value") == resolved
    }?.str("value") ?: resolved.ifEmpty { null }

    private fun model(id: String?) = models.objects().firstOrNull { it.str("value") == id }

    private fun modes(model: String?) = buildList {
        add(Option("default", "Ask before edits", "Claude asks before it changes files or runs commands."))
        add(Option("acceptEdits", "Accept edits", "Claude changes files with no question; commands still ask."))
        add(Option("plan", "Plan", "Claude only reads and makes a plan."))
        if (model(model)?.optBoolean("supportsAutoMode") == true) add(Option("auto", "Auto", "A classifier approves safe actions."))
    }

    private fun efforts(model: String?): List<Option> =
        (model(model) ?: models.objects().firstOrNull())?.optJSONArray("supportedEffortLevels").strings().map { Option(it, effortLabel(it)) }

    companion object {
        private const val START_MS = 90_000L
        private const val HOME = "/root"
        private const val LOGIN = "Claude Code is not logged in. Log in in the terminal, then start a new chat."
        private const val OUTPUT = 20_000

        /** Trust from Claude Code's own store (`~/.claude.json`): the folder or a parent folder. */
        fun trusted(config: String?, dir: String): Boolean {
            val projects = config?.let { runCatching { JSONObject(it).optJSONObject("projects") }.getOrNull() } ?: return false
            var path = dir
            while (path.isNotEmpty()) {
                if (projects.optJSONObject(path)?.optBoolean("hasTrustDialogAccepted") == true) return true
                path = path.substringBeforeLast('/', "")
            }
            return false
        }

        fun modelOptions(models: JSONArray) = models.objects().map {
            Option(it.str("value"), clean(it.str("displayName").ifEmpty { it.str("value") }), clean(it.str("description")))
        }

        /** The label of a "do not ask again" suggestion, like the terminal UI (research CC-14). */
        fun suggestionLabel(s: JSONObject): String = when (s.str("type")) {
            "addRules" -> {
                val rules = s.optJSONArray("rules").objects()
                val text = rules.joinToString(", ") { r -> r.str("ruleContent").ifEmpty { "any ${r.str("toolName")} call" } }
                "Yes, and do not ask again for: ${clean(text)}"
            }
            "setMode" -> when (s.str("mode")) {
                "acceptEdits" -> "Yes, and accept edits for this session"
                "auto" -> "Yes, and switch to auto mode"
                else -> "Yes, and switch to ${clean(s.str("mode"))} mode"
            }
            "addDirectories" -> "Yes, and allow ${clean(s.optJSONArray("directories").strings().joinToString(", "))} for this session"
            else -> "Yes, and do not ask again"
        }

        /** The `PermissionResult` for [answer] (research CC-14, CC-16, CC-17). */
        fun permission(ask: Ask, answer: Answer, input: JSONObject, suggestions: JSONArray?): JSONObject {
            fun allow(updatedInput: JSONObject = input) = JSONObject().put("behavior", "allow").put("updatedInput", updatedInput)
            fun deny(message: String) = JSONObject().put("behavior", "deny").put("message", message)
            return when (answer) {
                Answer.Dismiss -> deny(if (ask is Ask.Questions) "The user did not answer the questions." else "The user denied this tool call.")
                is Answer.Replies -> {
                    val questions = input.optJSONArray("questions").objects()
                    val answers = JSONObject()
                    questions.forEachIndexed { i, q ->
                        val reply = answer.replies.getOrNull(i) ?: return@forEachIndexed
                        val values = reply.labels + listOfNotNull(reply.other.takeIf { it.isNotBlank() })
                        if (values.isEmpty()) return@forEachIndexed
                        answers.put(q.str("question"), if (q.optBoolean("multiSelect")) JSONArray(values) else values.last())
                    }
                    allow(JSONObject(input.toString()).put("answers", answers))
                }
                is Answer.Pick -> when {
                    answer.choice.kind == Choice.Kind.Deny -> deny(
                        answer.feedback.ifBlank { if (ask is Ask.Plan) "The user wants to keep planning." else "The user denied this tool call." },
                    )
                    ask is Ask.Plan -> allow().put(
                        "updatedPermissions",
                        JSONArray().put(JSONObject().put("type", "setMode").put("mode", answer.choice.id).put("destination", "session")),
                    )
                    answer.choice.id.startsWith("s") -> {
                        val suggestion = suggestions?.optJSONObject(answer.choice.id.drop(1).toInt())
                        allow().apply { if (suggestion != null) put("updatedPermissions", JSONArray().put(suggestion)) }
                    }
                    else -> allow()
                }
            }
        }

        private fun response(id: String, response: JSONObject) = JSONObject().put("type", "control_response")
            .put("response", JSONObject().put("subtype", "success").put("request_id", id).put("response", response))
    }
}
