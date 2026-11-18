# Chat UI research: the Codex app-server protocol

Sources, 2026-11-17: the openai/codex source at tag `rust-v0.169.0` (`codex-rs/app-server-protocol`,
`app-server`, `core`, `tui`, `cli`, `login`), its checked-in schema
(`app-server-protocol/schema/typescript`, the stable API), Codex 0.166.0 on the Mac (`generate-ts`,
`generate-ts --experimental` and a live probe with an empty `CODEX_HOME`, logged out, no prompt to a
model), the GitHub release data, and `https://learn.chatgpt.com/docs/app-server`
(`developers.openai.com/codex/app-server` redirects there). "Real output" is from the 0.166.0 probe.
"Shape from schema" is a JSON object that follows the 0.169.0 schema exactly; the IDs and texts in it
are made up.

## CX-1 Transport and command line

- `codex app-server` with no subcommand runs the server in the same process, with stdio as the
  default transport (`--listen stdio://`). One JSON object per line, in both directions.
- The wire format is JSON-RPC 2.0 without the `"jsonrpc":"2.0"` field. The server sends no
  `jsonrpc` field. It accepts a request that has the field (real output: `{"jsonrpc":"2.0","id":2,
  "method":"account/read",...}` gets a normal answer).
- The stdio server does not use the app-server daemon. The daemon code (and its "this CLI has no
  complete local package" check, F-10) is only in `codex app-server daemon ...`
  (`app-server-daemon/src/prepare_install.rs`). `cli/src/main.rs` (0.169.0) calls
  `run_main_with_transport_options(... AppServerTransport::Stdio ...)` directly. `--no-daemon` is
  not necessary; `codex --no-daemon app-server --help` works, so the flag does no harm.
- Session source is `vscode` (`SessionSource::VSCode`) for each thread that the app-server starts.
  The TUI writes `cli`. `thread/list` shows both by default (CX-21).
- Logs go to stderr (ANSI colour, `WARN` and `ERROR` lines) and to `$CODEX_HOME/logs_2.sqlite`.
  stdout has only protocol lines.
- v2 is the current API. The client methods are `thread/*`, `turn/*`, `account/*`, `model/list`,
  and others. The v1 conversation methods are removed. Only the server requests
  `applyPatchApproval` and `execCommandApproval` stay, marked "DEPRECATED ... used for Turns started
  via the legacy APIs" (`app-server-protocol/src/protocol/common.rs`). A v2 client never gets them.

Recommended command (in the guest, through `Env.run`, cwd = the repository):

```
codex app-server --disable plugins
```

`--disable plugins` is the same as `-c features.plugins=false` (CX-5).

VERIFY: `codex app-server` starts under PRoot on the phone, with no daemon error.

## CX-2 Initialize

Real output (0.166.0):

```
>> {"id":1,"method":"initialize","params":{"clientInfo":{"name":"automa_probe","title":"Automa probe","version":"0.0.1"},"capabilities":{"experimentalApi":false}}}
<< {"id":1,"result":{"userAgent":"automa_probe/0.166.0 (Mac OS 27.2.0; arm64) unknown (automa_probe; 0.0.1)","codexHome":"/private/tmp/automa-research/codex-home","platformFamily":"unix","platformOs":"macos"}}
<< {"method":"remoteControl/status/changed","params":{"status":"disabled","serverName":"MacBook-di-user.local","installationId":"2175ede6-...","environmentId":null},"emittedAtMs":1794956520821}
>> {"method":"initialized"}
```

- `capabilities` (0.169.0, `v1.rs` `InitializeCapabilities`): `experimentalApi` (default false),
  `requestAttestation` (default false), `optOutNotificationMethods` (exact method names),
  `requiredHumanApproval`, `explicitGatewayOauth`, `mcpServerOpenaiFormElicitation`, `extensions`.
  `capabilities: null` is valid.
- `clientInfo.name` becomes the `originator` of new threads and goes into the `User-Agent` of model
  requests. Use a neutral name, for example `automa`.
- A request before `initialize` (real output): `{"error":{"code":-32600,"message":"Not initialized"},"id":0}`.
- A second `initialize` (real output): `{"error":{"code":-32600,"message":"Already initialized"},"id":9}`.
- A line that is not JSON gets no answer. The server writes `Failed to deserialize JSONRPCMessage`
  to stderr and continues (real output).

## CX-3 Experimental API

`experimentalApi: true` is necessary for: `turn/start.collaborationMode` (Plan mode, CX-17),
`collaborationMode/list`, the `availableDecisions` field of command approvals (CX-13), and the
`granular` approval policy. Without it, the server strips experimental fields from its requests and
rejects experimental input with `"<reason> requires experimentalApi capability"` (-32600)
(`app-server-protocol/src/experimental_api.rs`, `app-server/src/message_processor.rs`).

`item/tool/requestUserInput` is not gated, but it is marked "EXPERIMENTAL" and the model can use it
only in Plan mode (CX-16), which is gated.

Experimental shapes can change in each release. Pin the Codex version for the chat UI and test
again at each update.

## CX-4 Login state

Real output, logged out (0.166.0):

```
>> {"id":2,"method":"account/read","params":{"refreshToken":false}}
<< {"id":2,"result":{"account":null,"requiresOpenaiAuth":true,"workspaceRouting":null}}
```

0.169.0 adds `userId` (`string | null`, null when logged out), so the 0.169.0 answer is
`{"account":null,"userId":null,"requiresOpenaiAuth":true,...}` (shape from schema).
`workspaceRouting` is experimental; 0.166.0 sends it also without the opt-in.

- Not logged in = `account == null && requiresOpenaiAuth == true`.
- Logged in with ChatGPT (shape from schema):
  `{"account":{"type":"chatgpt","email":"user@example.com","planType":"plus","workspaceName":null},"userId":"user-abc","requiresOpenaiAuth":true}`.
  Other `account.type` values: `apiKey`, `amazonBedrock`, `gcp`.
- `account/updated` (`{authMode, planType}`) comes after a login, a logout or a workspace change.
  Then read `account/read` again.
- `turn/start` has no early login check (`app-server/src/request_processors/turn_processor.rs`).
  Without a login, the model request fails. The expected result is an `error` notification and
  `turn/completed` with `status: "failed"` and `codexErrorInfo: "unauthorized"`.

VERIFY: the error of a turn without login, on the phone (this test sends a request to OpenAI).

## CX-5 Network and disk at start (privacy)

Real output, logged out, empty `CODEX_HOME`, config as in DESIGN §8:

- At start: `GET https://chatgpt.com/backend-api/plugins/featured?platform=codex` (401).
- At start: the curated plugin sync. It copies `https://github.com/openai/plugins.git` to
  `$CODEX_HOME/.tmp/plugins` (fallback: `chatgpt.com/backend-api/plugins/export/curated`). The
  `CODEX_HOME` grew to 96 MB (`core-plugins/src/startup_sync.rs`).
- At `thread/start`: a Responses WebSocket pre-connect (stderr: `failed to connect to Responses
  WebSocket`), before any prompt.
- With `--disable plugins`: no featured-plugin request, no `.tmp/plugins`; `CODEX_HOME` 3 MB
  (real output).
- `--analytics-default-enabled` is not set, so analytics stay off; the config also sets
  `[analytics] enabled = false`. Memories (`features.memories`) are off by default.

The TUI embeds the same server, so the TUI has the same start traffic today.

VERIFY: `--disable plugins` removes nothing that the owner uses (skills stay in
`$CODEX_HOME/skills/.system`).

## CX-6 thread/start trusts a git repository (security)

With `sandbox_mode = "danger-full-access"`, `thread/start` with a `cwd` in a git repository that
has no trust decision writes this to `~/.codex/config.toml` (real output, 0.166.0, a new empty
`git init` folder):

```
[projects."/private/tmp/automa-research/codex-repo"]
trust_level = "trusted"
```

Source: `app-server/src/request_processors/thread_processor.rs` (0.169.0): "Project-local config can
launch host processes, so only the effective permissions after managed constraints can imply project
trust". `PermissionProfile::Disabled` (full access) always implies trust. A folder with no git and no
`.codex` folder gets no entry.

Effect: the project `.codex/` settings of a cloned repository apply with no question. This is
different from the TUI, which asks first (DESIGN §8 "Workspace trust").

With an existing `trust_level = "untrusted"` entry, `thread/start` does not change it, and the
thread gets `"approvalPolicy":"untrusted"` (real output).

Fix for the chat UI: before the first `thread/start` in a folder, Automa asks the user (a native
card, like the TUI trust question) and writes the answer to `[projects."<path>"]` in
`~/.codex/config.toml` (or with `config/value/write`).

VERIFY: an `untrusted` project does not load its `.codex/config.toml` (MCP servers, hooks).

## CX-7 When Codex asks for approval

`core/src/exec_policy.rs` (`render_decision_for_unmatched_command_for_platform`),
`core/src/safety.rs` (`assess_patch_safety`), 0.169.0, with full access (no sandbox):

| approvalPolicy | Commands | File changes (`apply_patch`) |
|---|---|---|
| `on-request` (default, and for trusted projects) | Run with no question. Only "dangerous" commands ask. In 0.169.0 that is only `rm` with a force option (`-f`, `-rf`, `--force`), also after `sudo` or `env` and in a `trap` (`shell-command/src/command_safety/is_dangerous_command.rs`). | No question. |
| `untrusted` (default for untrusted projects) | Each command asks, unless an exec policy rule allows it (for example a rule from `acceptWithExecpolicyAmendment`). There is no built-in "safe command" list in 0.169.0. | Each change asks. |
| `never` | No question. Dangerous commands are refused. | No question. |

- `approval_policy = "untrusted"` in `config.toml` is an error in 0.169.0 ("no longer supported").
  `thread/start` with `"approvalPolicy":"untrusted"` is accepted (real output:
  `"approvalPolicy":"untrusted"` in the answer).
- The TUI presets (`utils/approval-presets`) are "Read Only" and "Default" (both `on-request`,
  with a sandbox) and "Full Access" (`never`). The sandbox presets cannot run under PRoot (F-10).

Thus DESIGN §8 "Codex still asks before it runs commands" is true only for an untrusted folder.
For the chat UI, the approval cards appear only with `approvalPolicy: "untrusted"`, or for dangerous
commands.

## CX-8 Start a thread

Request (all fields are optional, `ThreadStartParams`): `model`, `modelProvider`, `serviceTier`,
`cwd`, `approvalPolicy`, `approvalsReviewer` (`user`), `sandbox` (`read-only` |
`workspace-write` | `danger-full-access`), `config` (a map of config overrides),
`baseInstructions`, `developerInstructions`, `ephemeral`.

Real output (0.166.0, shortened only where marked `...`):

```
>> {"id":5,"method":"thread/start","params":{"cwd":"/tmp/automa-research/codex-cwd","approvalPolicy":"untrusted","sandbox":"danger-full-access"}}
<< {"id":5,"result":{"thread":{"id":"01a1ebbf-1e6e-7b50-8fd0-c9c51b6421b0","sessionId":"01a1ebbf-1e6e-...","forkedFromId":null,"parentThreadId":null,"preview":"","ephemeral":false,"historyMode":"paginated","modelProvider":"openai","model":"gpt-6.1-sol","reasoningEffort":null,"createdAt":1794956533,"updatedAt":1794956533,"status":{"type":"idle"},"path":".../sessions/2026/11/18/rollout-...jsonl","cwd":"/tmp/automa-research/codex-cwd","cliVersion":"0.166.0","originator":"automa_probe","source":"vscode","gitInfo":null,"name":null,"turns":[],...},"model":"gpt-6.1-sol","modelProvider":"openai","serviceTier":null,"cwd":"/tmp/automa-research/codex-cwd","instructionSources":[],"approvalPolicy":"untrusted","approvalsReviewer":"user","sandbox":{"type":"dangerFullAccess"},"reasoningEffort":null,...}}
<< {"method":"thread/started","params":{"thread":{...same thread...}},"emittedAtMs":1794956533362}
```

- Keep `thread.id`, `model` and `reasoningEffort` (null = the model default) for the next requests.
- The rollout file is written only after the first turn. An unused thread can be lost.

## CX-9 Send a user message

```
{"id":10,"method":"turn/start","params":{"threadId":"T","input":[{"type":"text","text":"Fix the failing test"}]}}
```

(shape from schema). `text_elements` is optional (`#[serde(default)]`). The answer is
`{"id":10,"result":{"turn":{"id":"U","rootTurnId":"U","items":[],"itemsView":"notLoaded","status":"inProgress","error":null,"startedAt":null,"completedAt":null,"durationMs":null}}}`
(built in `turn_processor.rs`).

- Overrides on `turn/start` apply "for this turn and subsequent turns": `model`, `effort`,
  `summary` (`auto` | `concise` | `detailed` | `none`), `approvalPolicy`, `sandboxPolicy`, `cwd`,
  `collaborationMode` (experimental), `outputSchema`.
- A `turn/start` while a turn runs adds the input to the active turn (steer) and returns the same
  turn id (`start_or_steer_turn`). `turn/steer` with `expectedTurnId` does the same with a check.
- Images: `{"type":"localImage","path":"/root/x.png"}` (a guest path) or
  `{"type":"image","url":"data:..."}`. Only a note; not in the first version.

## CX-10 Message types on stdout

- A line with `id` and `result` or `error`: the answer to a client request.
- A line with `id` and `method`: a server request. The ids are integers from 0
  (`outgoing_message.rs`). The client must answer each one with the same `id`.
- A line with `method` and no `id`: a notification. It has `emittedAtMs` next to `params`.
- An `error` answer to a server request counts as a refusal: for a command approval, the server
  uses `ReviewDecision::denied("approval request failed")` (`bespoke_event_handling.rs`). Thus
  answer an unknown server request with `{"id":N,"error":{"code":-32601,"message":"unsupported"}}`.

## CX-11 Streaming

Order for one turn (`bespoke_event_handling.rs`, `event_mapping.rs`, doc page "Events"):

1. `turn/started` `{threadId, turn}`.
2. For each item: `item/started` `{item, threadId, turnId, startedAtMs}`, then deltas, then
   `item/completed` `{item, threadId, turnId, completedAtMs, contentTokenCount?}`. The
   `item/completed` item is the final state; replace the streamed state with it.
3. `thread/tokenUsage/updated` `{threadId, turnId, tokenUsage:{total, last, modelContextWindow}}`;
   each breakdown has `totalTokens, inputTokens, cachedInputTokens, cacheWriteInputTokens,
   outputTokens, reasoningOutputTokens`.
4. `turn/completed` `{threadId, turn}` with `turn.status` = `completed` | `interrupted` | `failed`
   and `turn.error` = `{message, codexErrorInfo, additionalDetails, misalignment}` or null.
   `turn.items` can be empty; the `item/*` notifications are the source of truth.

Deltas (shape from schema; all have `threadId`, `turnId`, `itemId`):

```
{"method":"item/agentMessage/delta","params":{"threadId":"T","turnId":"U","itemId":"I","delta":"Hel"}}
{"method":"item/reasoning/summaryTextDelta","params":{"threadId":"T","turnId":"U","itemId":"R","delta":"Reading the","summaryIndex":0}}
{"method":"item/reasoning/summaryPartAdded","params":{"threadId":"T","turnId":"U","itemId":"R","summaryIndex":1}}
{"method":"item/reasoning/textDelta","params":{"threadId":"T","turnId":"U","itemId":"R","delta":"...","contentIndex":0}}
{"method":"item/commandExecution/outputDelta","params":{"threadId":"T","turnId":"U","itemId":"C","delta":"ok 3 tests\n"}}
{"method":"item/plan/delta","params":{"threadId":"T","turnId":"U","itemId":"P","delta":"1. Add"}}
{"method":"item/fileChange/patchUpdated","params":{"threadId":"T","turnId":"U","itemId":"F","changes":[{"path":"src/a.kt","kind":{"type":"update","move_path":null},"diff":"@@ -1 +1 @@\n-a\n+b\n"}]}}
```

- `item/commandExecution/outputDelta.delta` is plain text (`String::from_utf8_lossy`), not
  base64. (Only `command/exec/outputDelta` is base64.)
- `item/fileChange/outputDelta` is deprecated; the server no longer sends it.
- `error` `{error: TurnError, willRetry, threadId, turnId}`: with `willRetry: true` the turn
  continues (show a short note); with false, `turn/completed` `failed` follows.
- `codexErrorInfo` values (0.169.0): `contextWindowExceeded`, `sessionBudgetExceeded`,
  `usageLimitExceeded`, `rateLimitExceeded`, `serverOverloaded`, `modelNotFound`, `unauthorized`,
  `badRequest`, `internalServerError`, `inferenceHookDenied` (new in 0.169), `cyberPolicy`,
  `tooManyDenials`, `{"httpConnectionFailed":{"httpStatusCode":502}}`,
  `{"responseStreamDisconnected":{...}}`, `other`, and others. Unknown strings are allowed.
- Other useful notifications: `thread/status/changed` (`{"type":"active","activeFlags":
  ["waitingOnApproval"|"waitingOnUserInput"]}`), `serverRequest/resolved` `{threadId, requestId}`,
  `turn/diff/updated` `{threadId, turnId, diff}` (one unified diff for the turn),
  `account/rateLimits/updated`, `model/rerouted` `{fromModel, toModel, reason}`, `warning`
  `{threadId, message}`, `configWarning`, `deprecationNotice`.

VERIFY: the real order and content of deltas in a logged-in turn on the phone.

## CX-12 Items

`ThreadItem` (0.169.0 `v2/ThreadItem.ts`). Each item has `type`, `id`, and optional
`startedAtMs`, `completedAtMs`.

| type | Fields for the UI |
|---|---|
| `userMessage` | `content: UserInput[]`, `clientId` |
| `agentMessage` | `text` (Markdown), `phase` (`commentary` \| `partial_answer` \| `final_answer` \| null), `delivery` (`async` \| null), `questions` (CX-16), `moderation` (new in 0.169) |
| `reasoning` | `summary: string[]`, `content: string[]` |
| `commandExecution` | `command` (one string), `cwd`, `status` (`inProgress` \| `completed` \| `failed` \| `declined`), `commandActions` (`read` {name, path} \| `listFiles` \| `search` {query, path} \| `unknown`), `aggregatedOutput`, `exitCode`, `durationMs`, `processId`, `source` (`agent` \| `userShell` \| ...) |
| `fileChange` | `changes: [{path, kind, diff}]`, `status` (same 4 values). `kind`: `{"type":"add"}` (diff = the new file text), `{"type":"delete"}` (diff = the old text), `{"type":"update","move_path":null}` (diff = a unified diff; a move adds "Moved to: ...") (`item_builders.rs`) |
| `mcpToolCall` | `server`, `tool`, `status` (`inProgress` \| `completed` \| `failed`), `arguments`, `result` {content, structuredContent, _meta}, `error` {message}, `durationMs` |
| `webSearch` | `query`, `action` (`search` {query, queries} \| `openPage` {url} \| `findInPage` {url, pattern} \| `other`), `results` |
| `plan` | `text` (the proposed plan of Plan mode, Markdown) |
| `contextCompaction` | only `id` |
| `imageView` | `path` |
| `enteredReviewMode`, `exitedReviewMode` | `review` |
| `dynamicToolCall`, `collabAgentToolCall`, `subAgentActivity`, `hookPrompt`, `functionCallOutput`, `sleep`, `imageGeneration` | Show a small generic card, or skip. |

There is no "todo" item. The todo list is `turn/plan/updated` (CX-18).

## CX-13 Command approval

Server request (shape from schema; `kind` defaults to `command`):

```
{"id":0,"method":"item/commandExecution/requestApproval","params":{"kind":"command","threadId":"T","turnId":"U","itemId":"C","startedAtMs":1794956600000,"approvalId":null,"environmentId":"local","reason":null,"command":"git push --force","cwd":"/root/repos/app","commandActions":[{"type":"unknown","command":"git push --force"}],"proposedExecpolicyAmendment":["git","push"],"availableDecisions":["accept",{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["git","push"]}},"cancel"]}}
```

Answer: `{"id":0,"result":{"decision":"accept"}}`.

`CommandExecutionApprovalDecision` (`v2/item.rs`):

| decision JSON | Meaning | TUI label (`tui/src/bottom_pane/approval_overlay.rs`) |
|---|---|---|
| `"accept"` | Run once. | "Yes, proceed" (network: "Yes, just this once") |
| `"acceptForSession"` | Run, and do not ask again for this command in this session. | "Yes, and don't ask again for this command in this session" (network: "... allow this host for this conversation") |
| `{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["git","push"]}}` | Run, and save a rule for commands with this prefix. | "Yes, and don't ask again for commands that start with `git push`" |
| `{"applyNetworkPolicyAmendment":{"network_policy_amendment":{"host":"example.com","action":"allow"}}}` | Save an allow or deny rule for the host. | "Yes, and allow this host in the future" / "No, and block this host in the future" |
| `"decline"` | Do not run; the turn continues. | "No, continue without running it" |
| `"cancel"` | Do not run, and interrupt the turn. | "No, and tell Codex what to do differently" (Esc) |

- Show the options in `availableDecisions` order (experimental field; it keeps the TUI order).
  The core default (`protocol/src/approvals.rs` `default_available_decisions`): normal command
  `[accept, acceptWithExecpolicyAmendment if proposed, cancel]`; network
  `[accept, acceptForSession, applyNetworkPolicyAmendment(allow) if proposed, cancel]`; extra
  permissions `[accept, cancel]`. Without the opt-in, use the same rule from the request fields.
- `networkApprovalContext` `{host, protocol}` set: the request is for network access; then
  `command` and `cwd` are null.
- `kind: "writeStdin"`: input to a running terminal. `kind: "humanCommand"` (new in 0.169): only
  with `requiredHumanApproval: true`; only `accept`, `decline`, `cancel` are valid.
- Order (doc page): `item/started` (the pending `commandExecution`) → request → answer →
  `serverRequest/resolved` → `item/completed` (`status: declined` after a no).

## CX-14 File change approval

```
{"id":1,"method":"item/fileChange/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"F","startedAtMs":1794956600000,"reason":null,"grantRoot":null}}
```

(shape from schema). The request has no diff. Take the changes from the `fileChange` item with the
same `itemId` (from `item/started` or `item/fileChange/patchUpdated`).

Answer: `{"id":1,"result":{"decision":"accept"}}`. `FileChangeApprovalDecision`: `accept`
("Yes, proceed"), `acceptForSession` ("Yes, and don't ask again for these files"), `decline` (no;
the turn continues), `cancel` (no, and interrupt; TUI: "No, and tell Codex what to do
differently"). The TUI does not show `decline`.

## CX-15 Other server requests

- `item/permissions/requestApproval` `{threadId, turnId, itemId, environmentId, startedAtMs, cwd,
  reason, permissions:{network:{enabled}, fileSystem:{read, write, entries}}}`. Answer
  `{"permissions":{...granted subset...},"scope":"turn"|"session","strictAutoReview":true?}`. A
  refusal is `{"permissions":{},"scope":"turn"}` (TUI "No, continue without permissions"). TUI
  options: grant for this turn, grant for this turn with strict auto review, grant for this session,
  no. With full access this request should not come.
- `mcpServer/elicitation/request` `{threadId, turnId, serverName, mode, ...}`. Modes: `form`
  {message, requestedSchema, _meta}, `url` {message, url, elicitationId}, `openai/form`. Answer
  `{"action":"accept"|"decline"|"cancel","content":{...}|null,"_meta":null}`. MCP tool-call
  approvals come here too (`features.tool_call_mcp_elicitation`, stable, on): mode `form`, empty
  schema, `_meta.codex_approval_kind = "mcp_tool_call"`, `_meta.persist` = `"session"`,
  `"always"` or both, `_meta.tool_name`. Answer "Allow" = `accept`; "Allow for this session" =
  `accept` with `"_meta":{"persist":"session"}`; "Allow and don't ask me again" = `accept` with
  `"_meta":{"persist":"always"}`; "Cancel" = `cancel` (`core/src/mcp_tool_call.rs`).
- `item/tool/call` (dynamic tools): only when the client registers tools on `thread/start`
  (experimental). Not used.
- `account/chatgptAuthTokens/refresh`: only for external token login. Not used.
- `attestation/generate`: only with `requestAttestation: true`. Not used.

## CX-16 Questions with choices (request_user_input)

Server request `item/tool/requestUserInput` (shape from schema):

```
{"id":2,"method":"item/tool/requestUserInput","params":{"threadId":"T","turnId":"U","itemId":"Q","isBlocking":true,"autoResolutionMs":null,"questions":[{"id":"db_choice","header":"Database","question":"Which storage do you want?","isOther":true,"isSecret":false,"options":[{"label":"SQLite (Recommended)","description":"One local file, no server."},{"label":"JSON file","description":"Simple, no queries."}]}]}}
```

Answer (TUI format, `tui/src/bottom_pane/request_user_input/mod.rs`):

```
{"id":2,"result":{"answers":{"db_choice":{"answers":["SQLite (Recommended)"]}}}}
{"id":2,"result":{"answers":{"db_choice":{"answers":["user_note: Use one SQLite file per user"]}}}}
{"id":2,"result":{"answers":{"db_choice":{"answers":["JSON file","user_note: keep it small"]}}}}
```

- Single choice only. There is no multi-select field. The answer list holds at most the selected
  label and one `user_note: <text>` entry (free text, "Other", or a note on an option).
- 1 to 3 questions, 2 to 3 options each. Codex sets `isOther: true` on each question; the client
  adds the "Other" free-text field (`core/src/tools/handlers/request_user_input_spec.rs`).
  `isSecret` is false from the model; mask the field when it is true.
- An answer with no entry for a question (or `{"answers":{}}`) means "not answered".
- When: only in Plan mode (`ModeKind::allows_request_user_input`). In Default mode only with the
  under-development feature `default_mode_request_user_input`. The tool is on unless
  `[tools.experimental_request_user_input] enabled = false`.
- Esc in the TUI interrupts the turn (`turn/interrupt`).
- `autoResolutionMs` is deprecated; use `isBlocking` (true in Plan mode).
- Other form: some catalog models have the tool `request_user_input_async`. Its questions come in
  an `agentMessage` with `delivery: "async"` and `questions: [{title, options: string[] | null}]`.
  There is no server request. The user answers in the next normal message.

## CX-17 Plan mode (collaboration modes)

- Two modes: `default` and `plan` (`protocol/src/config_types.rs`, `ModeKind`). The old feature
  `collaboration_modes` is "Removed" (always on).
- Switch with `turn/start.collaborationMode` (experimental). `settings.model` is required:

```
{"id":11,"method":"turn/start","params":{"threadId":"T","input":[{"type":"text","text":"Plan a login screen"}],"collaborationMode":{"mode":"plan","settings":{"model":"gpt-6.1-sol","reasoning_effort":"medium","developer_instructions":null}}}}
```

  (shape from schema). `developer_instructions: null` = the built-in instructions of the mode
  (`turn_processor.rs`). Send `{"mode":"default",...}` to go back.
- `collaborationMode/list` (experimental) gives the presets. From
  `models-manager/src/collaboration_mode_presets.rs`:
  `{"data":[{"name":"Plan","mode":"plan","model":null,"reasoning_effort":"medium"},{"name":"Default","mode":"default","model":null,"reasoning_effort":null}]}`.
- `thread/resume` returns the active `collaborationMode`.
- In Plan mode, the model explores, asks questions (CX-16) and ends with a `<proposed_plan>` block.
  The server makes a `plan` item from it (`item/plan/delta`, then `item/completed` with
  `{"type":"plan","text":"..."}`); the plan text is not in the `agentMessage`.
- After a plan, the TUI shows "Implement this plan?": "Yes, implement this plan" sends
  "Implement the plan." with the Default mode; "Yes, clear context and implement" starts a new
  thread with the plan; "No, stay in Plan mode" (`tui/src/chatwidget/plan_implementation.rs`).

VERIFY: the mode stays for later turns when a `turn/start` has no `collaborationMode` (not
confirmed; send it on each `turn/start`).

## CX-18 Todo list and diff

- `turn/plan/updated` `{threadId, turnId, explanation, plan:[{step, status: "pending" |
  "inProgress" | "completed"}]}` comes from the `update_plan` tool.
- In 0.169.0 this tool is off by default: `resolve_update_plan_enabled` is true only with
  `[tools.update_plan] enabled = true` in `config.toml` (`core/src/config/mod.rs`). Without it,
  there is no todo list.
- `turn/diff/updated` `{threadId, turnId, diff}` gives one unified diff for all changes of the turn.

VERIFY: whether the owner wants `[tools.update_plan] enabled = true` (it changes the model
instructions).

## CX-19 Interrupt

```
{"id":12,"method":"turn/interrupt","params":{"threadId":"T","turnId":"U"}}
{"id":12,"result":{}}
```

- The answer comes only after the turn stops (`TurnAborted`), not at once
  (`turn_processor.rs`, `respond_to_pending_interrupts`). Then `turn/completed` with
  `status: "interrupted"`.
- Wrong turn id: -32600 `"expected active turn id X but found Y"`. No turn: -32600
  `"no active turn to interrupt"`.
- At the end of a turn the server drops its open requests (approvals, questions) with an internal
  error `"client request resolved because the turn state was changed"`. A later answer has no
  effect. Close all open cards of the turn on `turn/completed`.

## CX-20 Models and reasoning effort

Real output (0.166.0, logged out, from the built-in catalog; one entry, efforts shortened):

```
>> {"id":3,"method":"model/list","params":{"limit":50,"includeHidden":false}}
<< {"id":3,"result":{"data":[{"id":"gpt-6.1-sol","model":"gpt-6.1-sol","upgrade":null,"displayName":"GPT-6.1-Sol","description":"Latest workhorse model for coding and everyday work.","hidden":false,"supportedReasoningEfforts":[{"reasoningEffort":"low","description":"Fast responses with lighter reasoning"},{"reasoningEffort":"medium","description":"Balances speed and reasoning depth for everyday tasks"},...],"defaultReasoningEffort":"low","inputModalities":["text","image"],"supportsPersonality":false,"serviceTiers":[{"id":"priority","name":"Fast","description":"2x speed, increased usage"}],"defaultServiceTier":null,"isDefault":true},...],"nextCursor":null}}
```

- 9 models in 0.166.0: `gpt-6.1-sol` (default), `gpt-6-astra`, `gpt-6-sol`, `gpt-6-luna`,
  `gpt-5.6-sol`, `gpt-6.1-terra`, `gpt-5.6-terra`, `gpt-5.6-luna`, `gpt-5.5`. Efforts: `low`,
  `medium`, `high`, `xhigh`, `max`, `ultra` (not all models have `max` and `ultra`).
  0.169.1 adds "GPT-6.2 Sol and Astra" to the catalog.
- `ReasoningEffort` is a free string in the schema. Show `supportedReasoningEfforts` of the selected
  model, with their `description`.
- Change for the next turn: `turn/start` with `"model":"gpt-6-astra","effort":"high"`. It stays
  for later turns. `serviceTier: "priority"` is the "Fast" option.
- Follow `nextCursor` until null.

VERIFY: the list after a login (the server can refresh the catalog from OpenAI).

## CX-21 Old threads and resume

- List for one folder (shape from schema):
  `{"id":20,"method":"thread/list","params":{"cwd":"/root/repos/app","limit":25,"sortKey":"updated_at"}}`
  → `{"data":[Thread,...],"nextCursor":"...","backwardsCursor":"..."}`. `cwd` is an exact match
  (a string or a list). Default sources: `cli`, `vscode` (and two custom ones,
  `rollout/src/lib.rs` `INTERACTIVE_SESSION_SOURCES`), so TUI threads show too. `Thread.preview`
  is usually the first user message; `name` is the title; `updatedAt` in seconds.
- Resume: `{"id":21,"method":"thread/resume","params":{"threadId":"T","excludeTurns":true}}`.
  The answer has the same fields as `thread/start`, plus `collaborationMode`,
  `turnsBackwardsCursor` and `itemsBackwardsCursor`. Resume accepts the same overrides
  (`approvalPolicy`, `sandbox`, `model`, `cwd`).
- History: new threads are `historyMode: "paginated"`. Full history in `thread/resume` or
  `thread/read` (`includeTurns`) is deprecated for them and sends a `deprecationNotice`
  (`thread_processor.rs`). Use
  `{"method":"thread/turns/list","params":{"threadId":"T","limit":20,"sortDirection":"desc","itemsView":"full"}}`
  (`itemsView`: `notLoaded`, `summary` = first user message and last agent message, `full`) or
  `thread/items/list` `{threadId, turnId?, cursor, limit, sortDirection}` →
  `{"data":[{"turnId","item","startedAtMs","completedAtMs"}],"nextCursor","backwardsCursor"}`.
  Items have the same shape as live items (CX-12).
- A thread that runs in the TUI at the same time: both processes use the same `~/.codex`
  (thread writer locks, SQLite state).

VERIFY: resume of a thread that the TUI made, and of a thread that is open in the TUI.

## CX-22 Login inside the app-server

- Browser: `{"method":"account/login/start","id":30,"params":{"type":"chatgpt"}}` →
  `{"type":"chatgpt","loginId":"<uuid>","authUrl":"https://auth.openai.com/oauth/authorize?..."}`.
  The redirect is `http://127.0.0.1:1455/auth/callback` (`login/src/server.rs`), the same as the
  TUI login (F-11). Then `account/login/completed` `{loginId, success, error}` and
  `account/updated`.
- Device code: `{"type":"chatgptDeviceCode"}` →
  `{"type":"chatgptDeviceCode","loginId":"...","verificationUrl":"https://auth.openai.com/codex/device","userCode":"ABCD-1234"}`
  (doc page; `login/src/device_code_auth.rs`). It needs no local callback port; the server polls
  for 15 minutes. If the server has no device login: "device code login is not enabled for this
  Codex server".
- API key: `{"type":"apiKey","apiKey":"sk-..."}` → `{"type":"apiKey"}`.
- Cancel: `account/login/cancel` `{loginId}` → `{"status":"canceled"|"notFound"}`. Logout:
  `account/logout` → `{}`.

Note only. Automa can keep the TUI login. The device code flow is a good option for the phone,
because the browser does not need to reach a port in the guest.

VERIFY: the device code login on the phone (owner, a live test).

## CX-23 Errors and process exit

- JSON-RPC codes (`app-server/src/error_code.rs`): -32600 invalid request, -32601 method not
  found, -32602 invalid params, -32603 internal, -32001 "Server overloaded; retry later.".
  Shutdown: -32600 with `data.reason = "serverShuttingDown"`.
- Close stdin: the server stops with exit code 0 (real output: about 0.1 s after stdin EOF).
- A crash or a kill: stdout gets EOF. Mark all open requests as failed and show a restart action.
  PRoot does not stop guest children (F-8), so stop the process tree with `env/Procs.kt`.
- Drain stderr all the time; a full pipe can block the server.
- Lines can be long (a `model/list` answer is about 9 KB; `aggregatedOutput` can be much more). Use
  a line reader with a high limit.

## CX-24 Changes between versions

- Newest stable: 0.169.1 (2026-11-17). Its 4 commits do not touch `app-server-protocol` (Bedrock
  and the model catalog only). Pre-releases: 0.171.0-alpha.12.
- 0.166.0 → 0.169.0, stable schema diff: `account/read.userId`; command approval `kind`
  `humanCommand` and capability `requiredHumanApproval`; `agentMessage.moderation`;
  `item/completed.contentTokenCount`; `model/list.modelProvider`; `thread/list.currentAccountOnly`;
  `thread/items/list.deferOutput`; `codexErrorInfo` `inferenceHookDenied`; notification
  `thread/section/changed`; new methods `modelProvider/list`, `modelProvider/config/write`,
  `account/securitySetup/read`, gateway OAuth. No change to `ToolRequestUserInput*` or
  `CommandExecutionRequestApprovalParams`.
- Removed (0.169.0): `thread/rollback` (use `thread/revert`). Deprecated: `thread/compacted`
  (use the `contextCompaction` item), `item/fileChange/outputDelta`, `personality`, full history in
  resume and read.
- Experimental (can change with no notice): `collaborationMode`, `collaborationMode/list`,
  `availableDecisions`, `thread/settings/update`, `turn/settings/update`, `item/plan/delta`,
  `item/tool/requestUserInput` (marked "EXPERIMENTAL" in the source).

## Recommended client design

1. Start `codex app-server --disable plugins` through `Env.run` in the repository folder (no PTY,
   pipes only). Read stdout line by line, drain stderr. One process for each chat screen, or one for
   the app with many threads.
2. Send `initialize` with `clientInfo {"name":"automa","title":"Automa","version":<app>}` and
   `capabilities {"experimentalApi":true,"optOutNotificationMethods":["remoteControl/status/changed","rawResponseItem/completed","rawResponse/completed"]}`,
   then `initialized`.
3. `account/read`. `account == null && requiresOpenaiAuth` → show "Log in to Codex" (TUI login,
   or later `account/login/start` with `chatgptDeviceCode`).
4. Trust: if `~/.codex/config.toml` has no `[projects."<path>"]` entry, ask the user first and
   write the answer (CX-6). Then `thread/start {cwd, approvalPolicy}`, or `thread/resume
   {threadId, excludeTurns:true}` + `thread/turns/list {itemsView:"full"}` for an old thread.
   Approval choice for the user: "Ask for each command" = `untrusted`; "Ask only for dangerous
   commands" = `on-request`.
5. `model/list` once; `collaborationMode` presets from CX-17. Send `model`, `effort` and
   `collaborationMode` on each `turn/start`.
6. Handle these notifications: `turn/started`, `turn/completed`, `item/started`,
   `item/completed`, `item/agentMessage/delta`, `item/reasoning/summaryTextDelta`,
   `item/reasoning/summaryPartAdded`, `item/commandExecution/outputDelta`,
   `item/fileChange/patchUpdated`, `item/plan/delta`, `turn/plan/updated`, `turn/diff/updated`,
   `thread/tokenUsage/updated`, `thread/status/changed`, `serverRequest/resolved`, `error`,
   `warning`, `account/updated`, `account/login/completed`, `account/rateLimits/updated`,
   `model/rerouted`, `thread/closed`.
7. Handle these server requests with native cards: `item/commandExecution/requestApproval`,
   `item/fileChange/requestApproval`, `item/tool/requestUserInput`, `mcpServer/elicitation/request`,
   `item/permissions/requestApproval`. Answer each other request with a -32601 error. Close the
   cards on `serverRequest/resolved` and on `turn/completed`.
8. Stop button: `turn/interrupt {threadId, turnId}`; wait for `turn/completed`.
9. Ignore: `item/reasoning/textDelta` (raw reasoning), `thread/realtime/*`, `fuzzyFileSearch/*`,
   `command/exec/*`, `process/*`, `fs/*`, `hook/*`, `mcpServer/startupStatus/updated`,
   `skills/changed`, `app/list/updated`, `thread/goal/*`, `thread/attachment/*`, the Windows
   notifications, and all unknown methods (log the name only).
10. Parse with `org.json` and accept unknown fields and unknown enum strings (the server sends
    fields that are not in the stable schema, for example `environments`, `extra`).
