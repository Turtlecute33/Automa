package app.automa.ui.settings

import android.app.KeyguardManager
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.BuildConfig
import app.automa.app
import app.automa.data.Secrets
import app.automa.env.Agent
import app.automa.env.Agents
import app.automa.env.Setup
import app.automa.git.GitHub
import app.automa.ui.Icons
import app.automa.ui.SectionHeader
import app.automa.ui.openLink
import app.automa.ui.userText
import app.automa.ui.nav.Navigator
import app.automa.ui.nav.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val scope = rememberCoroutineScope()
    val login by app.prefs.login.collectAsStateWithLifecycle()
    val lock by app.prefs.lock.collectAsStateWithLifecycle()
    val names by app.secrets.names.collectAsStateWithLifecycle()
    val setup by app.setup.state.collectAsStateWithLifecycle()
    val update by app.setup.update.collectAsStateWithLifecycle()
    val opencode by app.setup.opencode.collectAsStateWithLifecycle()
    val agents by app.agents.installed.collectAsStateWithLifecycle()
    val work by app.agents.work.collectAsStateWithLifecycle()
    var removing by remember { mutableStateOf<Agent?>(null) }
    var loggingIn by remember { mutableStateOf(false) }
    var loggedOut by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var resetRunning by remember { mutableStateOf(false) }
    var resetError by remember { mutableStateOf<String?>(null) }
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    var secure by remember { mutableStateOf(keyguard.isDeviceSecure) }
    // The user can set or remove the screen lock in the system settings and come back (review DS-L-20).
    LifecycleResumeEffect(Unit) {
        secure = keyguard.isDeviceSecure
        onPauseOrDispose {}
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.Back, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "h-github") { SectionHeader("GitHub") }
            item(key = "github") {
                val name = login
                if (name == null) {
                    ListItem(
                        headlineContent = { Text("Log in to GitHub") },
                        supportingContent = { Text(if (loggedOut) "Logged out. Revoke the old token on GitHub." else "For private repositories, push and releases") },
                        leadingContent = { Icon(Icons.Login, null) },
                        modifier = Modifier.clickable { loggingIn = true },
                    )
                } else {
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text("Logged in. Tap to log out.") },
                        leadingContent = { Icon(Icons.Logout, null) },
                        modifier = Modifier.clickable {
                            scope.launch { runCatching { app.github.logout() } }
                            loggedOut = true
                        },
                    )
                }
            }
            if (loggedOut && login == null) {
                item(key = "revoke") {
                    ListItem(
                        headlineContent = { Text("Revoke the token on GitHub") },
                        supportingContent = { Text("The token stays valid until you revoke it (GitHub CLI app)") },
                        leadingContent = { Icon(Icons.OpenInNew, null) },
                        modifier = Modifier.clickable { context.startActivity(Intent(Intent.ACTION_VIEW, GitHub.TOKENS_URL.toUri())) },
                    )
                }
            }
            item(key = "h-security") { SectionHeader("Security") }
            item(key = "keys") {
                val count = names.count { it != Secrets.GH_TOKEN }
                ListItem(
                    headlineContent = { Text("API keys") },
                    supportingContent = { Text(if (count == 0) "None" else "$count encrypted") },
                    leadingContent = { Icon(Icons.Key, null) },
                    modifier = Modifier.clickable { nav.go(Route.Keys) },
                )
            }
            item(key = "lock") {
                ListItem(
                    headlineContent = { Text("App lock") },
                    supportingContent = {
                        Text(if (secure) "Fingerprint, face or screen lock to open Automa" else "Set a screen lock first")
                    },
                    leadingContent = { Icon(Icons.Lock, null) },
                    trailingContent = { Switch(checked = lock, enabled = secure || lock, onCheckedChange = app.prefs::setLock) },
                )
            }
            item(key = "h-agents") { SectionHeader("Coding agents") }
            items(Agent.ON_DEMAND, key = { "agent-${it.command}" }) { agent ->
                AgentRow(
                    agent,
                    version = agents[agent],
                    work = work?.takeIf { it.agent == agent },
                    // One job at a time: not during the setup, an update or another install.
                    enabled = setup == Setup.State.Ready && update !is Setup.Update.Running && (work == null || work?.error != null),
                    // The app scope: the install continues when this screen closes.
                    onInstall = { app.scope.launch { app.setup.install(agent) } },
                    onRemove = { removing = agent },
                )
            }
            item(key = "h-env") { SectionHeader("Environment") }
            item(key = "env") {
                ListItem(
                    headlineContent = { Text(opencode?.let { "opencode $it" } ?: "opencode is not installed") },
                    supportingContent = { Text("Alpine Linux 3.24, git, gh") },
                    leadingContent = { Icon(Icons.Robot, null) },
                )
            }
            item(key = "update") {
                val state = update
                ListItem(
                    headlineContent = { Text("Update") },
                    supportingContent = {
                        Text(
                            when (state) {
                                Setup.Update.Idle -> "The newest opencode, the installed agents and the Alpine packages"
                                is Setup.Update.Running -> state.text
                                is Setup.Update.Done -> state.text
                                is Setup.Update.Failed -> state.text
                            },
                            color = if (state is Setup.Update.Failed) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    leadingContent = { Icon(Icons.Refresh, null) },
                    trailingContent = (state as? Setup.Update.Running)?.let { running ->
                        {
                            val progress = running.progress
                            if (progress != null) {
                                CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
                            } else {
                                CircularWavyProgressIndicator(Modifier.size(32.dp))
                            }
                        }
                    },
                    // The app scope: the update continues when this screen closes.
                    modifier = Modifier.clickable(enabled = setup == Setup.State.Ready && state !is Setup.Update.Running) {
                        app.scope.launch { app.setup.update() }
                    },
                )
            }
            item(key = "reset") {
                ListItem(
                    headlineContent = { Text("Reset the environment") },
                    supportingContent = {
                        Text(
                            resetError ?: "Deletes the Linux system and all repositories on this phone",
                            color = if (resetError != null) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                    },
                    leadingContent = { Icon(Icons.Delete, null) },
                    trailingContent = if (resetRunning) {
                        { CircularWavyProgressIndicator(Modifier.size(32.dp)) }
                    } else {
                        null
                    },
                    modifier = Modifier.clickable(enabled = !resetRunning) { resetting = true },
                )
            }
            item(key = "h-support") { SectionHeader("Support") }
            item(key = "donate") { LinkRow("Donate bitcoin", "btcpay.priorato.org", Icons.Bitcoin, DONATE_URL) }
            item(key = "x") { LinkRow("X", "@turtlecute33", Icons.AlternateEmail, "https://x.com/turtlecute33") }
            item(key = "nostr") { LinkRow("Nostr", "turtlecute@primal.net", Icons.Hub, "https://primal.net/turtlecute") }
            item(key = "about") {
                Text(
                    "Automa ${BuildConfig.VERSION_NAME} · GPL-3.0. PRoot (GPL-2.0), talloc (LGPL-3.0), Termux terminal " +
                        "(Apache-2.0), JetBrains Mono (OFL-1.1). Alpine Linux, opencode, Claude Code and Codex have their own licences.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    if (loggingIn) LoginSheet(onDismiss = { loggingIn = false })
    removing?.let { agent ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${agent.label}?") },
            text = { Text("Automa deletes the program. Your login and settings stay in the home folder (~/.${agent.command}).") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    app.scope.launch { app.setup.remove(agent) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text("Reset the environment?") },
            text = { Text("Automa closes all sessions and deletes the Linux system with all repositories. Changes that you did not push are lost. The keys stay.") },
            confirmButton = {
                TextButton(onClick = {
                    resetting = false
                    resetRunning = true
                    resetError = null
                    // The app scope: the reset continues when this screen closes. The sessions and the chat agents end
                    // first: they must not write into the guest during the delete (review SEC-16, DS-L-07).
                    val reset = app.scope.async { app.setup.reset { app.sessions.stopAll(); app.chats.stopAll() } }
                    // This screen waits for the reset, so that it can show an error (review KM-P-05).
                    scope.launch {
                        runCatching { reset.await() }
                            .onSuccess { nav.back() }
                            .onFailure { if (it !is CancellationException) resetError = userText(it) }
                        resetRunning = false
                    }
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel") } },
        )
    }
}

/** Settings › Support. The browser opens the links; Automa does not connect to these hosts. */
private const val DONATE_URL =
    "https://btcpay.priorato.org/api/v1/invoices?storeId=31ntjiLSTmSSuTC6FrR81z6AAku46bLF2FKAL3MFHtV5&orderId=Donation&checkoutDesc=Donation&currency=USD"

/** A row that opens [url] in the browser. */
@Composable
private fun LinkRow(title: String, text: String, icon: ImageVector, url: String) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(text) },
        leadingContent = { Icon(icon, null) },
        modifier = Modifier.clickable { openLink(context, url) },
    )
}

/** The download size and the size on the phone (estimates, DESIGN §3). */
private fun size(agent: Agent) = when (agent) {
    Agent.Claude -> "download 101 MB, 290 MB on the phone"
    Agent.Codex -> "download 86 MB, 290 MB on the phone"
    Agent.Opencode -> ""
}

/** An agent that the user installs on demand: its version, the install progress or error, and Remove. */
@Composable
private fun AgentRow(agent: Agent, version: String?, work: Agents.Work?, enabled: Boolean, onInstall: () -> Unit, onRemove: () -> Unit) {
    val error = work?.error
    ListItem(
        headlineContent = { Text(if (version != null) "${agent.label} $version" else agent.label) },
        supportingContent = {
            Text(
                when {
                    error != null -> userText(error)
                    work != null -> work.text
                    version != null -> "Tap a repository: chat or terminal. Log in with your account in the terminal at the first start."
                    else -> "Tap to install (${size(agent)})"
                },
                color = if (error != null) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
        },
        leadingContent = { Icon(Icons.Robot, null) },
        trailingContent = {
            when {
                work != null && error == null -> {
                    val progress = work.progress
                    if (progress != null) {
                        CircularWavyProgressIndicator(progress = { progress }, modifier = Modifier.size(32.dp))
                    } else {
                        CircularWavyProgressIndicator(Modifier.size(32.dp))
                    }
                }
                version != null -> IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Delete, "Remove ${agent.label}") }
            }
        },
        modifier = if (version == null) Modifier.clickable(enabled = enabled, onClick = onInstall) else Modifier,
    )
}
