# Review: privacy and security

2026-11-17. An independent review of `app/src/main` and the Automa changes in `terminal/`. Each
finding was checked in the code before the fix. Status: `fixed`, `accepted` (with a reason).

## SEC-1 [High] [fixed] Documents provider: `..` and links leave a tree grant

PROBLEM: `isChildDocument` compared the ID strings only, and `file()` accepted `..`. An app with a
tree grant on `home/repos/a` could read `home/repos/a/../../.local/share/opencode/auth.json`, write
`~/.profile`, or delete `home/repos/a/../../` (all of home).
FIX: `file()` refuses empty, `.` and `..` parts. `isChildDocument` compares the canonical files.
`deleteDocument` checks the ID with `file()` first.
VERIFY: a test app with a tree on `home/repos/a`: open and delete with these IDs fail.

## SEC-2 [Medium] [fixed] App lock: grants from before the lock still work

PROBLEM: the lock hid only the root. Kept tree or document grants could read and write home.
FIX: `file()` refuses all access while the lock is on.
VERIFY: grant a tree, turn on the lock, read with the kept URI: it fails.

## SEC-3 [Medium] [fixed] App lock: cold start race

PROBLEM: the splash hold stops the drawing, not the composition. The first composition could read the
lock setting before it was loaded, set `locked = false`, and show the app with no lock.
FIX: `MainActivity` shows no content until `app.ready`.
VERIFY: a debug sleep in `Prefs.load`, lock on, cold start: the lock screen shows.

## SEC-4 [Medium] [fixed] GitHub login: a token with no login name

PROBLEM: the token was saved before `gh api user`. A failure or a cancel after that left `GH_TOKEN`
in the secrets with no login name, so no screen could remove it. The re-lock after 60 s in the
browser removed the sheet and cancelled the flow.
FIX: the account is read with the temporary gh config first. Then the token, the name and the git
identity are saved with no cancel between them. The flow runs in the app scope; only a dismiss of
the sheet cancels it.
VERIFY: cut the network after the approval: `GH_TOKEN` is not in the secrets.

## SEC-5 [Medium] [fixed] A cloned repository can run code when opencode starts

PROBLEM: opencode loads `opencode.json` and `.opencode/` (plugins, MCP commands) from the project. One
tap on an untrusted clone could run its code with `GH_TOKEN` and the API keys.
FIX: `OPENCODE_DISABLE_PROJECT_CONFIG=1` in `/etc/profile.d/automa.sh` (setup version 2). The user can
unset it in `~/.profile` for trusted projects. `AGENTS.md` is not affected.
VERIFY: a repository with a project plugin that writes a marker file: no marker after a tap.

## SEC-6 [Low] [fixed] Secrets: a short Keystore error erased all secrets

PROBLEM: `read()` changed each failure into an empty map, and `put`/`remove` wrote that map.
FIX: `put` and `remove` read strictly and throw. Only `all()` and `load()` use an empty map.
VERIFY: a fake `open` that throws once: `put` throws and the file does not change.

## SEC-7 [Low] [fixed] Reset and delete: partial state and crashes

PROBLEM: reset deleted the rootfs in place: a stop during the delete could leave a guest with no
programs that counts as ready. Exceptions in the reset and delete launches stopped the app (for
example a folder name with a space, made in the shell).
FIX: reset renames to `rootfs.old` first (under the setup mutex); `check()` deletes a leftover
`rootfs.old`. The launches use `runCatching`. Delete accepts each listed folder name.
VERIFY: stop the app during a reset: the next start shows Install.

## SEC-8 [Low] [fixed] Clipboard writes from a session in the background

PROBLEM: guest output can set the clipboard with OSC 52, also from a session that is not shown.
FIX: only the session on the screen, in a window with focus, can set the clipboard. OSC 52 stays on,
because the opencode copy uses it. Terminal copies are not marked sensitive and not cleared after
60 s: they are normal text that the user copies (accepted).
VERIFY: `sleep 5; printf '\e]52;c;aGk=\a'` in a session, then leave the session: no change.

## SEC-9 [Low] [fixed] gh login folder after a stop

PROBLEM: the plain-text token in `/tmp/automa-gh-login/hosts.yml` was deleted only in `finally`.
FIX: also deleted at the start of each login and at each app start.
VERIFY: stop the app after the approval: at the next start the folder is gone.

## SEC-10 [Low] [fixed] Recents preview and Stop from the lock screen

