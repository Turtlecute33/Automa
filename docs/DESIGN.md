# Automa design

The single source of truth for Automa. Findings with IDs are in `research/`.

## 1. Goals, in order

1. Security and privacy. No telemetry. Secrets encrypted at rest. Each network host is in the README.
2. The real opencode runs on the phone, with no other app (no Termux, no remote computer).
3. Minimal: a small number of screens, a small APK, few dependencies.

## 2. Fixed decisions

- Name Automa, package `app.automa`. Android 15+ (minSdk 35), arm64 only.
- Each agent has two UIs: a native chat (§13) and its own TUI in a terminal view. The chat came
  after the TUI (decision of the owner, 2026-11-18: "native chat, all agents", with every
  permission and multiple-choice request of the agent as native controls). No WebView.
- The guest system is Alpine Linux (musl, small). PRoot runs it (research F-1, F-2).
- GitHub login uses the device flow of the GitHub CLI (`gh`) inside the guest. The APK has no
  OAuth client ID.
- Repositories are in `/root/repos/<name>` in the guest.
- The PRoot binaries are in git (`app/src/main/jniLibs`), so that a build does not need the Termux
  repository. `scripts/fetch-proot.zsh` makes them again from pinned packages.
- `owner/repo` clones with `git clone https://github.com/owner/repo.git`, not `gh repo clone`:
  gh needs a login also for public repositories (exit code 4).
- The AI provider is Tokens for Friends (TFF), an OpenAI-compatible gateway. The default opencode
  config enables only TFF (decision of the owner, 2026-11-17). §8.
- Claude Code and Codex are optional agents next to opencode. The user installs each one on
  demand (Settings → Coding agents) and logs in with an own account inside the agent (Claude
  subscription, ChatGPT). They do not use TFF. Automa has no model setting for them: the user
  selects the model with `/model` (decision of the owner, 2026-11-17).
- The look is the look of Varco: Catppuccin Mocha colours, JetBrains Mono in the terminal, a
  full-screen terminal with Varco's extra keys. The app is always dark (decision of the owner,
  2026-11-17). §5.

## 3. Guest environment

- Programs in the APK (`jniLibs/arm64-v8a`, legacy packaging, F-1): `libproot.so`,
  `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so`, from `scripts/fetch-proot.zsh`.
- Files: `noBackupFilesDir/rootfs` (the guest root), `cacheDir/proot` (`PROOT_TMP_DIR`).
- Command: `libproot.so --kill-on-exit --link2symlink -L -0 -r rootfs -b /dev -b /proc -b /sys
  -w <dir> /usr/bin/env -u LD_LIBRARY_PATH -u PROOT_LOADER -u PROOT_TMP_DIR <program>`.
  The guest environment (and the secrets, §7) goes in the process environment, not in argv, so
  that no secret is in `/proc/<pid>/cmdline`.
- Setup (`env/Setup.kt`), a list of steps. A mutex stops a second run (a double tap). Each step can run again; a failed setup continues
  at the failed step:
  1. Download the Alpine minirootfs (pinned URL and SHA-256 in `env/Pins.kt`) and extract it.
  2. Write the Automa files (`/etc/profile.d/automa.sh`, `/etc/gitconfig`, the opencode config,
     `cli.json` and `service.json`), set the sticky bit of `/tmp` and `/var/tmp` (mode 1777: the tar reader drops it, and
     Claude Code refuses a world-writable `/tmp` without it), and get the TFF model list when
     `TFF_API_KEY` is set (§8). A TFF error does not stop the setup.
  3. `apk upgrade` and `apk add` (Alpine signs its packages; the minirootfs has the keys).
  4. Download opencode (pinned version and SHA-256) to `/usr/local/bin/opencode`. The pin is on the
     2.x line (2.3.1, decision of the owner, 2026-11-18; research OC-1, OC-17).
  5. Write `/etc/automa-setup` with the setup version.
  A new app version with a new setup version runs steps 2–5 again (for example a new opencode).
  Step 4 runs only when opencode is missing or older than the pin, so a setup does not undo an update.
