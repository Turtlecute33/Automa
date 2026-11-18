# Feasibility: opencode on the phone, with no other app

Tests on 2026-11-17, emulator `megaglass-api35` (AOSP API 35, arm64, 4 KB pages), debug build with
targetSdk 37. Times are estimates from one run.

## F-1 PRoot runs from nativeLibraryDir with targetSdk 37

From targetSdk 29, SELinux does not let an app `execve()` a file in its data folder (W^X).
The native library folder of the APK is executable. Automa puts PRoot there as `libproot.so` and its
loader as `libproot-loader.so` (`PROOT_LOADER`), with legacy packaging, so that Android extracts them.
The loader maps the guest programs from app data with `mmap(PROT_EXEC)`, and SELinux allows that.

Result: in the `untrusted_app` domain, `proot -0 -r rootfs` runs Alpine 3.24.4 (`uid=0` in the guest).

VERIFY: `run-as` uses the `runas_app` domain, which has more permissions. Test only from the app
process (the debug `Probe` activity did this).

## F-2 The Termux PRoot build works with two string changes

`proot_5.1.107.102` (Termux) needs `libtalloc.so.2` and `libandroid-shmem.so`. The APK can hold only
`lib*.so` files. `scripts/fetch-proot.zsh` changes `libtalloc.so.2` to `libtalloc.so` in the
DT_NEEDED entry of proot and in the DT_SONAME entry of talloc. There is no symbol version entry for
talloc, so this is sufficient. `LD_LIBRARY_PATH=nativeLibraryDir` lets the linker find the two
libraries.

## F-3 Alpine packages install

`apk add libstdc++ libgcc git github-cli openssh-client-default ripgrep bash ca-certificates`:
41 packages, 75 MiB, about 3 s on the emulator. Versions: git 2.54.1, gh 2.97.0, ripgrep 15.1.0.

## F-4 opencode runs

`opencode-linux-arm64-musl` v1.19.6 (78 MB download, 219 MB binary): `opencode --version` about
0.2 s. `opencode serve --hostname 127.0.0.1` starts and refuses requests without
`OPENCODE_SERVER_PASSWORD` (401). Alpine does not have an opencode package, thus Automa downloads
the GitHub release asset and checks its SHA-256.

VERIFY: the TUI in the terminal view (F-7).

## F-5 DNS

musl reads `/etc/resolv.conf` and always uses port 53. The app cannot bind port 53
(`bind: Permission denied`), and the `-p` option of PRoot changes only `bind()`, not `sendto()`.
Thus a local relay through Android `DnsResolver` (Private DNS) is not possible without a changed
PRoot. Automa writes the DNS servers of the active network to `resolv.conf`: the validated Private
DNS servers when Private DNS is active, else the network DNS servers. The guest sends these queries
without encryption. With a VPN, the VPN DNS servers are used.

## F-6 opencode network flags (v1.19.6)

`packages/core/src/flag/flag.ts` and `packages/opencode/src/effect/runtime-flags.ts`:

- `OPENCODE_DISABLE_AUTOUPDATE`: no update check at start.
- `OPENCODE_DISABLE_MODELS_FETCH`: no request to models.dev; opencode uses its built-in model list.
- `OPENCODE_DISABLE_LSP_DOWNLOAD`: no download of language servers.
- `share: "disabled"` in the global config: no upload of sessions to opncd.ai.
- Default plugins are built in (not from npm), so they stay on.
- The web UI is built into the binary (`opencode-web-ui.gen.ts`). It goes to `app.opencode.ai`
  only when the built-in UI is missing. Automa does not use the web UI.

## F-7 Terminal

Termux `terminal-emulator` and `terminal-view` (tag v0.118.3) are Apache-2.0 (exception in the
GPL-3.0-only termux-app licence). About 8,300 lines of Java and one C file for the pseudo-terminal.

VERIFY: opencode TUI colours, mouse and key input in the terminal view, on the emulator.

## F-8 PRoot does not stop its tracees on a signal

Test on the emulator: PRoot ignores SIGTERM. After SIGKILL to PRoot, its tracee (`gh auth login`)
continues with parent PID 1. This build does not use `PTRACE_O_EXITKILL`. Thus `Process.destroy()`
and `TerminalSession.finishIfRunning()` (SIGKILL to the PRoot PID only) leave the guest programs
running.

