package app.automa.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.automa.chat.Diff
import app.automa.chat.Entry
import app.automa.chat.Status
import app.automa.chat.Todo
import app.automa.term.Look

/** One row of the chat list. */
@Composable
fun EntryRow(entry: Entry, modifier: Modifier = Modifier) {
    when (entry) {
        is Entry.User -> UserBubble(entry.text, modifier)
        is Entry.Text -> SelectionContainer(modifier) { MarkdownText(entry.text, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) }
        is Entry.Thought -> ThoughtRow(entry.text, modifier)
        is Entry.Tool -> ToolCard(entry, modifier)
        is Entry.Todos -> TodoCard(entry.items, modifier)
        is Entry.Note -> Text(
            entry.text,
            style = MaterialTheme.typography.labelMedium,
            color = if (entry.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun UserBubble(text: String, modifier: Modifier) {
    Box(modifier.fillMaxWidth().padding(start = 48.dp, end = 12.dp, top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)) {
            SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
    }
}

/** Reasoning, folded to two lines; a tap opens it. */
@Composable
private fun ThoughtRow(text: String, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    Text(
        text.trim(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (open) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()),
    )
}

/** A tool call: a status dot, the title, and on a tap the input, the diff and the output. */
@Composable
private fun ToolCard(tool: Entry.Tool, modifier: Modifier) {
    var open by rememberSaveable(tool.key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Column(Modifier.clickable { open = !open }.padding(12.dp).animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(tool.status)
                Spacer(Modifier.width(10.dp))
                Text(
                    tool.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = Mono,
                    maxLines = if (open) 6 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                tool.diff?.let { DiffCount(it) }
            }
            if (open) {
                tool.input?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(8.dp))
                    CodeBlock(it, maxLines = 40)
                }
                tool.diff?.let {
                    Spacer(Modifier.size(8.dp))
                    DiffView(it)
                }
                tool.output?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.size(8.dp))
                    CodeBlock(it.takeLast(OUTPUT_CHARS), maxLines = 60)
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: Status) {
    when (status) {
        Status.Running -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        Status.Done -> Box(Modifier.size(10.dp).background(Color(Look.ANSI[2]), CircleShape))
        Status.Failed -> Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
    }
}

@Composable
private fun DiffCount(diff: Diff) {
    val added = remember(diff) { diff.files.sumOf { f -> f.lines.count { it.startsWith("+") } } }
    val removed = remember(diff) { diff.files.sumOf { f -> f.lines.count { it.startsWith("-") } } }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Color(Look.ANSI[2]))) { append("+$added") }
            append(" ")
            withStyle(SpanStyle(color = Color(Look.ANSI[1]))) { append("−$removed") }
        },
        style = MaterialTheme.typography.labelMedium,
        fontFamily = Mono,
    )
}

/** Diff lines with green and red, for each file. */
@Composable
fun DiffView(diff: Diff, maxLines: Int = 200) {
    val text = remember(diff) {
        buildAnnotatedString {
            diff.files.forEachIndexed { i, file ->
                if (i > 0) append("\n")
                withStyle(SpanStyle(color = Color(Look.ANSI[4]))) { append(file.path) }
                file.lines.take(maxLines).forEach { line ->
                    append("\n")
                    val color = when (line.firstOrNull()) {
                        '+' -> Look.ANSI[2]
                        '-' -> Look.ANSI[1]
                        '@' -> Look.ANSI[6]
                        else -> Look.FOREGROUND
                    }
                    withStyle(SpanStyle(color = Color(color))) { append(line) }
                }
                if (file.lines.size > maxLines) append("\n… ${file.lines.size - maxLines} more lines")
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, softWrap = false)
        }
    }
}

@Composable
private fun TodoCard(items: List<Todo>, modifier: Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        when (item.state) {
                            Todo.State.Done -> "✓"
                            Todo.State.Running -> "▶"
                            Todo.State.Pending -> "○"
                        },
                        fontFamily = Mono,
                        color = if (item.state == Todo.State.Done) Color(Look.ANSI[2]) else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.widthIn(min = 20.dp),
                    )
                    Text(
                        item.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (item.state == Todo.State.Done) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                    )
                }
            }
        }
    }
}

private const val OUTPUT_CHARS = 20_000
