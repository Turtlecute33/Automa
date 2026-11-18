# Chat UI for Claude Code: the stream-json protocol

Research on 2026-11-17. Sources: the npm package `@anthropic-ai/claude-agent-sdk` 0.3.311 (its
`claudeCodeVersion` is 2.1.311) and 0.3.326 (newest; `sdk.d.ts`, `sdk-tools.d.ts`, `sdk.mjs`); the
strings in the Claude Code 2.1.311 `linux-arm64-musl` program (npm package
`@anthropic-ai/claude-agent-sdk-linux-arm64-musl` 0.3.311, SHA-256 `172150e3…09b9`, the same as
`Pins.CLAUDE_SHA256`; read only, not run); the docs on code.claude.com (`agent-sdk/user-input`,
`agent-sdk/permissions`, `agent-sdk/sessions`, `agent-sdk/streaming-output`,
`agent-sdk/streaming-vs-single-mode`, `agent-sdk/configuration`, `agent-sdk/slash-commands`,
`headless`, `cli-reference`); `claude --help` of 2.1.322 on the Mac. There was no logged-in run and
no prompt. Thus each statement about behaviour at run time has a VERIFY line. In the JSON examples,
the keys come from the source that is named. The values (ids, text, paths) are made up.

Short names in this file: "SDK" is the Agent SDK package. "CLI" is the `claude` program. "host" is
the program that writes to the stdin of the CLI (later: Automa).

## CC-1 Transport: one JSON object for each line, in the two directions

The SDK starts the CLI with three pipes (`stdio: ["pipe","pipe","pipe"]`, `sdk.mjs` 0.3.311). Each
message is one JSON object on one line, with `\n` at the end (`se(o)+"\n"`). The SDK does not use a
PTY. The CLI writes its log to stderr (`--debug-file` or `--debug-to-stderr`), not to stdout.

Line types on stdout (CLI to host), from the `SDKMessage` union and the `readMessages()` loop of
`sdk.mjs`:

- `system` (with a `subtype`), `assistant`, `user`, `stream_event`, `result`, `tool_progress`,
  `tool_use_summary`, `auth_status`, `rate_limit_event`, `conversation_reset`, `prompt_suggestion`.
- `control_request` (the CLI asks the host, for example `can_use_tool`).
- `control_response` (the answer to a request of the host).
- `control_cancel_request` (the CLI does not need the answer any more).
- `keep_alive` (no payload; "receivers must ignore it").

Line types on stdin (host to CLI): `user`, `control_request`, `control_response`,
`control_cancel_request`, `keep_alive`.

The envelopes (`SDKControlRequest`, `SDKControlResponse`, `ControlResponse`,
`ControlErrorResponse`, `SDKControlCancelRequest` in `sdk.d.ts` 0.3.311):

```json
{"type":"control_request","request_id":"k3j9x0a1b2c","request":{"subtype":"interrupt"}}
{"type":"control_response","response":{"subtype":"success","request_id":"k3j9x0a1b2c","response":{}}}
{"type":"control_response","response":{"subtype":"error","request_id":"k3j9x0a1b2c","error":"text"}}
{"type":"control_cancel_request","request_id":"k3j9x0a1b2c"}
```

The sender selects the `request_id`. It must be unique among the open requests of that sender. The
SDK uses `Math.random().toString(36).substring(2,15)`. "The receiver normally answers with exactly
one control_response carrying the same request_id."

- If the CLI sends a request that the host cannot do, the SDK answers with `subtype: "error"` (the
  handler throws). Exception: for `request_user_dialog` of a kind that the host did not declare, the
  host must not answer ("an error-subtype response is discarded and the dialog stays pending").
- The SDK answers an MCP `elicitation` with `{"action":"decline"}` when it has no handler.

VERIFY: the maximum line length on the phone (a `Write` of a large file or a long `Read` result is
one line).

## CC-2 Start: the command line and the environment

The SDK (`ProcessTransport.initialize()`, `sdk.mjs` 0.3.311, the same in 0.3.326) always passes:

```
--output-format stream-json --verbose --input-format stream-json
```

and adds, from its options: `--permission-prompt-tool=stdio` (when `canUseTool` is set),
`--include-partial-messages`, `--model <m>`, `--permission-mode <mode>`, `--resume <id>`,
`--continue`, `--session-id <uuid>`, `--fork-session`, `--setting-sources <list>`, `--effort <level>`,
`--thinking=adaptive|disabled`, `--max-thinking-tokens <n>`, `--thinking-display <d>`,
`--allowedTools <list>`, `--disallowedTools <list>`, `--tools <list>`, `--mcp-config <json>`,
`--strict-mcp-config`, `--add-dir <dir>`, `--fallback-model <m>`, `--max-turns <n>`,
`--max-budget-usd <n>`, `--no-session-persistence`, `--include-hook-events`.

The SDK does not pass `-p`. The CLI is non-interactive when stdout is not a TTY
(`return n||t||o||!process.stdout.isTTY`, CLI 2.1.311). With `-p` it is the same. The CLI refuses
some combinations:

- "Error: When using --print, --output-format=stream-json requires --verbose"
- "Error: --input-format=stream-json requires output-format=stream-json."

Environment that the SDK sets (`sdk.mjs` 0.3.311):

- `CLAUDE_CODE_ENTRYPOINT=sdk-ts` (only when it is not set). Without it, the CLI sets `sdk-cli` in
  non-interactive mode and `cli` in the TUI. The transcript keeps this value (CC-21).
- `CLAUDE_AGENT_SDK_VERSION=0.3.311`.
- `CLAUDE_CODE_SDK_READS_SESSION_STATE=1`. With it, the CLI writes
  `{"type":"system","subtype":"session_state_changed","state":"running"}` (also `idle`,
  `requires_action`). The doc comment calls `idle` the "authoritative turn-over signal".
- The SDK deletes `NODE_OPTIONS`.

`--permission-prompt-tool` does not show in `claude --help` (a hidden option). `--thinking <mode>`
and `--thinking-display <display>` are also hidden options in 2.1.311.

`--permission-mode` in 2.1.322 `--help` lists `acceptEdits, auto, bypassPermissions, manual,
dontAsk, plan`. `manual` is an alias for `default` (CLI 2.1.311: `function Ky(e){return
e==="manual"?"default":e}`; cli-reference: "requires Claude Code v2.1.200 or later"). From 2.1.302,
`claude -p` starts in `default` mode when no setting gives a mode (agent-sdk/permissions).

## CC-3 The `initialize` control request and its answer

The host sends `initialize` as the first control request. The SDK writes the first user message only
after it wrote `initialize` (`writeAfterInitialize`). With the default SDK options, all fields are
undefined, so the request on the wire is:

```json
{"request_id":"a8f3k2m9q1z","type":"control_request","request":{"subtype":"initialize"}}
```

