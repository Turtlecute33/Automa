package app.automa.ui.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.automa.term.Look

/** JetBrains Mono, read at app start off the main thread ([Look.font]); the system monospace font until then. */
val Mono: FontFamily get() = Look.cached?.let { FontFamily(it) } ?: FontFamily.Monospace

/** Agent text as Markdown ([Markdown]). A very long text stays plain (review SEC-20). */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    if (text.length > MAX_MARKDOWN) {
        Text(text, style = MaterialTheme.typography.bodyMedium, fontFamily = Mono, modifier = modifier)
        return
    }
    val blocks = remember(text) { Markdown.blocks(text) }
    val colors = MaterialTheme.colorScheme
    var link by remember { mutableStateOf<String?>(null) }
    val styles = remember(colors) {
        Markdown.Styles(
            code = SpanStyle(fontFamily = Mono, fontSize = 0.9.em, background = colors.surfaceContainerHighest, color = colors.tertiary),
            link = TextLinkStyles(SpanStyle(color = colors.primary, textDecoration = TextDecoration.Underline)),
            onLink = { link = it },
        )
    }
    link?.let { LinkDialog(it) { link = null } }
    val type = MaterialTheme.typography
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index) {
                when (block) {
                    is Block.Paragraph -> Text(remember(block, styles) { Markdown.inline(block.text, styles) }, style = type.bodyLarge)
                    is Block.Heading -> Text(
                        remember(block, styles) { Markdown.inline(block.text, styles) },
                        style = when (block.level) {
                            1 -> type.titleLarge
                            2 -> type.titleMedium
                            else -> type.titleSmall
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is Block.Code -> CodeBlock(block.text)
                    is Block.Quote -> Row {
                        Surface(color = colors.outline, modifier = Modifier.width(3.dp)) { Text(" ") }
                        Text(
                            remember(block, styles) { Markdown.inline(block.text, styles) },
                            style = type.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    is Block.Item -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                        val marker = if (block.marker[0].isDigit()) block.marker else "•"
                        Text(marker, style = type.bodyLarge, color = colors.onSurfaceVariant, modifier = Modifier.width(24.dp))
                        Text(remember(block, styles) { Markdown.inline(block.text, styles) }, style = type.bodyLarge)
                    }
                    is Block.Table -> CodeBlock(remember(block) { table(block.rows) })
                    Block.Rule -> HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Monospace text for code, tables, commands and outputs. It scrolls to the side, or with [wrap] it wraps, so that
 * all of a command is in view (permission cards, review SEC-17).
 */
@Composable
fun CodeBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, wrap: Boolean = false) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth()) {
        Box(if (wrap) Modifier.padding(10.dp) else Modifier.horizontalScroll(rememberScrollState()).padding(10.dp)) {
            Text(text, fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, maxLines = maxLines, softWrap = wrap)
        }
    }
}

/**
 * A link from agent text opens only after the user sees its real host (from the URL, not from the label) and taps
 * Open, as for links of guest programs (DESIGN §5, review SEC-21).
 */
@Composable
private fun LinkDialog(url: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open the link in the browser?") },
        text = {
            Column {
                Text(url.toUri().host.orEmpty(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 4)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
            }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private const val MAX_MARKDOWN = 60_000

/** A table as aligned monospace columns. */
private fun table(rows: List<List<String>>): String {
    val columns = rows.maxOfOrNull { it.size } ?: 0
    val widths = (0 until columns).map { c -> rows.maxOf { it.getOrNull(c)?.length ?: 0 } }
    return rows.joinToString("\n") { row -> (0 until columns).joinToString("  ") { c -> row.getOrElse(c) { "" }.padEnd(widths[c]) }.trimEnd() }
}
