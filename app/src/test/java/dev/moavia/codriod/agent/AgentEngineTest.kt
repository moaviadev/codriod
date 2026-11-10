package dev.moavia.codriod.agent

import dev.moavia.codriod.TestHttpServer
import dev.moavia.codriod.workspace.ProjectStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentEngineTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(): Pair<ProjectStore, String> {
        val store = ProjectStore(temp.newFolder("ws"))
        return store to store.createProject("demo", "Web").id
    }

    /** Replies from a script; records what the agent sent. */
    private class ScriptedBackend(private val replies: List<LlmReply>) : ChatBackend {
        val seen = mutableListOf<List<ChatMessage>>()
        var specs: List<ToolSpec> = emptyList()
        override suspend fun chat(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>): LlmReply {
            seen += messages.toList(); specs = tools
            return replies[seen.size - 1]
        }
    }

    private class Approvals(vararg decisions: Decision) : Approver {
        private val queue = ArrayDeque(decisions.toList())
        val asked = mutableListOf<ApprovalRequest>()
        override suspend fun approve(request: ApprovalRequest): Decision { asked += request; return queue.removeFirst() }
    }

    private fun tools(ws: AgentWorkspace, approver: Approver, always: BooleanArray = booleanArrayOf(false)) =
        AgentTools(ws, approver, { false }, { always[0] }, { always[0] = true })

    @Test fun agentReadsEditsWithApprovalAndAnswers() = runBlocking {
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id, ShellRunner("/bin/sh"))
        val backend = ScriptedBackend(listOf(
            LlmReply("Let me look.", listOf(ToolCall("c1", "read_file", """{"path":"index.html"}"""))),
            LlmReply("", listOf(ToolCall("c2", "edit_file", """{"path":"index.html","old_text":"Hello, Codriod.","new_text":"Hello, Agent."}"""),
                ToolCall("c3", "write_file", """{"path":"src/notes/todo.md","content":"- ship\n"}"""))),
            LlmReply("Done: updated the heading and added a todo.", emptyList())))
        val approvals = Approvals(Decision.Allow, Decision.Allow)
        val history = mutableListOf(ChatMessage(Role.User, "Change the heading"))
        var filesChanged = 0
        AgentEngine(backend, tools(ws, approvals)).run(history, "sys", AgentMode.Agent, true, onChange = {},
            onFilesChanged = { filesChanged++ })

        assertTrue(store.read(id, "index.html").contains("Hello, Agent."))
        assertEquals("- ship\n", store.read(id, "src/notes/todo.md"))
        assertEquals(2, approvals.asked.size)
        val edit = approvals.asked[0] as ApprovalRequest.Edit
        assertTrue(edit.diff.any { it.kind == DiffKind.Removed && it.text.contains("Hello, Codriod.") })
        assertTrue((approvals.asked[1] as ApprovalRequest.Edit).created)
        assertEquals(1, filesChanged)
        assertEquals("Done: updated the heading and added a todo.", history.last().text)
        assertTrue(backend.seen[1].last().text.contains("1 | "))      // read_file result fed back with line numbers
        assertTrue(backend.specs.any { it.name == "run_command" })
    }

    @Test fun rejectedEditAndAskModeChangeNothing() = runBlocking {
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id, ShellRunner("/bin/sh"))
        val before = store.read(id, "app.js")
        val backend = ScriptedBackend(listOf(
            LlmReply("", listOf(ToolCall("c1", "write_file", """{"path":"app.js","content":"x"}"""))),
            LlmReply("Okay, I will not change it.", emptyList())))
        AgentEngine(backend, tools(ws, Approvals(Decision.Deny))).run(mutableListOf(ChatMessage(Role.User, "go")), "s",
            AgentMode.Agent, true, onChange = {})
        assertEquals(before, store.read(id, "app.js"))
        assertTrue(backend.seen[1].last().text.contains("rejected"))

        val ask = ScriptedBackend(listOf(LlmReply("", listOf(ToolCall("c1", "delete_file", """{"path":"app.js"}"""))),
            LlmReply("I can't in Ask mode.", emptyList())))
        AgentEngine(ask, tools(ws, Approvals())).run(mutableListOf(ChatMessage(Role.User, "delete")), "s", AgentMode.Ask, true, onChange = {})
        assertEquals(before, store.read(id, "app.js"))
        assertFalse(ask.specs.any { it.name == "delete_file" })
    }

    @Test fun commandsNeedPermissionAndAlwaysIsRemembered() = runBlocking {
        assumeTrue("needs a POSIX shell", java.io.File("/bin/sh").canExecute())
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id, ShellRunner("/bin/sh"))
        val always = booleanArrayOf(false)
        val approvals = Approvals(Decision.AlwaysAllow)
        val backend = ScriptedBackend(listOf(
            LlmReply("", listOf(ToolCall("c1", "run_command", """{"command":"echo hi > out.txt; cat out.txt"}"""))),
            LlmReply("", listOf(ToolCall("c2", "run_command", """{"command":"ls"}"""))),
            LlmReply("ok", emptyList())))
        val history = mutableListOf(ChatMessage(Role.User, "run"))
        AgentEngine(backend, tools(ws, approvals, always)).run(history, "s", AgentMode.Agent, true, onChange = {})
        assertEquals(1, approvals.asked.size)
        assertTrue(always[0])
        assertTrue(backend.seen[1].last().text.contains("exit code 0") && backend.seen[1].last().text.contains("hi"))
        assertTrue(backend.seen[2].last().text.contains("out.txt"))
        assertEquals("hi\n", store.read(id, "out.txt"))
    }

    @Test fun shellTimesOut() {
        assumeTrue("needs a POSIX shell", java.io.File("/bin/sh").canExecute())
        val result = ShellRunner("/bin/sh").run(temp.root, "sleep 5", 1)
        assertTrue(result.timedOut)
        assertNull(result.exitCode)
    }

    @Test fun stepLimitAndEmptyAnswerBecomeErrors() = runBlocking {
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id, ShellRunner("/bin/sh"))
        val loop = ChatBackend { _, _, _ -> LlmReply("", listOf(ToolCall("c", "list_files", "{}"))) }
        val history = mutableListOf(ChatMessage(Role.User, "x"))
        AgentEngine(loop, tools(ws, Approvals()), maxSteps = 3).run(history, "s", AgentMode.Agent, true, onChange = {})
        assertEquals(Role.Error, history.last().role)
        val empty = mutableListOf(ChatMessage(Role.User, "x"))
        AgentEngine({ _, _, _ -> LlmReply("", emptyList(), stopReason = "length") }, tools(ws, Approvals()))
            .run(empty, "s", AgentMode.Agent, true, onChange = {})
        assertTrue(empty.last().text.contains("length"))
    }

    @Test fun historyRoundTripsAndDanglingToolCallsAreRepaired() {
        val messages = listOf(ChatMessage(Role.User, "hi"),
            ChatMessage(Role.Assistant, "", listOf(ToolCall("a", "read_file", "{\"path\":\"x\"}", "{\"extra_content\":1}"),
                ToolCall("b", "list_files", "{}"))),
            ChatMessage(Role.Tool, "content", toolCallId = "a", toolName = "read_file", summary = "Read x"))
        val decoded = ChatHistoryJson.decode(ChatHistoryJson.encode(messages))
        assertEquals(messages.map { it.copy(time = 0) }, decoded.map { it.copy(time = 0) })
        val repaired = ChatHistoryJson.repair(decoded)
        assertEquals(4, repaired.size)
        assertEquals("b", repaired.last().toolCallId)
    }

    @Test fun systemPromptCarriesContext() {
        val prompt = AgentPrompt.system("demo", listOf("index.html", "src/"), "index.html" to "<h1>x</h1>", "Use tabs.",
            "Answer in Urdu.", AgentMode.Agent, true)
        listOf("demo", "src/", "<h1>x</h1>", "Use tabs.", "Answer in Urdu.", "Mode: Agent", "Never ask the user to paste")
            .forEach { assertTrue(it, prompt.contains(it)) }
    }

    // ---- wire formats and HTTP --------------------------------------------------------------------

    @Test fun openAiAndAnthropicRequestsCarryToolTurns() {
        val turns = listOf(ChatMessage(Role.User, "q"),
            ChatMessage(Role.Assistant, "", listOf(ToolCall("t1", "read_file", """{"path":"a"}""", """{"id":"t1","extra_content":{"google":{"thought_signature":"sig"}}}"""),
                ToolCall("t2", "list_files", "{}"))),
            ChatMessage(Role.Tool, "A", toolCallId = "t1"), ChatMessage(Role.Tool, "B", toolCallId = "t2"),
            ChatMessage(Role.Error, "ignored"))
        val tools = AgentTools.baseSpecs(AgentMode.Ask)
        val openAi = OpenAiProtocol.request("m", "sys", turns, tools)
        val msgs = openAi.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals(JSONObject.NULL, msgs.getJSONObject(2).get("content"))
        assertEquals("sig", msgs.getJSONObject(2).getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("extra_content").getJSONObject("google").getString("thought_signature"))
        assertEquals(5, msgs.length())
        assertEquals("function", openAi.getJSONArray("tools").getJSONObject(0).getString("type"))

        val anthropic = AnthropicProtocol.request("m", "sys", turns, tools)
        val am = anthropic.getJSONArray("messages")
        assertEquals(3, am.length())          // user, assistant(tool_use x2), user(tool_result x2)
        assertEquals(2, am.getJSONObject(2).getJSONArray("content").length())
        assertEquals("sys", anthropic.getString("system"))
        assertEquals("tool_use", am.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
    }

    @Test fun modelListsFromDifferentProvidersParse() {
        val openAi = ModelListParser.parse("""{"data":[{"id":"gpt-5"},{"id":"text-embedding-3-small"},{"id":"whisper-1"},{"id":"gpt-4.1"}]}""")
        assertEquals(listOf("gpt-4.1", "gpt-5"), openAi.map { it.id })
        val router = ModelListParser.parse("""{"data":[{"id":"a/paid","pricing":{"prompt":"0.000001","completion":"0.000002"}},{"id":"b/free:free","pricing":{"prompt":"0","completion":"0"}}]}""")
        assertEquals("b/free:free", router.first().id); assertTrue(router.first().free)
        assertEquals(listOf("gemini-2.5-flash"), ModelListParser.parse("""{"data":[{"id":"models/gemini-2.5-flash"}]}""").map { it.id })
        assertEquals(listOf("openai/gpt-4.1"), ModelListParser.parse("""[{"id":"openai/gpt-4.1","name":"GPT-4.1"}]""").map { it.id })
        assertEquals(listOf("command-a-03-2025"), ModelListParser.parse("""{"models":[{"name":"command-a-03-2025"}]}""").map { it.id })
    }

    @Test fun clientTalksToOpenAiAndAnthropicStyleServers() = runBlocking {
        val headers = mutableMapOf<String, String?>()
        val server = TestHttpServer { req ->
            when (req.path) {
                "/v1/models" -> { headers["models"] = req.header("Authorization")
                    TestHttpServer.Response(200, """{"data":[{"id":"m-1"},{"id":"m-2"}]}""") }
                "/v1/chat/completions" -> { headers["chat"] = req.header("Authorization")
                    if (JSONObject(req.body).getString("model") == "bad") TestHttpServer.Response(401, """{"error":{"message":"Invalid API key"}}""")
                    else TestHttpServer.Response(200, """{"choices":[{"message":{"content":"hi there","tool_calls":[{"id":"x","type":"function","function":{"name":"list_files","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":5,"completion_tokens":2}}""") }
                "/a/v1/messages" -> { headers["anthropic"] = req.header("x-api-key") + "|" + req.header("anthropic-version")
                    TestHttpServer.Response(200, """{"content":[{"type":"text","text":"ok"},{"type":"tool_use","id":"tu","name":"read_file","input":{"path":"a"}}],"stop_reason":"tool_use","usage":{"input_tokens":3,"output_tokens":1}}""") }
                else -> TestHttpServer.Response(404)
            }
        }
        try {
            val base = server.baseUrl
            val spec = ProviderSpec("test", "Test", ApiStyle.OpenAI, "$base/v1", Tier.Free)
            assertEquals(listOf("m-1", "m-2"), LlmClient().listModels(spec, "k1", "$base/v1").map { it.id })
            assertEquals("Bearer k1", headers["models"])
            val reply = LlmClient().chat(spec, "k1", "$base/v1", "m-1", "sys", listOf(ChatMessage(Role.User, "hi")), emptyList())
            assertEquals("hi there", reply.text); assertEquals("list_files", reply.toolCalls.single().name); assertEquals(5, reply.inputTokens)
            val error = runCatching { LlmClient().chat(spec, "k1", "$base/v1", "bad", "", listOf(ChatMessage(Role.User, "x")), emptyList()) }
            assertEquals("HTTP 401: Invalid API key", error.exceptionOrNull()?.message)

            val claude = ProviderSpec("anthropic", "A", ApiStyle.Anthropic, "$base/a/v1", Tier.Pro)
            val a = LlmClient().chat(claude, "sk-ant", "$base/a/v1", "c", "sys", listOf(ChatMessage(Role.User, "hi")), AgentTools.baseSpecs(AgentMode.Ask))
            assertEquals("ok", a.text); assertEquals("""{"path":"a"}""", a.toolCalls.single().arguments)
            assertEquals("sk-ant|2023-06-01", headers["anthropic"])
        } finally { server.close() }
    }

    @Test fun everyProviderIsWellFormed() {
        assertEquals(Providers.all.size, Providers.all.map { it.id }.toSet().size)
        Providers.all.filter { it.id != Providers.CUSTOM }.forEach { spec ->
            assertTrue(spec.id, spec.baseUrl.startsWith("https://") || spec.tier == Tier.Local)
            if (spec.needsKey) assertTrue(spec.id, spec.keyUrl?.startsWith("https://") == true)
        }
        assertTrue(Providers.all.count { it.tier == Tier.Free || it.tier == Tier.FreeTier } >= 8)
    }
}