Optional fields of `SDKControlInitializeRequest` (0.3.311) that can help Automa:
`appendSystemPrompt`, `systemPrompt`, `title`, `promptSuggestions`, `supportedDialogKinds`,
`perTaskStopAffordance`, `agents`, `hooks`. Automa does not need them for the first version.

The answer (`SDKControlInitializeResponse`, plus the two lists of `ControlResponse`):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"a8f3k2m9q1z",
 "response":{
  "commands":[{"name":"compact","description":"Clear conversation history but keep a summary in context","argumentHint":"<optional custom summarization instructions>","builtin":true}],
  "agents":[{"name":"Explore","description":"Read-only search agent","model":"haiku"}],
  "output_style":"default","available_output_styles":["default","Explanatory","Learning"],
  "models":[{"value":"opus","resolvedModel":"claude-opus-5","displayName":"Opus","description":"Most capable","supportsEffort":true,"supportedEffortLevels":["low","medium","high","xhigh","max"],"supportsAdaptiveThinking":true,"supportsFastMode":true,"supportsAutoMode":true}],
  "account":{"email":"user@example.com","subscriptionType":"max","tokenSource":"claude.ai","apiKeySource":"none","apiProvider":"firstParty"},
  "pid":4242,"claude_code_version":"2.1.311","fast_mode_state":"off"},
 "pending_permission_requests":[],"pending_user_dialog_requests":[]}}
```

- `pending_permission_requests` is "Always present (possibly empty) on a success initialize
  response from Claude Code v2.1.268 or later".
- A second `initialize` to a running CLI is permitted (`reinitialize()`). The answer lists the
  `can_use_tool` requests that still wait. Automa can use this after the app was in the background.
- With `--await-initialize` (0.3.326 docs), the first stdin line must be `initialize`, else the CLI
  stops with `startup_failure_reason: "initialize_not_first"`. Without that flag, the order is free.

VERIFY: the real content of `models`, `commands` and `account` for a Claude subscription login.

## CC-4 The `system` messages at the start of a turn

`system/init` (`SDKSystemMessage`): "Session metadata the CLI emits at the start of each turn,
normally ahead of every other message of that turn". Fields: `session_id`, `model`, `cwd`, `tools`,
`mcp_servers`, `permissionMode`, `slash_commands`, `terminal_slash_commands`, `skills`, `plugins`,
`agents`, `apiKeySource`, `claude_code_version`, `output_style`, `fast_mode_state`, `effort`,
`view_mode`, `capabilities`.

```json
{"type":"system","subtype":"init","session_id":"5b3f2c1a-8d4e-4f6b-9a7c-2e1d0f9b8a6c","cwd":"/root/repos/demo",
 "model":"claude-opus-5","permissionMode":"default","apiKeySource":"none","claude_code_version":"2.1.311",
 "tools":["Agent","AskUserQuestion","Bash","Edit","ExitPlanMode","Glob","Grep","Read","TodoWrite","WebFetch","Write"],
 "mcp_servers":[],"slash_commands":["clear","compact","context","usage","model"],"terminal_slash_commands":["exit"],
 "skills":[],"plugins":[],"output_style":"default","capabilities":["interrupt_receipt_v1","interrupt_cancel_queued_v1"],
 "uuid":"0f2c…"}
```

- `terminal_slash_commands`: "Phone/remote UIs should hide these from command menus".
- `capabilities`: use it for feature checks, not the version text.

Other `system` subtypes that the chat UI can show:

- `status`: `{"type":"system","subtype":"status","status":"compacting"|"requesting"|null,"permissionMode":"plan"}`.
  It also reports a mode change (for example after `set_model` turns off `auto`).
- `api_retry`: `attempt`, `max_retries`, `retry_delay_ms`, `error_status`, `error`.
- `compact_boundary`: `compact_metadata.trigger` (`manual`/`auto`), `pre_tokens`, `post_tokens`.
- `permission_denied`: a tool call that a rule or the mode denied with no prompt (`tool_name`,
  `tool_use_id`, `message`, `decision_reason_type`).
- `local_command_output`: `content` (output of a local slash command).
- `informational`: `content`, `level` (`info`, `notice`, `suggestion`, `warning`).
- `session_state_changed` (CC-2).
- `commands_changed`: a new full list of commands ("REPLACE their cached command list").
- `task_started`, `task_progress`, `task_updated`, `task_notification`, `background_tasks_changed`:
  background shells and subagents (CC-23).

VERIFY: does `system/init` come before the first user message, or only at the start of the first
turn? Until this is known, Automa gives the session id itself with `--session-id` (CC-21).

## CC-5 Login state and start errors

- Before the start: `claude auth status` writes JSON and "Exits with code 0 if logged in, 1 if
  not" (cli-reference). CLI 2.1.311 source: `s={loggedIn:N,authMethod:h,apiProvider:b,
  analyticsDisabled:…,projectsDirectory:…,configDirectory:…}`, then email, orgId, orgName,
  subscriptionType for `claude.ai`, and `process.exit(N?0:1)`. `authMethod` is one of `none`,
  `claude.ai`, `oauth_token`, `api_key`, `api_key_helper`, `third_party`.
- In a turn: the CLI text for no login is `"Not logged in · Please run /login"` (CLI 2.1.311,
  `var pan=`). Other texts: `"OAuth token revoked · Please run /login"`, `"Login expired · Please
  run /login"`, `"Invalid API key · Fix external API key"`.
- `SDKAssistantMessage.error` is one of `authentication_failed`, `oauth_org_not_allowed`,
  `account_on_hold`, `verification_required`, `billing_error`, `rate_limit`, `overloaded`,
  `invalid_request`, `model_not_found`, `server_error`, `unknown`, `max_output_tokens`,
  `cloud_credential_error`. The same names are in `system/api_retry`.
- Docs (headless): "When a failure happens inside the run, such as missing authentication, Claude
  Code prints the failure as the result on stdout."
- Start failures: a zeroed `result` with `subtype: "error_during_execution"` and
  `startup_failure_reason` (for example `cwd_unavailable`, `temp_dir_unusable`, `bypass_root`,
  `cli_version_too_old`, `config_file_invalid` in 0.3.326). `errors` has the stderr text. For older
  causes the CLI writes this result "only when the host sets CLAUDE_CODE_STARTUP_FAILURE_RESULTS"
  (the variable is in CLI 2.1.311).
- Invalid flags: an error on stderr before the run, and a non-zero exit code.
- `/login` does not work in `-p` (CC-22). The user logs in in the terminal (the TUI), as today.

VERIFY: the exact lines (assistant with `error: "authentication_failed"`, then `result` with
`is_error: true`) when the login is missing or expired.

## CC-6 Send a user message

From `sdk.mjs` 0.3.311 (a string prompt becomes this line):

```json
{"type":"user","session_id":"","message":{"role":"user","content":[{"type":"text","text":"Fix the typo in README.md"}]},"parent_tool_use_id":null}
```

