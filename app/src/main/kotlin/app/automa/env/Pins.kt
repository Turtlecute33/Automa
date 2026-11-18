package app.automa.env

/**
 * The pinned downloads and packages of the guest (DESIGN §3). A change here needs a new [SETUP_VERSION],
 * so that installed apps run the setup steps again.
 */
object Pins {
    const val SETUP_VERSION = 6

    const val ALPINE_URL =
        "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.4-aarch64.tar.gz"
    const val ALPINE_SHA256 = "4b14c18f7e90a063b61462ea1462a5732c154bf6adad594974fe6487cd98a8de"
    const val ALPINE_MAX_BYTES = 32L shl 20

    /**
     * The opencode of the setup: the 2.x line (decision of the owner, 2026-11-18). The in-app update can install a newer
     * one of the same line; the setup never goes back to this one.
     */
    const val OPENCODE_VERSION = "2.3.1"
    const val OPENCODE_SHA256 = "aafe8ae239689fdeff3ed88ce21fb6137beccd3d6587a5f7e11eb0b68b781698"
    const val OPENCODE_MAX_BYTES = 256L shl 20
    const val OPENCODE_ASSET = "opencode-linux-arm64-musl.tar.gz"

    /**
     * The recent opencode releases, for the update. Not `releases/latest`: the maintainers mark the 1.x line as
     * "latest" (research OC-1), and the update follows the line of [OPENCODE_VERSION].
     */
    const val OPENCODE_RELEASES = "https://api.github.com/repos/anomalyco/opencode/releases?per_page=30"

    fun opencodeUrl(version: String) = "https://github.com/anomalyco/opencode/releases/download/v$version/$OPENCODE_ASSET"

    /**
     * Alpine packages. libgcc and libstdc++ are for opencode and Claude Code (Bun programs), ripgrep also for Claude
     * Code, zstd unpacks the agent downloads; the rest is for the user.
     */
    val PACKAGES = listOf(
        "bash", "ca-certificates", "git", "github-cli", "less", "libgcc", "libstdc++", "ncurses-terminfo-base",
        "openssh-client-default", "ripgrep", "zstd",
    )

    /**
     * The coding agents that the user installs on demand (DESIGN §3). The install takes these versions; Update takes
     * the newest. The downloads are zstd files: the SHA-256 of the .zst, and for Claude Code also of the program.
     */
    const val CLAUDE_VERSION = "2.1.311"
    const val CLAUDE_ZST_SHA256 = "95f37a2808a40596f1fcc005f64eaaf7975d6a46c365876845d0909714c5a91b"
    const val CLAUDE_SHA256 = "172150e3f9ea07e8e2e2e29862d7d9a25f5c234d8e909e4063df237665bf09b9"
    const val CLAUDE_BASE = "https://downloads.claude.ai/claude-code-releases"
    const val CLAUDE_PLATFORM = "linux-arm64-musl"

    /** The newest stable Claude Code version (a plain text), for the update. */
    const val CLAUDE_STABLE = "$CLAUDE_BASE/stable"

    fun claudeUrl(version: String) = "$CLAUDE_BASE/$version/$CLAUDE_PLATFORM/claude.zst"
    fun claudeManifest(version: String) = "$CLAUDE_BASE/$version/manifest.json"
    fun claudeZstManifest(version: String) = "$CLAUDE_BASE/$version/manifest.zst.json"

    const val CODEX_VERSION = "0.169.0"
    const val CODEX_ZST_SHA256 = "dd8303912bfac97ef952a334f89eaafb2a2ff9f207abb7badce2b87ed34a7883"
    const val CODEX_ASSET = "codex-aarch64-unknown-linux-musl.zst"
    const val CODEX_LATEST = "https://api.github.com/repos/openai/codex/releases/latest"

    fun codexUrl(version: String) = "https://github.com/openai/codex/releases/download/rust-v$version/$CODEX_ASSET"

    /** Size limit of an agent download (a .zst file). */
    const val AGENT_MAX_BYTES = 256L shl 20
}