PROBLEM: recents kept terminal text when the app lock was off. "Stop all" worked on a locked phone.
FIX: no recents preview at any time. The Stop action needs an unlocked phone. The terminal has no
`FLAG_SECURE`, so that the user can take screenshots of results (accepted).
VERIFY: recents shows no content; Stop on a locked phone asks for an unlock.

## SEC-11 [Low] [accepted] DNS without encryption in strict Private DNS mode

PROBLEM: in strict mode, the guest sends plain DNS to the addresses of the Private DNS host.
REASON: a fix needs a changed PRoot (a redirect of port 53 to an in-app relay through
`DnsResolver`). With no DNS, the guest cannot work. Documented in the README and research F-5.

## SEC-12 [High] [fixed] A cancel or Stop did not stop the guest programs (found during the fix tests)

PROBLEM: `Process.destroyForcibly()` and `finishIfRunning()` stop only PRoot, and PRoot ignores
SIGTERM. After SIGKILL, `gh`, `bash` and `opencode` continued as orphans (research F-8).
FIX: `env/Procs.kt` reads the tree from `/proc` and sends SIGKILL to each process. `Env.run` marks its
PRoot command line with `AUTOMA_RUN=<uuid>`.
VERIFY (done on the API 37 emulator, debug and release): login cancel, session close and "Stop all"
leave no `gh`, `bash`, `opencode` or `libproot.so` process.

## Checked and correct

Exported components (only the launcher activity; the provider needs MANAGE_DOCUMENTS), immutable
explicit PendingIntents, backup off, network security config, downloads (hash before rename, size
limit, https redirects), the tar reader, argv-only guest commands with `--` before the clone URL,
secrets only in the environment, no-op loggers, no personalised learning on the terminal and the key
fields, `FLAG_SECURE` on the keys screen and its dialog.

## SEC-9b [Low] [accepted] Update: the opencode SHA-256 comes from GitHub, not from the APK

PROBLEM: the setup checks opencode with a SHA-256 in the APK. The in-app update cannot: it reads the
`digest` of the asset from the GitHub release data. A compromised GitHub account of opencode or
GitHub itself could give a bad file with a matching digest.
FIX: accepted. It is the same trust as the official opencode installer, and the user starts each
update. Limits: HTTPS with the system CAs and CT, only the releases of the 2.x line in the release
list (`releases?per_page=30`, DESIGN §3; no drafts, no pre-releases), a plain `x.y.z` version that is
newer than the installed one (no downgrade), the download URL is made from the version (not taken
from the answer), the digest must be `sha256:` and 64 hex characters, size limits on the answer
(8 MiB) and the archive (256 MB).
VERIFY: unit test `opencodeRelease` (pre-release, bad tag, missing or bad digest, not JSON);
Settings → Update on a device.

## SEC-13 [Medium] [fixed] TFF model list: the key and untrusted model ids

PROBLEM: the TFF model list (DESIGN §8) sends `TFF_API_KEY` from the app process, and the answer
goes into the opencode config and onto the terminal. A redirect could send the key to another host.
A model id could have terminal control sequences, and a write into a missing guest during a reset
would make an empty rootfs that counts as ready (as SEC-7).
FIX: the request has no redirects (`instanceFollowRedirects = false`), HTTPS with the system CAs,
at most 1 MiB. Model ids must match `[A-Za-z0-9._:/@+-]{1,128}`, at most 300; `org.json` writes the
config. The write is a rename, under the setup mutex, and only on a ready guest (or in the setup).
The key is never in a log, in argv or in a file; opencode gets it from the environment.
VERIFY: unit test `TffTest.modelIds` (escape sequence, space, long id, duplicates, not JSON);
emulator: a made-up key shows "Server answered HTTP 401" and the old list stays.

## SEC-14 [Medium] [fixed] Agents and links: downloads and links from guest programs

PROBLEM: the agent install runs a large program from a publisher, and the link hook lets any guest
program (also code from a cloned repository) ask Automa to open a page.
FIX: the install takes a pinned version with a pinned SHA-256 of the .zst file (and, for Claude
Code, of the program), checked before zstd unpacks it; an update takes the publisher's SHA-256
(as SEC-9b). The write is a rename, under the setup mutex, on a ready guest only (SEC-7). The link
hook accepts only one https link (at most 4096 characters, no spaces or control characters), shows
the host, and opens the browser only after a tap. The watcher starts only when the folder exists.
Codex runs with no sandbox (F-10); the Android app sandbox is the limit, as for the other agents.
VERIFY: unit tests `AgentsTest` (manifest, release, links); emulator: install, Remove, the link bar
for both logins.

