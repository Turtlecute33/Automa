package app.automa.chat

import androidx.compose.runtime.Immutable

/** One row of a chat (DESIGN §13). [key] is stable for the list. */
@Immutable
sealed interface Entry {
    val key: String

    data class User(override val key: String, val text: String) : Entry

    /** Agent text, Markdown. */
    data class Text(override val key: String, val text: String) : Entry

    /** Reasoning or thinking text, shown folded. */
    data class Thought(override val key: String, val text: String) : Entry

    data class Tool(
        override val key: String,
        val kind: Kind,
        val title: String,
        val input: String? = null,
        val output: String? = null,
        val diff: Diff? = null,
        val status: Status = Status.Running,
    ) : Entry

    /** The task list of the agent (Claude TodoWrite, Codex plan, opencode todos). */
    data class Todos(override val key: String, val items: List<Todo>) : Entry

    /** A line from the app: an end of turn, an interrupt, a mode change, an error. */
    data class Note(override val key: String, val text: String, val error: Boolean = false) : Entry
}

enum class Kind { Command, Read, Edit, Search, Web, Task, Other }

enum class Status { Running, Done, Failed }

@Immutable
data class Todo(val text: String, val state: State) {
    enum class State { Pending, Running, Done }
}

/** File changes: for each file, its path and unified diff lines ("+", "-" or " " first). */
@Immutable
data class Diff(val files: List<File>) {
    /**
     * At most [MAX_LINES] [lines] (review DS-L-13, KM-H-12). [added] and [removed] count all the lines, and [more] is
     * the number of lines that are not kept.
     */
    @Immutable
    data class File(val path: String, val lines: List<String>, val added: Int, val removed: Int, val more: Int) {
        constructor(path: String, all: List<String>) : this(
            path, all.take(MAX_LINES), all.count { it.startsWith("+") }, all.count { it.startsWith("-") }, maxOf(0, all.size - MAX_LINES),
        )
    }

    companion object {
        const val MAX_LINES = 1000

        /** A diff from the old and the new text of one file: the removed lines, then the added lines. */
        fun of(path: String, old: String, new: String): Diff {
            val a = if (old.isEmpty()) emptyList() else old.lines()
            val b = if (new.isEmpty()) emptyList() else new.lines()
            // Keep the lines that are the same at the start and at the end as context.
            var start = 0
            while (start < a.size && start < b.size && a[start] == b[start]) start++
            var end = 0
            while (end < a.size - start && end < b.size - start && a[a.size - 1 - end] == b[b.size - 1 - end]) end++
            val lines = a.subList(maxOf(0, start - 2), start).map { " $it" } +
                a.subList(start, a.size - end).map { "-$it" } +
                b.subList(start, b.size - end).map { "+$it" } +
                a.subList(a.size - end, minOf(a.size, a.size - end + 2)).map { " $it" }
            return Diff(listOf(File(path, lines)))
        }

        /** A diff from unified diff text (lines with "@@", "+", "-" and " "). */
        fun unified(path: String, text: String): Diff =
            Diff(listOf(File(path, text.lines().filter { it.isNotEmpty() && it[0] in "+- @" && !it.startsWith("+++") && !it.startsWith("---") })))
    }
}

/** A request of the agent that waits for the user. */
@Immutable
sealed interface Ask {
    val id: String

    /**
     * Permission for a tool: [detail] is code (a command, a path), [message] is prose. [choices] in the order of the
     * agent. [feedback]: a deny can carry a text for the agent.
     */
    data class Permission(
        override val id: String,
        val title: String,
        val detail: String?,
        val diff: Diff?,
        val choices: List<Choice>,
        val feedback: Boolean = false,
        val message: String? = null,
        /** The agent wants "No" as the default (Claude `default_to_no`, review SEC-18). */
        val caution: Boolean = false,
    ) : Ask

    /** Questions with options (Claude AskUserQuestion, Codex request_user_input, opencode question). */
    data class Questions(override val id: String, val questions: List<Question>) : Ask

    /** A plan to approve before the agent starts to change files. */
    data class Plan(override val id: String, val plan: String, val choices: List<Choice>, val feedback: Boolean = true) : Ask
}

@Immutable
data class Choice(val id: String, val label: String, val kind: Kind) {
    enum class Kind { Allow, Always, Deny }
}

@Immutable
data class Question(
    val id: String,
    val header: String,
    val text: String,
    val options: List<Option>,
    val multi: Boolean,
    /** The user can write an own answer. */
    val other: Boolean,
    val secret: Boolean = false,
) {
    @Immutable
    data class Option(val label: String, val description: String)
}

/** The answer to an [Ask]. */
@Immutable
sealed interface Answer {
    /** A choice of a [Ask.Permission] or [Ask.Plan], with an optional text for the agent. */
    data class Pick(val choice: Choice, val feedback: String = "") : Answer

    /** For each question of [Ask.Questions], in order: the selected labels and the own answer (or ""). */
    data class Replies(val replies: List<Reply>) : Answer

    @Immutable
    data class Reply(val labels: List<String>, val other: String)

    /** The user closed the request with no answer. */
    data object Dismiss : Answer
}

/** The longest text of one row; a longer text keeps its end (review SEC-20). */
const val MAX_TEXT = 200_000

/** [old] and [delta] as one text of at most [MAX_TEXT] characters. */
fun grow(old: String, delta: String): String = (old + delta).let { if (it.length > MAX_TEXT) it.takeLast(MAX_TEXT) else it }

/** [text] cut to [max] characters, with a sign when it is cut (review SEC-17). */
fun cut(text: String, max: Int): String = if (text.length <= max) text else text.take(max) + "\n… ${text.length - max} more characters"

/** The label of an effort level ("xhigh" is "Extra high"). */
fun effortLabel(id: String) = if (id == "xhigh") "Extra high" else id.replaceFirstChar(Char::uppercase)

/** A model, mode or effort that the user can select. */
@Immutable
data class Option(val id: String, val label: String, val description: String = "")

/** All that the chat screen shows. */
@Immutable
data class ChatState(
    val entries: List<Entry> = emptyList(),
    /** Requests that wait for the user, oldest first. The screen shows the first. */
    val asks: List<Ask> = emptyList(),
    val starting: Boolean = true,
    val busy: Boolean = false,
    val model: String? = null,
    val models: List<Option> = emptyList(),
    val mode: String? = null,
    val modes: List<Option> = emptyList(),
    val effort: String? = null,
    val efforts: List<Option> = emptyList(),
    /** Slash commands for the composer. */
    val commands: List<Option> = emptyList(),
    /** The agent session or thread id, for a resume in the terminal. */
    val session: String? = null,
    /** The agent is not logged in or has no provider: the user must log in in the terminal. */
    val login: String? = null,
    /** The process ended: the reason. The chat is read-only then. */
    val ended: String? = null,
)
