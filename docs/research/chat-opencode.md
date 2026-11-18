# Chat UI for opencode: ACP or the HTTP server

Research on 2026-11-17. Sources: the source of github.com/anomalyco/opencode at tags v1.19.6 and
v2.3.0 (read only; v1.19.6 `packages/opencode/src/acp/*`, `src/question`, `src/permission`,
`src/tool`, `src/agent/agent.ts`, `packages/schema/src/v1/*`, `packages/sdk/openapi.json`; v2.3.0
`packages/cli/src/acp/*`, `packages/cli/src/services/standalone.ts`, `packages/core/src/*`,
`packages/schema/src/*`, the ACP tests in `packages/cli/test/acp/`); the ACP spec on
agentclientprotocol.com (`protocol/prompt-turn`, `tool-calls`, `session-setup`,
`session-config-options`, `slash-commands`, `v1/schema`, `v2/elicitation`, `updates`,
`rfds/request-cancellation`); the opencode docs (`docs/acp`, `docs/server`, `docs/permissions` for
V1; `docs/cli/acp`, `docs/permissions`, `docs/migrate-v1` for V2); runs of the Mac opencode 2.3.0
(Homebrew) with a new empty HOME and XDG folders, no provider key, and no network (`sandbox-exec`
denies all outbound connections except localhost). There was no prompt to a model. opencode 1.19.6
did not run (no Mac build was run); its statements come from the source.

In the JSON examples, the keys come from the named source. Examples marked "live" are real output of
the Mac run; there, the folder path is changed to `/root/repos/example`. In the other examples, the
values (ids, text, paths) are made up.

Short names: "agent" is the `opencode acp` process. "client" is the program on the other side of
stdin and stdout (later: Automa). "1.x" is v1.19.6, "2.x" is v2.3.0.

## OC-1 Two release lines; the Update of Automa follows "latest"

- GitHub has two opencode lines: 1.19.x and 2.x. On 2026-11-17, `releases/latest` gives
  **v1.19.6** (created 2026-11-17T07:21Z), but v2.3.0 (2026-11-13) is newer. The tag v2.3.1 exists
  without a release. The v1.19.6 notes say: "OpenCode 1 now offers the upgrade to OpenCode 2.0".
  Both lines have the asset `opencode-linux-arm64-musl.tar.gz`.
- GitHub gives the newest release by its creation date as "latest", if the maintainers do not set
  it. Thus the next 2.x release will probably be "latest", and Automa Update (`Pins.OPENCODE_LATEST`,
  no downgrade) will then move the phone from 1.x to 2.x.
- The ACP code of 2.x is a rewrite: 1.x `packages/opencode/src/acp` with `@agentclientprotocol/sdk`
  0.21.0; 2.x `packages/cli/src/acp` with SDK 1.6.0. The HTTP API of 2.x is also new (OC-16).
  The ACP wire format is the same standard in both lines, with the differences in OC-17.

Source: `GET api.github.com/repos/anomalyco/opencode/releases` and `releases/latest`; the
`package.json` files of both tags.

Update 2026-11-18: v2.3.1 was released (2026-11-17T22:53Z), and `releases/latest` still gives
v1.19.6. Thus the maintainers set "latest" on the 1.x line by hand, and the Update of Automa stays
on 1.x. The statement above ("the next 2.x release will probably be latest") is wrong.

VERIFY: which line `releases/latest` gives after the next release. Also that a non-opencode
release of the same repository (`process-daemon-v0.6.0`, no opencode asset) can never be "latest":
the Update would then fail with "Unexpected release data".

## OC-2 `opencode acp` also opens a TCP port on 127.0.0.1

The ACP channel is stdin and stdout. But in both lines the agent talks to an opencode HTTP server
inside, on a loopback TCP port. On Android, every app can connect to 127.0.0.1.

- 1.x (`src/cli/cmd/acp.ts`): `Server.listen(opts)` in the same process. The default is
  `--hostname 127.0.0.1 --port 0` (a random port); `acp` accepts the `--port` and `--hostname`
  options of `serve`. The server checks a password only when `OPENCODE_SERVER_PASSWORD` is set
  (`server/auth.ts` `required()`). **With no password, each app on the phone can use this server.**
  The inner SDK client sends `Authorization: Basic` from the same variable (`ServerAuth.headers()`).
- 2.x (`services/standalone.ts`): the agent starts a child `opencode serve --stdio --port 0` with
  `OPENCODE_PASSWORD` = 32 random bytes (base64url). The child deletes the password from its
  environment, so tools do not get it. The child stops when its stdin pipe closes (a lease).
  The V2 doc says "it doesn't open a network port for ACP"; this is true for the ACP channel only.

Live (2.x, during an ACP run): `lsof` shows the child `opencode serve --stdio --port 0` with
`127.0.0.1:65393 (LISTEN)`. A request with no password gets 401 (OC-16).

Result: ACP and `serve` have the same exposure: one loopback port with a password. For 1.x, Automa
must set a new random `OPENCODE_SERVER_PASSWORD` for each `opencode acp` start.

