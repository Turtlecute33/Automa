package app.automa.ui.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.data.Secrets
import app.automa.env.Tff
import app.automa.ui.Icons
import app.automa.ui.PrivateTextInput
import app.automa.ui.SecureWindow
import app.automa.ui.nav.Navigator
import app.automa.ui.userText
import kotlinx.coroutines.launch

/** API keys for opencode as encrypted environment variables (DESIGN §7). The values are never shown. */
@Composable
fun KeysScreen(nav: Navigator) {
    SecureWindow()
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val names by app.secrets.names.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API keys") },
                navigationIcon = { IconButton(onClick = nav::back) { Icon(Icons.Back, "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Add, null) }, text = { Text("Add key") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item(key = "about") {
                Text(
                    "Each key is an environment variable in all new sessions, for example ${Tff.KEY} for " +
                        "opencode. Automa keeps the keys encrypted with a key of this phone. The programs in a " +
                        "session can read them.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            item(key = "tff") { TffRow(hasKey = Tff.KEY in names) }
            items(names.filter { it != Secrets.GH_TOKEN }, key = { it }) { name ->
                ListItem(
                    headlineContent = { Text(name) },
                    leadingContent = { Icon(Icons.Key, null) },
                    trailingContent = {
                        IconButton(onClick = { scope.launch { runCatching { app.secrets.remove(name) } } }) { Icon(Icons.Delete, "Delete $name") }
                    },
                )
            }
        }
    }
    if (adding) AddKeyDialog(onDismiss = { adding = false })
}

/** The TFF models in opencode (DESIGN §8): the state of the list, and a reload. */
@Composable
private fun TffRow(hasKey: Boolean) {
    val app = LocalContext.current.app
    val status by app.tff.status.collectAsStateWithLifecycle()
    val text = when (val s = status) {
        Tff.Status.NoKey -> if (hasKey) "Tap reload to get the model list" else "Add ${Tff.KEY} to get the model list"
        Tff.Status.Loading -> "Getting the model list…"
        is Tff.Status.Loaded -> "${s.count} models. New opencode sessions show them."
        is Tff.Status.Failed -> userText(s.error)
    }
    ListItem(
        headlineContent = { Text("Tokens for Friends models") },
        supportingContent = {
            Text(text, color = if (status is Tff.Status.Failed) MaterialTheme.colorScheme.error else Color.Unspecified)
        },
        leadingContent = { Icon(Icons.Robot, null) },
        trailingContent = {
            if (status == Tff.Status.Loading) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else if (hasKey) {
                // The app scope: the request continues when this screen closes.
                IconButton(onClick = { app.scope.launch { app.setup.models() } }) { Icon(Icons.Refresh, "Reload the model list") }
            }
        },
    )
}

private val SUGGESTED = listOf(Tff.KEY)

@Composable
private fun AddKeyDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val valid = Secrets.validName(name) && name != Secrets.GH_TOKEN && Secrets.validValue(value)
    AlertDialog(
        onDismissRequest = onDismiss,
        // A dialog is a window of its own: FLAG_SECURE of the activity does not cover it.
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Add a key") },
        text = {
            PrivateTextInput {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTED.forEach { SuggestionChip(onClick = { name = it }, label = { Text(it) }) }
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { text -> name = text.uppercase().filter { it.isLetterOrDigit() || it == '_' }.take(64) },
                        label = { Text("Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it.take(8192) },
                        label = { Text("Value") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                scope.launch {
                    runCatching { app.secrets.put(name, value.trim()) }
                        .onSuccess {
                            if (name == Tff.KEY) app.scope.launch { app.setup.models() }
                            onDismiss()
                        }
                        .onFailure { error = userText(it) }
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