## SEC-15 [Medium] [fixed] Claude chat: the login check loads the project settings of an untrusted folder

PROBLEM: `ClaudeDriver.begin` runs `claude auth status` in the repository folder
(`guest.exit(…, dir)`). This second Claude Code process does not get `--setting-sources user`.
Its stdout is a pipe, so Claude Code skips the trust question and treats the folder as trusted
(CC-2, CC-25). Thus `.claude/settings.json` and `.claude/settings.local.json` of the clone can load.
These files can hold commands (`apiKeyHelper`, `awsAuthRefresh`, `awsCredentialExport`,
`otelHeadersHelper`) and an `env` block (for example `ANTHROPIC_BASE_URL`). `auth status` reports
`api_key_helper` as a login method (CC-5). If it runs such a command, or sends a request with the
login, one tap on "Claude Code" in a cloned repository runs a program of the repository or sends
the token to its server. The main process has this protection (CC-25); this process does not.
FIX: run `claude auth status` in `/root` (`Env.HOME`), not in the repository. Then only the user
settings apply. Alternative: the same `--setting-sources user` as the main process, if `auth
status` accepts it.
VERIFY: a made-up repository with `.claude/settings.json` `{"apiKeyHelper": "touch
/tmp/marker; echo x"}`, not trusted: open a Claude chat, then look for `/tmp/marker`. Do this test
also before the fix: if the marker is there, the severity is High.
STATUS: Fixed in 0.5.0: the check runs in `/root` (`ClaudeDriver.begin`). Unit test `claudeLoginCheckRunsInHome`. The emulator marker test is not done yet.

## SEC-16 [Medium] [fixed] Reset does not stop the chats

PROBLEM: Settings → Reset calls `app.sessions.closeAll()`, but not `app.chats.closeAll()`. The
dialog says "Automa closes all sessions". The agents of open chats continue, with `GH_TOKEN` and the
API keys in their environment, while `Setup.reset` renames and deletes the rootfs. The chat stays
open and accepts new messages. PRoot keeps the old rootfs path, so an agent that writes its
transcript with `mkdir -p` (`~/.claude/projects/…`, `~/.local/share/opencode/…`) can make
`rootfs/root/…` again. `Setup.plan` then sees a rootfs folder and skips the Rootfs step, and the
next setup fails (the SEC-7 failure). After a new setup, the old agent can also write into the new
guest.
FIX: one function stops the sessions and the chats and waits until all the process trees have
ended (`Pipe.stop` waits up to 1.5 s; not on the main thread). The reset calls it before the
rename. "Stop all" can use the same function.
VERIFY: emulator: open a Claude chat, send a message, reset during the turn. No `libproot.so`
process stays, and the next setup completes.
STATUS: Fixed in 0.5.0: `Chats.stopAll()` closes the chats and waits for their process trees; Reset calls it before `Setup.reset`. Emulator test not done yet.

## SEC-17 [Medium] [fixed] A permission card can hide a part of what the user allows

PROBLEM: the card is the only check before a command runs, but it does not always show the full
request:
- `CodeBlock(detail, maxLines = 30)` clips the text after line 30 with no sign. A command with 31
  or more lines (a heredoc, or many comment lines) shows only its start.
- `Tools.claudeInput` and `Tools.text` cut the input at 4,000 characters with no sign.
- The text does not wrap (`softWrap = false`). After many spaces, the end of a line is off the
  screen.
- `clean()` removes only ISO control characters. Bidi controls (U+202A–U+202E, U+2066–U+2069) and
  zero-width characters stay, so the visible order of a command can be different from the real
  order ("Trojan Source").
- The path of a Claude Edit or Write diff (`Tools.claudeDiff`) does not go through `clean()`. The
  Claude text deltas also go to the screen with no `clean()`, until the full block comes.

Failure case: text in a cloned repository tells the model to run a long command. Lines 1 to 30 look
safe; line 31 sends `$GH_TOKEN` to a server. The user reads the card and taps "Yes".
FIX: one function for the text of a card. It wraps the lines. When it cuts (lines or characters),
it shows "… N more lines" and the user can open the full text. It shows format characters (Unicode
category Cf, U+2028, U+2029) as visible marks, for example `<U+202E>`, in the detail, the title and
the paths. Use `clean()` for the diff path and for the stream deltas.
VERIFY: unit tests of the function: a command with 31 lines, a command with 5,000 characters, a
command with U+202E. Emulator: a fake agent with these requests shows the full text or the "more"
sign.
STATUS: Fixed in 0.5.0: the card text wraps and has no line limit (the card scrolls); `cut()` adds "… N more characters" (limit 20,000); `clean()` shows format characters as `<U+XXXX>`; the diff paths and the stream deltas go through `clean()`. Unit tests `cleanRemovesEscapesAndShowsFormatCharacters`, `longCommandsShowTheCut`. Emulator (fake agent): a 35-line command with U+202E shows in full, with `<U+202E>`.

