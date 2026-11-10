package dev.moavia.codriod.agent

import dev.moavia.codriod.workspace.ProjectStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AgentProTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(): Pair<ProjectStore, String> {
        val store = ProjectStore(temp.newFolder("ws"))
        return store to store.createProject("demo", "Web").id
    }

    private class Script(private val replies: List<LlmReply>) : ChatBackend {
        val seen = mutableListOf<List<ChatMessage>>()
        override suspend fun chat(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): LlmReply {
            seen += messages.toList(); val r = replies[seen.size - 1]; if (r.text.isNotEmpty()) onText?.invoke(r.text); return r
        }
    }

    private val allow = Approver { Decision.Allow }

    @Test fun commandSafety() {
        listOf("ls -la", "cat index.html", "grep -rn todo .", "find . -name '*.js'", "pwd", "codriod clipboard get", "codriod help")
            .forEach { assertTrue(it, CommandSafety.isSafe(it)) }
        listOf("rm a.txt", "ls; rm x", "cat a > b", "echo \$(id)", "find . -delete", "codriod clipboard set hi", "zip -r a.zip .", "ls | sh")
            .forEach { assertFalse(it, CommandSafety.isSafe(it)) }
        listOf("rm -rf /", "rm -rf ~", "rm -fr *", "sudo rm -rf / ", "rm -r -f ./", "mkfs.ext4 /dev/sda", "dd if=/dev/zero of=/dev/block/sda",
            ":(){ :|:& };:", "reboot", "su", "chmod -R 777 /", "pm uninstall com.x")
            .forEach { assertTrue(it, CommandSafety.isDangerous(it)) }
        listOf("rm -rf build", "rm -rf node_modules", "rm a.txt", "ls", "summary", "rm -f ./a.txt", "echo rm -rf /x").forEach { assertFalse(it, CommandSafety.isDangerous(it)) }
    }

    @Test fun planToolAndModes() = runBlocking {
        var seen: List<PlanStep>? = null
        val plan = PlanToolset { _, steps -> seen = steps }
        val out = plan.execute("update_plan", JSONObject("""{"title":"Call screen","steps":[{"text":"Make html","status":"done"},{"text":"CSS","status":"in_progress"},{"text":"Test","status":"pending"}]}"""), AgentMode.Plan)
        assertEquals(listOf(StepStatus.Done, StepStatus.InProgress, StepStatus.Pending), seen!!.map { it.status })
        assertTrue(out.content.contains("wait"))
        assertEquals("Call screen", PlanJson.decode(out.detail)!!.first)
        assertTrue(plan.specs(AgentMode.Ask).isEmpty())
        val (store, id) = project()
        val tools = AgentTools(ProjectAgentWorkspace(store, id), allow, listOf(plan))
        assertFalse(tools.specs(AgentMode.Plan).any { it.name == "write_file" })
        assertTrue(tools.specs(AgentMode.Plan).any { it.name == "update_plan" })
        assertTrue(tools.specs(AgentMode.Autopilot).any { it.name == "run_command" })
        val blocked = tools.execute(ToolCall("1", "write_file", """{"path":"x","content":"y"}"""), AgentMode.Plan)
        assertTrue(blocked.content.contains("Plan mode"))
    }

    @Test fun editReplaceAllRenameViewImageAndCheckpoints() = runBlocking {
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id)
        val changes = linkedMapOf<String, String?>()
        val tools = AgentTools(ws, allow, emptyList()) { path, original -> if (!changes.containsKey(path)) changes[path] = original }
        store.write(id, "a.txt", "x x x\n")
        assertTrue(tools.execute(ToolCall("1", "edit_file", """{"path":"a.txt","old_text":"x","new_text":"y"}"""), AgentMode.Agent).content.contains("more than once"))
        val edited = tools.execute(ToolCall("2", "edit_file", """{"path":"a.txt","old_text":"x","new_text":"y","replace_all":true}"""), AgentMode.Agent)
        assertEquals("y y y\n", store.read(id, "a.txt"))
        assertTrue(edited.detail!!.startsWith("DIFF a.txt +1 -1"))
        tools.execute(ToolCall("3", "rename_file", """{"from":"a.txt","to":"docs/b.txt"}"""), AgentMode.Agent)
        assertEquals("y y y\n", store.read(id, "docs/b.txt"))
        tools.execute(ToolCall("4", "write_file", """{"path":"new/c.txt","content":"c"}"""), AgentMode.Agent)
        assertEquals("x x x\n", changes["a.txt"])
        assertNull(changes["new/c.txt"])
        assertTrue(changes.containsKey("docs/b.txt"))
        File(store.root(id), "pic.png").writeBytes(byteArrayOf(1, 2, 3))
        val image = tools.execute(ToolCall("5", "view_image", """{"path":"pic.png"}"""), AgentMode.Ask)
        assertEquals(1, image.images.size)
    }

    @Test fun engineStreamsAndCarriesToolImages() = runBlocking {
        val (store, id) = project()
        val ws = ProjectAgentWorkspace(store, id)
        File(store.root(id), "shot.png").writeBytes(byteArrayOf(9))
        val backend = Script(listOf(LlmReply("Looking.", listOf(ToolCall("t", "view_image", """{"path":"shot.png"}"""))), LlmReply("Done.", emptyList())))
        val streamed = StringBuilder()
        val history = mutableListOf(ChatMessage(Role.User, "check", images = listOf("/x.jpg")))
        AgentEngine(backend, AgentTools(ws, allow)).run(history, "s", AgentMode.Agent, true, onChange = {}, onText = { streamed.append(it) })
        assertEquals("Looking.Done.", streamed.toString())
        assertEquals(1, history.first { it.role == Role.Tool }.images.size)
        val noVision = mutableListOf(ChatMessage(Role.User, "check", images = listOf("/x.jpg")))
        val b2 = Script(listOf(LlmReply("ok", emptyList())))
        AgentEngine(b2, AgentTools(ws, allow)).run(noVision, "s", AgentMode.Ask, true, onChange = {}, visionImages = false)
        assertTrue(b2.seen[0][0].images.isEmpty() && b2.seen[0][0].text.contains("cannot see images"))
    }

    @Test fun compactionKeepsPairsAndBudget() {
        val msgs = mutableListOf<ChatMessage>()
        repeat(30) { i ->
            msgs += ChatMessage(Role.User, "q$i")
            msgs += ChatMessage(Role.Assistant, "", listOf(ToolCall("c$i", "read_file", "{}")))
            msgs += ChatMessage(Role.Tool, "x".repeat(5000), toolCallId = "c$i", images = listOf("/i$i.jpg"))
            msgs += ChatMessage(Role.Assistant, "a$i")
        }
        val out = ContextCompactor.compact(msgs, 40_000)
        assertEquals(Role.User, out.first().role)
        assertTrue(out.sumOf { it.text.length } <= 60_000)
        assertEquals("a29", out.last().text)
        assertTrue(out.dropLast(14).all { it.images.isEmpty() })
        assertTrue(out.last { it.role == Role.Tool }.images.isNotEmpty())
        val long = ContextCompactor.compact(msgs.take(40), 1_000_000)
        assertTrue(long[2].text.length < 2000)
        assertEquals(5000, ContextCompactor.compact(msgs.take(8), 1_000_000)[2].text.length)
    }

    @Test fun imagesInRequests() {
        val images = ImageSource { "image/png" to "QUJD" }
        val msgs = listOf(ChatMessage(Role.User, "like this", images = listOf("a.png")),
            ChatMessage(Role.Assistant, "", listOf(ToolCall("t", "browser_screenshot", "{}"))),
            ChatMessage(Role.Tool, "shot", toolCallId = "t", images = listOf("s.jpg")),
            ChatMessage(Role.Assistant, "ok"))
        val o = OpenAiProtocol.request("m", "", msgs, emptyList(), images).getJSONArray("messages")
        assertEquals("image_url", o.getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("data:image/png;base64,QUJD", o.getJSONObject(0).getJSONArray("content").getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("tool", o.getJSONObject(2).getString("role"))
        assertEquals("user", o.getJSONObject(3).getString("role"))
        assertEquals(4 + 1, o.length())
        val a = AnthropicProtocol.request("m", "", msgs, emptyList(), images = images).getJSONArray("messages")
        assertEquals("image", a.getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("type"))
        val result = a.getJSONObject(2).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("image", result.getJSONArray("content").getJSONObject(1).getString("type"))
        assertTrue(ModelCaps.vision("gpt-4o-mini")); assertTrue(ModelCaps.vision("claude-sonnet-4-5")); assertTrue(ModelCaps.vision("gemini-2.5-flash"))
        assertFalse(ModelCaps.vision("llama-3.3-70b-versatile")); assertFalse(ModelCaps.vision("deepseek-chat"))
    }

    @Test fun openAiStreamAssemblesTextAndToolCalls() {
        val text = StringBuilder()
        val s = OpenAiStream { text.append(it) }
        listOf("""data: {"choices":[{"delta":{"role":"assistant","content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\"pa"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"th\":\"a\"}"}}]}}]}""",
            """data: {"choices":[{"delta":{"tool_calls":[{"index":1,"id":"call_2","function":{"name":"list_files","arguments":"{}"},"extra_content":{"google":{"thought_signature":"sig"}}}]}}]}""",
            """data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":7,"completion_tokens":3}}""",
            "", "data: [DONE]").forEach { if (!s.feed(it)) return@forEach }
        val r = s.result()
        assertEquals("Hello", r.text); assertEquals("Hello", text.toString())
        assertEquals(listOf("read_file", "list_files"), r.toolCalls.map { it.name })
        assertEquals("""{"path":"a"}""", r.toolCalls[0].arguments)
        assertTrue(r.toolCalls[1].raw!!.contains("thought_signature"))
        assertFalse(r.toolCalls[1].raw!!.contains("\"index\""))
        assertEquals(7, r.inputTokens); assertEquals("tool_calls", r.stopReason)
        val echo = OpenAiProtocol.request("m", "", listOf(ChatMessage(Role.Assistant, "", r.toolCalls)), emptyList())
        assertTrue(echo.toString().contains("thought_signature"))
        // A server that ignores "stream" and sends one JSON body.
        val whole = OpenAiStream {}
        whole.feed("""data: {"choices":[{"message":{"content":"full","tool_calls":[{"id":"x","function":{"name":"n","arguments":"{}"}}]},"finish_reason":"stop"}]}""")
        assertEquals("full", whole.result().text); assertEquals("n", whole.result().toolCalls.single().name)
    }

    @Test fun anthropicStreamAssemblesTextAndToolUse() {
        val text = StringBuilder()
        val s = AnthropicStream { text.append(it) }
        listOf("event: message_start", """data: {"type":"message_start","message":{"usage":{"input_tokens":12}}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"On it"}}""",
            """data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"tu1","name":"edit_file","input":{}}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"\"x\"}"}}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
            """data: {"type":"message_stop"}""").forEach { s.feed(it) }
        val r = s.result()
        assertEquals("On it", r.text); assertEquals("On it", text.toString())
        assertEquals("edit_file", r.toolCalls.single().name); assertEquals("""{"path":"x"}""", r.toolCalls.single().arguments)
        assertEquals(12, r.inputTokens); assertEquals(9, r.outputTokens); assertEquals("tool_use", r.stopReason)
    }

    @Test fun htmlToText() {
        val t = WebToolset.htmlToText("<html><head><style>x{}</style><script>bad()</script></head><body><h1>Title</h1><p>Hello &amp; <b>bye</b></p><ul><li>One</li><li>Two</li></ul></body></html>")
        assertTrue(t, t.contains("# Title")); assertTrue(t.contains("Hello & bye")); assertTrue(t.contains("• One")); assertFalse(t.contains("bad()"))
    }
}