- Update (Settings → Update, only on a ready guest, under the setup mutex, only when the user taps it):
  `apk upgrade -U`, then the newest stable opencode release of the line of the pin (2.x) from
  `api.github.com/repos/anomalyco/opencode/releases?per_page=30`. Not `releases/latest`: the
  maintainers mark the 1.x line as "latest" (OC-1). No drafts, no pre-releases; the version must be
  plain `x.y.z` and newer than the installed one (no downgrade). The SHA-256 is the `digest` of the
  `opencode-linux-arm64-musl.tar.gz` asset in the same answer (review SEC-9). opencode extracts to a
  temporary folder and then replaces `/usr/local/bin/opencode` with a rename, so a failure keeps the
  old program and open sessions continue.
- Agents (`env/Agents.kt`, research F-10), on demand, under the setup mutex, only on a ready guest:
  - Install: the pinned version in `env/Pins.kt` (Claude Code 2.1.311, Codex 0.169.0). The
    download is a zstd file with a pinned SHA-256 (`downloads.claude.ai`, GitHub releases).
    `zstd` (an Alpine package) unpacks it in the guest; for Claude Code, the SHA-256 of the
    program must also match. A rename puts it in `/usr/local/bin`, with a version file
    `/etc/automa-<command>`.
  - Update (Settings → Update): each installed agent goes to its newest stable release, with the
    SHA-256 from the publisher (Claude Code: `stable` and its manifests; Codex: the `digest` of
    the GitHub asset), as for opencode (SEC-9).
  - Remove: deletes the program and the version file. The login and the settings stay in
    `~/.claude` and `~/.codex`.
- PRoot needs `ptrace`. When the system blocks it (GrapheneOS "Native code debugging" off), PRoot
  writes `ptrace(TRACEME): Operation not permitted`. The setup and the update show what to change,
  and the setup panel opens App info.
- The tar reader refuses absolute paths, `..`, and writes through a symbolic link.
- Reset renames `rootfs` to `rootfs.old` (under the setup mutex) and then deletes it. `check()`
  deletes a leftover `rootfs.old` (review SEC-7).
- DNS: `/etc/resolv.conf` gets the DNS servers before each start (F-5). In strict Private DNS mode,
  the addresses of the Private DNS host (from the system resolver, because the validated server
  list is not a public API). Else the DNS servers of the active network.

## 4. Sessions

- A foreground service (`specialUse`) holds the terminal sessions, so that opencode continues when
  the screen is off. It holds a partial wake lock while a session runs. The notification has a
  Stop action. The service stops when the last session ends.
- The same service holds the jobs that the user starts with a tap: setup, update, GitHub login and
  clone (`Env.foreground`). From Android 15 the system blocks the network of a background app a few
  seconds after the user leaves it, and the guest programs have the same UID (research F-9). With
  the service, the GitHub login continues while the user is in the browser. The notification then
  says "Automa is working" and has no Stop action. The service stops after the last job and the
  last session.
- A session runs `bash -l`, or an agent in a repository and then `bash -l` in the same folder:
  `opencode` (a tap on the repository), or `claude` or `codex --no-daemon` (the repository menu,
  when installed).
- A stop (close, "Stop all", a cancel of `Env.run`) sends SIGKILL to each process of the tree, read
  from `/proc` (`env/Procs.kt`, research F-8). PRoot itself does not stop its programs.
  `Env.run` marks its PRoot command line with `AUTOMA_RUN=<uuid>` to find its process.

## 5. Terminal

- `:terminal` is a copy of Termux `terminal-emulator` and `terminal-view` (Apache-2.0, F-7,
  `terminal/README.md`).
- Look (`term/Look.kt`, the same as Varco): Catppuccin Mocha colours for the terminal engine and
  JetBrains Mono (OFL-1.1, read once off the main thread). The app theme has the same colours
  (always dark), and opencode gets the `catppuccin` theme in `~/.config/opencode/cli.json` (written
  only on a new guest; opencode 2.x moves an old `tui.json` there itself).
