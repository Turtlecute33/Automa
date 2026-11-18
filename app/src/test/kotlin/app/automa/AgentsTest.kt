package app.automa

import app.automa.env.Agents
import app.automa.env.Pins
import app.automa.env.Setup
import app.automa.term.Links
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The agent downloads (DESIGN §3) and the links from guest programs (DESIGN §5). Made-up data. */
class AgentsTest {
    private val sha = "a".repeat(64)

    @Test
    fun claudeManifest() {
        val json = """{"version": "9.9.9", "platforms": {
            "linux-arm64": {"checksum": "${"b".repeat(64)}"},
            "${Pins.CLAUDE_PLATFORM}": {"binary": "claude", "checksum": "$sha", "size": 1}}}"""
        assertEquals(sha, Agents.checksum(json))
        assertFailsWith<IOException> { Agents.checksum("""{"platforms": {"${Pins.CLAUDE_PLATFORM}": {"checksum": "XYZ"}}}""") }
        assertFailsWith<IOException> { Agents.checksum("""{"platforms": {}}""") }
        assertFailsWith<IOException> { Agents.checksum("<html>") }
    }

    @Test
    fun codexRelease() {
        fun json(tag: String, name: String = Pins.CODEX_ASSET) =
            """{"tag_name": "$tag", "prerelease": false, "assets": [{"name": "$name", "digest": "sha256:$sha"}]}"""
        assertEquals(Setup.Release("0.170.0", sha), Setup.release(json("rust-v0.170.0"), Pins.CODEX_ASSET, "rust-v"))
        assertNull(Setup.release(json("v0.170.0"), Pins.CODEX_ASSET, "rust-v"))
        assertNull(Setup.release(json("rust-v0.170.0-alpha.1"), Pins.CODEX_ASSET, "rust-v"))
        assertNull(Setup.release(json("rust-v0.170.0", "codex-x86_64-unknown-linux-musl.zst"), Pins.CODEX_ASSET, "rust-v"))
    }

    @Test
    fun links() {
        assertEquals("https://example.org/login?x=1", Links.parse("https://example.org/login?x=1\n"))
        assertNull(Links.parse("http://example.org/"))
        assertNull(Links.parse("file:///etc/passwd"))
        assertNull(Links.parse("https://"))
        assertNull(Links.parse("https://example.org/a b"))
        assertNull(Links.parse("https://example.org/\u001b[2J"))
        assertNull(Links.parse("https://example.org/\nhttps://other.example/"))
        assertNull(Links.parse("https://example.org/" + "x".repeat(5000)))
        // The host on the screen must be the host that the browser opens.
        assertNull(Links.parse("https:///evil.example/login"))
        assertNull(Links.parse("https://\\evil.example/login"))
        assertNull(Links.parse("https://github.com@evil.example/"))
        assertNull(Links.parse("https://\u0430pple.com/"))
        assertNull(Links.parse("https://evil.example\u202E/moc.buhtig"))
        assertEquals("https://xn--pple-43d.com:8443/a?b#c", Links.parse("https://xn--pple-43d.com:8443/a?b#c"))
    }
}