- `content` can also be a plain string (docs, streaming-vs-single-mode).
- Optional `uuid` (a UUID of the host). The CLI sends it back as `user_message_uuid` and
  `user_message_uuids` on the first reply frames and on `result`. Automa can use it to link a reply
  to the message that the user sent.
- Optional `priority`: `now`, `next`, `later`. A message that the host sends while tools run "reaches
  Claude after those tool calls finish" (docs).
- `@path` mentions and `/commands` in the text work. `client_composed: true` turns them off.
- Images (note only): an `image` block in `content`:
  `{"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64>"}}`
  (docs, streaming-vs-single-mode).
- `--replay-user-messages` makes the CLI write each stdin user message back on stdout
  (`isReplay: true`). Automa does not need it.

## CC-7 Streaming: `stream_event` lines (`--include-partial-messages`)

`SDKPartialAssistantMessage`: `{"type":"stream_event","event":<one Messages API stream event>,
"parent_tool_use_id":null,"uuid":"…","session_id":"…"}`. The `event` is a raw Anthropic streaming
event: `message_start`, `content_block_start`, `content_block_delta`, `content_block_stop`,
`message_delta`, `message_stop`.

```json
{"type":"stream_event","event":{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}},"parent_tool_use_id":null,"uuid":"…","session_id":"…"}
{"type":"stream_event","event":{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"The user wants"}},"parent_tool_use_id":null,"uuid":"…","session_id":"…"}
{"type":"stream_event","event":{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"I will fix"}},"parent_tool_use_id":null,"uuid":"…","session_id":"…"}
{"type":"stream_event","event":{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_01Ab","name":"Edit","input":{}}},"parent_tool_use_id":null,"uuid":"…","session_id":"…"}
{"type":"stream_event","event":{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"file_path\": \"/root/re"}},"parent_tool_use_id":null,"uuid":"…","session_id":"…"}
```

Order (docs, streaming-output): `message_start`, `content_block_start`, deltas, then the full
`assistant` message of that block, then `content_block_stop`; the same for the next block; then
`message_delta`, `message_stop`. Rules from the docs:

- Stream events come only for the main session. Subagents send no token deltas.
- A cut stream still ends with `message_stop` (from 2.1.290). `content_block_stop` can be missing
  for an open `tool_use` block: then the tool input is not complete.
- `signature_delta` events carry the thinking signature. Automa ignores them.
- Thinking text can be empty: the display mode decides (`--thinking-display summarized|omitted`,
  or `set_max_thinking_tokens` with `thinking_display`, CC-20).

VERIFY: the default thinking display of 2.1.311 for the subscription models on the phone.

## CC-8 Full `assistant` messages and content blocks

`SDKAssistantMessage`: `message` is a Messages API message (`id`, `model`, `content`,
`stop_reason`, `usage`). "the CLI emits one assistant message per completed content block, so
several consecutive assistant messages can share message.id"; on those, `stop_reason` is null and
`usage` is not final.

```json
{"type":"assistant","message":{"id":"msg_01Xy","type":"message","role":"assistant","model":"claude-opus-5",
 "content":[{"type":"tool_use","id":"toolu_01Ab","name":"Bash","input":{"command":"npm test","description":"Run the test suite"}}],
 "stop_reason":null,"usage":{"input_tokens":12,"output_tokens":40}},
 "parent_tool_use_id":null,"uuid":"…","session_id":"…"}
```

Block types: `text` (`text`), `thinking` (`thinking`, `signature`), `redacted_thinking`, `tool_use`
(`id`, `name`, `input`). Other fields of the wrapper: `error` (CC-5), `aborted: true` (cut by an
interrupt), `parent_tool_use_id` (set inside a subagent), `agent_id`, `subagent_type`,
`supersedes` (uuids of earlier frames to remove, after a refusal fallback), `timestamp`.

Key the UI by `message.id` and the block position. Replace the streamed text with the full block
when the `assistant` line comes.

## CC-9 `user` messages with `tool_result`, and `tool_use_result`

After a tool runs, the CLI writes a `user` line. `message.content` has a `tool_result` block (the
text for the model). `tool_use_result` has the full structured output of the tool:
"render from it instead of parsing the tool_result text" (`SDKUserMessage`, 0.3.311).

```json
{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01Ab","content":"3 passed","is_error":false}]},
 "parent_tool_use_id":null,"uuid":"…","session_id":"…",
 "tool_use_result":{"stdout":"3 passed","stderr":"","interrupted":false}}
```

- `tool_result.content` can be a string or a list of blocks (`text`, `image`).
- A denied or failed tool has `is_error: true`, and the text says why.
- A WebFetch or WebSearch that "stepped aside" has `{"detachedToolCall":true}` in place of its output.

## CC-10 `result`: the end of a turn

"The CLI emits exactly one result message per turn ... treat it as the turn-complete signal"
(`SDKResultMessage`). Some `system` lines can still follow.

```json
{"type":"result","subtype":"success","is_error":false,"duration_ms":8123,"duration_api_ms":6900,"num_turns":3,
 "result":"I fixed the typo.","stop_reason":"end_turn","total_cost_usd":0.0412,
 "usage":{"input_tokens":2048,"output_tokens":310,"cache_read_input_tokens":0,"cache_creation_input_tokens":0},
 "modelUsage":{"claude-opus-5":{"inputTokens":2048,"outputTokens":310,"cacheReadInputTokens":0,"cacheCreationInputTokens":0,"webSearchRequests":0,"costUSD":0.0412,"contextWindow":200000,"maxOutputTokens":32000}},
 "permission_denials":[],"terminal_reason":"completed","uuid":"…","session_id":"5b3f2c1a-…"}
```

- `subtype`: `success` (also with `is_error: true` when the turn ended on an API error; then
  `result` has the error text), `error_during_execution` (also after an interrupt),
  `error_max_turns`, `error_max_budget_usd`, `error_max_structured_output_retries`.
- Error results have `errors: string[]` in place of `result`.
- `total_cost_usd` and `modelUsage` are running totals for the process, "read the latest result
  rather than summing". They are estimates. For a subscription login the cost is not a bill.
- `usage` is for the main loop of this turn only.
- `terminal_reason`: `completed`, `aborted_streaming`, `aborted_tools`, `max_turns`, `api_error`,
  `prompt_too_long`, `model_error`, and others (`TerminalReason`).
- `permission_denials`: `[{tool_name, tool_use_id, tool_input}]`.
- `queued_turn_count`: sends that still wait in the queue.

`rate_limit_event` (`rate_limit_info`: `status` `allowed|allowed_warning|rejected`, `resetsAt`,
`rateLimitType` `five_hour|seven_day|…`, `utilization`) can come for subscription users. Show it as
a small notice.

## CC-11 Tool cards: the input of the common tools

