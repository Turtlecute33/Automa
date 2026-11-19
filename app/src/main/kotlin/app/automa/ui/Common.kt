package app.automa.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.core.net.toUri
import java.io.IOException
import java.security.GeneralSecurityException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The one place that turns an exception into text for the user. */
fun userText(e: Throwable): String = when (e) {
    is UnknownHostException -> "No connection to the server"
    is SocketTimeoutException -> "The connection timed out"
    is SSLException -> "The secure connection failed"
    is GeneralSecurityException -> "The keys cannot be opened now. Try again."
    is IOException, is IllegalArgumentException -> e.message ?: "The operation failed"
    else -> "The operation failed (${e.javaClass.simpleName})"
}

/** Opens an https [url] in the browser. Nothing happens when the device has no browser. */
fun openLink(context: Context, url: String) {
    if (url.startsWith("https://")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}

/** The keyboard must not learn from the fields in [content] (secrets). DESIGN §7. */
@Composable
fun PrivateTextInput(content: @Composable () -> Unit) = InterceptPlatformTextInput(
    interceptor = { request, next ->
        next.startInputMethod(
            PlatformTextInputMethodRequest { info ->
                request.createInputConnection(info).also {
                    info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            },
        )
    },
    content = content,
)

/** No screenshots, no screen recording and no recents preview while this is shown. DESIGN §7. */
@Composable
fun SecureWindow() {
    val activity: Activity? = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The title of a group of list rows. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