`ProcessHandle` (`descendants()`, `Process.toHandle()`) is in the API 35 and 37 SDK stubs, but not in
the runtime: `NoClassDefFoundError` on the API 37 emulator. Kotlin 2.4.21 also does not resolve these
members. Do not trust the stubs for `java.*` APIs; test them on a device.

Automa reads the process tree from `/proc` (only the processes of the app user are visible) and sends
SIGKILL to each process (`env/Procs.kt`). `Env.run` puts `AUTOMA_RUN=<uuid>` in the PRoot command
line, so that a cancel finds its own PRoot process.

VERIFY: cancel the GitHub login sheet, close a session with opencode, and "Stop all": no `gh`,
`opencode` or `libproot.so` process stays (`ps -A` as the app user).

## F-9 Android blocks the network of a background app

Pixel 9 Pro XL, GrapheneOS, Android 17, 2026-11-17 (`dumpsys connectivity`: "Background firewall
chain enabled: true"). About 5 s after the user leaves Automa, the rule `10172-background-default`
blocks its network (`dumpsys netpolicy`). The state "last activity" (the app before the browser) is
also blocked. The guest programs have the same UID, so they lose the network too. The cached app
freezer can also freeze the app and its guest processes (same cgroup, `freezer_cutoff_adj=900`).

Effect on the GitHub login (0.2.0): the user opens the browser, and `gh auth login` dies about 5 s
after the block (three attempts). GitHub keeps the approval, but gh cannot get the token.

Fix: a foreground service keeps the network (`procState=FGS`, `effective=NONE`). `Env.foreground`
holds `SessionService` in the foreground while a setup, an update, a login or a clone runs. These
jobs start from a tap, so the foreground start is allowed.

Result on the phone (0.2.1): the login sheet open, Home, 12 s: `procState=FGS`, `effective=NONE`,
`gh` still runs, no freeze. A swipe down on the sheet stops `gh` and PRoot, and the service stops.

VERIFY: a full GitHub login with the browser (owner, L-2).

## F-10 Claude Code and Codex run under PRoot (2026-11-17, emulator API 37)

- Claude Code 2.1.311 `linux-arm64-musl` (a Bun program, 286 MB) runs: `claude --version`, the
  first-start screens and the login. It needs libgcc, libstdc++ and the Alpine ripgrep
  (`USE_BUILTIN_RIPGREP=0`). The download is `claude.zst` (101 MB); `manifest.json` and
  `manifest.zst.json` give the SHA-256 of the program and of the .zst file.
- Codex 0.169.0 `aarch64-unknown-linux-musl` (static Rust, 290 MB) runs `--version` and `exec`.
  Its TUI starts a shared background server (app-server daemon). The daemon needs the complete
  Codex package (`codex-package.json`), else Codex stops with "this CLI has no complete local
  package". There is no setting or variable to turn the daemon off, only `--no-daemon`, which also
  works before a subcommand (`codex --no-daemon exec --help`). Automa starts `codex --no-daemon`
  and adds a shell alias. The daemon would also stay after the session closes.
- The Codex sandbox (bubblewrap) cannot run under PRoot: `codex sandbox linux ls` gives "cannot
  establish app-server socket mount isolation". Automa's Codex config sets
  `sandbox_mode = "danger-full-access"`: the Android app sandbox is the limit, the same as for
  opencode and Claude Code.

VERIFY: a full login and a task with each agent on the Pixel (owner, L-8).

## F-11 Logins open the Android browser

The guest has no browser. Both agents use `$BROWSER` or `xdg-open` (Codex: the `webbrowser`
crate). Automa's `xdg-open` writes the link to `/run/automa/link` (a rename), a `FileObserver`
reads it, and the terminal shows the host and an Open button.

- Claude Code: the link goes to `claude.com/cai/oauth/authorize` with a redirect to
  `platform.claude.com/oauth/code/callback`, which shows a code. The user pastes the code into
  Claude Code ("Paste code here if prompted").
- Codex: the redirect goes to `http://127.0.0.1:1455/auth/callback`, a server in the guest. Android
  shares the loopback interface between apps: a connection from the shell UID to port 1455 works.
  The open session keeps the foreground service, so the server keeps the network (F-9).

VERIFY: the browser part of both logins on the Pixel (owner, L-8).
