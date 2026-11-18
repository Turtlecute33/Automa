# Device tests

Results of the live tests in `../LIVE-TESTS.md` on the owner's phone. All numbers are estimates.

| Test | Date | Build | Result |
|---|---|---|---|
| L-2 GitHub login (owner, Claude read the log) | 2026-11-17 | 0.2.0 release | Fails three times: GitHub accepts the code, but Android blocks the network of Automa about 5 s after the switch to the browser, and `gh` dies (research F-9). Fixed in 0.2.1 (foreground service during the login); a full login is not tested yet. |
| L-1 setup (Claude, adb) | 2026-11-17 | 0.1.0 release | Fails at "Install the packages": `proot error: ptrace(TRACEME): Operation not permitted`. GrapheneOS (Android 17) blocks native code debugging for Automa. The log also shows `RESTRICT_MEMORY_DCL` for Automa. 0.2.0 shows the fix text and "Open App info". |

## Emulator results (Claude, 2026-11-17)

AOSP API 35, arm64, 4 KB pages, emulator network.

| Check | Debug | Release (R8) |
|---|---|---|
| Cold start (`am start -W`, TotalTime) | 473 ms | 257 ms |
| Setup (download, extract, apk, opencode) | about 15 s | about 15 s |
| Clone `octocat/Hello-World` | about 2 s | — |
| opencode TUI: input, ↓, Esc, Ctrl+P | works | — |
| Shell: `opencode --version` | 1.19.6 | 1.19.6 |
| Session notification, foreground service | works | — |
| Files app root "Automa" | works | — |

Google APIs API 37 (Android 17), arm64, 16 KB pages (`ombra-api37`), debug build:

| Check | Result |
|---|---|
| Setup | about 18 s |
| `opencode debug paths` in a shell | works |
| opencode TUI (with the Zen provider off: "Connect a provider") | works |
| 0.2.0 Settings → Update, marker set to 1.19.5 (2026-11-17) | `apk upgrade -U`, then download of 1.19.6 with the GitHub digest; new file (new inode), no temporary folder left; `opencode --version` 1.19.6 |

VERIFY on the Pixel: the 16 KB page mode (developer option) on a real device.
