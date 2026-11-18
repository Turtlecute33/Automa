package app.automa

import app.automa.chat.Answer
import app.automa.chat.Ask
import app.automa.chat.ClaudeDriver
import app.automa.chat.CodexDriver
import app.automa.chat.Driver
import app.automa.chat.Entry
import app.automa.chat.Guest
import app.automa.chat.OpencodeDriver
import app.automa.chat.Status
import app.automa.chat.clean
import app.automa.chat.strings
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The three chat drivers with scripted agent messages (made-up data, DESIGN §13). No process, no network. */
class ChatTest {
    private class FakeGuest(val files: MutableMap<String, String> = HashMap(), val canAppend: Boolean = true) : Guest {
        val reads = ArrayList<Pair<String, String>>()
        val runs = ArrayList<Pair<List<String>, String>>()
        override fun read(path: String, max: Int, within: String): String? {
            reads += path to within
            return files[path]?.takeIf { path.startsWith(within.trimEnd('/') + "/") }
        }
        override suspend fun exit(command: List<String>, cwd: String): Int {
            runs += command to cwd
            return 0
        }
        override fun append(path: String, text: String): Boolean {
            if (canAppend) files[path] = files[path].orEmpty() + text
            return canAppend
        }
    }

    private val dir = "/root/repos/demo"
    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    private val sent = ArrayList<JSONObject>()

    private fun <D : Driver> D.wired(): D = apply { attach { synchronized(sent) { sent += it } } }

    private fun Driver.feed(vararg lines: String) = runBlocking { lines.forEach { receive(JSONObject(it)) } }