## SEC-18 [Low] [fixed] A tap can answer the next card

PROBLEM: `ChatScreen` shows `state.asks.firstOrNull()` in one `AskCard` with no `key(ask.id)`. When
card A closes (an answer, or a cancel from the agent), card B uses the same buttons and the same
scroll position, and the buttons get the `onClick` of B. A double tap on "Yes", or a press that
ends after the agent cancelled A, approves B. The user did not see B. If the user scrolled A down to
its buttons, B also opens scrolled down, so its command is not in view. Claude Code can send two
`can_use_tool` requests at the same time (two tool calls in one message). Also, the card ignores
`default_to_no` of Claude Code (CC-13): "Yes" is always the first, filled button.
FIX: `key(ask.id) { AskCard(…) }`, so that each card gets new state and a press in progress stops.
Ignore taps on the choices for about 500 ms after a card appears. With `default_to_no`, show "No"
first and "Yes" as an outlined button.
VERIFY: emulator, a fake agent with two permission requests: a fast double tap on "Yes" answers
only the first request.
STATUS: Fixed in 0.5.0: `key(ask.id)`, taps arm after 400 ms, one answer for each card; `default_to_no` puts "No" first and shows "Yes" as an outlined button. Unit test `claudeDefaultToNoPutsNoFirst`. Emulator (fake agent): a fast double tap on "Yes" answered only the first of two requests; with `default_to_no`, "No" is first and "Yes" is outlined.

## SEC-19 [Low] [fixed] Codex trust: Codex starts when Automa cannot save "Do not trust"

PROBLEM: when `guest.append` fails, `CodexDriver.prepare` shows an error line and returns true.
`thread/start` then finds no entry for the folder and trusts it (CX-6), so the `.codex/` settings of
the clone load (MCP servers, programs). The answer "Do not trust" has no effect. `append` fails for
example when the storage is full, or when `~/.codex/config.toml` is a link out of the guest root.
FIX: when the append fails, do not start: end the chat with the error. Before the start, read the
file again and check that the table is in it.
VERIFY: unit test with a fake `Guest` whose `append` returns false: the agent does not start, and
the chat shows the error.
STATUS: Fixed in 0.5.0: no start when the append fails or the table is not in the file after it; also no start when the chat closes during the question. Unit tests `codexDoesNotStartWhenTrustIsNotSaved`, `codexTrustIsSavedBeforeTheStart`.

## SEC-20 [Low] [fixed] Crafted agent text can freeze the app

