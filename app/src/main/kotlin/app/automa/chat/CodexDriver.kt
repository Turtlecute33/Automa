package app.automa.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Codex through `codex app-server` (JSON-RPC over stdio, DESIGN §13, research CX-1 to CX-24). Approvals, questions
 * and MCP tool approvals are server requests and become cards. A plan of Plan mode ends with a local approval card.
 */
class CodexDriver(dir: String, private val guest: Guest) : Driver(dir) {
    private val rpc = Rpc(::write, ::request, ::notify)
    private var thread = ""
    private var turn = ""

    /** A Stop came while the turn/start answer with the turn id waited. */
    private var stopping = false
    private var turns = 0
    private var models = JSONArray()
    private var plan: String? = null
    private var error = false

    /** Text for the agent after a "No, and stop": it goes as the next message when the turn has ended. */
    private var next: String? = null
    private var trust: CompletableDeferred<Answer>? = null

    /** The open server requests, by card id. */
    private val requests = HashMap<String, Pair<Any, JSONObject>>()

    /** The file changes of each fileChange item, for the approval card (research CX-14). */
    private val changes = HashMap<String, JSONArray>()

    override fun command() = listOf("codex", "app-server", "--disable", "plugins")

    /**
     * `thread/start` trusts a git repository with no question (research CX-6), and a trusted folder loads its
     * `.codex/` settings, which can start programs. Thus the user decides first, and Automa writes the answer. One
     * decision at a time: two chats in a new folder both wrote the table, and a table twice makes the TOML invalid.
     */
    override suspend fun prepare(): Boolean = TRUST_LOCK.withLock { decide() }

    private suspend fun decide(): Boolean {
        val config = guest.read(CONFIG, 1 shl 20).orEmpty()
        val table = "[projects.\"${toml(dir)}\"]"
        if (table in config) return true
        val answer = CompletableDeferred<Answer>()
        trust = answer
        ask(
            Ask.Permission(
                TRUST, "Trust this folder?", null, null,
                listOf(Choice("trusted", "Trust", Choice.Kind.Allow), Choice("untrusted", "Do not trust", Choice.Kind.Deny)),
                message = "Codex then loads the settings of this repository in .codex/. They can start programs. Trust only " +
                    "repositories that you know.",
            ),
        )
        val pick = answer.await() as? Answer.Pick ?: return false
        // Codex must not start when the answer is not saved: thread/start would trust the folder (review SEC-19).
        if (!guest.append(CONFIG, "\n$table\ntrust_level = \"${pick.choice.id}\"\n") || table !in guest.read(CONFIG, 1 shl 20).orEmpty()) {
            end("Automa could not save the trust answer in ~/.codex/config.toml, so Codex did not start.")
            return false
        }
        return true
    }

    override suspend fun begin() {
        // Also after an error: the composer must not stay in "Starting" (review KM-M-30).
        try {
            rpc.call(
                "initialize",
                JSONObject()
                    .put("clientInfo", JSONObject().put("name", "automa").put("title", "Automa").put("version", "1"))
                    .put(
                        "capabilities",
                        JSONObject().put("experimentalApi", true)
                            .put("optOutNotificationMethods", JSONArray(listOf("remoteControl/status/changed", "rawResponseItem/completed", "rawResponse/completed"))),
                    ),
            )
            rpc.notify("initialized")
            val account = rpc.call("account/read", JSONObject().put("refreshToken", false))
            if (account.isNull("account") && account.optBoolean("requiresOpenaiAuth")) update { it.copy(login = LOGIN) }
            models = rpc.call("model/list", JSONObject().put("limit", 100).put("includeHidden", false)).optJSONArray("data") ?: JSONArray()
            val started = rpc.call(
                "thread/start",
                JSONObject().put("cwd", dir).put("approvalPolicy", "untrusted").put("sandbox", "danger-full-access"),
            )
            thread = started.optJSONObject("thread")?.str("id").orEmpty()
            val model = started.str("model").ifEmpty { models.objects().firstOrNull { it.optBoolean("isDefault") }?.str("id") }
            update {
                it.copy(
                    starting = false,
                    session = thread,
                    models = models.objects().map { m -> Option(m.str("id"), clean(m.str("displayName")), clean(m.str("description"))) },
                    model = model,
                    efforts = efforts(model),
                    effort = started.str("reasoningEffort").ifEmpty { null } ?: default(model),
                    modes = MODES,
                    mode = "ask",
                )
            }
        } finally {
            update { it.copy(starting = false) }
        }
    }

