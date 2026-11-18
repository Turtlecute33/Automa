package app.automa.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.git.Remote
import app.automa.git.Repos
import app.automa.ui.Icons
import app.automa.ui.userText
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Clone `owner/repo`, an https link, or account repositories after a GitHub login (DESIGN §6). The user can select
 * more than one account repository; they clone one after the other, and an error does not stop the others.
 */
@Composable
fun CloneSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val login by app.prefs.login.collectAsStateWithLifecycle()
    val local by app.repos.list.collectAsStateWithLifecycle()
    val cloned = remember(local) { local.mapTo(HashSet()) { it.name } }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var input by remember { mutableStateOf("") }
    var remotes by remember { mutableStateOf<List<Remote>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var line by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    val busy = job != null

    LaunchedEffect(login) {
        if (login != null) remotes = runCatching { app.github.remotes() }.getOrElse { error = userText(it); emptyList() }
    }

    /** Clones [specs] one after the other. The sheet closes when all are cloned; else it shows the errors. */
    fun clone(specs: List<String>) {
        if (busy || specs.isEmpty()) return
        error = null
        job = scope.launch {
            val failed = mutableListOf<String>()
            specs.forEachIndexed { i, spec ->
                val prefix = if (specs.size > 1) "${i + 1}/${specs.size} $spec: " else ""
                line = prefix
                runCatching { app.repos.clone(spec) { line = prefix + it } }
                    .onSuccess { selected -= spec }
                    .onFailure { failed += if (specs.size > 1) "$spec: ${userText(it)}" else userText(it) }
            }
            job = null
            if (failed.isEmpty()) onDismiss() else error = failed.joinToString("\n")
        }
    }

    // The selection, else the typed name.
    val specs = selected.toList().ifEmpty { listOf(input).filter { it.isNotBlank() } }

    ModalBottomSheet(onDismissRequest = { job?.cancel(); onDismiss() }) {
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Clone a repository", style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(300) },
                label = { Text("owner/repo or https link") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { clone(listOf(input)) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { clone(specs) }, enabled = !busy && specs.isNotEmpty()) {
                    Text(if (selected.size > 1) "Clone ${selected.size}" else "Clone")
                }
                if (busy) LoadingIndicator()
            }
            if (busy && line.isNotEmpty()) {
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (login == null) {
                Text(
                    "Log in to GitHub in Settings to see your repositories and to clone private ones.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        val list = remotes
        if (login != null && list == null) {
            LoadingIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        }
        if (!list.isNullOrEmpty()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                items(list, key = { it.name }) { remote ->
                    val here = Repos.source(remote.name)?.name in cloned
                    val checked = remote.name in selected
                    fun toggle() {
                        selected = if (checked) selected - remote.name else selected + remote.name
                    }
                    ListItem(
                        headlineContent = { Text(remote.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = when {
                            here -> { { Text("Cloned") } }
                            remote.description.isNotBlank() -> { { Text(remote.description, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                            else -> null
                        },
                        leadingContent = { Checkbox(checked = checked || here, onCheckedChange = { toggle() }, enabled = !busy && !here) },
                        trailingContent = if (remote.private) {
                            { Icon(Icons.Lock, "Private") }
                        } else {
                            null
                        },
                        modifier = Modifier.clickable(enabled = !busy && !here, onClick = ::toggle),
                    )
                }
            }
        }
    }
}
