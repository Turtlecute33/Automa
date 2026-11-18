package app.automa

import app.automa.env.Tar
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarTest {
    private val dir: File = Files.createTempDirectory("tar-test").toFile()
    private val outside: File = Files.createTempDirectory("tar-outside").toFile()

    @AfterTest
    fun clean() {
        dir.deleteRecursively()
        outside.deleteRecursively()
    }

    @Test
    fun extractsFilesFoldersLinksAndModes() {
        val tar = archive {
            entry("./", '5')
            entry("bin/", '5')
            entry("bin/tool", '0', "#!/bin/sh\n", mode = "0755")
            entry("etc/conf", '0', "a=1\n")
            entry("bin/sh", '2', link = "/bin/busybox")
            entry("etc/copy", '1', link = "etc/conf")
        }
        assertEquals(5, Tar.extract(ByteArrayInputStream(tar), dir))
        assertEquals("#!/bin/sh\n", File(dir, "bin/tool").readText())
        assertTrue(File(dir, "bin/tool").canExecute())
        assertEquals("/bin/busybox", Files.readSymbolicLink(File(dir, "bin/sh").toPath()).toString())
        assertEquals("a=1\n", File(dir, "etc/copy").readText())
    }

    @Test
    fun readsPaxAndGnuLongNames() {
        val long = "d/" + "x".repeat(150)
        val tar = archive {
            entry("././@PaxHeader", 'x', pax("path", long))
            entry("short", '0', "pax")
            entry("././@LongLink", 'L', "$long-gnu\u0000")
            entry("short2", '0', "gnu")
        }
        Tar.extract(ByteArrayInputStream(tar), dir)
        assertEquals("pax", File(dir, long).readText())
        assertEquals("gnu", File(dir, "$long-gnu").readText())
    }

    @Test
    fun refusesUnsafeNames() {
        for (name in listOf("../evil", "a/../../evil", "/etc/evil")) {
            assertFailsWith<IOException>(name) { Tar.extract(ByteArrayInputStream(archive { entry(name, '0', "x") }), dir) }
        }
        assertFalse(File(dir.parentFile, "evil").exists())
    }

    @Test
    fun refusesAWriteThroughASymbolicLink() {
        val tar = archive {
            entry("link", '2', link = outside.path)
            entry("link/evil", '0', "x")
        }
        assertFailsWith<IOException> { Tar.extract(ByteArrayInputStream(tar), dir) }
        assertFalse(File(outside, "evil").exists())
    }

    @Test
    fun refusesAFolderEntryOnALinkToOutside() {
        val tar = archive {
            entry("link", '2', link = outside.path)
            entry("link/", '5')
        }
        assertFailsWith<IOException> { Tar.extract(ByteArrayInputStream(tar), dir) }
    }

    @Test
    fun extractsOnlyTheNamedEntries() {
        val tar = archive {
            entry("opencode", '0', "bin", mode = "0755")
            entry("README", '0', "text")
        }
        assertEquals(1, Tar.extract(ByteArrayInputStream(tar), dir, only = setOf("opencode")))
        assertTrue(File(dir, "opencode").exists())
        assertFalse(File(dir, "README").exists())
    }

    @Test
    fun refusesADamagedHeader() {
        val tar = archive { entry("file", '0', "x") }
        tar[0] = 'g'.code.toByte()
        assertFailsWith<IOException> { Tar.extract(ByteArrayInputStream(tar), dir) }
    }

    private fun pax(key: String, value: String): String {
        val body = " $key=$value\n"
        var length = body.length + 1
        while ("$length$body".length != length) length = "$length$body".length
        return "$length$body"
    }

    private class Builder {
        val out = ByteArrayOutputStream()

        fun entry(name: String, type: Char, data: String = "", link: String = "", mode: String = "0644") {
            val bytes = data.toByteArray()
            val h = ByteArray(512)
            fun put(off: Int, len: Int, text: String) = text.toByteArray().copyInto(h, off, 0, minOf(len, text.length))
            put(0, 100, name)
            put(100, 8, "$mode\u0000".padStart(8, '0'))
            put(108, 8, "0000000\u0000")
            put(116, 8, "0000000\u0000")
            put(124, 12, bytes.size.toString(8).padStart(11, '0') + "\u0000")
            put(136, 12, "00000000000\u0000")
            h[156] = type.code.toByte()
            put(157, 100, link)
            put(257, 6, "ustar\u0000")
            put(263, 2, "00")
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            put(148, 8, sum.toString(8).padStart(6, '0') + "\u0000 ")
            out.write(h)
            out.write(bytes)
            out.write(ByteArray((512 - bytes.size % 512) % 512))
        }
    }

    private fun archive(block: Builder.() -> Unit): ByteArray =
        Builder().apply(block).out.apply { write(ByteArray(1024)) }.toByteArray()
}