    override suspend fun receive(json: JSONObject) = rpc.receive(json)

    private suspend fun notify(method: String, p: JSONObject) {
        when (method) {
            "turn/started" -> {
                turn = p.optJSONObject("turn")?.str("id").orEmpty()
                update { it.copy(busy = true) }
            }
            "turn/completed" -> completed(p.optJSONObject("turn") ?: JSONObject())
            "item/started" -> item(p.optJSONObject("item") ?: return)
            "item/completed" -> item(p.optJSONObject("item") ?: return)
            "item/agentMessage/delta" -> append("msg-${p.str("itemId")}", p.str("delta"), thought = false)
            "item/reasoning/summaryTextDelta" -> append("rs-${p.str("itemId")}", p.str("delta"), thought = true)
            "item/reasoning/summaryPartAdded" -> append("rs-${p.str("itemId")}", "\n\n", thought = true)
            "item/plan/delta" -> append("plan-${p.str("itemId")}", p.str("delta"), thought = false)
            "item/commandExecution/outputDelta" -> {
                val key = "item-${p.str("itemId")}"
                (find(key) as? Entry.Tool)?.let { t -> put(key) { t.copy(output = ((t.output ?: "") + clean(p.str("delta"))).takeLast(OUTPUT)) } }
            }
            "item/fileChange/patchUpdated" -> {
                val id = p.str("itemId")
                val list = p.optJSONArray("changes") ?: return
                changes[id] = list
                (find("item-$id") as? Entry.Tool)?.let { t -> put("item-$id") { t.copy(diff = diff(list, dir)) } }
            }
            "turn/plan/updated" -> put("todo-${p.str("turnId")}") { key ->
                Entry.Todos("todo-${p.str("turnId")}", p.optJSONArray("plan").objects().map {
                    Todo(clean(it.str("step")), when (it.str("status")) {
                        "completed" -> Todo.State.Done
                        "inProgress" -> Todo.State.Running
                        else -> Todo.State.Pending
                    })
                })
            }
            "error" -> {
                val e = p.optJSONObject("error") ?: JSONObject()
                if (p.optBoolean("willRetry")) {
                    note("Retry: ${e.str("message")}")
                } else {
                    error = true
                    failure(e)
                }
            }
            "warning" -> note(p.str("message"))
            "serverRequest/resolved" -> requests.entries.firstOrNull { it.value.first.toString() == p.opt("requestId").toString() }?.key?.let {
                requests.remove(it)
                done(it)
            }
            "account/updated" -> if (!p.isNull("authMode")) update { it.copy(login = null) }
            "model/rerouted" -> note("Codex uses ${p.str("toModel")} for this turn (${p.str("reason")}).")
        }
    }

    private fun failure(e: JSONObject) {
        if (e.opt("codexErrorInfo") == "unauthorized") update { it.copy(login = LOGIN) }
        else note(e.str("message").ifEmpty { "The turn failed." }, error = true)
    }

    private suspend fun completed(t: JSONObject) {
        turns++
        update { it.copy(busy = false) }
        // The server drops its open requests at the end of a turn (research CX-19).
        requests.keys.toList().forEach(::done)
        requests.clear()
        when (t.str("status")) {
            "interrupted" -> note("Stopped.")
            "failed" -> if (!error) failure(t.optJSONObject("error") ?: JSONObject())
        }
        error = false
        val text = plan
        plan = null
        if (text != null && flow.value.mode == "plan" && t.str("status") == "completed") {
            ask(
                Ask.Plan(
                    PLAN, text,
                    listOf(Choice("implement", "Yes, implement this plan", Choice.Kind.Allow), Choice("stay", "No, stay in Plan mode", Choice.Kind.Deny)),
                    feedback = false,
                ),
            )
        }
        next?.let {
            next = null
            send(it)
        }
    }

    private fun append(key: String, delta: String, thought: Boolean) = put(key) { old ->
        val text = grow((old as? Entry.Text)?.text ?: (old as? Entry.Thought)?.text ?: "", clean(delta))
        if (thought) Entry.Thought(key, text) else Entry.Text(key, text)
    }