    @Test
    fun claudeStreamsTextAndKeepsTheFullBlock() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"message_start","message":{"id":"m1"}}}""",
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}}""",
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}}""",
            """{"type":"stream_event","parent_tool_use_id":null,"event":{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}}""",
            """{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m1","content":[{"type":"text","text":"Hello **world**"}]}}""",
        )
        val texts = d.state.value.entries.filterIsInstance<Entry.Text>()
        assertEquals(listOf("Hello **world**"), texts.map { it.text })
    }

    @Test
    fun claudePermissionAlwaysSendsTheSuggestion() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"t1",
               "input":{"command":"npm test","description":"Run the tests"},
               "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash","ruleContent":"npm test *"}],"behavior":"allow","destination":"localSettings"}]}}""",
        )
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertEquals(listOf("Yes", "Yes, and do not ask again for: npm test *", "No"), ask.choices.map { it.label })
        assertTrue("npm test" in ask.detail.orEmpty())
        runBlocking { d.answer(ask, Answer.Pick(ask.choices[1])) }
        val r = sent.single().getJSONObject("response")
        assertEquals("p1", r.getString("request_id"))
        val p = r.getJSONObject("response")
        assertEquals("allow", p.getString("behavior"))
        assertEquals("t1", p.getString("toolUseID"))
        assertEquals("npm test", p.getJSONObject("updatedInput").getString("command"))
        assertEquals("npm test *", p.getJSONArray("updatedPermissions").getJSONObject(0).getJSONArray("rules").getJSONObject(0).getString("ruleContent"))
        assertTrue(d.state.value.asks.isEmpty())
    }

    @Test
    fun claudeDenyCarriesTheNote() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed("""{"type":"control_request","request_id":"p2","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"t2","input":{"command":"rm -rf build"}}}""")
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        runBlocking { d.answer(ask, Answer.Pick(ask.choices.last(), "Keep the build folder")) }
        val p = sent.single().getJSONObject("response").getJSONObject("response")
        assertEquals("deny", p.getString("behavior"))
        assertEquals("Keep the build folder", p.getString("message"))
    }

    @Test
    fun claudeQuestionsAnswerByQuestionText() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"type":"control_request","request_id":"q1","request":{"subtype":"can_use_tool","tool_name":"AskUserQuestion","tool_use_id":"t3","input":{"questions":[
               {"question":"Which library?","header":"Library","multiSelect":false,"options":[{"label":"A","description":"a"},{"label":"B","description":"b"}]},
               {"question":"Which features?","header":"Features","multiSelect":true,"options":[{"label":"X","description":""},{"label":"Y","description":""}]}]}}}""",
        )
        val ask = assertIs<Ask.Questions>(d.state.value.asks.single())
        assertEquals(listOf(false, true), ask.questions.map { it.multi })
        runBlocking { d.answer(ask, Answer.Replies(listOf(Answer.Reply(emptyList(), "C, my own"), Answer.Reply(listOf("X", "Y"), "")))) }
        val input = sent.single().getJSONObject("response").getJSONObject("response").getJSONObject("updatedInput")
        assertEquals("C, my own", input.getJSONObject("answers").getString("Which library?"))
        assertEquals(listOf("X", "Y"), input.getJSONObject("answers").getJSONArray("Which features?").strings())
        assertEquals(2, input.getJSONArray("questions").length())
    }

    @Test
    fun claudePlanApprovalSetsTheMode() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed("""{"type":"control_request","request_id":"e1","request":{"subtype":"can_use_tool","tool_name":"ExitPlanMode","tool_use_id":"t4","input":{"plan":"1. Fix it"}}}""")
        val ask = assertIs<Ask.Plan>(d.state.value.asks.single())
        assertEquals("1. Fix it", ask.plan)
        runBlocking { d.answer(ask, Answer.Pick(ask.choices.first())) }
        val p = sent.single().getJSONObject("response").getJSONObject("response")
        assertEquals("acceptEdits", p.getJSONArray("updatedPermissions").getJSONObject(0).getString("mode"))
    }

    @Test
    fun claudeToolResultGivesTheDiffAndCancelRemovesTheCard() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m2","content":[{"type":"tool_use","id":"t5","name":"Edit","input":{"file_path":"/root/repos/demo/README.md","old_string":"teh","new_string":"the"}}]}}""",
            """{"type":"user","parent_tool_use_id":null,"message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t5","content":"ok","is_error":false}]},
               "tool_use_result":{"filePath":"/root/repos/demo/README.md","structuredPatch":[{"oldStart":2,"oldLines":1,"newStart":2,"newLines":1,"lines":["-teh app","+the app"]}]}}""",
            """{"type":"control_request","request_id":"p3","request":{"subtype":"can_use_tool","tool_name":"Read","tool_use_id":"t6","input":{"file_path":"/etc/hosts"}}}""",
            """{"type":"control_cancel_request","request_id":"p3"}""",
            """{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login"}""",
        )
        val tool = assertIs<Entry.Tool>(d.state.value.entries.single())
        assertEquals("Edit README.md", tool.title)
        assertEquals(Status.Done, tool.status)
        assertEquals(listOf("@@ -2,1 +2,1 @@", "-teh app", "+the app"), tool.diff!!.files.single().lines)
        assertTrue(d.state.value.asks.isEmpty())
        assertTrue(d.state.value.login != null)
    }

    @Test
    fun claudeTrustComesFromItsOwnStore() {
        val config = """{"projects":{"/root/repos":{"hasTrustDialogAccepted":true},"/root/other":{"hasTrustDialogAccepted":false}}}"""
        assertTrue(ClaudeDriver.trusted(config, "/root/repos/demo"))
        assertFalse(ClaudeDriver.trusted(config, "/root/other"))
        assertFalse(ClaudeDriver.trusted("not json", "/root/repos/demo"))
    }

    @Test
    fun codexCommandApprovalKeepsTheDecisionOrder() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"id":0,"method":"item/commandExecution/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"C","command":"git push --force","cwd":"/root/repos/demo",
               "availableDecisions":["accept",{"acceptWithExecpolicyAmendment":{"execpolicy_amendment":["git","push"]}},"cancel"]}}""",
        )
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertEquals(listOf("Yes", "Yes, and do not ask again for commands that start with git push", "No, and stop"), ask.choices.map { it.label })
        runBlocking { d.answer(ask, Answer.Pick(ask.choices[1])) }
        val reply = sent.single()
        assertEquals(0, reply.getInt("id"))
        assertEquals("git", reply.getJSONObject("result").getJSONObject("decision").getJSONObject("acceptWithExecpolicyAmendment").getJSONArray("execpolicy_amendment").getString(0))
    }

    @Test
    fun anUnknownCodexDecisionIsNotAnAllow() {
        // A new decision type can have any effect: it must not look like a plain Yes (review KM-M-29).
        val choice = CodexDriver.decisionChoice(0, JSONObject().put("grantRootForever", JSONObject()))
        assertEquals(app.automa.chat.Choice.Kind.Other, choice.kind)
    }

    @Test
    fun codexNewAndDeletedFilesHaveNoExtraLine() {
        val changes = org.json.JSONArray(
            """[{"path":"$dir/a.txt","kind":{"type":"add"},"diff":"one\ntwo\n"},{"path":"$dir/b.txt","kind":{"type":"delete"},"diff":"old\n"},
               {"path":"$dir/c.txt","kind":{"type":"add"},"diff":""}]""",
        )
        val files = CodexDriver.diff(changes, dir).files
        assertEquals(listOf(listOf("+one", "+two"), listOf("-old"), emptyList()), files.map { it.lines })
    }

    @Test
    fun codexNoWithANoteAnswersAlsoWhenTheSteerFails() = runBlocking {
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"id":7,"method":"item/commandExecution/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"C","command":"rm -rf build",
               "availableDecisions":["accept","decline","cancel"]}}""",
        )
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        val answering = launch(kotlinx.coroutines.Dispatchers.Default) { d.answer(ask, Answer.Pick(ask.choices[1], "use the other folder")) }
        while (synchronized(sent) { sent.none { it.optString("method") == "turn/start" } }) kotlinx.coroutines.delay(10)
        val steer = synchronized(sent) { sent.first { it.optString("method") == "turn/start" } }
        d.feed("""{"id":${steer.getLong("id")},"error":{"code":-32600,"message":"no active turn"}}""")
        answering.join()
        val reply = synchronized(sent) { sent.single { it.optInt("id", -1) == 7 && it.has("result") } }
        assertEquals("decline", reply.getJSONObject("result").getString("decision"))
    }

    @Test
    fun codexQuestionsUseUserNote() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"id":2,"method":"item/tool/requestUserInput","params":{"threadId":"T","turnId":"U","itemId":"Q","questions":[{"id":"db","header":"Database","question":"Which storage?","isOther":true,"isSecret":false,
               "options":[{"label":"SQLite","description":"One file"},{"label":"JSON","description":"Simple"}]}]}}""",
        )
        val ask = assertIs<Ask.Questions>(d.state.value.asks.single())
        runBlocking { d.answer(ask, Answer.Replies(listOf(Answer.Reply(listOf("SQLite"), "one file per user")))) }
        val answers = sent.single().getJSONObject("result").getJSONObject("answers").getJSONObject("db").getJSONArray("answers")
        assertEquals(listOf("SQLite", "user_note: one file per user"), answers.strings())
    }

    @Test
    fun codexItemsStreamAndFileApprovalShowsTheDiff() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"method":"item/started","params":{"item":{"type":"agentMessage","id":"I","text":""}}}""",
            """{"method":"item/agentMessage/delta","params":{"itemId":"I","delta":"Fix"}}""",
            """{"method":"item/agentMessage/delta","params":{"itemId":"I","delta":"ed"}}""",
            """{"method":"item/started","params":{"item":{"type":"fileChange","id":"F","status":"inProgress","changes":[{"path":"/root/repos/demo/a.kt","kind":{"type":"update","move_path":null},"diff":"@@ -1 +1 @@\n-a\n+b\n"}]}}}""",
            """{"id":1,"method":"item/fileChange/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"F","reason":null}}""",
        )
        assertEquals("Fixed", (d.state.value.entries.first() as Entry.Text).text)
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertEquals(listOf("@@ -1 +1 @@", "-a", "+b"), ask.diff!!.files.single().lines)
        runBlocking { d.answer(ask, Answer.Pick(ask.choices.first())) }
        assertEquals("accept", sent.single().getJSONObject("result").getString("decision"))
    }

    @Test
    fun codexFileApprovalWithNoFilesSaysSo() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed("""{"id":1,"method":"item/fileChange/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"X","reason":null}}""")
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertNull(ask.diff)
        assertTrue(ask.caution)
        assertTrue(ask.message!!.isNotEmpty())
    }

    @Test
    fun codexPlanEndsWithAnApprovalCard() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        runBlocking { d.select(app.automa.chat.Setting.Mode, "plan") }
        d.feed(
            """{"method":"item/completed","params":{"item":{"type":"plan","id":"P","text":"1. Add a test"}}}""",
            """{"method":"turn/completed","params":{"turn":{"id":"U","status":"completed"}}}""",
        )
        val ask = assertIs<Ask.Plan>(d.state.value.asks.single())
        assertEquals("1. Add a test", ask.plan)
    }

    @Test
    fun opencodePermissionAndToolMerge() {
        val d = OpencodeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"tool_call","toolCallId":"c1","name":"edit","title":"README.md","kind":"edit","status":"pending","rawInput":{}}}}""",
            """{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s","update":{"sessionUpdate":"tool_call_update","toolCallId":"c1","status":"completed",
               "content":[{"type":"diff","path":"/root/repos/demo/README.md","oldText":"Hello\n","newText":"Hello, world\n"}]}}}""",
            """{"jsonrpc":"2.0","id":0,"method":"session/request_permission","params":{"sessionId":"s","toolCall":{"toolCallId":"c2","title":".env","kind":"read","status":"pending","rawInput":{"path":".env"}},
               "options":[{"optionId":"once","kind":"allow_once","name":"Allow once"},{"optionId":"always","kind":"allow_always","name":"Always allow"},{"optionId":"reject","kind":"reject_once","name":"Reject"}]}}""",
        )
        val tool = d.state.value.entries.filterIsInstance<Entry.Tool>().first()
        assertEquals(Status.Done, tool.status)
        assertEquals(listOf("-Hello", "+Hello, world"), tool.diff!!.files.single().lines.filter { it[0] != ' ' })
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertEquals(listOf("Allow once", "Always allow", "Reject"), ask.choices.map { it.label })
        runBlocking { d.answer(ask, Answer.Pick(ask.choices[1])) }
        val outcome = sent.single().getJSONObject("result").getJSONObject("outcome")
        assertEquals("selected", outcome.getString("outcome"))
        assertEquals("always", outcome.getString("optionId"))
    }

    @Test
    fun opencodeQuestionFormGivesContent() {
        val d = OpencodeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"jsonrpc":"2.0","id":1,"method":"elicitation/create","params":{"mode":"form","sessionId":"s","message":"Questions","requestedSchema":{"type":"object","properties":{
               "q0":{"type":"string","title":"Runtime","description":"Which runtime?","oneOf":[{"const":"Bun","title":"Bun","description":"Fast"},{"const":"Node","title":"Node","description":"Stable"}]},
               "q0_custom":{"type":"string","title":"Runtime (other)"},
               "q1":{"type":"array","title":"Goals","description":"What matters?","items":{"anyOf":[{"const":"Fast","title":"Fast"},{"const":"Small","title":"Small"}]}},
               "q1_custom":{"type":"string"}},"required":[]}}}""",
        )
        val ask = assertIs<Ask.Questions>(d.state.value.asks.single())
        assertEquals(listOf("q0", "q1"), ask.questions.map { it.id })
        assertEquals(listOf(false, true), ask.questions.map { it.multi })
        runBlocking { d.answer(ask, Answer.Replies(listOf(Answer.Reply(listOf("Bun"), ""), Answer.Reply(listOf("Fast"), "Safe")))) }
        val result = sent.single().getJSONObject("result")
        assertEquals("accept", result.getString("action"))
        val content = result.getJSONObject("content")
        assertEquals("Bun", content.getString("q0"))
        assertEquals(listOf("Fast"), content.getJSONArray("q1").strings())
        assertEquals("Safe", content.getString("q1_custom"))
    }

    @Test
    fun opencodeChunksJoin() {
        val d = OpencodeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"I found "}}}}""",
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"the bug"}}}}""",
            """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_thought_chunk","messageId":"m:r","content":{"type":"text","text":"hmm"}}}}""",
        )
        val entries = d.state.value.entries
        assertEquals("I found the bug", (entries[0] as Entry.Text).text)
        assertIs<Entry.Thought>(entries[1])
        assertNull(d.state.value.login)
    }

    @Test
    fun opencodePlanCardOnlyAfterACompleteTurn() = runBlocking {
        for ((reason, card) in listOf("cancelled" to false, "max_tokens" to false, "end_turn" to true)) {
            synchronized(sent) { sent.clear() }
            val d = OpencodeDriver(dir, FakeGuest()).wired()
            d.feed("""{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"current_mode_update","currentModeId":"plan"}}}""")
            val sending = launch(kotlinx.coroutines.Dispatchers.Default) { d.send("Plan the fix") }
            while (synchronized(sent) { sent.none { it.optString("method") == "session/prompt" } }) kotlinx.coroutines.delay(10)
            val prompt = synchronized(sent) { sent.first { it.optString("method") == "session/prompt" } }
            d.feed(
                """{"jsonrpc":"2.0","method":"session/update","params":{"update":{"sessionUpdate":"agent_message_chunk","messageId":"m","content":{"type":"text","text":"1. Change a.kt"}}}}""",
                """{"jsonrpc":"2.0","id":${prompt.getLong("id")},"result":{"stopReason":"$reason"}}""",
            )
            sending.join()
            assertEquals(card, d.state.value.asks.any { it is Ask.Plan }, reason)
        }
    }

    @Test
    fun cleanRemovesEscapesAndShowsFormatCharacters() {
        assertEquals("red text\tok\n", clean("\u001B[31mred\u001B[0m text\tok\n\u0007"))
        assertEquals("title", clean("\u001B]0;evil\u0007title"))
        // Trojan Source: the bidi override and a zero-width space become visible (review SEC-17).
        assertEquals("ls <U+202E>txt.sh<U+200B>", clean("ls \u202Etxt.sh\u200B"))
    }

    @Test
    fun longCommandsShowTheCut() {
        val long = "echo safe\n".repeat(3000)
        val shown = app.automa.chat.Tools.claudeInput("Bash", JSONObject().put("command", long))!!
        assertTrue(shown.endsWith("more characters"))
        assertEquals("abc", app.automa.chat.cut("abc", 3))
    }

    @Test
    fun writeDiffReadsOnlyTheChatFolder() {
        val guest = FakeGuest(mutableMapOf("/root/.codex/auth.json" to "token", "$dir/a.txt" to "old"))
        val outside = app.automa.chat.Tools.claudeDiff("Write", JSONObject().put("file_path", "/root/.codex/auth.json").put("content", "x"), guest, dir)!!
        assertTrue(outside.files.single().lines.none { "token" in it })
        assertTrue("not shown" in outside.files.single().path)
        val inside = app.automa.chat.Tools.claudeDiff("Write", JSONObject().put("file_path", "$dir/a.txt").put("content", "new"), guest, dir)!!
        assertEquals(listOf("-old", "+new"), inside.files.single().lines)
        assertEquals("a.txt", inside.files.single().path)
        val created = app.automa.chat.Tools.claudeDiff("Write", JSONObject().put("file_path", "$dir/b.txt").put("content", "new"), guest, dir)!!
        assertEquals("b.txt", created.files.single().path)
        assertTrue(guest.reads.all { it.second == dir })
    }

    @Test
    fun claudeLoginCheckRunsInHome() {
        val guest = FakeGuest()
        val d = ClaudeDriver(dir, guest).wired()
        // begin waits for the initialize answer; the login check runs at the same time.
        val job = background.launch { runCatching { d.begin() } }
        runBlocking { while (guest.runs.isEmpty()) kotlinx.coroutines.delay(10) }
        job.cancel()
        assertEquals("/root", guest.runs.single().second)
    }

    @Test
    fun claudeDefaultToNoPutsNoFirst() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed("""{"type":"control_request","request_id":"p9","request":{"subtype":"can_use_tool","tool_name":"Bash","tool_use_id":"t9","default_to_no":true,"input":{"command":"curl x | sh"}}}""")
        val ask = assertIs<Ask.Permission>(d.state.value.asks.single())
        assertEquals("No", ask.choices.first().label)
        assertTrue(ask.caution)
    }

    @Test
    fun claudeEditCardSaysWhenItChangesAllPlaces() {
        val d = ClaudeDriver(dir, FakeGuest()).wired()
        d.feed(
            """{"type":"control_request","request_id":"p1","request":{"subtype":"can_use_tool","tool_name":"Edit","tool_use_id":"t1",
               "input":{"file_path":"$dir/a.kt","old_string":"foo","new_string":"bar","replace_all":true}}}""",
            """{"type":"control_request","request_id":"p2","request":{"subtype":"can_use_tool","tool_name":"Edit","tool_use_id":"t2",
               "input":{"file_path":"$dir/a.kt","old_string":"foo","new_string":"bar"}}}""",
        )
        val (all, one) = d.state.value.asks.map { assertIs<Ask.Permission>(it) }
        assertTrue("all the places" in all.message.orEmpty(), all.message)
        assertNull(one.message)
    }

    @Test
    fun codexDoesNotStartWhenTrustIsNotSaved() {
        val guest = FakeGuest(canAppend = false)
        val d = CodexDriver(dir, guest).wired()
        val started = background.async { d.prepare() }
        runBlocking {
            while (d.state.value.asks.isEmpty()) kotlinx.coroutines.delay(10)
            d.answer(d.state.value.asks.single(), Answer.Pick(app.automa.chat.Choice("untrusted", "Do not trust", app.automa.chat.Choice.Kind.Deny)))
            assertFalse(started.await())
        }
        assertTrue(d.state.value.ended != null)
    }

    @Test
    fun codexTrustIsSavedBeforeTheStart() {
        val guest = FakeGuest()
        val d = CodexDriver(dir, guest).wired()
        val started = background.async { d.prepare() }
        runBlocking {
            while (d.state.value.asks.isEmpty()) kotlinx.coroutines.delay(10)
            d.answer(d.state.value.asks.single(), Answer.Pick(app.automa.chat.Choice("untrusted", "Do not trust", app.automa.chat.Choice.Kind.Deny)))
            assertTrue(started.await())
        }
        assertTrue("[projects.\"$dir\"]\ntrust_level = \"untrusted\"" in guest.files["/root/.codex/config.toml"].orEmpty())
    }

    @Test
    fun twoCodexChatsInOneFolderAskOnce() {
        // Two trust tables for one folder make ~/.codex/config.toml invalid TOML, and Codex then does not start at all.
        val guest = FakeGuest()
        val first = CodexDriver(dir, guest).wired()
        val second = CodexDriver(dir, guest).wired()
        val a = background.async { first.prepare() }
        val b = background.async { second.prepare() }
        runBlocking {
            while (first.state.value.asks.isEmpty() && second.state.value.asks.isEmpty()) kotlinx.coroutines.delay(10)
            kotlinx.coroutines.delay(100)
            val asking = listOf(first, second).filter { it.state.value.asks.isNotEmpty() }
            assertEquals(1, asking.size)
            asking.single().answer(asking.single().state.value.asks.single(), Answer.Pick(app.automa.chat.Choice("trusted", "Trust", app.automa.chat.Choice.Kind.Allow)))
            assertTrue(a.await())
            assertTrue(b.await())
        }
        assertEquals(1, Regex(Regex.escape("[projects.\"$dir\"]")).findAll(guest.files["/root/.codex/config.toml"].orEmpty()).count())
    }

    @Test
    fun aLargeDiffKeepsFewLinesButItsCounts() {
        // Many Write rows of large files kept all their lines: out of memory (review DS-L-13, KM-H-12).
        val old = (1..5000).joinToString("\n") { "old $it" }
        val new = (1..3000).joinToString("\n") { "new $it" }
        val file = app.automa.chat.Diff.of("a.txt", old, new).files.single()
        assertEquals(app.automa.chat.Diff.MAX_LINES, file.lines.size)
        assertEquals(3000, file.added)
        assertEquals(5000, file.removed)
        assertEquals(8000 - app.automa.chat.Diff.MAX_LINES, file.more)
        assertEquals(1, app.automa.chat.Diff.of("b.txt", "a", "b").files.single().added)
    }

    @Test
    fun finalTextsAndTitlesHaveALimit() {
        val long = "x".repeat(300_000)
        val codex = CodexDriver(dir, FakeGuest()).wired()
        codex.feed(
            """{"method":"item/completed","params":{"item":{"type":"agentMessage","id":"I","text":"$long"}}}""",
            """{"method":"item/completed","params":{"item":{"type":"reasoning","id":"R","summary":["$long"]}}}""",
            """{"method":"item/completed","params":{"item":{"type":"plan","id":"P","text":"$long"}}}""",
            """{"method":"item/started","params":{"item":{"type":"commandExecution","id":"C","command":"$long","status":"inProgress"}}}""",
        )
        val claude = ClaudeDriver(dir, FakeGuest()).wired()
        claude.feed(
            """{"type":"system","subtype":"local_command_output","content":"$long"}""",
            """{"type":"assistant","parent_tool_use_id":null,"message":{"id":"m","content":[{"type":"tool_use","id":"t","name":"Bash","input":{"command":"$long"}}]}}""",
        )
        for (entry in codex.state.value.entries + claude.state.value.entries) {
            val text = when (entry) {
                is Entry.Text -> entry.text
                is Entry.Thought -> entry.text
                is Entry.Tool -> entry.title.also { assertTrue(it.length <= 200, "title of ${entry.key}") }
                else -> ""
            }
            assertTrue(text.length <= app.automa.chat.MAX_TEXT + 40, "${entry.key}: ${text.length}")
        }
    }

    @Test
    fun codexForgetsTheChangesOfADoneItem() {
        val d = CodexDriver(dir, FakeGuest()).wired()
        val item = """{"type":"fileChange","id":"F","status":"%s","changes":[{"path":"$dir/a.kt","kind":{"type":"update","move_path":null},"diff":"@@ -1 +1 @@\n-a\n+b\n"}]}"""
        d.feed(
            """{"method":"item/started","params":{"item":${item.format("inProgress")}}}""",
            """{"method":"item/completed","params":{"item":${item.format("completed")}}}""",
            """{"id":1,"method":"item/fileChange/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"F","reason":null}}""",
        )
        assertNull(assertIs<Ask.Permission>(d.state.value.asks.single()).diff)
    }

    @Test
    fun aFormWithManyFieldsIsReadFast() {
        val properties = JSONObject()
        repeat(60_000) { properties.put("f$it", JSONObject().put("type", "string")) }
        val start = System.nanoTime()
        val questions = app.automa.chat.Forms.questions(JSONObject().put("properties", properties))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(60_000, questions.size)
        // An estimate on the test JVM: a list search for each field took seconds.
        assertTrue(ms < 1000, "took $ms ms")
    }

    @Test
    fun aFailedHandshakeDoesNotStayStarting() = runBlocking {
        for (d in listOf(CodexDriver(dir, FakeGuest()), OpencodeDriver(dir, FakeGuest()))) {
            synchronized(sent) { sent.clear() }
            d.wired()
            val begin = launch(kotlinx.coroutines.Dispatchers.Default) { runCatching { d.begin() } }
            while (synchronized(sent) { sent.none { it.optString("method") == "initialize" } }) kotlinx.coroutines.delay(10)
            val init = synchronized(sent) { sent.first { it.optString("method") == "initialize" } }
            d.feed("""{"jsonrpc":"2.0","id":${init.getLong("id")},"error":{"code":-32603,"message":"internal error"}}""")
            begin.join()
            assertFalse(d.state.value.starting, d.javaClass.simpleName)
        }
    }

    /** The first sent message with [method]; waits for it. */
    private suspend fun sentWith(method: String): JSONObject = kotlinx.coroutines.withTimeout(5000) {
        var found: JSONObject? = null
        while (found == null) {
            found = synchronized(sent) { sent.firstOrNull { it.optString("method") == method } }
            if (found == null) kotlinx.coroutines.delay(10)
        }
        found
    }

    @Test
    fun codexStopBeforeTheTurnIdStopsTheNewTurn() = runBlocking {
        val d = CodexDriver(dir, FakeGuest()).wired()
        for (n in 0..1) {
            synchronized(sent) { sent.clear() }
            val sending = launch(kotlinx.coroutines.Dispatchers.Default) { d.send("Fix it") }
            val start = sentWith("turn/start")
            // A Stop while turn/start waits: no id yet (first turn), or only the id of the turn before.
            d.interrupt()
            assertTrue(synchronized(sent) { sent.none { it.optString("method") == "turn/interrupt" } })
            d.feed("""{"id":${start.getLong("id")},"result":{"turn":{"id":"U$n","status":"inProgress"}}}""")
            val stop = sentWith("turn/interrupt")
            assertEquals("U$n", stop.getJSONObject("params").getString("turnId"))
            d.feed(
                """{"id":${stop.getLong("id")},"result":{}}""",
                """{"method":"turn/completed","params":{"turn":{"id":"U$n","status":"interrupted"}}}""",
            )
            sending.join()
        }
    }

    @Test
    fun codexStopAndNoAndStopCancelTheOtherCards() = runBlocking {
        val requests = arrayOf(
            """{"id":1,"method":"item/commandExecution/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"C","command":"make","availableDecisions":["accept","decline","cancel"]}}""",
            """{"id":2,"method":"item/fileChange/requestApproval","params":{"threadId":"T","turnId":"U","itemId":"F","reason":null}}""",
        )
        fun decision(id: Int) = synchronized(sent) { sent.single { it.optInt("id", -1) == id && it.has("result") } }.getJSONObject("result").getString("decision")
        // Stop: all cards close at once, and each request gets its cancel answer.
        val d = CodexDriver(dir, FakeGuest()).wired()
        d.feed("""{"method":"turn/started","params":{"turn":{"id":"U"}}}""", *requests)
        val stopping = launch(kotlinx.coroutines.Dispatchers.Default) { d.interrupt() }
        val stop = sentWith("turn/interrupt")
        assertTrue(d.state.value.asks.isEmpty())
        d.feed("""{"id":${stop.getLong("id")},"result":{}}""")
        stopping.join()
        assertEquals(listOf("cancel", "cancel"), listOf(decision(1), decision(2)))
        // "No, and stop" on the first card also closes and cancels the second.
        synchronized(sent) { sent.clear() }
        val e = CodexDriver(dir, FakeGuest()).wired()
        e.feed(*requests)
        val first = assertIs<Ask.Permission>(e.state.value.asks.first())
        e.answer(first, Answer.Pick(first.choices.single { it.label == "No, and stop" }))
        assertTrue(e.state.value.asks.isEmpty())
        assertEquals(listOf("cancel", "cancel"), listOf(decision(1), decision(2)))
    }

    @Test
    fun onlySafeIdsGoIntoACommandLine() {
        assertTrue(app.automa.chat.safe("ses_e143d4d47ffeechXHNrkuDVjGh"))
        assertTrue(app.automa.chat.safe("01a1ebbf-1e6e-7b50-8fd0-c9c51b6421b0"))
        assertFalse(app.automa.chat.safe("x;id"))
        assertFalse(app.automa.chat.safe("\$(id)"))
        assertFalse(app.automa.chat.safe(""))
    }
}
