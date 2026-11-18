package app.automa.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.automa.chat.Answer
import app.automa.chat.Ask
import app.automa.chat.Choice
import app.automa.chat.Diff
import app.automa.chat.Question
import app.automa.ui.PrivateTextInput

/**
 * A request of the agent (DESIGN §13): a permission, questions with options, or a plan. It stays above the composer
 * until the user answers. [more] is the number of requests after this one.
 */
@Composable
fun AskCard(ask: Ask, more: Int, onAnswer: (Answer) -> Unit, modifier: Modifier = Modifier) {
    // A new card ignores taps for a short time, and a card answers once: a double tap must not answer the next card
    // (review SEC-18).
    var armed by remember(ask.id) { mutableStateOf(false) }
    var answered by remember(ask.id) { mutableStateOf(false) }
    LaunchedEffect(ask.id) {
        delay(ARM_MS)
        armed = true
    }
    val answer: (Answer) -> Unit = { if (armed && !answered) { answered = true; onAnswer(it) } }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (ask) {
                is Ask.Permission -> PermissionBody(ask, answer)
                is Ask.Questions -> QuestionsBody(ask, answer)
                is Ask.Plan -> PlanBody(ask, answer)
            }
            if (more > 0) Text("$more more after this", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PermissionBody(ask: Ask.Permission, onAnswer: (Answer) -> Unit) {
    var note by remember(ask.id) { mutableStateOf("") }
    Text(ask.title, style = MaterialTheme.typography.titleMedium)
    ask.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    ask.detail?.takeIf { it.isNotBlank() }?.let { CodeBlock(it, wrap = true) }
    // For an Edit or a Codex file change the diff is the only view of the change: all the kept lines, the card scrolls
    // (review KM-M-26, DS-L-58).
    ask.diff?.let { DiffView(it, maxLines = Diff.MAX_LINES) }
    if (ask.feedback) NoteField(note, { note = it }, "Note for the agent if you deny (optional)")
    Choices(ask.choices, ask.caution) { onAnswer(Answer.Pick(it, if (it.kind == Choice.Kind.Deny) note.trim() else "")) }
    // A request with no choices must not block the chat (review DS-L-23, KM-M-34).
    if (ask.choices.isEmpty()) OutlinedButton(onClick = { onAnswer(Answer.Dismiss) }, modifier = Modifier.fillMaxWidth()) { Text("Dismiss") }
}

@Composable
private fun PlanBody(ask: Ask.Plan, onAnswer: (Answer) -> Unit) {
    var note by remember(ask.id) { mutableStateOf("") }
    if (!ask.plan.trimStart().startsWith("#")) Text("Plan", style = MaterialTheme.typography.titleMedium)
    MarkdownText(ask.plan)
    if (ask.feedback) NoteField(note, { note = it }, "What to change in the plan (optional)")
    Choices(ask.choices, caution = false) { onAnswer(Answer.Pick(it, if (it.kind == Choice.Kind.Deny) note.trim() else "")) }
}

/** The choices of the agent, one full-width button each: the label can be long ("Yes, and do not ask again for …"). */
@Composable
private fun Choices(choices: List<Choice>, caution: Boolean, onPick: (Choice) -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { choice ->
            val click = {
                haptic.performHapticFeedback(if (choice.kind == Choice.Kind.Deny) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
                onPick(choice)
            }
            val wide = Modifier.fillMaxWidth()
            when (choice.kind) {
                Choice.Kind.Allow -> if (caution) OutlinedButton(onClick = click, modifier = wide) { Text(choice.label) } else Button(onClick = click, modifier = wide) { Text(choice.label) }
                Choice.Kind.Always -> FilledTonalButton(onClick = click, modifier = wide) { Text(choice.label) }
                Choice.Kind.Deny, Choice.Kind.Other -> OutlinedButton(onClick = click, modifier = wide) { Text(choice.label) }
            }
        }
    }
}

@Composable
private fun QuestionsBody(ask: Ask.Questions, onAnswer: (Answer) -> Unit) {
    val haptic = LocalHapticFeedback.current
    // For each question: the selected labels and the own answer.
    val picks = remember(ask.id) { ask.questions.map { mutableStateListOf<String>() } }
    val others = remember(ask.id) { ask.questions.map { mutableStateOf("") } }
    ask.questions.forEachIndexed { i, q ->
        if (q.header.isNotBlank()) {
            Text(q.header.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(q.text, style = MaterialTheme.typography.titleMedium)
        if (q.multi && q.options.isNotEmpty()) {
            Text("Select one or more", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        q.options.forEach { option ->
            val selected = option.label in picks[i]
            OptionRow(option, selected, q.multi) {
                if (q.multi) {
                    if (selected) picks[i].remove(option.label) else picks[i].add(option.label)
                } else {
                    picks[i].clear()
                    picks[i].add(option.label)
                    others[i].value = ""
                }
            }
        }
        if (q.other || q.options.isEmpty()) {
            NoteField(
                others[i].value,
                {
                    others[i].value = it
                    if (!q.multi && it.isNotEmpty()) picks[i].clear()
                },
                if (q.options.isEmpty()) "Answer" else "Other answer",
                secret = q.secret,
            )
        }
    }
    val complete = ask.questions.indices.all { picks[it].isNotEmpty() || others[it].value.isNotBlank() }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onAnswer(Answer.Dismiss) }) { Text("Skip") }
        Button(
            onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                onAnswer(Answer.Replies(ask.questions.indices.map { Answer.Reply(picks[it].toList(), others[it].value.trim()) }))
            },
            enabled = complete,
            modifier = Modifier.weight(1f),
        ) { Text("Send answer") }
    }
}

@Composable
private fun OptionRow(option: Question.Option, selected: Boolean, multi: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.clickable(onClick = onClick).padding(end = 12.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (multi) Checkbox(selected, onCheckedChange = { onClick() }) else RadioButton(selected, onClick = onClick)
            Column(Modifier.weight(1f)) {
                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                if (option.description.isNotBlank()) {
                    Text(option.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A text field whose text the keyboard does not learn (DESIGN §7). */
@Composable
private fun NoteField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false) {
    PrivateTextInput {
        OutlinedTextField(
            value = value,
            onValueChange = { onChange(it.take(MAX_NOTE)) },
            label = { Text(label) },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            // A secret answer: a password keyboard with no suggestions and no autocorrect, as for the API keys.
            keyboardOptions = if (secret) KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false) else KeyboardOptions.Default,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private const val MAX_NOTE = 4000
private const val ARM_MS = 400L