- The terminal is full screen, with no top bar. Two rows of extra keys, copied from Varco:
  `[● session ▾] ESC TAB CTRL` and `ALT ⎘ / | -`, with the arrows on the right. A long press on
  `/`, `|` and `-` sends `\`, `&` and `_`. Arrows repeat while held. Ctrl and Alt: a tap is for the
  next key, a long press locks. The session chip opens a sheet: Home, Share, Close, and the other
  sessions.
- No Android font has ⏴⏵⏶⏷ (U+23F4–U+23F7; Claude Code shows `⏵⏵` for its mode). The terminal engine
  writes ◀▶▲▼ of JetBrains Mono for them.
- Share: the selected text, or else all the text of the session, goes to the Android share sheet.
- Links (research F-11): `$BROWSER` and `/usr/local/bin/xdg-open` write an https link to
  `/run/automa/link` (with a rename). `term/Links` watches the folder with a `FileObserver`
  (started only on a ready guest). The terminal shows "Open the link in the browser?" with the
  host; the browser opens only after a tap. Only https, at most 4096 characters, no spaces or
  control characters. The agent logins use it.
- Files: `files/HomeProvider` is a documents provider for the guest home folder `/root`, so that the
  system Files app and document pickers can open, share and import files. Only the system can bind
  (MANAGE_DOCUMENTS). No path leaves the home folder, and it does not follow links. With the app lock
  on, it has no root.

## 6. GitHub

- Login: `gh auth login --web` with `GH_CONFIG_DIR` in a temporary folder. Automa shows the
  one-time code and opens `https://github.com/login/device` in the browser. After the login,
  Automa reads the token with `gh auth token` and the account with `gh api user` (same folder).
  Then, with no cancel between them, it keeps the token in the vault (§7), the login name and the
  git identity. The folder is deleted after the login, at the next login and at each app start.
- The flow runs in the app scope, so the app lock does not stop it. Only a dismiss of the sheet
  cancels it (review SEC-4).
- Each session gets `GH_TOKEN`. `/etc/gitconfig` uses `gh auth git-credential` for github.com,
  so `git push` works. The commit identity is the GitHub name and the no-reply address
  `<id>+<login>@users.noreply.github.com`, so the private e-mail address does not go into commits.
- Logout deletes the token from the vault. Open sessions and chats keep `GH_TOKEN` in their
  environment until they close. The token stays valid on GitHub until the user
  revokes it there (Settings → Applications).

- Clone: `owner/repo`, an https link, or account repositories. The user can select more than one
  account repository; they clone one after the other, and an error does not stop the others.
  Repositories that are on the phone show "Cloned" and cannot be selected.

## 7. Secrets

- `Vault`: one AES-256-GCM key in AndroidKeyStore (StrongBox first, then TEE), copied from Fonte.
- `Secrets`: a map of environment variables (for example `TFF_API_KEY`), encrypted as one
  JSON object in `noBackupFilesDir/secrets.bin`. The GitHub token is `GH_TOKEN` in this map.
- The keys screen has `FLAG_SECURE`, and value fields have no personalised learning.
- `put` and `remove` throw when the file cannot be opened, so that a short Keystore error does not
  erase the secrets (review SEC-6). `all()` gives no secrets in that case.
- Limit: the guest programs can read the secrets, because they are in their environment. opencode
  `/connect` keeps provider keys in a plain file in the guest (`~/.local/share/opencode`).

## 8. Network

- The app itself connects only for the setup downloads: `dl-cdn.alpinelinux.org` and `github.com`
  (with `release-assets.githubusercontent.com`), for an update that the user starts:
  `api.github.com` (release data) and the same hosts, for the TFF model list (below), and for an
  agent install or update that the user starts: `downloads.claude.ai` (Claude Code), `github.com`
  and `api.github.com` (Codex). No automatic update check. HTTPS only, system CAs only.
- TFF (`env/Tff.kt`). opencode 1.19.6 could not read the model list of a custom provider, so Automa
  gets it: `GET https://gateway.trytokenfactory.dev/v1/models` with `Authorization: Bearer
  $TFF_API_KEY`, only when the key is in `Secrets`, and only when the user saves the key, taps
  reload on the keys screen, or the setup runs. No redirects (the key goes to no other host), at
  most 1 MiB and 300 models. A model id has only `[A-Za-z0-9._:/@+-]`, 1–128 characters, because
  it goes into the config and onto the terminal. Automa writes the provider to the opencode
  managed config `/etc/opencode/opencode.json` (opencode reads it last): the built-in
  `@ai-sdk/openai-compatible` SDK (no npm download), the base URL, `"env": ["TFF_API_KEY"]` and
  the models. The write is under the setup mutex and only on a ready guest (SEC-7).
- The default `~/.config/opencode/opencode.json` has `"enabled_providers": ["tff"]`, so the model
  list of opencode shows only TFF. The setup writes it when it is missing or when it is still the
  default of setup version 2; a file that the user changed stays.
