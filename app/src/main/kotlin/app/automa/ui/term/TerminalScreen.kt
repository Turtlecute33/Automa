package app.automa.ui.term

import android.content.Intent
import android.util.TypedValue
import android.view.WindowInsets as PlatformInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.automa.app
import app.automa.term.Look
import app.automa.term.Tab
import app.automa.ui.Icons
import app.automa.ui.SectionHeader
import app.automa.ui.nav.Navigator
import app.automa.ui.nav.Route
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One terminal session, full screen, as in Varco (DESIGN §5): the Termux view and the extra keys. The session chip
 * in the keys opens a sheet with Home, Share, Close and the other sessions.
 */
@Composable
fun TerminalScreen(id: Int, nav: Navigator) {
    val context = LocalContext.current
    val app = context.app
    val tabs by app.sessions.tabs.collectAsStateWithLifecycle()
    val tab = tabs.firstOrNull { it.id == id }
    val session = remember(id) { app.sessions.session(id) }
    if (tab == null || session == null) {
        LaunchedEffect(Unit) { nav.remove(Route.Terminal(id)) }
        return
    }
    val fontSize by app.prefs.fontSize.collectAsStateWithLifecycle()
    val mods = remember { Modifiers() }
    var view by remember { mutableStateOf<TerminalView?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    val link by app.links.link.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(Look.BACKGROUND))
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        val client = ViewClient(
                            this,
                            mods,
                            onFontStep = { bigger -> app.prefs.setFontSize(app.prefs.fontSize.value + if (bigger) 1 else -1) },
                            onCopyMode = { selecting = it },
                        )
                        setTerminalViewClient(client)
                        // setTextSize makes the renderer; setTypeface needs it. The font is set before the first
                        // layout, so that the PTY does not get a size from the built-in monospace font first.
                        val px = sp(this, app.prefs.fontSize.value)
                        setTextSize(px)
                        tag = px
                        Look.cached?.let(::setTypeface)
                        // The columns do not fill the width exactly; the rest has the terminal background.
                        setBackgroundColor(Look.BACKGROUND)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        attachSession(session)
                        app.sessions.view = this
                        view = this
                        post { client.showKeyboard() }
                    }
                },
                update = { v ->
                    // setTextSize makes a new renderer and lays out again: only on a real change.
                    val px = sp(v, fontSize)
                    if (v.tag != px) {
                        v.setTextSize(px)
                        v.tag = px
                    }
                },
                modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            )
            tab.exit?.let { code ->
                ExitBar(code, onClose = { app.sessions.close(id) }, modifier = Modifier.align(Alignment.BottomCenter))
            }
            link?.let { url ->
                LinkBar(
                    host = url.toUri().host.orEmpty(),
                    onOpen = {
                        app.links.clear()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
                    },
                    onDismiss = app.links::clear,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
        ExtraKeys(
            view = { view },
            mods = mods,
            // Only the folder: the chip is narrow. The sheet shows the full title.
            chipLabel = tab.title.substringBefore(" · "),
            chipDot = dot(tab),
            onChip = {
                // The keyboard would cover the sheet.
                view?.windowInsetsController?.hide(PlatformInsets.Type.ime())
                sheet = true
            },
            onPaste = { app.sessions.onPasteTextFromClipboard(session) },
        )
    }

    // The font is read once, off the main thread (AutomaApp reads it at start). Until then, the built-in monospace.
    LaunchedEffect(view) {
        val v = view ?: return@LaunchedEffect
        if (Look.cached == null) v.setTypeface(withContext(Dispatchers.IO) { Look.font(context) })
    }
    DisposableEffect(id) {
        onDispose { if (app.sessions.view === view) app.sessions.view = null }
    }
    // Links from guest programs (xdg-open), for example a login. The setup made the folder: the guest is ready here.
    LaunchedEffect(Unit) { app.links.start() }
    // Back ends a text selection and the terminal stays open. With enableOnBackInvokedCallback, Back does not go to
    // TerminalView.onKeyPreIme.
    BackHandler(enabled = selecting) { view?.stopTextSelectionMode() }

    if (sheet) {
        SessionSheet(
            tabs = tabs,
            current = id,
            onDismiss = { sheet = false },
            // To Home, also when the terminal opened from a chat (the chat is then between them).
            onHome = { sheet = false; nav.go(Route.Home) },
            onShare = { sheet = false; view?.let(::share) },
            onClose = { sheet = false; app.sessions.close(id) },
            onSwitch = { other ->
                sheet = false
                nav.back()
                nav.go(Route.Terminal(other))
            },
            onCloseOther = app.sessions::close,
        )
    }
}

/** Green while the program runs, red after an error exit. */
private fun dot(tab: Tab) = Color(if (tab.exit == null) Look.ANSI[2] else Look.ANSI[1])

private fun sp(view: TerminalView, size: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.toFloat(), view.resources.displayMetrics).toInt()

/** The selected text, or else all the text of the session, to the share sheet. At most the last 100,000 characters. */
private fun share(view: TerminalView) {
    val text = if (view.isSelectingText) {
        view.selectedText.also { view.stopTextSelectionMode() }
    } else {
        view.currentSession?.emulator?.screen?.transcriptText
    }
    if (text.isNullOrBlank()) return
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.takeLast(100_000))
    view.context.startActivity(Intent.createChooser(send, null))
}

@Composable
private fun ExitBar(code: Int, onClose: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("The process ended with code $code.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClose) { Text("Close") }
        }
    }
}

/**
 * A program asks to open a link (DESIGN §5). The host is shown, so that the user can check it; the browser opens only
 * after a tap.
 */
@Composable
private fun LinkBar(host: String, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.padding(12.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Open the link in the browser?", style = MaterialTheme.typography.bodyMedium)
                Text(host, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = onDismiss) { Text("Cancel") }
            FilledTonalButton(onClick = onOpen) { Text("Open") }
        }
    }
}

@Composable
private fun SessionSheet(
    tabs: List<Tab>,
    current: Int,
    onDismiss: () -> Unit,
    onHome: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
    onSwitch: (Int) -> Unit,
    onCloseOther: (Int) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(Icons.Back, "Home", onHome)
            SheetButton(Icons.Share, "Share", onShare)
            SheetButton(Icons.Close, "Close", onClose)
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            item(key = "header") { SectionHeader("Sessions") }
            items(tabs, key = { it.id }) { tab ->
                ListItem(
                    headlineContent = { Text(tab.title + if (tab.id == current) "  ·  shown" else "") },
                    supportingContent = { Text(tab.exit?.let { "Ended with code $it" } ?: tab.dir) },
                    leadingContent = { Box(Modifier.size(10.dp).background(dot(tab), CircleShape)) },
                    trailingContent = {
                        if (tab.id != current) IconButton(onClick = { onCloseOther(tab.id) }) { Icon(Icons.Close, "Close ${tab.title}") }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = if (tab.id != current) Modifier.clickable { onSwitch(tab.id) } else Modifier,
                )
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun SheetButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) {
        Icon(icon, null, Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