From `sdk-tools.d.ts` 0.3.311 (`*Input` types). The list of tools of a session is `system/init`
`tools`.

| Tool | Input fields | Card |
|---|---|---|
| `Bash` | `command`, `description?`, `timeout?`, `run_in_background?`, `dangerouslyDisableSandbox?` | Show `description`, then `command` in mono. |
| `Read` | `file_path`, `offset?`, `limit?`, `pages?`, `allow_large?` | Path and line range. |
| `Edit` | `file_path`, `old_string`, `new_string`, `replace_all?` | Diff (CC-12). |
| `Write` | `file_path`, `content` | New file or diff (CC-12). |
| `Grep` | `pattern`, `path?`, `glob?`, `output_mode?`, `-i?`, `-n?`, `-A?`, `-B?`, `-C?`, `context?`, `type?`, `head_limit?`, `offset?`, `multiline?` | Pattern and path. |
| `Glob` | `pattern`, `path?` | Pattern and path. |
| `WebFetch` | `url`, `prompt`, `offset?` | Host of the URL and the prompt. |
| `WebSearch` | `query`, `allowed_domains?`, `blocked_domains?` | Query. |
| `Agent` (old name `Task`) | `description`, `prompt`, `subagent_type?`, `model?`, `effort?`, `run_in_background?`, `name?`, `isolation?` | Subagent card (CC-23). |
| `TodoWrite` | `todos: [{content, status: pending|in_progress|completed, activeForm}]` | Checklist. |
| `AskUserQuestion` | CC-16 | Question card. |
| `ExitPlanMode` | CC-17 | Plan card. |
| `NotebookEdit` | `notebook_path`, … | Generic card. |

- `MultiEdit` is not a tool in 2.1.311 (not in `BUILTIN_TOOL_NAMES`). Old transcripts can have it.
  Show a generic card.
- Old names that rules still accept (`t_` in `sdk.mjs`): `Task`→`Agent`, `KillShell`/`KillBash`→
  `TaskStop`.
- Other tools can come: `TaskCreate`, `TaskUpdate`, `TaskList`, `TaskGet`, `TaskStop`, `Monitor`,
  `ToolSearch`, `Skill`, `EnterPlanMode`, `mcp__<server>__<tool>`. Show a generic card with the tool
  name and the JSON input (made safe, CC-26).
- Useful outputs (`*Output` types): `BashOutput` (`stdout`, `stderr`, `interrupted`,
  `backgroundTaskId?`, `gitOperation?` with commit, push and PR data), `GlobOutput` (`filenames`,
  `numFiles`, `truncated`), `GrepOutput` (`mode`, `filenames`, `content?`, `numMatches?`),
  `TodoWriteOutput` (`oldTodos`, `newTodos`), `WebFetchOutput` (`url`, `code`, `codeText`,
  `bytes`, `result`), `FileReadOutput` (`type: "text"` with `file.filePath`, `file.content`,
  `numLines`, `startLine`, `totalLines`; also `image` and other types).

## CC-12 Diffs for `Edit` and `Write`

Two sources:

1. Before the run (in the permission card): the `tool_use` input. `Edit` gives `old_string`,
   `new_string`, `replace_all`. `Write` gives `content` only. For a `Write` diff before approval,
   Automa must read the old file itself (the guest file system is local).
2. After the run: `tool_use_result` (`FileEditOutput`, `FileWriteOutput`):

```json
{"filePath":"/root/repos/demo/README.md","oldString":"teh","newString":"the","originalFile":"# Demo\nteh app\n",
 "structuredPatch":[{"oldStart":2,"oldLines":1,"newStart":2,"newLines":1,"lines":["-teh app","+the app"]}],
 "userModified":false,"replaceAll":false}
```

```json
{"type":"update","filePath":"/root/repos/demo/notes.md","content":"new text\n","originalFile":"old text\n",
 "structuredPatch":[{"oldStart":1,"oldLines":1,"newStart":1,"newLines":1,"lines":["-old text","+new text"]}]}
```

- `structuredPatch` is a list of unified-diff hunks. Each line starts with `-`, `+` or a space.
- `Write` `type` is `create` or `update`. `originalFile` is null for a new file, "or when the
  previous content was too large". `structuredPatch` can be empty ("the diff timed out").
- Optional `gitDiff` has `additions`, `deletions` and a `patch` text.

## CC-13 Permission request: the `can_use_tool` control request

The CLI sends it only when the host passed `--permission-prompt-tool stdio`. Without it, in `-p`
the CLI denies each call that needs approval (permission modes doc). The request is built in CLI
2.1.311 (`e.request({subtype:"can_use_tool",tool_name:…})`). Fields (`SDKControlPermissionRequest`):

| Field | Meaning |
|---|---|
| `tool_name`, `input`, `tool_use_id` | The call. `input` can already be normalised (for example the plan text, CC-17). |
| `display_name`, `title`, `description` | Text from the CLI for the card. `title` is "Full permission prompt sentence", for example "Claude wants to read foo.txt". |
| `permission_suggestions` | `PermissionUpdate[]` for "don't ask again" (CC-14). |
| `blocked_path` | The path that is outside the allowed folders. |
| `decision_reason`, `decision_reason_type` | Why it asks (`rule`, `mode`, `safetyCheck`, `workingDir`, `classifier`, `hook`, `other`, …). "May carry ANSI escapes; sanitize before rendering." |
| `suppress_always_allow_rule` | Do not show a "don't ask again" option. |
| `default_to_no` | Do not select "Yes" first. |
| `requires_user_interaction` | The card itself is the answer (AskUserQuestion, ExitPlanMode). No one-tap Yes/No. |
| `classifier_approvable`, `matched_ask_rule`, `mcp_server`, `agent_id` | Extra data. `agent_id` is set inside a subagent. |

Example (Bash, with the suggestion format of CLI 2.1.311 `zcr()`):

```json
{"type":"control_request","request_id":"p7d2x9","request":{"subtype":"can_use_tool",
 "tool_name":"Bash","display_name":"Bash","input":{"command":"npm test","description":"Run the test suite"},
 "tool_use_id":"toolu_01Ab","description":"Run the test suite",
 "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],"behavior":"allow","destination":"localSettings"}]}}
```

Suggestion formats in CLI 2.1.311:

- Bash prefix: `{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"<prefix> *"}],"behavior":"allow","destination":"localSettings"}`;
  an exact command: `ruleContent` without ` *`.
- Edit and Write: `{"type":"setMode","mode":"acceptEdits","destination":"session"}`, and, for a path
  outside the folders, `{"type":"addDirectories","directories":["/root/other"],"destination":"session"}`
  (`ykt()`).
- `PermissionUpdate` also has `replaceRules`, `removeRules`, `removeDirectories`. Destinations:
  `userSettings`, `projectSettings`, `localSettings` (the file `.claude/settings.local.json`),
  `session`, `cliArg`.

