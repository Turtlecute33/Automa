package app.automa.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.chat.ChatState
import app.automa.chat.ChatTab
import app.automa.chat.Option
import app.automa.chat.Setting
import app.automa.chat.clean
import app.automa.env.Agent
import app.automa.ui.Icons
import app.automa.ui.PrivateTextInput
import app.automa.ui.nav.Navigator
import app.automa.ui.nav.Route
import kotlinx.coroutines.launch

/**
 * A chat with an agent (DESIGN §13): the messages and tool calls, the request of the agent above the composer, and
 * chips for the model, the mode and the effort. The terminal UI of the agent stays one tap away.
 */
@Composable
fun ChatScreen(id: Int, nav: Navigator) {
    val app = LocalContext.current.app
    val tabs by app.chats.tabs.collectAsStateWithLifecycle()
    val tab = tabs.firstOrNull { it.id == id }
    val driver = remember(id) { app.chats.driver(id) }
    if (tab == null || driver == null) {
        LaunchedEffect(Unit) { nav.remove(Route.Chat(id)) }
        return
    }
    val state by driver.state.collectAsStateWithLifecycle()
    // The app scope: a turn continues when the user leaves the screen. The chat ends only with its agent.
    val scope = app.scope
    val ui = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var text by rememberSaveable(id) { mutableStateOf("") }
    var picking by remember { mutableStateOf<Setting?>(null) }

    fun terminal() {
        val dir = tab.dir
        val command = driver.terminal()
        ui.launch { nav.go(Route.Terminal(app.sessions.open(dir, tab.agent, command))) }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        ChatBar(tab, state, onBack = nav::back, onTerminal = ::terminal, onRestart = {
            app.chats.restart(id)?.let { new ->
                nav.remove(Route.Chat(id))
                nav.go(Route.Chat(new))
            }
        }, onClose = { app.chats.close(id) })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = rememberLazyListState()
            // Newest at the bottom: a reversed list stays at the end while the agent writes.
            LazyColumn(state = list, reverseLayout = true, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.busy && state.asks.isEmpty()) item(key = "working") { Working() }
                state.login?.let { reason ->
                    item(key = "login") {
                        // opencode needs a TFF key (API keys); Claude Code and Codex log in in their terminal UI.
                        if (tab.agent == Agent.Opencode) LoginCard(reason, "Open API keys") { nav.go(Route.Keys) }
                        else LoginCard(reason, "Open the terminal", ::terminal)
                    }
                }
                items(state.entries.asReversed(), key = { it.key }) { EntryRow(it) }
                if (state.starting && state.asks.isEmpty() && state.entries.isEmpty()) item(key = "starting") { Working("Starting ${tab.agent.label}…") }
            }
        }
        state.asks.firstOrNull()?.let { ask ->
            // A new state for each card: a press on one card cannot go to the next (review SEC-18).
            key(ask.id) {
                AskCard(ask, more = state.asks.size - 1, onAnswer = { answer -> scope.launch { driver.answer(ask, answer) } })
            }
        }
        if (state.ended != null) {
            EndBar(state.ended!!, onRestart = {
                app.chats.restart(id)?.let { new ->
                    nav.remove(Route.Chat(id))
                    nav.go(Route.Chat(new))
                }
            })
        } else {
            Settings(state, onPick = { picking = it })
            Commands(text, state.commands) { text = it }
            Composer(
                text = text,
                onText = { text = it },
                busy = state.busy,
                ready = !state.starting && state.asks.isEmpty(),
                agent = tab.agent.label,
                onSend = {
                    val message = text.trim()
                    if (message.isNotEmpty()) {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        text = ""
                        scope.launch { driver.send(message) }
                    }
                },
                onStop = {
                    haptic.performHapticFeedback(HapticFeedbackType.Reject)
                    scope.launch { driver.interrupt() }
                },
            )
        }
    }

    picking?.let { setting ->
        val (current, options) = when (setting) {
            Setting.Model -> state.model to state.models
            Setting.Mode -> state.mode to state.modes
            Setting.Effort -> state.effort to state.efforts
        }
        OptionSheet(setting, current, options, onDismiss = { picking = null }) { option ->
            picking = null
            scope.launch { driver.select(setting, option.id) }
        }
    }
}

