package app.automa.chat

import org.json.JSONArray
import org.json.JSONObject

/** Tool cards: the kind, a short title and the input text of a tool call (research CC-11, CC-12). */
object Tools {
    private const val INPUT = 20_000

    /** [path] in the folder [dir] as a relative path; other paths stay as they are. */
    fun short(path: String, dir: String) = clean(path.removePrefix("$dir/"))

    fun claude(name: String, input: JSONObject, dir: String): Pair<Kind, String> {
        val path = short(input.str("file_path").ifEmpty { input.str("notebook_path") }, dir)
        return when (name) {
            "Bash" -> Kind.Command to clean(input.str("description").ifEmpty { input.str("command") })
            "Read" -> Kind.Read to "Read $path"
            "Edit", "MultiEdit", "NotebookEdit" -> Kind.Edit to "Edit $path"
            "Write" -> Kind.Edit to "Write $path"
            "Grep" -> Kind.Search to "Search ${clean(input.str("pattern"))}"
            "Glob" -> Kind.Search to "Find ${clean(input.str("pattern"))}"
            "WebFetch" -> Kind.Web to "Fetch ${host(input.str("url"))}"
            "WebSearch" -> Kind.Web to "Search the web: ${clean(input.str("query"))}"
            "Agent", "Task" -> Kind.Task to "Agent: ${clean(input.str("description"))}"
            "AskUserQuestion" -> Kind.Other to "Questions"
            "ExitPlanMode" -> Kind.Other to "Plan"
            else -> Kind.Other to clean(if (name.startsWith("mcp__")) name.removePrefix("mcp__").replaceFirst("__", ": ") else name)
        }
    }

    fun claudeInput(name: String, input: JSONObject): String? = when (name) {
        "Bash" -> "$ " + input.str("command")
        "Read" -> input.str("file_path") + if (input.has("offset")) " (from line ${input.optInt("offset")})" else ""
        "Edit", "Write", "MultiEdit" -> null
        "Grep", "Glob" -> input.str("pattern") + input.str("path").takeIf { it.isNotEmpty() }?.let { "\nin $it" }.orEmpty()
        "WebFetch" -> input.str("url") + "\n" + input.str("prompt")
        "WebSearch" -> input.str("query")
        "Agent", "Task" -> input.str("prompt")
        "AskUserQuestion" -> input.optJSONArray("questions").objects().joinToString("\n") { it.str("question") }
        "ExitPlanMode" -> input.str("plan")
        else -> input.toString(2)
    }?.let { cut(clean(it), INPUT) }

    /**
     * The diff of an Edit or a Write before it runs. For a Write, the old file comes from the guest, only from the chat
     * folder: a model must not show other files, for example agent logins (review SEC-22). For other paths, the
     * label says that the Write replaces a file that the card does not show.
     */
    fun claudeDiff(name: String, input: JSONObject, guest: Guest, dir: String): Diff? {
        val path = input.str("file_path")
        return when (name) {
            "Edit" -> Diff.of(short(path, dir), clean(input.str("old_string")), clean(input.str("new_string")))
            "Write" -> {
                val old = guest.read(path, OLD_FILE, within = dir)
                // Outside the chat folder the old file is not read: the diff must not look like a new file.
                val outside = old == null && (!path.startsWith("$dir/") || "/../" in path)
                val label = short(path, dir) + if (outside) " (replaces the file; the old text is not shown)" else ""
                Diff.of(label, clean(old.orEmpty()), clean(input.str("content")))
            }
            else -> null
        }
    }

    /** The diff of an Edit or a Write after it ran: `structuredPatch` of `tool_use_result`. */
    fun claudePatch(result: JSONObject, dir: String): Diff? {
        val hunks = result.optJSONArray("structuredPatch") ?: return null
        if (hunks.length() == 0) return null
        val lines = hunks.objects().flatMap { h ->
            listOf("@@ -${h.optInt("oldStart")},${h.optInt("oldLines")} +${h.optInt("newStart")},${h.optInt("newLines")} @@") +
                h.optJSONArray("lines").strings().map(::clean)
        }
        return Diff(listOf(Diff.File(short(result.str("filePath"), dir), lines)))
    }

    /** The host of an https link, for a title. */
    fun host(url: String) = clean(url.substringAfter("://").substringBefore('/').take(100))

    /** A JSON value as short text for a card. */
    fun text(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }.let { cut(clean(it), INPUT) }

    const val OLD_FILE = 512 shl 10
}

/**
 * Forms with choices: a JSON schema (MCP and ACP elicitation, research OC-9, CX-15) as questions, and the answers as
 * the form content. `oneOf` is one choice, an array with `items.anyOf` is a multiple choice, `<key>_custom` is an own
 * answer for `<key>`, and a plain string is a free answer.
 */
object Forms {
    fun questions(schema: JSONObject): List<Question> {
        val properties = schema.optJSONObject("properties") ?: return emptyList()
        // Android keeps the order of the schema; opencode names its fields q0, q1, … (research OC-9).
        val keys = properties.keys().asSequence().toList().sortedBy { it.removePrefix("q").substringBefore('_').toIntOrNull() ?: Int.MAX_VALUE }
        return keys.filterNot { it.endsWith("_custom") && it.removeSuffix("_custom") in keys }.mapNotNull { key ->
            val p = properties.optJSONObject(key) ?: return@mapNotNull null
            val multi = p.str("type") == "array"
            val choices = (if (multi) p.optJSONObject("items")?.optJSONArray("anyOf") else p.optJSONArray("oneOf")).objects()
            if (p.str("type") !in setOf("string", "array")) return@mapNotNull null
            Question(
                id = key,
                header = clean(p.str("title")).take(40),
                text = clean(p.str("description").ifEmpty { p.str("title").ifEmpty { key } }),
                options = choices.map { Question.Option(clean(it.str("const")), clean(it.str("description").ifEmpty { it.str("title") })) },
                multi = multi,
                other = "${key}_custom" in keys || choices.isEmpty(),
            )
        }
    }

    /** The form content for [replies], in the order of [questions]. */
    fun content(questions: List<Question>, replies: List<Answer.Reply>): JSONObject {
        val content = JSONObject()
        questions.forEachIndexed { i, q ->
            val reply = replies.getOrNull(i) ?: return@forEachIndexed
            when {
                q.options.isEmpty() -> if (reply.other.isNotBlank()) content.put(q.id, reply.other)
                q.multi -> {
                    if (reply.labels.isNotEmpty()) content.put(q.id, JSONArray(reply.labels))
                    if (reply.other.isNotBlank()) content.put("${q.id}_custom", reply.other)
                }
                else -> {
                    reply.labels.firstOrNull()?.let { content.put(q.id, it) }
                    if (reply.other.isNotBlank()) content.put("${q.id}_custom", reply.other)
                }
            }
        }
        return content
    }
}
