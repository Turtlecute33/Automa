package app.automa.ui.settings

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.git.GitHub
import app.automa.ui.Icons
import app.automa.ui.userText

/**
 * The GitHub device flow (DESIGN §6): gh in the guest gives a one-time code; the user types it at
 * github.com/login/device in the browser. The flow runs in the app scope ([app.automa.git.GitHub.start]), so it
 * continues when the app lock removes this sheet. A swipe down cancels it. The sheet closes after the login.
 */
@Composable
fun LoginSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.app
    val state by app.github.login.collectAsStateWithLifecycle()
    val name by app.prefs.login.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { if (app.github.login.value !is GitHub.Login.Waiting) app.github.start(app.scope) }
    LaunchedEffect(name) { if (name != null) onDismiss() }

    ModalBottomSheet(onDismissRequest = { app.github.cancel(); onDismiss() }) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Log in to GitHub", style = MaterialTheme.typography.headlineSmall)
            when (val s = state) {
                is GitHub.Login.Failed -> {
                    Text(userText(s.error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { app.github.start(app.scope) }) { Text("Try again") }
                }
                is GitHub.Login.Waiting -> s.code?.let { code ->
                    Text("Open GitHub and type this code:", style = MaterialTheme.typography.bodyLarge)
                    Text(code, style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                    Button(onClick = {
                        copy(context, code)
                        context.startActivity(Intent(Intent.ACTION_VIEW, GitHub.DEVICE_URL.toUri()))
                    }) {
                        Icon(Icons.OpenInNew, null)
                        Text("Copy code and open GitHub", Modifier.padding(start = 8.dp))
                    }
                    Text(
                        "Automa waits here. After you approve the access on GitHub, the login completes. " +
                            "The token stays encrypted on this phone.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } ?: LoadingIndicator()
                GitHub.Login.Idle -> LoadingIndicator()
            }
        }
    }
}

/** The code is short-lived, but it is marked sensitive so that the clipboard preview hides it. */
private fun copy(context: android.content.Context, text: String) {
    val clip = ClipData.newPlainText("GitHub code", text).apply {
        description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
}