The CLI waits with no time limit: "permission prompts have no park deadline" (`CanUseTool` doc).

## CC-14 Permission answers

The answer is a `control_response` with `subtype: "success"`. Its `response` is a
`PermissionResult`. The CLI checks it with this schema (CLI 2.1.311, `vA`): allow
`{behavior, updatedInput?, updatedPermissions?, toolUseID?, decisionClassification?}` or deny
`{behavior, message, interrupt?, toolUseID?, decisionClassification?}`. The SDK always adds
`toolUseID` (the `tool_use_id` of the request). `decisionClassification` is `user_temporary`,
`user_permanent` or `user_reject` (for telemetry; optional).

Allow once:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"p7d2x9","response":{"behavior":"allow","updatedInput":{"command":"npm test","description":"Run the test suite"},"toolUseID":"toolu_01Ab"}}}
```

("Before v2.1.207, Claude Code rejected an allow result that omitted updatedInput". Send the input
back.)

Allow with a changed input: the same, with a changed `updatedInput`. "Claude sees the result but
isn't told you changed anything."

Allow always (send back the suggestion that the user selected):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"p7d2x9","response":{"behavior":"allow","updatedInput":{"command":"npm test","description":"Run the test suite"},
 "updatedPermissions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],"behavior":"allow","destination":"localSettings"}],
 "toolUseID":"toolu_01Ab","decisionClassification":"user_permanent"}}}
```

Deny with a message (Claude reads the message as the tool result):

```json
{"type":"control_response","response":{"subtype":"success","request_id":"p7d2x9","response":{"behavior":"deny","message":"Do not run the tests now. Fix the import first.","toolUseID":"toolu_01Ab"}}}
```

Deny and stop the turn: add `"interrupt":true` to the deny answer.

The TUI options (CLI 2.1.311, `dft()`, `A1()`, `syr()`, `sIe()`) and the protocol answer for each:

| TUI option | Answer |
|---|---|
| "Yes" | allow, `updatedInput` = input |
| "Yes" + text "and tell Claude what to do next" | allow. The schema has no feedback field: send the text as the next user message. |
| "Yes, and don’t ask again for: `<prefix>`" (the user can edit the prefix) | allow + `updatedPermissions: [{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"<prefix>"}],"behavior":"allow","destination":"localSettings"}]` |
| "Yes, and don’t ask again for any Bash command" (prefix `*`) | allow + `addRules` `[{"toolName":"Bash"}]`, `localSettings` |
| "Yes, and always allow access to `<dirs>` for this session" | allow + `addDirectories`, `session` |
| "Yes, and switch to **accept edits (…)** for this session" | allow + `setMode` `acceptEdits`, `session` |
| "Yes, and switch to auto mode" | allow + `setMode` `auto`, `session` (only when auto mode is available) |
| "No" | deny, `message` = a fixed text |
| "No" + text "and tell Claude what to do differently" | deny, `message` = the text |
| Esc | deny with `interrupt: true`, or the `interrupt` control request (CC-18) |

The TUI builds the label of a suggestion row from the suggestion. Automa can do the same: show one
"don't ask again" row for each suggestion, with a label from its `type`, `rules` and `destination`.
Do not show it when `suppress_always_allow_rule` is true.

VERIFY: the exact `title`, `display_name` and `description` texts on the phone.

## CC-15 Cancelled prompts and pending prompts

- When the turn ends before the answer (an interrupt, another client answered), the CLI can send
  `{"type":"control_cancel_request","request_id":"p7d2x9"}`. "The sender stops waiting at once and
  ignores any control_response that still arrives". Automa removes the card.
- After an app restart with the same CLI process, a new `initialize` lists the open requests in
  `pending_permission_requests` (CC-3).
- If the host closes stdin, the CLI cancels the open prompt (headless doc, SIGTERM part).

## CC-16 AskUserQuestion

The CLI always asks the host for this tool: `checkPermissions` gives
`{behavior:"ask",message:"Answer questions?",…}` and `requiresUserInteraction()` is true (CLI 2.1.311).
An allow rule does not skip it. In `dontAsk` mode, and with `--permission-prompts none`, the CLI
denies or removes it.

Input (`AskUserQuestionInput`, 0.3.311):

```json
{"questions":[
  {"question":"Which date library should we use?","header":"Library","multiSelect":false,
   "options":[{"label":"date-fns","description":"Small, functional"},{"label":"Luxon","description":"Time zones built in"}]},
  {"question":"Which features do you want?","header":"Features","multiSelect":true,
   "options":[{"label":"Dark mode","description":"Theme switch"},{"label":"Offline","description":"Local cache"},{"label":"Export","description":"CSV export"}]}]}
```

Limits (schema in CLI 2.1.311): 1 to 4 questions, 2 to 4 options for each question, `header` "max
12 chars", question texts unique, option labels unique in a question. "There should be no 'Other'
option, that will be provided automatically" (the host shows "Other"). `preview` on an option
comes only with `toolConfig.askUserQuestion.previewFormat` (`CLAUDE_CODE_QUESTION_PREVIEW_FORMAT`);
Automa does not set it.

Answer: allow with `updatedInput` = the original `questions` and `answers`. The key is the full
`question` text. The value is the selected `label`:

```json
{"type":"control_response","response":{"subtype":"success","request_id":"q1w2e3","response":{"behavior":"allow",
 "updatedInput":{"questions":[…the questions as received…],
  "answers":{"Which date library should we use?":"date-fns","Which features do you want?":["Dark mode","Offline"]}},
 "toolUseID":"toolu_01Qq"}}}
```

- Multi-select: a list of labels, or one string with `", "` between the labels. The CLI schema joins
  a list with `", "` (`n+", "+s`). The output type says "multi-select answers are comma-separated".
- "Other": put the text of the user as the value (not the word "Other").
- Notes for one question: `annotations: {"<question>": {"notes": "…"}}`. The value
  `"(notes only)"` means notes with no option.
- A general reply in place of the answers: `response: "<text>"`. Then Claude gets "The user
  responded: …".
- Claude gets: "Your questions have been answered: … You can now continue with these answers in
  mind." With no answer: "The user did not answer the questions."
- Dismiss: deny with a message (for example "The user dismissed the questions").
- `AskUserQuestion` "is not currently available in subagents" (docs).

## CC-17 Plan mode: ExitPlanMode and EnterPlanMode

ExitPlanMode in CLI 2.1.311:

- `checkPermissions` gives `{behavior:"ask",message:"Exit plan mode?",updatedInput:e}`. The host
  gets `can_use_tool` with `tool_name: "ExitPlanMode"`. The tool fails outside plan mode.
- The model writes the plan to a plan file. "This tool does NOT take the plan content as a
  parameter". The CLI puts `plan` and `planFilePath` into the input ("injected by
  normalizeToolInput from disk"). In another variant (no plan file) the model passes `plan` directly
  (max 32768 bytes as JSON).