PROBLEM: `MarkdownText` parses agent text on the main thread, in the composition. Some work in
`Markdown.inline` grows with the square of the length: for each `[` it searches the rest of the
paragraph for `](`; for each `` ` `` it copies the rest of the text (`text.drop(i)`); for each `*` or
`_`, `closer` scans to the end of the line. The `HEADING` regex also backtracks on a long line of
spaces. A paragraph of about 100 KB with many `[` and no `](` takes seconds, and Android shows "App
not responding". If the user or the system stops the app, all sessions and agents stop. While a text
streams, the parse runs again for each frame. The text can come from a cloned repository: a plan
file (the plan card shows it), or a text that the model must copy.
Also, the memory has no total limit: an agent text has no size limit (each delta copies the full
text), and `OpencodeDriver.tools` keeps the full raw JSON of each tool call (opencode 2.x sends the
full old and new file text) until the chat closes.
FIX: make `inline` linear: search a mark only to the end of the line, and do not copy the rest of
the text. Parse off the main thread, or show a text over a limit (for example 50,000 characters) as
plain text. Limit one entry (for example 200,000 characters, then "…"). Keep only the necessary
fields in `tools`.
VERIFY: unit test: `Markdown.inline` on 200,000 `[` characters, and `Markdown.blocks` on `# ` and
100,000 spaces and `x`, end in less than 100 ms each (estimate, test JVM).
STATUS: Fixed in 0.5.0: each search stops at the end of the line and after 1,000 characters; `inline` keeps a text over 20,000 characters plain, `MarkdownText` one over 60,000; the regexes run only on short lines; one row has at most 200,000 characters (`grow`), a chat at most 2,000 rows; opencode drops finished tool calls. Unit test `craftedTextStaysFast` (0.1 s, estimate).

## SEC-21 [Low] [fixed] Chat links open with no host check

PROBLEM: `Markdown.inline` makes `LinkAnnotation.Url` links. A tap opens the URL at once through
the default `UriHandler`. The label can show a different host:
`[https://github.com/login](https://attacker.example/login)`, or
`https://github.com@attacker.example/`. The terminal shows the host and asks first (DESIGN §5); the
chat does not. Agent text can come from a cloned repository (prompt injection), so this is a
phishing path to the GitHub or AI login.
FIX: a `LinkInteractionListener` that sends the URL to the same "Open the link in the browser?" bar
as `term/Links`, with the host from `Uri.getHost()` (not from the label).
VERIFY: emulator: a tap on a Markdown link shows the bar with the real host; the browser opens only
after a second tap.
STATUS: Fixed in 0.5.0: links are `LinkAnnotation.Clickable`; a tap opens a dialog with the host from the URL, and the browser opens only after "Open". Unit tests `onlyHttpsLinksAreActive`, `aLinkLabelHoldsNoOtherLink` (a label with a link made a second link inside). Emulator (fake agent): the label `https://github.com/login` with the target `attacker.example` shows "attacker.example".

## SEC-22 [Low] [fixed] A Write diff can show agent logins on the screen

PROBLEM: for a Claude `Write`, `Tools.claudeDiff` reads the old file with `guest.read(file_path)`.
The path comes from the model, and `EnvGuest` accepts each file in the guest root. The read occurs
for each `tool_use` in an `assistant` message, before Claude Code checks the call. A model that a
cloned repository controls can name `/root/.codex/auth.json`, `/root/.claude/.credentials.json` or
`/root/.local/share/opencode/auth.json`. The app then shows the tokens as "-" lines in the tool row
and in the card. The chat has no `FLAG_SECURE`, so a screenshot or a screen share can keep them.
The guest root check itself is correct (see the chat checked list).
FIX: read the old file only when its canonical path is in the chat folder (`dir`). For other paths,
show "Replaces <path>" with no old text.
VERIFY: unit test with a fake `Guest` that counts reads: a Write to `/root/.codex/auth.json` reads
no file.
STATUS: Fixed in 0.5.0: `Guest.read(…, within = dir)` checks the canonical path against the chat folder (also through links). Unit test `writeDiffReadsOnlyTheChatFolder`.

## SEC-23 [Low] [fixed] Codex "Ask for risky commands" almost never asks

PROBLEM: this mode sends `approvalPolicy: "on-request"`. In Codex 0.169.0 that asks only for `rm`
with a force option. All other commands and all file changes run with no card (CX-7). The text
"Codex asks only before dangerous commands" makes the user expect a card for `curl … | sh` or
`git push --force`.
FIX: change the description to the facts, for example "Only `rm -f` asks. Other commands and file
changes run with no question." Keep the mode. Change the label only if the owner agrees.
VERIFY: read the mode sheet in a Codex chat.
STATUS: Fixed in 0.5.0: the mode is "Ask only for rm -f" with the description "Other commands and all file changes run with no question".

## SEC-24 [Low] [fixed] A close during the start can leave the agent running

PROBLEM: `Chats.close` cancels the job and starts `driver.stop()` on a thread at once. If the job is
in `Env.start` (`withContext(Dispatchers.IO) { ProcessBuilder.start() }`), the process starts, but
`withContext` then throws `CancellationException` and the `Process` is lost. `Driver.pipe` is still
null, so `stop()` does nothing. The agent (with the secrets, and for opencode 1.x its loopback
port) waits on stdin until the garbage collector closes the pipe. "Stop all" and "New chat" have the
same window. The window is short (the time of the process start).
FIX: start the process with no cancel (`NonCancellable`), keep it in `pipe`, then check the job: if
it was cancelled, call `pipe.stop()` and return.
VERIFY: emulator, debug build with a delay after the process start: close the chat during the
delay. No `libproot.so` process stays.
STATUS: Fixed in 0.5.0: `Env.start` runs with `NonCancellable`; `Driver.run` stops the new pipe when the job was cancelled; `pipe` is volatile.
That fix did not work: `withContext(NonCancellable + Dispatchers.IO)` drops its result after a cancel, because the dispatcher changes. Fixed again: `Driver.run` and `Env.run` wrap the start in `withContext(NonCancellable)` with no dispatcher change. Unit test `aStartThatTheCloseInterruptsKeepsItsResult`. The emulator test is not done yet.

## SEC-25 [Low] [fixed] The terminal command line uses agent ids with no check

PROBLEM: `Sessions.open` runs `bash -lc "<launch>; exec bash -l"`. For Codex and opencode, `launch`
has `thread.id` from `thread/start` and `sessionId` from `session/new`, with no check. An id with
`;`, `$(…)` or a newline runs a command in a new terminal session when the user taps the terminal
button. There is no gain of rights today: the agent program makes these ids, and it can run
programs anyway. But all incoming data is untrusted (CLAUDE.md, SEC-13), and the fix is small. The
Claude id is a local UUID and is safe.
FIX: `terminal()` gives a command line only when the id matches `[A-Za-z0-9_-]{1,128}`; else null
(the TUI opens a new session).
VERIFY: unit test: a `thread/start` answer with the id `x;id`: `terminal()` is null.
STATUS: Fixed in 0.5.0: `safe(id)` (`[A-Za-z0-9_-]{1,128}`) before an id goes into `terminal()`. Unit test `onlySafeIdsGoIntoACommandLine`.

## Checked and correct (chat)

2026-11-18, the native chat (DESIGN §13):

- Commands: argv only (`bash -lc 'exec "$@"'`), so no shell quoting of the agent command. No
  secret in argv: the secrets, the PRoot mark and `OPENCODE_SERVER_PASSWORD` go in the environment.
  No log calls in `chat/` and `ui/chat/`; errors and stderr go only to the screen, through `clean()`.
- opencode password: 32 bytes from `SecureRandom`, new for each start, not in a file or a log. It
  reaches the child tools of opencode 1.x, but they have the same UID and can read
  `/proc/<pid>/environ` anyway. The port is on 127.0.0.1 (DESIGN §13: another UID gets 401). The
  chat opens no other port.
- Claude trust: `~/.claude.json` with a size limit; no file, bad JSON or more than 8 MiB means
  "untrusted" (fail closed). The parent walk stops before `/` (stricter than Claude Code). A parent
  trust (for example `/root/repos`) covers all clones below it, the same as in Claude Code. A guest
  program can write `~/.claude.json` or `~/.codex/config.toml`, but it can also write `~/.bashrc`
  or user hooks, so this gives no new rights.
- Codex trust: the card comes before the start; a dismiss means "untrusted", and a close stops the
  start. The TOML key escapes `\` and `"`: no injection. A control character in a folder name makes
  the file invalid, so Codex does not start (fail closed). Only a guest program can make such a
  name (`Repos.validName` checks clones).
- opencode: `bash -l` loads `/etc/profile.d/automa.sh` (`OPENCODE_DISABLE_PROJECT_CONFIG`);
  `session/new` has no MCP servers; no `fs` or `terminal` capability; `fs/write_text_file` writes
  nothing.
- Answers: each answer removes its request first, so a second tap or a replay sends nothing.
  Dismiss, Skip and Stop give deny, cancel or decline (Codex `requestUserInput`: empty answers).
  Cancelled requests close their cards (Claude `control_cancel_request`, Codex
  `serverRequest/resolved` and the turn end, opencode `$/cancel_request`). The agent cannot make
  the `trust` or `plan` cards: agent requests get the prefix `r`, and Claude has no local cards.
- `EnvGuest.read` and `append`: the canonical path must be in the rootfs (no `..`, no link out),
  with a size limit for reads. A link swap between the check and the read gives a guest program
  nothing new (same UID; the text goes only to the screen).
- Limits: 8 MiB for each line, 40 stderr lines of 500 characters, tool output 20,000 characters.
- Markdown links: only `https://`, 9 to 2048 characters, no spaces or control characters, only on
  a tap. Code blocks and tool output have no links.
- The composer and all answer fields have no personalised learning. Secret questions (`isSecret`)
  use a password field, and the chat does not keep the answer. The chat has no `FLAG_SECURE`, the
  same as the terminal (SEC-10); the API keys screen keeps it.
- Close and "Stop all": end of stdin, then SIGKILL of the tree after 1.5 s (F-8); `--kill-on-exit`
  stops the children when the agent ends. The reader thread only parses and queues; all protocol
  state changes on the serial context.
