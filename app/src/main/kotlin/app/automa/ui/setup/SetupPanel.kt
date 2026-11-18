package app.automa.ui.setup

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.automa.env.Pins
import app.automa.env.Setup

/** The setup of the guest system (DESIGN §3): what it does, its progress, and the error with the log. */
@Composable
fun SetupPanel(state: Setup.State, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (state) {
            Setup.State.Checking, Setup.State.Ready -> Unit
            is Setup.State.Needed -> {
                Text(
                    if (state.update) "Update the environment" else "Install the environment",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    if (state.update) {
                        "This version of Automa has new packages or a new opencode ($OPENCODE). Your repositories stay."
                    } else {
                        "Automa downloads Alpine Linux (4 MB) and opencode $OPENCODE (78 MB), and installs git, gh and " +
                            "other tools (about 80 MB). All of it stays on this phone. Use Wi-Fi if you can."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = onStart) { Text(if (state.update) "Update" else "Install") }
            }
            is Setup.State.Running -> {
                Text(state.step.label, style = MaterialTheme.typography.headlineSmall)
                if (state.progress != null) {
                    LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                }
                Log(state.log)
            }
            is Setup.State.Failed -> {
                Text("${state.step.label}: failed", style = MaterialTheme.typography.headlineSmall)
                Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                if (state.blocked) {
                    // GrapheneOS shows its exploit protection settings in App info.
                    val context = LocalContext.current
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        }) { Text("Open App info") }
                        OutlinedButton(onClick = onStart) { Text("Try again") }
                    }
                } else {
                    Button(onClick = onStart) { Text("Try again") }
                }
                Log(state.log)
            }
        }
    }
}

private const val OPENCODE = Pins.OPENCODE_VERSION

/** The last output lines of the package manager, newest at the bottom. */
@Composable
private fun Log(lines: List<String>) {
    if (lines.isEmpty()) return
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { list.scrollToItem(lines.lastIndex) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.medium) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp).padding(12.dp)) {
            itemsIndexed(lines, key = { i, _ -> i }) { _, line ->
                Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