- On allow, the CLI ends plan mode and goes back to the mode before plan mode
  (`prePlanMode ?? "default"`), unless the answer already set a mode.
- With `updatedInput.plan`, the CLI uses the edited plan and writes it to the plan file.

The TUI options (CLI 2.1.311, `ugt()`, `pgt()`, `sIe(…, {labelVariant:"plan-keep-context"})`) and
the answers. The TUI sends the mode change in the same answer, as `setMode` with `session`:

| TUI option | Answer |
|---|---|
| "Yes, auto-accept edits" | allow + `updatedPermissions: [{"type":"setMode","mode":"acceptEdits","destination":"session"}]` |
| "Yes, manually approve edits" | allow + `[{"type":"setMode","mode":"default","destination":"session"}]` |
| "Yes, and switch to auto mode" (when available) | allow + `setMode` `auto` |
| "No, keep planning" + text "Tell Claude what to change" | deny, `message` = the text |
| "Yes, clear context … and auto-accept edits" (only with the setting `showClearContextOnPlanAccept`) | Not possible over the protocol (the TUI answers deny and starts a new conversation itself). |

```json
{"type":"control_response","response":{"subtype":"success","request_id":"e5r6t7","response":{"behavior":"allow",
 "updatedInput":{"plan":"## Plan\n1. Fix the import\n2. Run the tests"},
 "updatedPermissions":[{"type":"setMode","mode":"acceptEdits","destination":"session"}],
 "toolUseID":"toolu_01Pl"}}}
```

After a deny, the model stays in plan mode. Its tool result says "The user chose to stay in plan
mode rather than proceed with implementation."

EnterPlanMode (input `{}`): in 2.1.311 it asks ("Enter plan mode?", TUI: "Yes, enter plan mode" /
"No, start implementing now") only when an internal gate is on and the mode is not `auto` or `plan`.
Else the CLI allows it with no prompt.

The host can also start in plan mode (`--permission-mode plan`) or change it (CC-19).

VERIFY on the phone: the `input` of ExitPlanMode in `can_use_tool` (with `plan` and
`planFilePath`?), and that allow + `setMode acceptEdits` gives `system/status` with
`permissionMode: "acceptEdits"`.

## CC-18 Interrupt a turn

```json
{"type":"control_request","request_id":"i9o8u7","request":{"subtype":"interrupt"}}
{"type":"control_response","response":{"subtype":"success","request_id":"i9o8u7","response":{"still_queued":[]}}}
```

- `still_queued` exists with the capability `interrupt_receipt_v1`. `cancel_queued: true` (capability
  `interrupt_cancel_queued_v1`) also removes the queued user messages; they come back in
  `cancelled`.
- After the interrupt (docs, streaming-vs-single-mode): "that response ends with an
  error_during_execution result". A cut assistant message has `aborted: true`. An open `can_use_tool`
  gets a `control_cancel_request` (CC-15). Messages that were in the queue still run, unless
  `cancel_queued`.
- The transcript gets the texts `[Request interrupted by user]` or `[Request interrupted by user for
  tool use]` (strings in CLI 2.1.311).
- Without `perTaskStopAffordance: true` in `initialize`, an interrupt also stops background tasks
  ("ABSENCE also fails closed").

VERIFY: which lines come between the interrupt and the `result` on the phone.

## CC-19 Permission modes and a mode change in a running session

Modes (`PermissionMode`): `default` (TUI name "Manual"), `acceptEdits`, `plan`, `bypassPermissions`,
`dontAsk`, `auto`. Docs (agent-sdk/permissions):

- `default`: no automatic approvals by mode; calls that need approval go to `can_use_tool`.
- `acceptEdits`: Edit, Write, NotebookEdit and `mkdir`, `touch`, `rm`, `rmdir`, `mv`, `cp`, `sed` in
  the working folders, with no prompt.
- `plan`: Claude only reads and plans. Edits go to the host also when an allow rule matches.
- `dontAsk`: each prompt becomes a deny.
- `auto`: a classifier model approves or blocks. Only for some models and plans.
- `bypassPermissions`: no prompts. It needs `--allow-dangerously-skip-permissions`. The CLI refuses
  it as root: "--dangerously-skip-permissions cannot be used with root/sudo privileges for security
  reasons" (`startup_failure_reason: "bypass_root"`). The guest runs as uid 0 (`proot -0`), so this
  mode is not available in Automa.

Change the mode while the session runs:

```json
{"type":"control_request","request_id":"m1n2b3","request":{"subtype":"set_permission_mode","mode":"acceptEdits"}}
```