    private fun item(item: JSONObject) {
        val id = item.str("id")
        val status = when (item.str("status")) {
            "completed" -> Status.Done
            "failed", "declined" -> Status.Failed
            else -> Status.Running
        }
        when (val type = item.str("type")) {
            "agentMessage" -> item.str("text").takeIf { it.isNotEmpty() }?.let { text -> put("msg-$id") { Entry.Text("msg-$id", clean(text).takeLast(MAX_TEXT)) } }
            "reasoning" -> item.optJSONArray("summary").strings().joinToString("\n\n").takeIf { it.isNotBlank() }?.let { text ->
                put("rs-$id") { Entry.Thought("rs-$id", clean(text).takeLast(MAX_TEXT)) }
            }
            "plan" -> item.str("text").takeIf { it.isNotEmpty() }?.let { text ->
                val shown = cut(clean(text), MAX_TEXT)
                plan = shown
                put("plan-$id") { Entry.Text("plan-$id", shown) }
            }
            "commandExecution" -> put("item-$id") { old ->
                val action = item.optJSONArray("commandActions").objects().singleOrNull()
                val title = when (action?.str("type")) {
                    "read" -> "Read ${clean(action.str("path").ifEmpty { action.str("name") })}"
                    "search" -> "Search ${clean(action.str("query"))}"
                    "listFiles" -> "List files"
                    else -> clean(item.str("command"))
                }.take(Tools.TITLE)
                val output = item.str("aggregatedOutput").takeIf { it.isNotEmpty() }?.let { clean(it).takeLast(OUTPUT) }
                val code = if (item.isNull("exitCode") || !item.has("exitCode")) "" else "\nexit code ${item.optInt("exitCode")}"
                Entry.Tool(
                    "item-$id", Kind.Command, title, "$ " + cut(clean(item.str("command")), Tools.INPUT),
                    (output ?: (old as? Entry.Tool)?.output)?.let { it + code } ?: code.trim().ifEmpty { null },
                    status = status,
                )
            }
            "fileChange" -> {
                val list = item.optJSONArray("changes") ?: JSONArray()
                // Only an open item or a waiting request needs the changes later: do not keep them all (review DS-L-13).
                if (status == Status.Running || requests.values.any { it.second.str("itemId") == id }) changes[id] = list else changes.remove(id)
                val paths = list.objects().joinToString(", ") { Tools.short(it.str("path"), dir) }
                put("item-$id") { Entry.Tool("item-$id", Kind.Edit, "Edit $paths", diff = diff(list, dir), status = status) }
            }
            "mcpToolCall" -> put("item-$id") {
                val result = item.optJSONObject("result")?.optJSONArray("content").objects()
                    .filter { it.str("type") == "text" }.joinToString("\n") { it.str("text") }
                Entry.Tool(
                    "item-$id", Kind.Other, "${clean(item.str("server"))}: ${clean(item.str("tool"))}",
                    Tools.text(item.opt("arguments")),
                    clean(item.optJSONObject("error")?.str("message") ?: result).takeLast(OUTPUT),
                    status = status,
                )
            }
            "webSearch" -> put("item-$id") {
                val url = item.optJSONObject("action")?.str("url").orEmpty()
                Entry.Tool("item-$id", Kind.Web, if (url.isNotEmpty()) "Open ${Tools.host(url)}" else "Search the web: ${clean(item.str("query"))}", status = status)
            }
            "contextCompaction" -> if (status == Status.Done || item.str("status").isEmpty()) put("item-$id") { Entry.Note("item-$id", "The conversation was compacted.") }
            "userMessage", "hookPrompt", "functionCallOutput", "sleep" -> Unit
            else -> put("item-$id") { Entry.Tool("item-$id", Kind.Other, clean(type), status = status) }
        }
    }