## OC-3 Start and handshake

Command (in the guest, through `Env`, DESIGN §3): working folder = the repository, then
`opencode acp`. stdout has only ACP messages: one JSON-RPC 2.0 object per line (`ndJsonStream`).
The environment needs the DESIGN §8 flags (a direct exec does not read `/etc/profile.d/automa.sh`),
`TFF_API_KEY`, `GH_TOKEN`, and for 1.x the password of OC-2.

Request (client to agent). The client declares no `fs` and no `terminal` methods; `elicitation.form`
turns on question forms in 2.x (OC-9):

```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":1,"clientCapabilities":{"fs":{"readTextFile":false,"writeTextFile":false},"terminal":false,"elicitation":{"form":{}}},"clientInfo":{"name":"automa","version":"0.4.0"}}}
```

Answer of 2.x (live):

```json
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"mcpCapabilities":{"http":true,"sse":false},"promptCapabilities":{"embeddedContext":true,"image":true},"sessionCapabilities":{"additionalDirectories":{},"close":{},"delete":{},"fork":{},"list":{},"resume":{}},"_meta":{"opencode/child-session-updates":true}},"authMethods":[{"description":"Run `opencode auth login` in the terminal","name":"Login with opencode","id":"opencode-login"}],"_meta":{"steering":{"supported":true}},"agentInfo":{"name":"OpenCode","version":"2.3.0"}}}
```

Answer of 1.x (from `acp/service.ts` `initialize`):

```json
{"protocolVersion":1,"agentCapabilities":{"loadSession":true,"mcpCapabilities":{"http":true,"sse":true},"promptCapabilities":{"embeddedContext":true,"image":true},"sessionCapabilities":{"close":{},"fork":{},"list":{},"resume":{}}},"authMethods":[{"description":"Run `opencode auth login` in the terminal","name":"Login with opencode","id":"opencode-login"}],"agentInfo":{"name":"OpenCode","version":"1.19.6"}}
```

- `agentInfo.version` tells the client which line runs. Use it to turn features on (OC-17).
- `authenticate` with `opencode-login` does nothing (`{}`). TFF uses the key in the environment, so
  the client does not call it.
- End: the client closes stdin. 1.x and 2.x stop at EOF. 2.x writes
  `opencode acp: server exited unexpectedly (...)` to stderr and exits with 1 if its child dies.

VERIFY: 1.x writes no log line to stdout (its logs go to a file). The start time under PRoot.

## OC-4 New session; "no provider" and "no model"

```json
{"jsonrpc":"2.0","id":2,"method":"session/new","params":{"cwd":"/root/repos/example","mcpServers":[]}}
```

2.x with a made-up TFF provider in the user config (no key; live):

```json
{"jsonrpc":"2.0","id":2,"result":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"tff/example-model-a","options":[{"value":"tff/example-model-a","name":"tff/example-model-a"},{"value":"tff/example-model-b","name":"tff/example-model-b"}]},{"id":"effort","name":"Effort","description":"Available effort levels for this model","category":"thought_level","type":"select","currentValue":"default","options":[{"value":"low","name":"Low"},{"value":"medium","name":"Medium"},{"value":"high","name":"High"},{"value":"default","name":"Default"}]},{"id":"mode","name":"Session Mode","category":"mode","type":"select","currentValue":"build","options":[{"value":"build","name":"Build","description":"The default agent. Executes tools based on configured permissions."},{"value":"plan","name":"Plan","description":"Read-only agent for exploring the codebase and planning work before implementation."}]}]}}
```

After the answer, an `available_commands_update` comes (OC-14). There is no `modes` and no
`models` field; the client uses `configOptions` only (the ACP spec prefers config options).

No model:

- 2.x with `"enabled_providers": ["tff"]` and no TFF provider (live), after about 5 s of retries
  (`catalog.ts` polls up to 5 s):
  `{"jsonrpc":"2.0","id":2,"error":{"code":-32603,"message":"Internal error: No models are available","data":{"errorName":"CatalogNotReady"}}}`.
  No session is made.
- 1.x (`service.ts` `selectDefaultModel`): `session/new` succeeds. The model option has
  `"currentValue":"unknown/unknown"` and `"options":[]`. The first prompt then fails.

Other start errors:

