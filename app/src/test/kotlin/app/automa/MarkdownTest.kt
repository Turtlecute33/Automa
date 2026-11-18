package app.automa

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import app.automa.chat.Pipe
import app.automa.ui.chat.Block
import app.automa.ui.chat.Markdown
import app.automa.ui.chat.head
import app.automa.ui.chat.table
import app.automa.ui.chat.tail
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTest {
    private val styles = Markdown.Styles(SpanStyle(), TextLinkStyles(), onLink = {})

    @Test
    fun readsBlocks() {
        val text = """
            |# Title
            |Some *text*
            |on two lines.
            |
            |- one
            |  more of one
            |  - two
            |1. first
            |```kotlin
            |val x = 1
            |
            |```
            |> quoted
            |---
            || a | b |
            ||---|---|
            || 1 | 2 |
            """.trimMargin()
        assertEquals(
            listOf(
                Block.Heading(1, "Title"),
                Block.Paragraph("Some *text*\non two lines."),
                Block.Item(0, "-", "one\nmore of one"),
                Block.Item(1, "-", "two"),
                Block.Item(0, "1.", "first"),
                Block.Code("kotlin", "val x = 1\n"),
                Block.Quote("quoted"),
                Block.Rule,
                Block.Table(listOf(listOf("a", "b"), listOf("1", "2"))),
            ),
            Markdown.blocks(text),
        )
    }

    @Test
    fun openCodeFenceWhileStreaming() {
        assertEquals(listOf(Block.Paragraph("Look:"), Block.Code("", "partial")), Markdown.blocks("Look:\n```\npartial"))
    }

    @Test
    fun inlineMarks() {
        val s = Markdown.inline("a **b** *c* `d*e` snake_case_name \\*f\\* ~~g~~", styles)
        assertEquals("a b c d*e snake_case_name *f* g", s.text)
        assertEquals(4, s.spanStyles.size)
    }

    @Test
    fun onlyHttpsLinksAreActive() {
        val s = Markdown.inline("[ok](https://example.org/a) [no](javascript:alert(1)) see https://example.org/b.", styles)
        assertEquals("ok no see https://example.org/b.", s.text)
        val urls = s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Clickable).tag }
        assertEquals(listOf("https://example.org/a", "https://example.org/b"), urls)
        val blank = Markdown.inline("[login](https:///evil.example/login)", styles)
        assertTrue(blank.getLinkAnnotations(0, blank.length).isEmpty())
    }

    @Test
    fun aLinkLabelHoldsNoOtherLink() {
        val s = Markdown.inline("[https://github.com/login](https://attacker.example/login)", styles)
        val urls = s.getLinkAnnotations(0, s.length).map { (it.item as LinkAnnotation.Clickable).tag }
        assertEquals(listOf("https://attacker.example/login"), urls)
    }

    @Test
    fun craftedTextStaysFast() {
        // Review SEC-20: these took seconds with searches to the end of the text. Times are estimates (test JVM).
        val brackets = "[".repeat(19_000)
        val spaces = "# " + " ".repeat(100_000) + "x"
        val marks = "*a ".repeat(6_000) + "`".repeat(1000)
        val start = System.nanoTime()
        Markdown.inline(brackets, styles)
        Markdown.inline(marks, styles)
        Markdown.blocks(spaces)
        Markdown.blocks(brackets + "\n" + "|" + "-|".repeat(50_000))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1500, "took $ms ms")
    }

    @Test
    fun anUnclosedBacktickRunIsReadOnce() {
        // Each backtick of an unclosed run counted and copied the rest of the run: 20,000 gave 200 million steps.
        val ticks = "x" + "`".repeat(19_999)
        val start = System.nanoTime()
        repeat(3) { assertEquals(ticks, Markdown.inline(ticks, styles).text) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 100, "took $ms ms")
        // The unclosed run "``" stays plain; the single ticks after it still make a code span.
        val s = Markdown.inline("``a` and `b", styles)
        assertEquals("``aandb", s.text)
        assertEquals(1, s.spanStyles.size)
    }

    @Test
    fun aWideTableIsNotPadded() {
        // The padding makes each row as wide as the widest one: 59,999 characters became 150 million.
        val rows = listOf(listOf("x".repeat(29_000), "y")) + List(10_000) { listOf("", "z") }
        assertTrue(table(rows).length < 100_000)
        assertEquals("a    bb\nccc  d", table(listOf(listOf("a", "bb"), listOf("ccc", "d"))))
    }

    @Test
    fun toolRowCutsShowASign() {
        // The tool row showed the first 40 input lines and the first 60 output lines with no sign (review DS-L-85).
        val text = (1..100).joinToString("\n") { "line $it" } + "\n"
        assertEquals((1..40).joinToString("\n") { "line $it" } + "\n… 60 more lines", head(text, 40))
        assertEquals("… 40 earlier lines\n" + (41..100).joinToString("\n") { "line $it" }, tail(text, 60))
        assertEquals("a\nb", head("a\nb", 40))
        assertEquals("a\nb", tail("a\nb", 60))
    }

    @Test
    fun pipeSplitsLinesAndDropsLongOnes() {
        val long = "x".repeat(Pipe.MAX_LINE + 10)
        val input = "{\"a\":1}\r\n$long\n{\"b\":2}\nlast".toByteArray()
        val lines = ArrayList<String>()
        Pipe.lines(ByteArrayInputStream(input)) { lines += it }
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", "last"), lines)
        assertTrue(lines.none { it.length > Pipe.MAX_LINE })
    }
}