- The guest programs connect to the hosts that the user asks for: `dl-cdn.alpinelinux.org`
  (apk), `github.com` and `api.github.com` (git, gh), and the AI provider that the user selects in
  opencode. They use the Alpine CA bundle, not the Android network security config.
- opencode privacy defaults (F-6, checked in the 2.3.1 source): `OPENCODE_DISABLE_AUTOUPDATE=1`
  (Automa updates opencode, §3), `OPENCODE_DISABLE_MODELS_FETCH=1`, `OPENCODE_DISABLE_FREE_MODELS=1`,
  `"share": "disabled"`,
  `"enabled_providers": ["tff"]`, `"disabled_providers": ["opencode"]`. With no provider, opencode
  selects the free model of OpenCode Zen, and the first prompt goes to opencode.ai. With Zen off,
  opencode asks for a provider first. The user can change all of them in the guest. opencode 2.x
  reads the 1.x keys (`autoupdate` becomes `update`; `enabled_providers` becomes a policy that denies
  all other providers).
- opencode 2.x background server: by default, `opencode` starts a detached server that other
  sessions share (127.0.0.1:49374, its password in a state file), and that does not end with the
  session. The setup writes `~/.config/opencode/service.json` `{"disabled": true}` (only when it is
  missing), so that each start has a private server, a child of the session that ends with it.
- `OPENCODE_DISABLE_PROJECT_CONFIG=1`: opencode does not load `opencode.json` and `.opencode/`
  (plugins, MCP commands) from the project, so that a cloned repository cannot run code when it
  opens (review SEC-5). The user can unset it in `~/.profile`.
- gh: `GH_NO_UPDATE_NOTIFIER=1`, `GH_NO_EXTENSION_UPDATE_NOTIFIER=1`.
- Claude Code: `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1` (no telemetry, error reports,
  feedback, release notes, feature flags or self-update). It connects to Anthropic for the login
  and the prompts.
- Codex: `~/.codex/config.toml` (written at the install, only when it is missing):
  `check_for_update_on_startup = false`, `[analytics] enabled = false`, `[feedback] enabled =
  false`, and `sandbox_mode = "danger-full-access"` (its sandbox cannot run under PRoot, F-10).
  Codex asks before it runs commands only in an untrusted folder or for dangerous commands
  (research CX-7). It connects to OpenAI for the login and the prompts.
- The agents keep their login tokens in plain files in the guest (`~/.claude/.credentials.json`,
  `~/.codex/auth.json`), the same as `/connect` in opencode (§7).
- Workspace trust: in the terminal, Claude Code and Codex ask before they trust a folder; a cloned
  repository's `.claude/` or `.codex/` settings apply only after that. The chat skips these
  questions in the agents, so it has its own trust rules (§13).

## 9. Security checklist

- `allowBackup="false"`, data extraction rules exclude all, `memtagMode="sync"`.
- Only the launcher activity is exported. The service is not exported.
- Network security config: no cleartext, system CAs only, certificate transparency (Android 16 and
  later).
- Downloads: HTTPS only, SHA-256 (pinned for the setup, from the GitHub release data for an
  update), size limit.
- Release logs: no messages, only exception classes.
- App lock (optional): `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`. No content before the settings are
  read (SEC-3). The documents provider has no root and refuses new access while the lock is on
  (SEC-2); a file that is open already stays open.
- No recents preview. The terminal has no `FLAG_SECURE`, so that the user can take screenshots.
- "Stop all" in the notification needs an unlocked phone.
- Only the session on the screen can set the clipboard (OSC 52 and copies, SEC-8).
- Review: `review/privacy-security.md`.

## 10. Dependencies

| Dependency | Reason |
|---|---|
| kotlinx-coroutines | Background work and state. |
| Compose BOM, material3, activity-compose, lifecycle | UI. |
| navigation3 | Back stack and predictive back. |
| profileinstaller | Faster first start of a sideloaded app. |
| JetBrains Mono (font file, OFL-1.1, about 200 KB) | The terminal font of Varco. `docs/licences/`. |
| `:terminal` (vendored, Apache-2.0) | Terminal emulator and view. A terminal is too large to write again. |
| PRoot, talloc, libandroid-shmem (binaries, GPL-2.0, LGPL-3.0, BSD-3) | Run the guest system. |

## 11. Tests