    private suspend fun request(id: Any, method: String, p: JSONObject) {
        val key = "r$id"
        val card = when (method) {
            "item/commandExecution/requestApproval" -> {
                val network = p.optJSONObject("networkApprovalContext")
                Ask.Permission(
                    key,
                    if (network != null) "Allow network access to ${clean(network.str("host"))}?" else "Run this command?",
                    listOfNotNull(
                        p.str("command").takeIf { it.isNotEmpty() }?.let { "$ " + clean(it) },
                        p.str("reason").takeIf { it.isNotEmpty() }?.let(::clean),
                    ).joinToString("\n\n"),
                    null,
                    decisions(p).mapIndexed { i, d -> decisionChoice(i, d) },
                    feedback = true,
                )
            }
            "item/fileChange/requestApproval" -> {
                // The request has no files: they come from the item before it. With no item, the card must not look
                // like an empty change.
                val diff = changes[p.str("itemId")]?.let { diff(it, dir) }
                Ask.Permission(
                    key, "Change these files?", clean(p.str("reason")), diff,
                    listOf(
                        Choice("accept", "Yes", Choice.Kind.Allow),
                        Choice("acceptForSession", "Yes, and do not ask again for these files", Choice.Kind.Always),
                        Choice("decline", "No, continue without it", Choice.Kind.Deny),
                        Choice("cancel", "No, and stop", Choice.Kind.Deny),
                    ),
                    feedback = true,
                    message = if (diff == null) "Codex did not send the files of this change." else null,
                    caution = diff == null,
                )
            }
            "item/permissions/requestApproval" -> Ask.Permission(
                key, "Give Codex more permissions?",
                listOfNotNull(Tools.text(p.opt("permissions")), clean(p.str("reason")).ifEmpty { null }).joinToString("\n\n"),
                null,
                listOf(
                    Choice("turn", "Yes, for this turn", Choice.Kind.Allow),
                    Choice("session", "Yes, for this session", Choice.Kind.Always),
                    Choice("no", "No", Choice.Kind.Deny),
                ),
            )
            "item/tool/requestUserInput" -> Ask.Questions(key, p.optJSONArray("questions").objects().map { q ->
                Question(
                    id = q.str("id"),
                    header = clean(q.str("header")),
                    text = clean(q.str("question")),
                    options = q.optJSONArray("options").objects().map { Question.Option(clean(it.str("label")), clean(it.str("description"))) },
                    multi = false,
                    other = q.optBoolean("isOther", true),
                    secret = q.optBoolean("isSecret"),
                )
            })
            "mcpServer/elicitation/request" -> elicitation(key, p) ?: run {
                rpc.reply(id, JSONObject().put("action", "decline").put("content", JSONObject.NULL))
                return
            }
            else -> {
                rpc.refuse(id, -32601, "unsupported")
                return
            }
        }
        requests[key] = id to p.put("method", method)
        ask(card)
    }

    /** An MCP tool approval (research CX-15), or a form. Null for a mode that Automa does not show (a link). */
    private fun elicitation(key: String, p: JSONObject): Ask? {
        val meta = p.optJSONObject("_meta")
        if (meta?.str("codex_approval_kind") == "mcp_tool_call") {
            val persist = when (val v = meta.opt("persist")) {
                is String -> listOf(v)
                is JSONArray -> v.strings()
                else -> emptyList()
            }
            return Ask.Permission(
                key, "Allow the tool ${clean(meta.str("tool_name").ifEmpty { p.str("serverName") })}?", clean(p.str("message")), null,
                listOfNotNull(
                    Choice("accept", "Allow", Choice.Kind.Allow),
                    Choice("session", "Allow for this session", Choice.Kind.Always).takeIf { "session" in persist },
                    Choice("always", "Allow and do not ask again", Choice.Kind.Always).takeIf { "always" in persist },
                    Choice("cancel", "Cancel", Choice.Kind.Deny),
                ),
            )
        }
        if (p.str("mode") != "form") return null
        val questions = Forms.questions(p.optJSONObject("requestedSchema") ?: JSONObject())
        if (questions.isEmpty()) return null
        note("${clean(p.str("serverName"))}: ${clean(p.str("message"))}")
        return Ask.Questions(key, questions)
    }

    override suspend fun answer(ask: Ask, answer: Answer) = act {
        if (ask.id == TRUST) {
            done(TRUST)
            trust?.complete(answer)
            return@act
        }
        if (ask.id == PLAN) {
            done(PLAN)
            if ((answer as? Answer.Pick)?.choice?.id == "implement") {
                update { it.copy(mode = "ask") }
                send("Implement the plan.")
            }
            return@act
        }
        val (id, p) = requests.remove(ask.id) ?: return@act done(ask.id)
        done(ask.id)
        val result = result(p, answer)
        rpc.reply(id, result)
        // "No, and stop" stops the turn: the other cards of the turn must not stay to allow (review KM-M-28).
        if (result.opt("decision") == "cancel") cancel(requests.toList())
    }

    /** Closes the cards of [open] and answers each request with its "cancel" answer. */
    private suspend fun cancel(open: List<Pair<String, Pair<Any, JSONObject>>>) {
        open.forEach { (card, _) ->
            requests.remove(card)
            done(card)
        }
        open.forEach { (_, request) -> rpc.reply(request.first, result(request.second, Answer.Dismiss)) }
    }

