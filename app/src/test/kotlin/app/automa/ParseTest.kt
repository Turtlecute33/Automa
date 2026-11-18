package app.automa

import app.automa.data.Secrets
import app.automa.env.Dns
import app.automa.env.Pins
import app.automa.env.Procs
import app.automa.env.Setup
import app.automa.env.Setup.Step
import app.automa.git.GitHub
import app.automa.git.Repos
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure parts: clone sources, gh output, setup plan, versions, release data, secret names and resolv.conf. */
class ParseTest {
    @Test
    fun cloneSources() {
        assertEquals(
            listOf("git", "clone", "--", "https://github.com/octo/demo.git", "/root/repos/demo"),
            Repos.source(" octo/demo.git ")?.program,
        )
        assertEquals("demo", Repos.source("https://codeberg.org/octo/demo.git")?.name)
        assertEquals(
            listOf("git", "clone", "--", "https://example.org/a/b/", "/root/repos/b"),
            Repos.source("https://example.org/a/b/")?.program,
        )
        // No other schemes, no passwords in links, no option-like or empty names.
        for (bad in listOf("http://example.org/a/b", "ssh://git@example.org/a/b", "https://user:pw@example.org/a/b",
            "file:///etc/passwd", "-o/x", "octo/..", "https://example.org/", "--upload-pack=x", "")) {
            assertNull(Repos.source(bad), bad)
        }
    }

    @Test
    fun ghDeviceCodeAndToken() {
        assertEquals("AB12-CD34", GitHub.code("! First copy your one-time code: AB12-CD34"))
        assertNull(GitHub.code("Open this URL to continue in your web browser: https://github.com/login/device"))
        assertTrue(GitHub.validToken("gho_" + "a".repeat(36)))
        assertFalse(GitHub.validToken("gho_abc def ghi jkl mno pqr"))
        assertFalse(GitHub.validToken("short"))
    }

    @Test
    fun ghRepoList() {
        val out = "warning: something\n" +
            """[{"nameWithOwner":"octo/a","isPrivate":true,"description":null},{"nameWithOwner":"octo/b","isPrivate":false,"description":"B"}]"""
        val remotes = GitHub.parseRemotes(out)
        assertEquals(listOf("octo/a", "octo/b"), remotes.map { it.name })
        assertEquals("", remotes[0].description)
        assertTrue(remotes[0].private)
    }

    @Test
    fun setupPlan() {
        assertEquals(listOf(Step.Rootfs, Step.Configure, Step.Packages, Step.Opencode), Setup.plan(false, null))
        assertEquals(listOf(Step.Configure, Step.Packages), Setup.plan(true, Pins.OPENCODE_VERSION))
        assertEquals(listOf(Step.Configure, Step.Packages, Step.Opencode), Setup.plan(true, "0.0.1"))
        assertEquals(listOf(Step.Configure, Step.Packages, Step.Opencode), Setup.plan(true, "damaged"))
        // The setup keeps an opencode from the in-app update.
        assertEquals(listOf(Step.Configure, Step.Packages), Setup.plan(true, "99.0.0"))
    }

    @Test
    fun versions() {
        assertTrue(Setup.newer("1.20.0", "1.19.6"))
        assertTrue(Setup.newer("1.19.10", "1.19.9"))
        assertTrue(Setup.newer("2.0.0", "1.99.99"))
        assertTrue(Setup.newer("1.0.0", null))
        assertFalse(Setup.newer("1.19.6", "1.19.6"))
        assertFalse(Setup.newer("1.19.5", "1.19.6"))
        assertFalse(Setup.newer("1.20.0-beta.1", "1.19.6"))
    }

