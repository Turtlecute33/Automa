package app.automa.ui.home

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.env.Agent
import app.automa.env.Env
import app.automa.env.Setup
import app.automa.git.Repo
import app.automa.ui.Icons
import app.automa.ui.SectionHeader
import app.automa.ui.nav.Navigator
import app.automa.ui.nav.Route
import app.automa.ui.setup.SetupPanel
import app.automa.ui.userText
import kotlinx.coroutines.launch

/** The open sessions and the repositories. Before the setup is complete, the setup panel. */
@Composable
fun HomeScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val setup by app.setup.state.collectAsStateWithLifecycle()
    val tabs by app.sessions.tabs.collectAsStateWithLifecycle()
    val chats by app.chats.tabs.collectAsStateWithLifecycle()
    val repos by app.repos.list.collectAsStateWithLifecycle()
    val agents by app.agents.installed.collectAsStateWithLifecycle()
    var cloning by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Repo?>(null) }
    var opening by remember { mutableStateOf<Repo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val ready = setup == Setup.State.Ready

    // The session notification needs this permission. Without it, the sessions work and the notification is hidden.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun started() {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    fun open(dir: String, agent: Agent?) {
        started()
        scope.launch { nav.go(Route.Terminal(app.sessions.open(dir, agent))) }
    }
    fun chat(dir: String, agent: Agent) {
        started()
        nav.go(Route.Chat(app.chats.open(dir, agent)))
    }

    LifecycleResumeEffect(ready) {
        if (ready) scope.launch { app.repos.refresh() }
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Automa") },
                actions = {
                    if (ready) IconButton(onClick = { open(Env.HOME, agent = null) }) { Icon(Icons.Terminal, "New shell") }
                    IconButton(onClick = { nav.go(Route.Settings) }) { Icon(Icons.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            if (ready) {
                ExtendedFloatingActionButton(
                    onClick = { cloning = true },
                    icon = { Icon(Icons.Add, null) },
                    text = { Text("Clone") },
                )
            }
        },
    ) { padding ->
        if (!ready) {
            LaunchedEffect(setup) { if (setup is Setup.State.Checking) app.setup.check() }
            SetupPanel(setup, onStart = { app.scope.launch { app.setup.run() } }, Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            if (tabs.isNotEmpty() || chats.isNotEmpty()) {
                item(key = "sessions") { SectionHeader("Open sessions") }
                items(chats, key = { "chat-${it.id}" }) { chat ->
                    ListItem(
                        headlineContent = { Text(chat.title) },
                        supportingContent = { Text("Chat") },
                        leadingContent = { Icon(Icons.Robot, null) },
                        trailingContent = {
                            IconButton(onClick = { app.chats.close(chat.id) }) { Icon(Icons.Close, "Close chat") }
                        },
                        modifier = Modifier.clickable { nav.go(Route.Chat(chat.id)) },
                    )
                }
                items(tabs, key = { "tab-${it.id}" }) { tab ->
                    ListItem(
                        headlineContent = { Text(tab.title) },
                        supportingContent = { Text(tab.exit?.let { "Ended with code $it" } ?: tab.dir) },
                        leadingContent = { Icon(Icons.Terminal, null) },
                        trailingContent = {
                            IconButton(onClick = { app.sessions.close(tab.id) }) { Icon(Icons.Close, "Close session") }
                        },
                        modifier = Modifier.clickable { nav.go(Route.Terminal(tab.id)) },
                    )
                }
            }
            item(key = "repos") { SectionHeader("Repositories") }
            error?.let { text ->
                item(key = "error") {
                    Text(
                        text,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (repos.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No repositories. Clone one, or open a shell and use git.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(repos, key = { "repo-${it.name}" }) { repo ->
                ListItem(
                    headlineContent = { Text(repo.name) },
                    supportingContent = repo.branch?.let { { Text(it) } },
                    leadingContent = { Icon(Icons.Robot, null) },
                    modifier = Modifier.clickable { opening = repo },
                )
            }
            // Room for the Clone button.
            item(key = "end") { Spacer(Modifier.height(88.dp)) }
        }
    }

    if (cloning) CloneSheet(onDismiss = { cloning = false })
    opening?.let { repo ->
        val dir = "${Env.REPOS}/${repo.name}"
        OpenSheet(
            repo,
            agents = listOf(Agent.Opencode) + Agent.ON_DEMAND.filter { it in agents },
            onDismiss = { opening = null },
            onChat = { agent -> opening = null; chat(dir, agent) },
            onTerminal = { agent -> opening = null; open(dir, agent) },
            onDelete = { opening = null; deleting = repo },
        )
    }
    deleting?.let { repo ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${repo.name}?") },
            text = { Text("Automa deletes the folder on this phone, also the changes that you did not push.") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    error = null
                    // The error shows on the screen (review KM-P-05).
                    scope.launch { runCatching { app.repos.delete(repo.name) }.onFailure { error = userText(it) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