    /** The answer to the server request [p]. */
    private suspend fun result(p: JSONObject, answer: Answer): JSONObject {
        val pick = answer as? Answer.Pick
        return when (p.str("method")) {
            "item/commandExecution/requestApproval" -> {
                val decision = pick?.let { decisions(p).getOrNull(it.choice.id.toInt()) } ?: "cancel"
                if (pick != null && pick.choice.kind == Choice.Kind.Deny && pick.feedback.isNotBlank()) feedback(decision, pick.feedback)
                JSONObject().put("decision", decision)
            }
            "item/fileChange/requestApproval" -> {
                val decision = pick?.choice?.id ?: "cancel"
                if (pick != null && pick.choice.kind == Choice.Kind.Deny && pick.feedback.isNotBlank()) feedback(decision, pick.feedback)
                JSONObject().put("decision", decision)
            }
            "item/permissions/requestApproval" -> when (pick?.choice?.id) {
                "turn", "session" -> JSONObject().put("permissions", p.optJSONObject("permissions") ?: JSONObject()).put("scope", pick.choice.id)
                else -> JSONObject().put("permissions", JSONObject()).put("scope", "turn")
            }
            "item/tool/requestUserInput" -> answers(p, answer)
            "mcpServer/elicitation/request" -> when {
                answer is Answer.Replies -> JSONObject().put("action", "accept")
                    .put("content", Forms.content(Forms.questions(p.optJSONObject("requestedSchema") ?: JSONObject()), answer.replies))
                pick == null || pick.choice.id == "cancel" -> JSONObject().put("action", "cancel").put("content", JSONObject.NULL)
                else -> JSONObject().put("action", "accept").put("content", JSONObject.NULL).put(
                    "_meta",
                    if (pick.choice.id == "accept") JSONObject.NULL else JSONObject().put("persist", pick.choice.id),
                )
            }
            else -> JSONObject()
        }
    }

    /**
     * A text with a "No": with "decline" the turn continues and gets it at once (a steer); else after the turn. A failed
     * steer is an error line: the reply to the request must still go, else Codex waits with no card.
     */
    private suspend fun feedback(decision: Any, text: String) {
        if (decision != "decline") {
            next = text
            return
        }
        try {
            rpc.call("turn/start", JSONObject().put("threadId", thread).put("input", input(text)))
        } catch (e: IOException) {
            fail(e)
        }
    }

    override suspend fun send(text: String) = act {
        add(Entry.User(key("user"), text))
        update { it.copy(busy = true) }
        plan = null
        val s = flow.value
        val params = JSONObject().put("threadId", thread).put("input", input(text))
            .put("approvalPolicy", if (s.mode == "auto") "on-request" else "untrusted")
        s.model?.let { model ->
            params.put("model", model)
            params.put(
                "collaborationMode",
                JSONObject().put("mode", if (s.mode == "plan") "plan" else "default").put(
                    "settings",
                    JSONObject().put("model", model).put("reasoning_effort", s.effort ?: JSONObject.NULL).put("developer_instructions", JSONObject.NULL),
                ),
            )
        }
        s.effort?.let { params.put("effort", it) }
        // A Stop before the id of this turn is known waits for the id; the id of the turn before must not get it
        // (review KM-M-28).
        turn = ""
        stopping = false
        try {
            turn = rpc.call("turn/start", params).optJSONObject("turn")?.str("id").orEmpty()
        } catch (e: Exception) {
            update { it.copy(busy = false) }
            throw e
        }
        if (stopping && turn.isNotEmpty()) {
            stopping = false
            stopTurn()
        }
    }

    override suspend fun interrupt() = act {
        // The cards close at once: a card of the turn must not get an allow while the turn stops. Their answers go after
        // the stop, because the server drops the open requests of a stopped turn (review KM-M-28, research CX-19).
        val open = requests.toList()
        open.forEach { (card, _) -> done(card) }
        try {
            if (turn.isNotEmpty()) stopTurn() else stopping = flow.value.busy
        } finally {
            cancel(open)
        }
    }

    private suspend fun stopTurn() = rpc.call("turn/interrupt", JSONObject().put("threadId", thread).put("turnId", turn))

    override suspend fun select(setting: Setting, id: String) = act {
        update {
            when (setting) {
                Setting.Model -> it.copy(model = id, efforts = efforts(id), effort = it.effort?.takeIf { e -> efforts(id).any { o -> o.id == e } } ?: default(id))
                Setting.Effort -> it.copy(effort = id)
                Setting.Mode -> it.copy(mode = id)
            }
        }
    }

    override fun terminal() = if (turns > 0 && safe(thread)) "codex --no-daemon resume $thread" else null

    override fun closed() {
        rpc.close()
        trust?.complete(Answer.Dismiss)
    }