- Provider key missing or wrong at prompt time: JSON-RPC error `-32000` ("provider authentication
  required", `RequestError.authRequired`). 1.x adds `data.providerId`.
- Other provider or server errors: `-32603` with `data.errorName` (2.x `ServerUnavailable` when the
  child server is gone) and a safe message.
- 2.x with a relative `cwd`: `-32602`.

VERIFY: 1.x `session/new` with no TFF key on the phone (is the TFF provider listed without the key?
In 2.x it is listed, live). The exact error of a prompt with a missing TFF key in both lines.

## OC-5 Send a user message

```json
{"jsonrpc":"2.0","id":3,"method":"session/prompt","params":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","prompt":[{"type":"text","text":"Fix the failing test in src/app.ts"}]}}
```

- The answer comes at the end of the turn (OC-6). All progress comes before it as `session/update`.
- Images (note only): `{"type":"image","mimeType":"image/png","data":"<base64>"}`
  (`promptCapabilities.image` is true). 2.x refuses an image with no `data` and no `uri` (`-32602`).
  A `resource_link` with a `file://` uri or a `resource` with text also works (`content.ts`).
- 2.x runs one prompt for each session. A second `session/prompt` during a turn gets `-32603`
  "Session already has an active ACP prompt" (`turn.ts`). To add text during a turn, 2.x has the
  extension `_session/steering` `{"sessionId":"…","prompt":[…]}`. The answer is
  `{"outcome":"injected"}` or `{"outcome":"promptRequired","reason":"noRunningTurn"}`. The source
  calls it an "Interim extension until ACP has native mid-turn steering".

VERIFY: the size limit of one incoming message in 2.x (`MessageTooLargeError`; the client must keep
images small).

## OC-6 Streaming and end of turn

Text and thought chunks (`acp/event.ts` 1.x, `acp/translate.ts` 2.x):

```json
{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"ses_1","update":{"sessionUpdate":"agent_message_chunk","messageId":"msg_7","content":{"type":"text","text":"I found the bug"}}}}
{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"ses_1","update":{"sessionUpdate":"agent_thought_chunk","messageId":"msg_7:reasoning:0","content":{"type":"text","text":"The test expects"}}}}
```

- `messageId` of text: the assistant message id. Of thoughts: 1.x the part id (`prt_…`), 2.x
  `<messageId>:reasoning:<n>`. Append chunks with the same `messageId`.
- 1.x sends text only of parts that are not `ignored`. 2.x sends `session.text.delta` and
  `session.reasoning.delta` events.
- 2.x retries: `{"sessionUpdate":"session_info_update","_meta":{"opencode/retry":{"attempt":1,"nextRetryAt":"2026-11-17T10:00:05.000Z","error":{…}}}}`,
  and `"_meta":{"opencode/retry":null}` when the retry starts.

End of turn: first a `usage_update`, then the answer to `session/prompt` (both lines send the usage
before the answer):

```json
{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"ses_1","update":{"sessionUpdate":"usage_update","used":18342,"size":128000,"cost":{"amount":0.0123,"currency":"USD"}}}}
{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn","usage":{"inputTokens":18000,"outputTokens":342,"totalTokens":18342,"cachedReadTokens":12000},"_meta":{}}}
```

- `stopReason`: `end_turn`, `cancelled` (abort), `max_tokens` (output length), `refusal` (content
  filter). Other failures are a JSON-RPC error, not a stop reason (OC-4).
- `usage`: `thoughtTokens`, `cachedReadTokens`, `cachedWriteTokens` only when they are not 0.
  1.x adds `userMessageId` when the request had `messageId`.
- `usage_update` comes only when the model has a context limit. The TFF models in the managed
  config have no `limit`, so it does not come for them.

## OC-7 Tool calls

Start (2.x, `translate.ts` on `session.tool.input.started`):

```json
{"sessionUpdate":"tool_call","toolCallId":"call_3","name":"edit","title":"edit","kind":"edit","status":"pending","locations":[],"rawInput":{}}
```

Then `tool_call_update` with `"status":"in_progress"` and `rawInput` (the tool input), then
`completed` or `failed`. Fields in an update replace the old values; absent fields stay.

Completed edit (2.x; `tool.ts` `completedToolUpdate` and `permission.ts` `withCompletedDiffs`).
`oldText` is the full file before, `newText` the full file after; `oldText` is `null` for a new file:

```json
{"sessionUpdate":"tool_call_update","toolCallId":"call_3","status":"completed","locations":[{"path":"/root/repos/example/README.md"}],"content":[{"type":"content","content":{"type":"text","text":"Edit applied successfully."}},{"type":"diff","path":"/root/repos/example/README.md","oldText":"Hello\n","newText":"Hello, world\n"}],"rawOutput":{"metadata":{}}}
```

Failed (both lines, `errorToolUpdate`):

```json
{"sessionUpdate":"tool_call_update","toolCallId":"call_4","status":"failed","kind":"execute","title":"npm test","locations":[{"path":"/root/repos/example"}],"rawInput":{"command":"npm test","cwd":"/root/repos/example"},"content":[{"type":"content","content":{"type":"text","text":"Command failed"}}],"rawOutput":{"error":"Command failed"}}
```

Fields:

- Tool name: 2.x `name` (`shell`, `read`, `edit`, `write`, `patch`, `glob`, `grep`, `webfetch`,
  `websearch`, `todowrite`, `question`, `subagent`, `skill`, MCP tools). 1.x has no `name`; the
  first `tool_call` has `title` = the tool name (`bash`, `read`, `edit`, `write`, `apply_patch`,
  `task`, `todowrite`, …), except for the shell (`title` = the command).
- Title: the shell command for the shell; else the title of the tool (1.x: for example the relative
  file path), else the tool name.
- `kind`: `execute` (shell), `edit` (edit, write, patch), `read`, `search` (grep, glob), `fetch`
  (webfetch), `think` (task/subagent), `other`.
- Input keys: 1.x `filePath`, `oldString`, `newString`, `content`, `command`, `workdir`; 2.x `path`
  instead of `filePath`. For the shell, the agent adds `cwd` to `rawInput`.
- Output: `content[0]` is the text output (for `read`, the file text). `rawOutput.metadata` has the
  tool metadata; 1.x also `rawOutput.output`.
- Shell progress: 1.x sends the output so far as `content` of each `in_progress` update
  (`metadata.output`). 2.x sends no output before `completed`.
- Diffs in 1.x: only the `edit` tool, and only the replaced snippet (`oldText` = `oldString`,
  `newText` = `newString`). `write` and `apply_patch` have no `diff` content; their unified diff is
  in `rawOutput.metadata.diff` (`edit`) or `rawOutput.metadata.files[].patch` (`apply_patch`).
  1.x makes full-file diffs only in a permission request for `edit` (OC-8).
- The todo list: there is no ACP `plan` update in either line. The `todowrite` tool call has the
  list in `rawInput.todos`.

## OC-8 Permissions

Request (agent to client), 1.x, for a file outside the project (`acp/permission.ts`; the
`permission.asked` metadata comes from `tool/external-directory.ts`):

```json
{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"ses_1","toolCall":{"toolCallId":"call_5","title":"/etc","kind":"other","status":"pending","locations":[{"path":"/etc/hosts"},{"path":"/etc"}],"rawInput":{"filepath":"/etc/hosts","parentDir":"/etc"}},"options":[{"optionId":"once","kind":"allow_once","name":"Allow once"},{"optionId":"always","kind":"allow_always","name":"Always allow"},{"optionId":"reject","kind":"reject_once","name":"Reject"}]}}
```

2.x, for a `.env` read (`acp/permission.ts` `ask`): the same `options`, and the tool call has
`name`, the tool input as `rawInput`, and `diff` previews for file changes:

```json
{"sessionId":"ses_1","toolCall":{"toolCallId":"call_6","name":"read","title":".env","kind":"read","status":"pending","locations":[{"path":"/root/repos/example/.env"}],"rawInput":{"path":".env"}},"options":[…]}
```

Answers (client to agent):

```json
{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"selected","optionId":"once"}}}
{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"selected","optionId":"always"}}}
{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"selected","optionId":"reject"}}}
{"jsonrpc":"2.0","id":0,"result":{"outcome":{"outcome":"cancelled"}}}
```

- The three options are fixed (no `reject_always`, no feedback text). `cancelled`, an error answer,
  or an unknown `optionId` is the same as `reject`.
- `always`: 1.x allows the request patterns until the agent stops (memory). 2.x saves the rule for
  the project (`permission/saved`).
- `reject`: the tool call fails, and the turn stops. 1.x: `RejectedError` sets `blocked`
  (`session/processor.ts`), unless `experimental.continue_loop_on_deny` is true; the agent also
  rejects all other open requests of the session. The answer is then `end_turn` (VERIFY). 2.x: a
  decline interrupts the step (`runner/step.ts`, error type `aborted`), so the stop reason is
  `cancelled` (VERIFY).
- Which tools ask in the default config (Automa adds no `permission` rules):
  - 1.x (`agent/agent.ts`): `"*": "allow"`, but `ask` for `external_directory` (paths outside the
    project), `doom_loop` (the same tool call again and again) and `read` of `*.env` and `*.env.*`
    (not `*.env.example`). The plan agent denies `edit` except plan files. Thus shell commands,
    edits and web fetches run with no question.
  - 2.x (`schema/src/agent.ts`, docs `permissions`): `*` allow, and `ask` only for `read` of `*.env`
    and `*.env.*`.
- 1.x only: after `once` or `always` for an `edit` permission, the agent sends
  `fs/write_text_file` to the client with the new file text (`writeProposedEdit`), also when the
  client declared `"writeTextFile":false`. The tool writes the file itself. The client must answer
  `{"jsonrpc":"2.0","id":N,"result":null}` and write nothing. An error answer is an unhandled promise
  rejection in the agent.
- 2.x can send `$/cancel_request` `{"requestId":0}` for an open permission request (cancel, or the
  request is no longer necessary). The client then closes the card.

VERIFY: `doom_loop` and `external_directory` cards on the phone; 1.x with an error answer to
`fs/write_text_file`; 2.x `external_directory` (it seems to fall under `*` allow).

## OC-9 Questions with choices

opencode has a `question` tool in both lines. Its input (`schema/v1/question.ts`,
`schema/src/question.ts`): `questions[]` with `question`, `header` (max 30 characters), `options[]`
(`label`, `description`) and `multiple`. A free-text answer is always possible ("Type your own
answer" is added automatically). The tool returns the answers as arrays of labels.

**1.x in ACP: not possible.**

- `tool/registry.ts`: the tool is on only when `OPENCODE_CLIENT` is `app`, `cli` or `desktop`, or
  `OPENCODE_ENABLE_QUESTION_TOOL=1`. `opencode acp` sets `OPENCODE_CLIENT=acp`, so the model does
  not get the tool. It then asks in plain text.
- With `OPENCODE_ENABLE_QUESTION_TOOL=1`, the tool publishes `question.asked`, but the ACP bridge
  (`acp/event.ts`) handles only `session.status`, `permission.asked`, `message.part.updated` and
  `message.part.delta`. The question then waits until a cancel.
- `plan_exit` (the plan approval question) is on only for `OPENCODE_CLIENT=cli` with
  `OPENCODE_EXPERIMENTAL_PLAN_MODE` (OC-10).
- A possible work-around (not tested): `opencode acp --port <P>` with `OPENCODE_SERVER_PASSWORD`
  and `OPENCODE_ENABLE_QUESTION_TOOL=1`; the client reads `question.asked` from `GET /event` (SSE)
  on that port and answers with `POST /question/{id}/reply`. This is a second protocol.

**2.x in ACP: OK, with `elicitation/create` (form mode).** The question tool makes a form
(`core/src/tool/plugin/question.ts`, fields `q0`, `q1`, …, `custom: true`). The bridge
(`acp/elicitation.ts`) sends it when the client declared `"elicitation":{"form":{}}` (OC-3). The
ACP spec stabilized elicitation on 2026-07-22. Request (shape from `elicitation.ts` and
`test/acp/elicitation.test.ts`):

```json
{"jsonrpc":"2.0","id":1,"method":"elicitation/create","params":{"mode":"form","sessionId":"ses_1","toolCallId":"call_question","message":"Questions","requestedSchema":{"type":"object","properties":{"q0":{"type":"string","title":"Runtime","description":"Which runtime?","oneOf":[{"const":"Bun","title":"Bun","description":"Fast"},{"const":"Node","title":"Node","description":"Stable"}]},"q0_custom":{"type":"string","title":"Runtime (other)","description":"Type your own answer"},"q1":{"type":"array","title":"Goals","description":"What matters?","items":{"anyOf":[{"const":"Fast","title":"Fast","description":"Speed"}]}},"q1_custom":{"type":"string","title":"Goals (other)","description":"Add your own answer"}},"required":[]}}}
```

- Single choice: `type` `string` with `oneOf`. Multiple choice: `type` `array` with `items.anyOf`.
  Free text: the `<key>_custom` field. `title` = `header`, `description` = `question`.
- `toolCallId` links the card to the `question` tool call (sent before, OC-7).

Answers:

```json
{"jsonrpc":"2.0","id":1,"result":{"action":"accept","content":{"q0":"Bun","q1":["Fast"],"q1_custom":"Small"}}}
{"jsonrpc":"2.0","id":1,"result":{"action":"decline"}}
{"jsonrpc":"2.0","id":1,"result":{"action":"cancel"}}
```

- `accept`: a non-empty `<key>_custom` replaces a single choice and is added to a multiple choice.
  The test shows the result `{"q0":"Bun","q1":["Fast","Small"]}`; the model gets
  `User has answered your questions: "Which runtime?"="Bun", "What matters?"="Fast, Small". …`.
- `decline`, `cancel`, an error, or a wrong value type: the form is cancelled. The tool fails with
  "The user dismissed this question", and the step is interrupted (`runner/step.ts`).
- Client with no `elicitation.form`: the agent cancels the form with the message "The question
  couldn't be shown to the user in this client. Continue without an answer: …". The turn continues.
- The bridge never sends a form with fields that look like secrets (`password`, `token`, `api key`,
  …), and only forms of kind `question` and `websearch.provider`.

HTTP comparison:

- 1.x `serve`: SSE `GET /event`
  `{"id":"evt_1","type":"question.asked","properties":{"id":"que_1","sessionID":"ses_1","questions":[{"question":"Which runtime?","header":"Runtime","options":[{"label":"Bun","description":"Fast"},{"label":"Node","description":"Stable"}],"multiple":false}],"tool":{"messageID":"msg_1","callID":"call_question"}}}`.
  Answer `POST /question/que_1/reply` `{"answers":[["Bun"]]}` (one array of labels for each
  question, free text as a label); dismiss `POST /question/que_1/reject`. In `serve`,
  `OPENCODE_CLIENT` is `cli`, so the tool is on. (`packages/sdk/openapi.json` 1.19.6.)
- 2.x `serve`: event `form.created` `{"form":{"id":"frm_1","sessionID":"ses_1","title":"Questions","metadata":{"kind":"question","tool":{"messageID":"msg_1","id":"call_question"}},"fields":[…]}}`;
  answer `POST /api/session/{sessionID}/form/{formID}/reply` `{"answer":{"q0":"Bun"}}`; cancel
  `DELETE /api/session/{sessionID}/form/{formID}` (live OpenAPI).

VERIFY: a real question card on the phone with a TFF model (2.x), the stop reason after `decline`.

## OC-10 Plan mode and mode change

- The modes are the primary agents: `build` and `plan` (1.x names in lower case; 2.x names
  "Build" and "Plan"). The client changes the mode with the `mode` config option (OC-12) or
  `session/set_mode`:

```json
{"jsonrpc":"2.0","id":4,"method":"session/set_mode","params":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","modeId":"build"}}
{"jsonrpc":"2.0","id":4,"result":{}}
```

  (live, 2.x). An unknown mode: `{"code":-32602,"message":"Invalid params: mode not found: nonexistent","data":{"mode":"nonexistent"}}` (live).
- Neither line sends `current_mode_update` or a `plan` update. The agent cannot change the mode
  itself in ACP.
- Plan approval: there is no approval request in ACP. 1.x has `plan_exit` (a Yes/No question, then
  a switch to `build`), but only for the CLI client with `OPENCODE_EXPERIMENTAL_PLAN_MODE`
  (`tool/registry.ts`, `tool/plan.ts`). 2.x has no approval tool: the plan agent "remains in Plan
  mode until the user switches agents" (`core/src/plugin/plan.ts`). Thus "approve the plan" in the
  chat = set the mode to `build`, then send "Do the plan".
- 2.x adds a synthetic message to the session when the mode changes ("You are in Plan mode …",
  "You are NO LONGER in Plan mode").
- Plan agent limits: 1.x denies `edit` except `.opencode/plans/*.md` and the data plans folder.
  2.x denies `edit` except `~/.opencode/plan/*`.

## OC-11 Interrupt

```json
{"jsonrpc":"2.0","method":"session/cancel","params":{"sessionId":"ses_1"}}
```

- It is a notification (no answer). The open `session/prompt` then gets
  `{"stopReason":"cancelled",…}`.
- ACP spec: the client must answer all open `session/request_permission` requests with
  `{"outcome":{"outcome":"cancelled"}}`.
- 1.x: `session.abort` on the server; the assistant message gets `MessageAbortedError`.
- 2.x: the agent interrupts the session, waits up to 5 s for the last events
  (`CancelDrainTimeout`), and sends `tool_call_update` `"status":"failed"` with the text
  "Cancelled" for each tool that did not end. Open permission and question requests get the
  fallback answer (reject, cancel). A `$/cancel_request` for the `session/prompt` request id does
  the same as `session/cancel`.
- A cancel with no running turn does nothing (live, 2.x).

VERIFY: in 1.x, the `tool_call_update` of a running shell command after a cancel.

## OC-12 Models and effort

The model list and the current model come in `configOptions` (OC-4): option `model`
(`currentValue` `<provider>/<model>`), option `effort` (category `thought_level`, only when the
model has variants), option `mode`. Change (live, 2.x):

```json
{"jsonrpc":"2.0","id":5,"method":"session/set_config_option","params":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","configId":"model","value":"tff/example-model-b"}}
{"jsonrpc":"2.0","id":6,"method":"session/set_config_option","params":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","configId":"effort","value":"high"}}
```

- The answer has the full new `configOptions` (live: `currentValue` `tff/example-model-b`, then
  `high`).
- The agent sends `config_option_update` with the full list when the list changes without a
  request (2.x: model, agent or command changes on the server; 1.x: after a model change).
- `session/set_model`: 1.x implements it (`unstable_setSessionModel`). 2.x answers
  `{"code":-32601,"message":"\"Method not found\": session/set_model"}` (live). Do not use it.
- Variants: 1.x shows `effort` only when the model has `variants`. 2.x showed `low`, `medium`,
  `high`, `default` for the made-up TFF models (live). A model value `<provider>/<model>/<variant>`
  also selects a variant.
- Errors: `-32602` "model not found: …", "effort not found: …". 2.x reloads its catalog once
  before it fails.

VERIFY: that a TFF model accepts the 2.x effort values (they become a request option); the 1.x
variants of the TFF models.

## OC-13 Sessions: list, load, resume

```json
{"jsonrpc":"2.0","id":9,"method":"session/list","params":{"cwd":"/root/repos/example"}}
```

Answer (live, 2.x):

```json
{"jsonrpc":"2.0","id":9,"result":{"sessions":[{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","cwd":"/root/repos/example","title":"New session - 2026-11-17T23:06:07.930Z","updatedAt":"2026-11-17T23:06:12.100Z"}],"nextCursor":"eyJvcmRlciI6ImRlc2Mi…"}}
```

- Pages of 100, newest first. 1.x: only root sessions (`roots: true`), the cursor is a time in
  milliseconds as a string. 2.x: an opaque cursor; it can be present also on the last page (live).
- `session/load` `{"sessionId":"…","cwd":"…","mcpServers":[]}`: the agent replays the full history as
  `session/update` before the answer (ACP spec "MUST"). User text: `user_message_chunk`; assistant
  text: `agent_message_chunk`; reasoning: `agent_thought_chunk`; each tool: `tool_call`, then one
  `tool_call_update` with the end state (`acp/event.ts` `replayMessage`, `acp/replay.ts`). The
  answer has `configOptions` with the model and mode of the session. 2.x reads the history in pages
  of 200.
- `session/resume`: the same answer, no replay.
- 2.x checks the folder: a load with another `cwd` gets
  `{"code":-32602,"message":"Invalid params: session ses_… does not belong to cwd: /root/repos/other"}`
  (live).
- `session/close`: ends the work and frees the session in the agent (`{}`, live). 2.x deletes a
  session that has no messages (live: a session with a mode change stayed, probably because of the
  synthetic plan message of OC-10). `session/delete` (2.x only) deletes it. `session/fork`
  (1.x `unstable_forkSession`) copies it.
- The sessions are in the opencode database in the guest (`~/.local/share/opencode`), the same as
  for the TUI. A chat and the TUI can thus open the same sessions.

VERIFY: the replay of a real session with tools (no prompt was sent here); 1.x and 2.x reading each
other's sessions after an update.

## OC-14 Slash commands

After `session/new`, `session/load` and `session/resume` (live, 2.x):

```json
{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"ses_e143d4d47ffeechXHNrkuDVjGh","update":{"sessionUpdate":"available_commands_update","availableCommands":[{"name":"init","description":"guided AGENTS.md setup"},{"name":"review","description":"review changes [commit|branch|pr], defaults to uncommitted"},{"name":"goal","description":"keep working until an objective is met [pause|resume|clear]"},{"name":"compact","description":"Compact the session"}]}}}
```

- 1.x sends the config commands and the skills (no `input` hints). `compact` works also when it is
  not in the list (`session.summarize`).
- To run a command, send it as the text of a `session/prompt`: `"/review"` or `"/goal fix the
  tests"`. The agent finds the leading `/` (after trim).
- 1.x: a text that starts with `/` and is not a known command or `compact` goes nowhere. The answer
  is `end_turn` at once, and the model gets no text (`service.ts` `prompt`). Thus a message such as
  "/etc/hosts is wrong" is lost. The client must not send such text to 1.x unchanged (for example,
  put a space or a word before it). 2.x sends an unknown command to the model as text
  (`prompt.ts`).
- The TUI commands (`/undo`, `/redo`, `/models`, `/sessions`, …) do not exist in ACP (docs `acp`).

VERIFY: 2.x answers `session/prompt` for a command when the command is accepted
(`turn.ts` `if (prompt.command) return "succeeded"`); late updates of the command turn can be lost.

## OC-15 2.x only: sub-agents and turns with no prompt

- 2.3.0 runs sub-agents in the background (release notes). Their updates come in the parent stream
  with `_meta["opencode/child-session"]` `{id, parentID, depth, title}`, the `toolCallId` prefix
  `<childSessionId>:` and the title prefix `<child title>: `, during the parent turn.
- When the client declares `"_meta":{"opencode/child-session-updates":true}` in
  `clientCapabilities`, the updates come instead as the notification
  `opencode/session/child_update` (`acp/child.ts`), with `status` events.
- After the prompt answer, permission requests and questions of open sub-agents still come. A
  report of a sub-agent can start a parent turn with no `session/prompt`. The client must accept
  updates at all times, not only during a prompt.

## OC-16 The other way: `opencode serve` (HTTP and SSE)

- Start: `opencode serve --hostname 127.0.0.1 --port <P>` with `OPENCODE_SERVER_PASSWORD`
  (2.x also `OPENCODE_PASSWORD`). Basic auth, user `opencode`. Live (2.x): no password in the
  request: 401; with the password: `GET /openapi.json` 200 (448,135 bytes); `GET /doc` 200. 1.x
  without the variable has no auth at all (OC-2). 1.x prints "Warning: OPENCODE_SERVER_PASSWORD is
  not set; server is unsecured."
- 1.x API (`packages/sdk/openapi.json`): `POST /session`, `POST /session/{id}/prompt_async`,
  `GET /event` (SSE: `message.part.updated`, `message.part.delta`, `permission.asked`,
  `question.asked`, `session.status`, …), `POST /permission/{requestID}/reply`
  `{"reply":"once"|"always"|"reject","message":"…"}` (a reject can carry feedback text),
  `POST /question/{requestID}/reply`, `POST /question/{requestID}/reject`,
  `POST /session/{id}/abort`.
- 2.x API (live OpenAPI, "Experimental HttpApi surface"): `POST /api/session/{sessionID}/prompt`
  `{"text":"…"}`, `GET /api/event`, `POST /api/session/{sessionID}/permission/{requestID}/reply`
  `{"decision":"once"}`, `POST /api/session/{sessionID}/form/{formID}/reply`,
  `POST /api/session/{sessionID}/interrupt`, `POST /api/session/{sessionID}/model`,
  `POST /api/session/{sessionID}/agent`, `GET /api/model`, `GET /api/agent`, `GET /api/command`.
- For: questions also in 1.x; reject with feedback text; all server data.
- Against: a client for each line (1.x and 2.x have different routes and events), an HTTP client
  and an SSE parser in Kotlin, the client must keep the password and find the port, and the client
  must build the tool cards and diffs itself from raw parts.

## OC-17 Version differences and unstable parts

| Item | 1.19.6 | 2.3.0 |
|---|---|---|
| Inner server | in the process; auth only with `OPENCODE_SERVER_PASSWORD` | child `serve --stdio`; random password |
| Questions in ACP | no (OC-9) | `elicitation/create` |
| `session/set_model` | yes (unstable) | `-32601` |
| `name` in tool calls | no | yes |
| Tool names, input keys | `bash`, `task`, `apply_patch`; `filePath` | `shell`, `subagent`, `patch`; `path` |
| Edit diffs | snippet, `edit` only | full file, `edit`/`write`/`patch` |
| Shell output while it runs | yes | no |
| Reject | turn stops (`blocked`) | step interrupted (`cancelled`) |
| No model at `session/new` | success, `unknown/unknown` | `-32603` `CatalogNotReady` |
| `session/delete`, `additionalDirectories`, steering, child updates | no | yes |
| `cwd` check on load | no | yes (`-32602`) |
| MCP over SSE | yes | no |

Also changed in 2.x (docs `migrate-v1`, source): `OPENCODE_DISABLE_LSP_DOWNLOAD` is not read (V2
runs no language servers); `OPENCODE_PASSWORD` is the new name, `OPENCODE_SERVER_USERNAME` is gone;
`OPENCODE_DISABLE_MODELS_FETCH`, `OPENCODE_DISABLE_AUTOUPDATE` and
`OPENCODE_DISABLE_PROJECT_CONFIG` are still read; `/etc/opencode/opencode.json` is still read with
the highest precedence; `enabled_providers` becomes a deny-by-default policy; `tui.json` moves to
`cli.json`; V1 plugins do not run. The V1 provider syntax of Automa (`npm`, `options.baseURL`,
`env`) loaded in 2.x (live).

Unstable:

- ACP: `session/set_model` (removed in 2.x), `session/fork` (1.x `unstable_`), `usage` in the
  prompt answer. ACP v2 is a draft (2026-07-20); opencode uses protocol version 1.
- opencode extensions: `_session/steering`, `opencode/child-session-updates`,
  `opencode/session/child_update`, `_meta["opencode/retry"]`, `_meta["opencode/compaction"]`.
- The 2.x ACP code changes fast (2.2.0 to 2.3.0 in 17 days, with "Breaking changes" sections).

## Recommended client design

Use ACP (`opencode acp`) on opencode **2.x**. Reasons: ACP is one standard JSON-RPC protocol for
both lines, with ready tool cards, full-file diffs, permission requests, question forms, modes,
models, cancel and history replay. The client needs no HTTP client, no SSE parser and (in 2.x) no
password. On 1.19.6, ACP cannot ask questions with choices (OC-9), and the owner needs them. The
next Update will probably move the phone to 2.x anyway (OC-1).

1. Pin a 2.x release for the setup, and select the Update from the 2.x line, not from
   `releases/latest` (decision of the owner). DESIGN §2 and §12 now say "the TUI" and "no native
   chat UI"; they need an update. The TUI can stay for the terminal.
2. One `opencode acp` process for each open chat, in the repository folder, through `Env` (PRoot),
   with the DESIGN §8 environment and the secrets. For 1.x (until the pin moves), also a new
   random `OPENCODE_SERVER_PASSWORD` (OC-2). The foreground service holds while a turn runs (F-9).
   A close sends EOF to stdin; a stop uses the `/proc` kill of F-8 (it also stops the 2.x child).
3. Transport: read stdout line by line (UTF-8, one JSON object per line, with a size limit for each
   line); write one line for each message; keep stderr in a small ring buffer (no content in
   release logs). Parse with `org.json`. Ignore lines that are not JSON.
4. JSON-RPC: a request id counter and a map of open requests. Agent requests:
   `session/request_permission` (card, OC-8), `elicitation/create` (card, OC-9),
   `fs/write_text_file` (1.x: answer `null`, write nothing), all others: error `-32601`.
   `$/cancel_request`: close the card and answer the request with error `-32800`.
5. Handshake: `initialize` as in OC-3, read `agentInfo.version`; then `session/new`, or
   `session/list` and `session/load`. Show "No model" for `CatalogNotReady` (2.x) or an empty model
   list (1.x), and "Check the TFF key" for `-32000`.
6. State: one pure Kotlin state machine for each session (unit tests with a scripted fake agent,
   no mocking library): messages by `messageId`, tool calls by `toolCallId` (merge partial
   updates), config options, commands, the open cards. Accept updates also when no prompt runs
   (OC-15).
7. Cards: permission with the three options; question form from `requestedSchema` (`oneOf` = single
   choice, `items.anyOf` = multiple choice, `<key>_custom` = free text); model, effort and mode
   pickers from `configOptions` through `session/set_config_option` only; Stop = `session/cancel`,
   then answer open permission cards with `cancelled`; "Approve plan" = mode `build` and a new prompt.
8. Security: all agent data is untrusted. Limit the text size of each card and diff. Show paths as
   guest paths; open files only through `HomeProvider`. Open links only through the link check of
   DESIGN §5. Declare no `fs` and no `terminal` capabilities.

VERIFY (owner, on the phone): 2.x under PRoot (its child server, its start time, the database);
a TFF turn with a tool call, a `.env` permission, a question form and a cancel.