    @Test
    fun opencodeRelease() {
        val sha = "45407388777dfc1894591c9727c169f202ad92c75757038950630a0fbb12ca97"
        fun json(tag: String = "v1.20.0", pre: Boolean = false, name: String = Pins.OPENCODE_ASSET, digest: String = "sha256:$sha") =
            """{"tag_name":"$tag","draft":false,"prerelease":$pre,"assets":[
              {"name":"opencode-linux-arm64.tar.gz","digest":"sha256:${"0".repeat(64)}"},
              {"name":"$name","digest":"$digest"}]}"""
        assertEquals(Setup.Release("1.20.0", sha), Setup.release(json()))
        assertNull(Setup.release(json(pre = true)))
        assertNull(Setup.release(json(tag = "v1.20.0-rc.1")))
        assertNull(Setup.release(json(tag = "../../evil")))
        assertNull(Setup.release(json(name = "other.tar.gz")))
        assertNull(Setup.release(json(digest = sha)))
        assertNull(Setup.release(json(digest = "sha256:XYZ")))
        assertNull(Setup.release("<html>rate limit</html>"))

        // The update follows the 2.x line of the pin, not "latest" (1.x): the newest stable 2.x release.
        val list = "[" + listOf(
            json(tag = "v2.4.0", pre = true), json(tag = "v1.19.6"), json(tag = "v2.3.1"), json(tag = "process-daemon-v0.6.0"),
            json(tag = "v2.3.0"), json(tag = "v1.99.0"), json(tag = "v3.0.0"),
        ).joinToString(",") + "]"
        assertEquals(Setup.Release("2.3.1", sha), Setup.newest(list, major = "2"))
        assertNull(Setup.newest("[]", major = "2"))
        assertNull(Setup.newest("{\"message\":\"rate limit\"}", major = "2"))
    }

    @Test
    fun secretNames() {
        assertTrue(Secrets.validName("ANTHROPIC_API_KEY"))
        for (bad in listOf("PATH", "HOME", "LD_PRELOAD", "PROOT_LOADER", "lower", "1ABC", "A-B", "")) {
            assertFalse(Secrets.validName(bad), bad)
        }
        assertFalse(Secrets.validValue(""))
        assertFalse(Secrets.validValue("a\u0000b"))
        assertTrue(Secrets.validValue("sk-test"))
    }

    @Test
    fun procStatParent() {
        assertEquals(42, Procs.parent("1234 (bash) S 42 1234 1234 0 -1"))
        assertEquals(7, Procs.parent("99 (a) b) (c) R 7 99 99"))
        assertNull(Procs.parent("garbage"))
    }

    @Test
    fun procTreeKeepsTracedOrphans() {
        // 10 is PRoot, 11 its child, 12 a grandchild. 13 left the tree (setsid and a double fork: parent 1), but PRoot
        // still traces it. 20 and 21 are another tree.
        val up = mapOf(
            10 to listOf(1), 11 to listOf(10, 10), 12 to listOf(11, 10), 13 to listOf(1, 10),
            20 to listOf(1), 21 to listOf(20, 20),
        )
        val tree = Procs.tree(10, up)
        assertEquals(10, tree.first())
        assertEquals(setOf(10, 11, 12, 13), tree.toSet())
        assertEquals(tree.size, tree.toSet().size)
        assertEquals(listOf(20, 21), Procs.tree(20, up))
        assertEquals(10, Procs.tracer("Name:\tsh\nState:\tS (sleeping)\nTracerPid:\t10\nUid:\t10123\n"))
        assertNull(Procs.tracer("Name:\tsh\nTracerPid:\t0\n"))
        assertNull(Procs.tracer(""))
    }

    @Test
    fun resolvConf() {
        val net = listOf("192.0.2.1", "192.0.2.2", "192.0.2.3", "192.0.2.4").map(InetAddress::getByName)
        val private = listOf(InetAddress.getByName("198.51.100.9"))
        assertEquals("# Written by Automa before each start.\nnameserver 198.51.100.9\n", Dns.conf(private, net))
        assertEquals(3, Dns.conf(emptyList(), net).lines().count { it.startsWith("nameserver") })
    }
}
