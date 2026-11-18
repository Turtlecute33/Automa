package app.automa

import app.automa.env.Tree
import app.automa.git.Repo
import app.automa.git.Repos
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** File work on paths that a guest program can change: FIFOs, links, large files, folders with no permissions. */
class TreeTest {
    private val dir: File = Files.createTempDirectory("tree-test").toFile()

    @AfterTest
    fun clean() {
        dir.walkTopDown().forEach { it.setReadable(true); it.setWritable(true); it.setExecutable(true) }
        dir.deleteRecursively()
    }

    @Test
    fun readsASmallRegularFile() {
        val file = File(dir, "marker").apply { writeText("6\n") }
        assertEquals("6\n", Tree.readSmall(file))
        assertNull(Tree.readSmall(File(dir, "missing")))
    }

    @Test
    fun refusesALargeFileALinkAndAFolder() {
        val big = File(dir, "big").apply { writeBytes(ByteArray(2000)) }
        assertNull(Tree.readSmall(big, max = 1000))
        val link = File(dir, "link")
        Files.createSymbolicLink(link.toPath(), File(dir, "marker").apply { writeText("6\n") }.toPath())
        assertNull(Tree.readSmall(link))
        assertNull(Tree.readSmall(dir))
    }

    @Test
    fun doesNotWaitForAFifo() {
        val fifo = File(dir, "fifo")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.path).start().waitFor())
        var result: String? = "not read"
        val reader = thread { result = Tree.readSmall(fifo) }
        reader.join(5000)
        // Unblock a reader that waits in open(), so that the test run can end.
        if (reader.isAlive) fifo.outputStream().close()
        assertFalse(reader.isAlive)
        assertNull(result)
    }

    @Test
    fun repositoryListSkipsLinks() {
        val repos = File(dir, "repos")
        File(repos, "demo/.git").mkdirs()
        File(repos, "demo/.git/HEAD").writeText("ref: refs/heads/main\n")
        File(repos, "plain").mkdirs()
        // A guest program can link a folder outside the repositories (for example the home folder) into the list.
        File(dir, "home/.git").mkdirs()
        Files.createSymbolicLink(File(repos, "wide").toPath(), File(dir, "home").toPath())
        assertEquals(listOf(Repo("demo", "main")), Repos.scan(repos))
    }

    @Test
    fun deletesFoldersWithNoPermissions() {
        val top = File(dir, "top")
        File(top, "a/b").mkdirs()
        File(top, "a/b/file").writeText("x")
        File(top, "a/c").mkdirs()
        val none = PosixFilePermissions.fromString("---------")
        Files.setPosixFilePermissions(File(top, "a/b").toPath(), none)
        Files.setPosixFilePermissions(File(top, "a").toPath(), none)
        Files.setPosixFilePermissions(top.toPath(), none)
        Tree.delete(top)
        assertFalse(top.exists())
    }
}