- Unit tests: tar reader (path checks), setup step state machine, version order, release data
  parser, gh output parser, secret names, resolv.conf text, TFF model list (untrusted ids) and the
  managed config, the Claude Code manifest, the Codex release data, and the link check.
- Emulator: setup, a shell session, the opencode TUI.
- Emulator: the install, Remove and install again of both agents, `claude --version`,
  `codex --version`, their first-start screens, and the link bar for both logins.
- Unit tests (chat, §13): the three drivers with scripted agent messages (streaming, tool cards and
  diffs, each permission answer, questions with one and more choices and an own answer, plan
  approval, cancelled requests, the login state), the Claude trust store, forms, the Markdown
  reader (only https links are active), the line splitter and `clean()`.
- Emulator (Android 17, API 37), version 0.5.0: the chat starts under PRoot for the three agents
  with no login (Claude Code `initialize` with its models, the Codex trust card and `thread/start`,
  opencode `session/new`), the login and "no model" cards, and the opencode loopback port answers
  401 to another UID.
- Emulator (Android 17, API 37), version 0.6.0: the setup update to opencode 2.3.1 under PRoot
  (25 s, estimate); the TUI with the `catppuccin` theme (opencode moved `tui.json` to `cli.json`);
  its private server is a child of the session (no detached service) and answers 401 to another UID;
  the ACP chat shows the "no model" card; no process stays after a close; Settings → Update reads
  the release list and keeps 2.3.1.
- Not tested by Claude: GitHub login, push, AI providers and the agent logins (live tests for the owner,
  `docs/LIVE-TESTS.md`), each chat card with a real agent turn (L-10), behaviour on the Pixel.

## 12. Non-goals

- iOS (no fork or exec for apps). Other ABIs than arm64. A local AI model.
- A WebView. A chat client that uses HTTP or an extra program in the guest (§13).
- Access to shared storage (no storage permission). Files move with the share sheet and the
  documents provider.

## 13. Chat

The native chat (decision of the owner, 2026-11-18). Research: `research/chat-claude.md` (CC),
`research/chat-codex.md` (CX), `research/chat-opencode.md` (OC).

- A tap on a repository opens a sheet. A tap on an agent opens a chat; its terminal button opens
  the TUI of the agent (§5). The sheet also has Shell and Delete.
- Transport (`chat/Pipe`): the agent runs in the guest through `bash -l` (so that
  `/etc/profile.d/automa.sh` applies), with stdin and stdout pipes and one JSON object for each line.
  No PTY. Lines that are not JSON are ignored; a line over 8 MiB is dropped; stderr keeps its last
  40 lines for the end text. A close: end of stdin, then after 1.5 s SIGKILL of the process tree
  (F-8).
- Protocols, one driver each (`chat/*Driver.kt`):
  - Claude Code: `claude -p` with stream-json in and out and the control protocol of the Agent SDK
    (`--permission-prompt-tool stdio`), `--session-id <new uuid>` (CC-2, CC-3).
  - Codex: `codex app-server --disable plugins` (JSON-RPC, the v2 API with `experimentalApi`,
    CX-1 to CX-3). `--disable plugins` stops the plugin request and download at the start (CX-5).
  - opencode: `opencode acp` (Agent Client Protocol, OC-3).
- One model for the three agents (`chat/Model.kt`): rows (user, text, thinking, tool, task list,
  note), requests that wait for the user (permission, questions, plan), the model, mode and effort
  lists, the slash commands. The protocol code has no Android types, so that unit tests drive it
  with scripted messages.