@Composable
private fun ChatBar(tab: ChatTab, state: ChatState, onBack: () -> Unit, onTerminal: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        windowInsets = WindowInsets(0),
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Back, "Back") } },
        title = {
            Column {
                Text(tab.dir.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(tab.agent.label, label(state.model, state.models)).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        actions = {
            IconButton(onClick = onTerminal) { Icon(Icons.Terminal, "Open in the terminal") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.More, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("New chat") }, leadingIcon = { Icon(Icons.Refresh, null) }, onClick = { menu = false; onRestart() })
                    DropdownMenuItem(text = { Text("Close chat") }, leadingIcon = { Icon(Icons.Close, null) }, onClick = { menu = false; onClose() })
                }
            }
        },
    )
}

/** The label of the option with [id], or the id itself. The id comes from the agent: cleaned for the screen (review DS-L-86). */
private fun label(id: String?, options: List<Option>) = id?.let { options.firstOrNull { o -> o.id == id }?.label ?: clean(it) }

@Composable
private fun Working(text: String = "Working…") {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        LinearWavyProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** The agent is not logged in or has no model. The agent logins run in their terminal UI, which shows the link (DESIGN §5). */
@Composable
private fun LoginCard(reason: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(reason, style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EndBar(reason: String, onRestart: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(reason, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(8.dp))
            OutlinedButton(onClick = onRestart) { Text("New chat") }
        }
    }
}

/** Chips for the model, the mode and the effort, when the agent gives a list. */
@Composable
private fun Settings(state: ChatState, onPick: (Setting) -> Unit) {
    val chips = listOfNotNull(
        state.models.takeIf { it.isNotEmpty() }?.let { Setting.Model to (label(state.model, it) ?: "Model") },
        state.modes.takeIf { it.isNotEmpty() }?.let { Setting.Mode to (label(state.mode, it) ?: "Mode") },
        state.efforts.takeIf { it.isNotEmpty() }?.let { Setting.Effort to (label(state.effort, it) ?: "Effort") },
    )
    if (chips.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { (setting, text) ->
            AssistChip(
                onClick = { onPick(setting) },
                label = { Text(text, maxLines = 1) },
                trailingIcon = { Icon(Icons.ExpandMore, null, Modifier.size(18.dp)) },
            )
        }
    }
}

/** Slash commands that match the text, while it is only a command name. */
@Composable
private fun Commands(text: String, commands: List<Option>, onPick: (String) -> Unit) {
    if (!text.startsWith("/") || ' ' in text || commands.isEmpty()) return
    val matches = remember(text, commands) { commands.filter { it.id.startsWith(text.drop(1), ignoreCase = true) }.take(6) }
    Column(Modifier.fillMaxWidth()) {
        matches.forEach { command ->
            ListItem(
                // Cleaned for the screen only; the composer gets the id as the agent gave it (review DS-L-86).
                headlineContent = { Text("/${clean(command.id)}", fontFamily = Mono) },
                supportingContent = command.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPick("/${command.id} ") },
            )
        }
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, busy: Boolean, ready: Boolean, agent: String, onSend: () -> Unit, onStop: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        PrivateTextInput {
            TextField(
                value = text,
                onValueChange = { onText(it.take(MAX_MESSAGE)) },
                placeholder = { Text("Message $agent") },
                maxLines = 6,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.size(8.dp))
        if (busy) {
            FilledTonalIconButton(onClick = onStop, modifier = Modifier.size(52.dp)) { Icon(Icons.Stop, "Stop") }
        } else {
            FilledIconButton(onClick = onSend, enabled = ready && text.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(Icons.ArrowUp, "Send") }
        }
    }
}

@Composable
private fun OptionSheet(setting: Setting, current: String?, options: List<Option>, onDismiss: () -> Unit, onPick: (Option) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            when (setting) {
                Setting.Model -> "Model"
                Setting.Mode -> "Mode"
                Setting.Effort -> "Effort"
            },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        // The ids come from the agent: a key twice (or the key "end") makes the LazyColumn throw, and the app stops.
        val unique = remember(options) { options.distinctBy { it.id } }
        LazyColumn {
            items(unique, key = { "option-${it.id}" }) { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    supportingContent = option.description.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { RadioButton(selected = option.id == current, onClick = { onPick(option) }) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onPick(option) },
                )
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private const val MAX_MESSAGE = 100_000