The answer is an empty success, or an error (for example "Cannot set permission mode to auto: auto
mode unavailable for this model"). The new mode applies "immediately for all subsequent tool
requests". The CLI reports mode changes in `system/status` (`permissionMode`) and in the next
`system/init`. A permission answer with `setMode` (CC-14, CC-17) also changes the mode.

VERIFY: that `auto` is available for the subscription of the owner (`supportsAutoMode` in
`models`).

## CC-20 Models, effort and thinking

- List: the `models` field of the `initialize` answer (`ModelInfo`: `value`, `displayName`,
  `description`, `resolvedModel?`, `supportsEffort?`, `supportedEffortLevels?`,
  `supportsAdaptiveThinking?`, `supportsFastMode?`, `supportsAutoMode?`). The SDK
  `supportedModels()` reads this field. There is also the control request `list_models`; in CLI
  2.1.311 it answers `{"models":[…]}`.
- The current model: `system/init` `model`, and `message.model` of each `assistant` line.
- Change the model for the next turns:
  `{"type":"control_request","request_id":"s1","request":{"subtype":"set_model","model":"sonnet"}}`.
  "Omitted, null, or 'default' resets to the session default model." In `auto` mode, a model with no
  auto support sets the mode to `default` (a `system/status` line). At start: `--model <alias|id>`.
- Effort: at start `--effort low|medium|high|xhigh|max`. In a session:
  `{"subtype":"apply_flag_settings","settings":{"effortLevel":"high"}}` (null resets).
  `system/init` can carry `effort`. The levels of a model are `supportedEffortLevels`.
- Thinking: at start `--thinking adaptive|disabled` and `--thinking-display summarized|omitted`. In
  a session: `{"subtype":"set_max_thinking_tokens","max_thinking_tokens":0}` turns thinking off
  ("On Opus 4.6, this is treated as on/off (0 = disabled, any other value = adaptive)"), `null`
  resets, `thinking_display` changes the display. The SDK marks it as deprecated.
- The slash commands `/model <name>`, `/effort <level>` and `/fast` also work as user messages in
  `-p` (CC-22).

## CC-21 Sessions: files, list, resume and old messages

- The session id is a UUID. It is in each line (`session_id`). The host can select it for a new
  session: `--session-id <uuid>` ("must be a valid UUID"). Automa then knows the file name before
  the first turn.
- File: `~/.claude/projects/<cwd with each non-alphanumeric character replaced by "-">/<uuid>.jsonl`
  (sessions doc; `hl(e){return e.replace(/[^a-zA-Z0-9]/g,"-")}` in `sdk.mjs`). Names over 200
  characters get a hash. Example: `/root/repos/demo` → `/root/.claude/projects/-root-repos-demo/`.
  Subagent transcripts: `<dir>/<uuid>/subagents/agent-<agentId>.jsonl`.
- Resume: `--resume <uuid>` (also a session name, or the full path of a `.jsonl` file).
  `--continue` takes the newest session of the folder. `--fork-session` with one of them gives a new
  id. From 2.1.223 the CLI finds an id in any project folder.
- The CLI does not send the old messages again at a resume. The docs tell hosts to read the file
  (`getSessionMessages()`, `listSessions()`). VERIFY on the phone: no old `assistant` lines come
  after `--resume`.

How the SDK lists sessions (`listSessions`, `sdk.mjs` 0.3.311): for each `.jsonl` file it reads the
first and the last 64 KiB (`Sn=65536`). It skips a file when the first line has
`"isSidechain":true`. Title = `customTitle` (last part, then first part) or `aiTitle` or
`lastPrompt` or `summary` or the first real user prompt. Also `gitBranch`, `cwd`, `tag`, the
`timestamp` of the first line, and the file time. With `includeProgrammatic: false` it hides
sessions with `entrypoint` `sdk-cli`, `sdk-ts` or `sdk-py` "for parity with terminal /resume".
Thus the TUI `/resume` picker does not show the chat sessions of Automa (their `entrypoint` is
`sdk-cli`), but `--resume <id>` works for both.

How the SDK shows the old messages (`getSessionMessages`): it keeps lines with `type` `user`,
`assistant`, `progress`, `system`, `attachment` that have a `uuid`. It takes the newest leaf that
is not a sidechain, follows `parentUuid` to the root, and reverses the list. It drops `isMeta`,
`isSidechain` and `teamName` entries. A `compact_boundary` entry can relink parts
(`compactMetadata.preservedSegment` / `preservedMessages`).

Line format (fields from the CLI 2.1.311 writer `YUr()`/`kxt()`; key order is not a contract):

```json
{"parentUuid":null,"isSidechain":false,"userType":"external","cwd":"/root/repos/demo","sessionId":"5b3f2c1a-…","version":"2.1.311","gitBranch":"main","entrypoint":"sdk-cli","type":"user","message":{"role":"user","content":"Fix the typo"},"uuid":"u-1","timestamp":"2026-11-17T10:00:00.000Z"}
{"parentUuid":"u-1","isSidechain":false,"type":"assistant","message":{"id":"msg_01Xy","role":"assistant","model":"claude-opus-5","content":[{"type":"tool_use","id":"toolu_01Ab","name":"Edit","input":{"file_path":"/root/repos/demo/README.md","old_string":"teh","new_string":"the"}}]},"uuid":"a-1","timestamp":"…","sessionId":"5b3f2c1a-…"}
{"parentUuid":"a-1","isSidechain":false,"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_01Ab","content":"The file was updated."}]},"toolUseResult":{"filePath":"…","structuredPatch":[…]},"uuid":"u-2","timestamp":"…","sessionId":"5b3f2c1a-…"}
{"type":"last-prompt","lastPrompt":"Fix the typo","leafUuid":"u-2","sessionId":"5b3f2c1a-…"}
{"type":"ai-title","aiTitle":"Fix README typo","sessionId":"5b3f2c1a-…"}
```

- The structured tool output is `toolUseResult` (camelCase) in the file, and `tool_use_result` on
  the wire.
- Other entry types: `custom-title`, `tag`, `summary`, `relocated`, `file-history-snapshot`,
  `queue-operation`, `content-replacement`. Ignore unknown types.
- One assistant reply can be several lines with the same `message.id` (one block each).

VERIFY: a real transcript of 2.1.311 on the phone (field names, `entrypoint`, the `ai-title` line).

## CC-22 Slash commands in stream-json mode

Send a command as the text of a user message (`"/compact"`). The list of the session is
`system/init` `slash_commands` and the `initialize` `commands`. Docs (headless, slash-commands):

- Work: user skills and custom commands; `/compact` (gives `system/status` `compacting`, then
  `system/compact_boundary`), `/clear` (also `/reset`, `/new`; gives `conversation_reset` with
  `new_conversation_id`), `/context`, `/usage`; `/model <name>`, `/effort <level>`, `/fast`,
  `/color`, `/rename <title>` with an argument; `/mcp` (a text summary); `/config key=value`;
  `/output-style <style>`.
- Do not work: commands that need the terminal, for example `/login`, `/theme`,
  `/terminal-setup`. The result is "`/theme isn't available in this environment.`" with no model
  turn. `/model` with no argument does not open a picker; Automa shows its own picker (CC-20).
- An unknown `/name` goes to the model as a normal message (from 2.1.274), with a note that the
  command did not run.
- `/compact` with too little history ends with `success` and the text "Not enough messages to
  compact."

VERIFY: whether `/clear` gives a new `session_id` (a new `.jsonl` file) in the next `system/init`.

## CC-23 Subagents and background work

- `Agent` runs "in the background by default" (`AgentInput.run_in_background` doc). The tool result
  is then `status: "async_launched"` with `agentId`, `outputFile`. The end comes as
  `system/task_notification` (`status` `completed|failed|stopped`, `summary`, `tool_use_id`).
- In the foreground, its frames come with `parent_tool_use_id` = the id of the `Agent` call. By
  default only `tool_use` and `tool_result` blocks; `--forward-subagent-text` also sends text and
  thinking.
- `tool_progress` (`tool_use_id`, `tool_name`, `elapsed_time_seconds`) can drive a timer on a card.
- Stop one task: `{"subtype":"stop_task","task_id":"…"}`. Move running work to the background:
  `{"subtype":"background_tasks"}`.
- After stdin closes, the CLI waits for background work; it stops it after 10 minutes of idle wait
  by default (`CLAUDE_CODE_PRINT_BG_WAIT_CEILING_MS`).

## CC-24 Process end and stop under PRoot

- Normal end: close stdin. The CLI ends the current turn and the background work, then exits. Exit
  code 0 on success, not 0 on a failure (headless doc).
- SIGINT ends the turn. SIGTERM: exit code 143, "leaves the turn that was in progress unfinished and
  records no result for it". The CLI stops the process tree of a running Bash command.
- PRoot does not pass signals to its tracee and ignores SIGTERM (research F-8). Thus Automa must
  send SIGINT or SIGTERM to the `claude` process itself (found in `/proc`), or use the `interrupt`
  control request and then close stdin. SIGKILL of the tree (`env/Procs.kt`) stays the last step.
- When the process ends without a `result`, the turn is lost. The next `--resume` continues from the
  file; `CLAUDE_CODE_RESUME_INTERRUPTED_TURN=1` makes the CLI run the cut turn again.

VERIFY: that SIGINT to the guest `claude` PID under PRoot ends the turn cleanly.

## CC-25 Security: `-p` skips the workspace trust dialog

`claude --help`: "The workspace trust dialog is skipped when Claude is run in non-interactive mode
(via -p, or when stdout is not a TTY". Docs (headless): "a `-p` session runs the hooks in a
project's `.claude/settings.json` and connects the servers in its `.mcp.json`, even in a folder
you've never trusted." DESIGN §8 says that Claude Code asks before it trusts a folder. For the chat
this is not true: a cloned repository can start programs (hooks, MCP servers) at the first turn.

Controls in the CLI:

- `--setting-sources user` loads only `~/.claude/settings.json`: no project hooks, no project
  permission rules, no project skills, and no project `CLAUDE.md` ("Must include 'project' to load
  CLAUDE.md files").
- `--strict-mcp-config` with no `--mcp-config`: no MCP servers from `.mcp.json`, `~/.claude.json` or
  plugins.
- `--bare` is not usable: it reads only `ANTHROPIC_API_KEY` or `apiKeyHelper`, never the OAuth
  login.

Other untrusted data: `decision_reason`, `title`, tool inputs, file content and MCP names come from
the model or the repository. The SDK doc says that `decision_reason` "May carry ANSI escapes;
sanitize before rendering", and that MCP server names are "untrusted text, escape it before
display".

VERIFY: that `--setting-sources user --strict-mcp-config` stops a project `SessionStart` hook and a
`.mcp.json` server (test with a made-up repository on the emulator).

## CC-26 Version changes and fragile parts

- 2.1.311 → 2.1.326 (SDK 0.3.311 → 0.3.326): the parts that Automa uses did not change. The CLI
  arguments of the SDK are the same. `PermissionResult`, `SDKControlPermissionRequest`,
  `SDKSystemMessage`, `AskUserQuestionInput` and `ExitPlanModeInput` are the same. New in 0.3.326:
  the control request `set_has_subscribers`, the message `system/session_writes_lost`, new
  `startup_failure_reason` values, `hook_on_failure_supported` and `mcp_servers_applied` in the
  `initialize` answer, the tool `SetGoal` in place of `ProposeGoal`. Some tool outputs changed (the
  `Read` output type `file_unchanged` is gone in 0.3.326).
- 2.1.322 (`--help` on the Mac) lists `manual` in place of `default`. Both work.
- The protocol grows by new optional fields ("Evolves additively"). New message types and
  subtypes come often. Automa must ignore unknown types, subtypes and fields.
- Automa's Update installs the newest stable CLI. Thus the CLI can be newer than the code of the
  app. Parse each field as optional.
- Fragile (not a public contract): the `.jsonl` transcript format; the `ExitPlanMode` input (plan
  file or `plan` argument, by an internal gate); the `title`/`display_name` texts; the TUI labels;
  the hidden options `--permission-prompt-tool`, `--thinking`, `--thinking-display`.
- The manifest of SDK 0.3.311 has `"sdkCompat":{"testedWrapperVersions":[…],"harnessSchema":1}`.
  It tells which SDK versions Anthropic tested with this CLI. Automa is not one of them.

## Recommended client design

Command (in the repository folder, through `Env`, environment not argv for secrets):

```
claude -p --input-format stream-json --output-format stream-json --verbose
       --include-partial-messages --permission-prompt-tool stdio --permission-mode default
       (--session-id <new uuid> | --resume <uuid>)
       [--model <value from models>] [--effort <level>] [--thinking-display summarized]
       [--setting-sources user --strict-mcp-config]      (folder not trusted yet, CC-25)
```

Environment: the current guest environment (with `CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1`,
`USE_BUILTIN_RIPGREP=0`), plus `CLAUDE_CODE_SDK_READS_SESSION_STATE=1` and
`CLAUDE_CODE_STARTUP_FAILURE_RESULTS=1`. Do not set `CLAUDE_CODE_ENTRYPOINT`.

Start sequence:

1. Run `claude auth status`. Exit code 1: show "Log in to Claude Code" and open the TUI for the
   login (the chat cannot log in).
2. Start the process. Write `initialize`. Wait for its answer (time limit). Fill the model picker
   from `models` and the command menu from `commands` (hide `terminal_slash_commands`).
3. Enable the input field. Send each message as a `user` line with a `uuid`.

Handle these lines:

- `stream_event`: `text_delta`, `thinking_delta`, `input_json_delta` (live text and tool input).
- `assistant`: `text`, `thinking`, `tool_use` blocks (replace the live text); `error`; `aborted`.
- `user`: `tool_result` + `tool_use_result` (tool card result, diff from `structuredPatch`).
- `result`: end of turn; error text; cost and tokens as estimates; `permission_denials`.
- `system`: `init` (session id, model, mode), `status` (mode, compacting), `session_state_changed`
  (busy/idle), `api_retry`, `compact_boundary`, `permission_denied`, `local_command_output`,
  `informational`, `task_notification`, `commands_changed`.
- `conversation_reset`: clear the message list.
- `rate_limit_event`: a small notice.
- `control_request`: `can_use_tool` → a card: AskUserQuestion card, ExitPlanMode card, or a
  permission card (Yes / one row for each suggestion / No with text / Stop). `elicitation` →
  `{"action":"decline"}`. `request_user_dialog` → no answer. Other subtypes → error answer.
- `control_cancel_request`: remove the card.
- `control_response`: complete the open request of the app (initialize, interrupt,
  set_permission_mode, set_model, apply_flag_settings).

Ignore: `keep_alive`, `hook_*`, `task_progress`, `task_updated`, `background_tasks_changed`,
`tool_use_summary`, `prompt_suggestion`, `thinking_tokens`, `files_persisted`, `memory_recall`,
`mirror_error`, `worker_shutting_down`, `plugin_install`, and each unknown type or field.

Controls: Stop = `interrupt`. Mode chip = `set_permission_mode` (`default`, `acceptEdits`, `plan`;
`auto` only when `supportsAutoMode`; no `bypassPermissions`, CC-19). Model picker = `set_model`.
Effort = `apply_flag_settings` `effortLevel`. Close = close stdin, then signals to the `claude`
PID, then SIGKILL of the tree (CC-24).

Old sessions: list `~/.claude/projects/<encoded cwd>/*.jsonl` from Kotlin (head and tail 64 KiB,
CC-21). Open one: show its messages from the file (parentUuid chain), then start the CLI with
`--resume <uuid>`.

Security: a trust card for each repository before the first chat with project settings (CC-25).
Remove control characters and ANSI escapes from all model and repository text before display.
Show links only as text; open them only after a tap, as in `term/Links`.