- Requests (each one is a card above the composer; the next ones wait):

  | Agent | Request | Card |
  |---|---|---|
  | Claude Code | `can_use_tool` (CC-13, CC-14) | Yes; one row for each "do not ask again" suggestion; No, with an optional note for Claude |
  | Claude Code | AskUserQuestion (CC-16) | 1 to 4 questions, one or more choices, an own answer |
  | Claude Code | ExitPlanMode (CC-17) | Yes and accept edits; Yes and ask before edits; No, keep planning (with a note) |
  | Codex | command approval (CX-13) | the decisions of Codex in its order (Yes; do not ask again for the command or prefix; host rules; No and continue; No and stop) |
  | Codex | file change approval (CX-14) | Yes; Yes for these files; No and continue; No and stop, with the diff |
  | Codex | permissions, MCP tool approval and MCP forms (CX-15) | turn, session or no; allow, session, always or cancel; questions |
  | Codex | `requestUserInput` (CX-16) | questions with one choice and an own answer (`user_note:`) |
  | opencode | `session/request_permission` (OC-8) | the options of opencode (once, always, reject) |
  | opencode | `elicitation/create` (OC-9, opencode 2.x) | questions with one or more choices and an own answer |

  A plan of Codex or opencode in Plan mode ends with a local plan card: "Yes" changes to the
  default mode and sends "Implement the plan." (Codex) or "Do the plan." (opencode). A Codex "No"
  with a note: the note goes to Codex at once ("No and continue", a steer) or after the turn ("No
  and stop"). opencode 1.x has no question tool in ACP (OC-9): the model asks in its text. Skip
  or Stop always answers "no" or "cancel". A card of a request that the agent cancelled closes.
- Stop: Claude `interrupt`, Codex `turn/interrupt`, opencode `session/cancel` and "cancelled" for
  its open requests.
- Modes: Claude `default`, `acceptEdits`, `plan` and `auto` (when the model has it). No
  `bypassPermissions`: Claude Code refuses it as root (CC-19). Codex: "Ask before changes"
  (`approvalPolicy: untrusted`, each command and file change asks), "Ask only for rm -f"
  (`on-request`), "Plan" (collaboration mode `plan`); sent with each turn. opencode: its agents
  `build` and `plan`; by default only `.env` reads ask (OC-8). The Codex mode "Ask only for rm -f"
  (`on-request`) asks almost never (CX-7, review SEC-23).
- Trust. A cloned repository must not start programs without a decision of the user:
  - Claude Code: `-p` skips the trust question (CC-25). The chat uses the trust store of Claude
    Code (`~/.claude.json`: the folder or a parent folder). An untrusted folder gets
    `--setting-sources user --strict-mcp-config` (no project CLAUDE.md, hooks or MCP servers) and a
    note. The user trusts a folder in the TUI.
  - Codex: `thread/start` trusts a git repository with no question (CX-6). Before the start, the
    chat asks (Trust, Do not trust) and adds `[projects."<folder>"] trust_level` to
    `~/.codex/config.toml`.
  - opencode: `OPENCODE_DISABLE_PROJECT_CONFIG=1` (§8).
- Network: `opencode acp` opens an HTTP server on 127.0.0.1, on a random port, for its own client
  (OC-2): 1.x in the same process, 2.x in a child `opencode serve` with its own random password. For
  1.x, the chat sets a new random `OPENCODE_SERVER_PASSWORD` (32 bytes) for each start, in the
  environment. Emulator test: another UID gets 401. The opencode TUI has its own private server
  (§8). The chat itself opens no port and makes no request.
- Login: the chat does not log in. Claude Code: `claude auth status` at the start and the error
  texts (CC-5). Codex: `account/read` (CX-4). opencode: no model or error -32000. The card opens the
  TUI (the login there uses the link bar, §5), or the API keys screen for opencode.
- Untrusted text: `clean()` removes terminal escape sequences and control characters from all agent
  and repository text, and shows bidi and zero-width characters as `<U+XXXX>` (SEC-17). Markdown
  becomes styled text only; only https links are active, and a tap shows the real host before the
  browser opens (SEC-21). A permission card wraps its text and shows where it cuts. Limits (SEC-20):
  tool output 20,000 characters, card input 20,000, one row 200,000, a chat 2,000 rows, Markdown
  60,000 (longer text stays plain), a message 100,000. A Write diff reads the old file only in the
  chat folder (SEC-22).
- Cards: a new card ignores taps for 400 ms and answers once; with Claude `default_to_no`, "No"
  comes first (SEC-18). The login check of Claude Code runs in `/root` (SEC-15). Reset stops the
  chats and waits for their processes before it deletes the guest (SEC-16).
- The composer and the answer fields have no personalised learning (§7).
- Background: open chats count in the session notification like terminal sessions (§4); "Stop all"
  closes them. User actions run in the app scope, so a turn continues when the user leaves the
  screen.
- The terminal button of a chat opens the TUI on the same session after the first turn
  (`claude --resume`, `codex resume`, `opencode --session`).
- Not in this version: a list of old chats (a Claude resume does not send the old messages, CC-21),
  images, the login in the chat, a message while a turn runs.