    private fun model(id: String?) = models.objects().firstOrNull { it.str("id") == id }

    private fun efforts(model: String?) = model(model)?.optJSONArray("supportedReasoningEfforts").objects().map {
        Option(it.str("reasoningEffort"), effortLabel(it.str("reasoningEffort")), clean(it.str("description")))
    }

    private fun default(model: String?) = model(model)?.str("defaultReasoningEffort")?.ifEmpty { null }

    companion object {
        private const val CONFIG = "/root/.codex/config.toml"
        private val TRUST_LOCK = Mutex()
        private const val TRUST = "trust"
        private const val PLAN = "plan"
        private const val OUTPUT = 20_000
        private const val LOGIN = "Codex is not logged in. Log in in the terminal, then start a new chat."

        val MODES = listOf(
            Option("ask", "Ask before changes", "Codex asks before each command and each file change."),
            Option("auto", "Ask only for rm -f", "Other commands and all file changes run with no question (review SEC-23)."),
            Option("plan", "Plan", "Codex explores, asks questions and makes a plan."),
        )

        private fun input(text: String) = JSONArray().put(JSONObject().put("type", "text").put("text", text))

        /** A TOML basic string body. */
        private fun toml(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"")

        /** The decisions of a command approval, in the order of the TUI (research CX-13). */
        fun decisions(p: JSONObject): List<Any> {
            p.optJSONArray("availableDecisions")?.let { list -> return (0 until list.length()).map { list.get(it) } }
            val amendment = p.optJSONArray("proposedExecpolicyAmendment")
            return listOfNotNull(
                "accept",
                amendment?.let { JSONObject().put("acceptWithExecpolicyAmendment", JSONObject().put("execpolicy_amendment", it)) },
                "cancel",
            )
        }

        fun decisionChoice(index: Int, d: Any): Choice {
            val id = index.toString()
            return when {
                d == "accept" -> Choice(id, "Yes", Choice.Kind.Allow)
                d == "acceptForSession" -> Choice(id, "Yes, and do not ask again for this command in this session", Choice.Kind.Always)
                d == "decline" -> Choice(id, "No, continue without it", Choice.Kind.Deny)
                d == "cancel" -> Choice(id, "No, and stop", Choice.Kind.Deny)
                d is JSONObject && d.has("acceptWithExecpolicyAmendment") -> {
                    val prefix = d.optJSONObject("acceptWithExecpolicyAmendment")?.optJSONArray("execpolicy_amendment").strings().joinToString(" ")
                    Choice(id, "Yes, and do not ask again for commands that start with ${clean(prefix)}", Choice.Kind.Always)
                }
                d is JSONObject && d.has("applyNetworkPolicyAmendment") -> {
                    val a = d.optJSONObject("applyNetworkPolicyAmendment")?.optJSONObject("network_policy_amendment")
                    val host = clean(a?.str("host").orEmpty())
                    if (a?.str("action") == "deny") Choice(id, "No, and block $host in the future", Choice.Kind.Deny)
                    else Choice(id, "Yes, and allow $host in the future", Choice.Kind.Always)
                }
                else -> Choice(id, clean(d.toString()).take(80), Choice.Kind.Allow)
            }
        }

        /** The answer to `item/tool/requestUserInput` (research CX-16): the label, and an own text as `user_note:`. */
        fun answers(p: JSONObject, answer: Answer): JSONObject {
            val out = JSONObject()
            if (answer is Answer.Replies) {
                p.optJSONArray("questions").objects().forEachIndexed { i, q ->
                    val reply = answer.replies.getOrNull(i) ?: return@forEachIndexed
                    val values = reply.labels.take(1) + listOfNotNull(reply.other.takeIf { it.isNotBlank() }?.let { "user_note: $it" })
                    if (values.isNotEmpty()) out.put(q.str("id"), JSONObject().put("answers", JSONArray(values)))
                }
            }
            return JSONObject().put("answers", out)
        }

        /** The diff of the changes of a fileChange item (research CX-12). */
        fun diff(changes: JSONArray, dir: String): Diff = Diff(changes.objects().map { c ->
            val text = clean(c.str("diff"))
            val path = Tools.short(c.str("path"), dir)
            when (c.optJSONObject("kind")?.str("type")) {
                "add" -> Diff.File(path, text.lines().map { "+$it" })
                "delete" -> Diff.File(path, text.lines().map { "-$it" })
                else -> Diff.unified(path, text).files.first()
            }
        })
    }
}
