package app.automa.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/** A Markdown block of an agent message (DESIGN §13). Only what agents write: no HTML and no images. */
@Immutable
sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Code(val language: String, val text: String) : Block
    data class Quote(val text: String) : Block
    data class Item(val depth: Int, val marker: String, val text: String) : Block
    data class Table(val rows: List<List<String>>) : Block
    data object Rule : Block
}

/**
 * A small Markdown reader for agent text. The text is untrusted: it only becomes styled text, and a link is active
 * only for https (the browser opens after a tap).
 */
object Markdown {
    private val FENCE = Regex("""^(`{3,}|~{3,})(.*)$""")
    private val HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val RULE = Regex("""^([-*_])(\s*\1){2,}\s*$""")
    private val ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
    private val TABLE_RULE = Regex("""^\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?$""")

    fun blocks(text: String): List<Block> {
        val out = ArrayList<Block>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotEmpty()) out += Block.Paragraph(para.toString())
            para.clear()
        }
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            // The regexes only on short lines: a crafted long line must not take long (review SEC-20).
            val fence = FENCE.matchEntire(trimmed)
            val heading = if (trimmed.length <= SHORT && trimmed.startsWith("#")) HEADING.matchEntire(trimmed) else null
            val item = if (line.length <= LONG) ITEM.matchEntire(line) else null
            when {
                fence != null -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith(fence.groupValues[1])) code += lines[i++]
                    out += Block.Code(fence.groupValues[2].trim(), code.joinToString("\n"))
                }
                trimmed.isEmpty() -> flush()
                heading != null -> {
                    flush()
                    out += Block.Heading(heading.groupValues[1].length, heading.groupValues[2])
                }
                trimmed.length <= SHORT && RULE.matches(trimmed) -> {
                    flush()
                    out += Block.Rule
                }
                trimmed.startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) quote += lines[i++].trim().removePrefix(">").trim()
                    i--
                    out += Block.Quote(quote.joinToString("\n"))
                }
                item != null -> {
                    flush()
                    val depth = (item.groupValues[1].replace("\t", "  ").length / 2).coerceAtMost(4)
                    out += Block.Item(depth, item.groupValues[2], item.groupValues[3])
                }
                trimmed.startsWith("|") -> {
                    flush()
                    val rows = ArrayList<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val row = lines[i++].trim()
                        if (row.length > LONG || !TABLE_RULE.matches(row)) rows += row.trim('|').split('|').map(String::trim)
                    }
                    i--
                    out += Block.Table(rows)
                }
                // A line with more indent after a list item continues the item.
                para.isEmpty() && line.startsWith("  ") && out.lastOrNull() is Block.Item -> {
                    val last = out.removeAt(out.lastIndex) as Block.Item
                    out += last.copy(text = last.text + "\n" + trimmed)
                }
                else -> {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(line.trimEnd())
                }
            }
            i++
        }
        flush()
        return out
    }

    /** Styles for [inline]. A tap on a link calls [onLink]; the screen asks before it opens the browser (review SEC-21). */
    @Immutable
    data class Styles(val code: SpanStyle, val link: TextLinkStyles, val onLink: (String) -> Unit)

    /**
     * `code`, **bold**, *italic*, ~~strike~~, [text](https://…) and bare https links. A backslash escapes a mark. Each
     * search for a closing mark stops at the end of the line and after [WINDOW] characters, so that the work is linear
     * in the length; a text longer than [MAX_INLINE] stays plain (review SEC-20).
     */
    fun inline(text: String, styles: Styles): AnnotatedString =
        if (text.length > MAX_INLINE) AnnotatedString(text) else buildAnnotatedString { inlineTo(text, styles) }

    /** [link]: the text is the label of a link, so it holds no other link. */
    private fun AnnotatedString.Builder.inlineTo(text: String, styles: Styles, link: Boolean = false) {
        val ends = IntArray(text.length + 1)
        var next = text.length
        for (j in text.length - 1 downTo 0) {
            if (text[j] == '\n') next = j
            ends[j] = next
        }
        fun limit(at: Int) = minOf(ends[minOf(at, text.length)], at + WINDOW)
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            append(plain.toString())
            plain.clear()
        }
        while (i < text.length) {
            val c = text[i]
            val rest = text.length - i
            when {
                c == '\\' && rest > 1 && text[i + 1] in ESCAPABLE -> {
                    plain.append(text[i + 1])
                    i += 2
                    continue
                }
                c == '`' -> {
                    var ticks = 0
                    while (i + ticks < text.length && text[i + ticks] == '`') ticks++
                    val end = find(text, "`".repeat(ticks), i + ticks, limit(i))
                    if (end > 0) {
                        flush()
                        withStyle(styles.code) { append(text.substring(i + ticks, end).trim()) }
                        i = end + ticks
                        continue
                    }
                }
                (text.startsWith("**", i) || text.startsWith("__", i)) && opens(text, i + 2) -> {
                    val end = find(text, text.substring(i, i + 2), i + 2, limit(i))
                    if (end > i + 2) {
                        flush()
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { inlineTo(text.substring(i + 2, end), styles, link) }
                        i = end + 2
                        continue
                    }
                }
                text.startsWith("~~", i) && opens(text, i + 2) -> {
                    val end = find(text, "~~", i + 2, limit(i))
                    if (end > i + 2) {
                        flush()
                        withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { inlineTo(text.substring(i + 2, end), styles, link) }
                        i = end + 2
                        continue
                    }
                }
                (c == '*' || c == '_') && opens(text, i + 1) && (c == '*' || i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    val end = closer(text, c, i + 1, limit(i))
                    if (end > 0) {
                        flush()
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { inlineTo(text.substring(i + 1, end), styles, link) }
                        i = end + 1
                        continue
                    }
                }
                c == '[' && !link -> {
                    val close = find(text, "](", i + 1, limit(i))
                    val end = if (close > 0) closeParen(text, close + 2, limit(i)) else -1
                    if (end > 0 && '\n' !in text.substring(i, end)) {
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end).trim()
                        flush()
                        if (safe(url) && !link) withLink(LinkAnnotation.Clickable(url, styles.link) { styles.onLink(url) }) { inlineTo(label, styles, link = true) } else inlineTo(label, styles, link)
                        i = end + 1
                        continue
                    }
                }
                !link && text.startsWith("https://", i) && (i == 0 || !text[i - 1].isLetterOrDigit()) -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace() && text[end] !in "<>\"`") end++
                    while (end > i && text[end - 1] in ".,;:!?)]'") end--
                    val url = text.substring(i, end)
                    flush()
                    if (safe(url)) withLink(LinkAnnotation.Clickable(url, styles.link) { styles.onLink(url) }) { append(url) } else append(url)
                    i = end
                    continue
                }
            }
            plain.append(c)
            i++
        }
        flush()
    }

    /** A mark opens only before a character that is not a space. */
    private fun opens(text: String, at: Int) = at < text.length && !text[at].isWhitespace()

    /** The first [needle] in [text] from [from], before [limit], or -1. */
    private fun find(text: String, needle: String, from: Int, limit: Int): Int {
        var j = from
        while (j + needle.length <= limit) {
            if (text.startsWith(needle, j)) return j
            j++
        }
        return -1
    }

    private fun closer(text: String, mark: Char, from: Int, limit: Int): Int {
        var j = from + 1
        while (j < limit && text[j] != '\n') {
            val after = j + 1 >= text.length || !text[j + 1].isLetterOrDigit() || mark == '*'
            if (text[j] == mark && !text[j - 1].isWhitespace() && after && (j + 1 >= text.length || text[j + 1] != mark)) return j
            j++
        }
        return -1
    }

    /** The `)` that closes a link target from [from], with nested pairs, or -1. */
    private fun closeParen(text: String, from: Int, limit: Int): Int {
        var depth = 0
        for (j in from until minOf(limit, text.length)) {
            when (text[j]) {
                '(' -> depth++
                ')' -> if (depth == 0) return j else depth--
                '\n' -> return -1
            }
        }
        return -1
    }

    /** Only https links can open, at most 2048 characters, with no spaces or control characters. */
    fun safe(url: String) = url.length in 9..2048 && url.startsWith("https://") && url.none { it.isWhitespace() || it.isISOControl() }

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"
    private const val SHORT = 300
    private const val LONG = 2000
    private const val WINDOW = 1000
    private const val MAX_INLINE = 20_000
}
