package dev.moavia.codriod.cli

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** A CLI process whose other end is a scripted fake server in the test. */
class FakeProcess : Process() {
    private val toServer = PipedOutputStream()
    val serverIn = BufferedReader(InputStreamReader(PipedInputStream(toServer, 1 shl 16)))
    private val fromServer = PipedInputStream(1 shl 16)
    private val serverOut = PrintWriter(OutputStreamWriter(PipedOutputStream(fromServer)), true)
    val received = LinkedBlockingQueue<JSONObject>()
    @Volatile var alive = true

    init { Thread { try { while (true) { val l = serverIn.readLine() ?: break; received.put(JSONObject(l)) } } catch (_: IOException) {} }.apply { isDaemon = true }.start() }

    fun send(o: JSONObject) = serverOut.println(o.toString())
    fun next(method: String, timeoutSec: Long = 5): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val m = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (m.optString("method") == method) return m
        }
        throw AssertionError("no $method received")
    }
    fun reply(request: JSONObject, result: Any) = send(JSONObject().put("id", request.get("id")).put("result", result))
    fun nextResponse(id: Long, timeoutSec: Long = 5): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < deadline) {
            val m = received.poll(100, TimeUnit.MILLISECONDS) ?: continue
            if (!m.has("method") && m.optLong("id") == id) return m
        }
        throw AssertionError("no response to $id")
    }

    override fun getOutputStream(): OutputStream = toServer
    override fun getInputStream(): InputStream = fromServer
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int { while (alive) Thread.sleep(10); return 0 }
    override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
    override fun destroy() { alive = false; runCatching { serverOut.close() } }
}

class CliBackendTest {
    private val uiThread = Executors.newSingleThreadExecutor()
    private val ui = uiThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @After fun tearDown() { scope.cancel(); uiThread.shutdownNow() }

    private fun host(process: FakeProcess, opened: MutableList<String> = mutableListOf()) = object : CliHost {
        override fun launch(command: List<String>): Process = process
        override val guestCwd = "/root/projects/p"
        override fun guestPathForImage(hostPath: String) = "/tmp/codriod-attach/x.jpg"
        override fun openUrl(url: String) { opened += url }
        override fun log(line: String) {}
    }

    private fun waitUntil(what: String, timeoutMs: Long = 5000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (runBlocking(ui) { check() }) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for $what")
    }

    @Test fun codexFullTurn() {
        val p = FakeProcess()
        val opened = mutableListOf<String>()
        val codex = CodexBackend(host(p, opened), scope, ui = ui)
        val s = codex.state
        val starting = scope.launch(ui) { codex.start() }
        val init = p.next("initialize")
        assertFalse("Codex messages have no jsonrpc field", init.has("jsonrpc"))
        assertEquals("codriod", init.getJSONObject("params").getJSONObject("clientInfo").getString("name"))
        p.reply(init, JSONObject().put("userAgent", "codex_cli_rs/0.167.0"))
        p.next("initialized")
        p.reply(p.next("account/read"), JSONObject().put("account", JSONObject().put("type", "chatgpt").put("email", "a@b.c").put("planType", "plus"))
            .put("requiresOpenaiAuth", true))
        p.reply(p.next("model/list"), JSONObject().put("data", JSONArray().put(JSONObject().put("id", "gpt-5-codex").put("model", "gpt-5-codex")
            .put("displayName", "GPT-5 Codex").put("description", "Coding").put("hidden", false).put("isDefault", true)
            .put("supportedReasoningEfforts", JSONArray().put(JSONObject().put("reasoningEffort", "low").put("description", ""))
                .put(JSONObject().put("reasoningEffort", "high").put("description", "")))
            .put("defaultReasoningEffort", "high"))))
        val resets = System.currentTimeMillis() / 1000 + 7200
        p.reply(p.next("account/rateLimits/read"), JSONObject().put("rateLimits", JSONObject()
            .put("primary", JSONObject().put("usedPercent", 62).put("windowDurationMins", 300).put("resetsAt", resets))
            .put("secondary", JSONObject().put("usedPercent", 28).put("windowDurationMins", 10080))))
        val list = p.next("thread/list")
        assertEquals("/root/projects/p", list.getJSONObject("params").getString("cwd"))
        p.reply(list, JSONObject().put("data", JSONArray().put(JSONObject().put("id", "t1").put("preview", "Old chat").put("updatedAt", 1700000000)
            .put("status", JSONObject().put("type", "idle")))))
        runBlocking { starting.join() }
        assertTrue(s.connected)
        assertEquals("a@b.c", s.account!!.label); assertEquals("plus", s.account!!.plan)
        assertEquals("gpt-5-codex", s.model); assertEquals("high", s.effort)
        assertEquals(62, s.usage.primaryPercent); assertEquals(28, s.usage.secondaryPercent); assertEquals(10080L, s.usage.secondaryWindowMins)
        assertEquals("Old chat", s.threads.single().title)

        // a turn: thread/start, turn/start with text + image, streamed items, an approval, completion
        val sending = scope.launch(ui) { codex.send("Run the tests", listOf("/data/x.jpg")) }
        val start = p.next("thread/start")
        assertEquals("danger-full-access", start.getJSONObject("params").getString("sandbox"))
        assertEquals("untrusted", start.getJSONObject("params").getString("approvalPolicy"))
        p.reply(start, JSONObject().put("thread", JSONObject().put("id", "t2")))
        val turnStart = p.next("turn/start")
        val params = turnStart.getJSONObject("params")
        assertEquals("t2", params.getString("threadId")); assertEquals("high", params.getString("effort"))
        assertEquals("localImage", params.getJSONArray("input").getJSONObject(1).getString("type"))
        assertEquals("/tmp/codriod-attach/x.jpg", params.getJSONArray("input").getJSONObject(1).getString("path"))
        p.reply(turnStart, JSONObject().put("turn", JSONObject().put("id", "turn1").put("status", "inProgress")))
        runBlocking { sending.join() }
        fun note(method: String, params: JSONObject) = p.send(JSONObject().put("method", method).put("params", params))
        note("turn/started", JSONObject().put("threadId", "t2").put("turn", JSONObject().put("id", "turn1")))
        note("item/started", JSONObject().put("threadId", "t2").put("turnId", "turn1").put("item", JSONObject().put("type", "reasoning").put("id", "r1")))
        note("item/reasoning/summaryTextDelta", JSONObject().put("itemId", "r1").put("delta", "Checking package.json"))
        note("item/started", JSONObject().put("item", JSONObject().put("type", "commandExecution").put("id", "c1").put("command", "npm test")
            .put("cwd", "/root/projects/p").put("status", "inProgress").put("commandActions", JSONArray())))
        // npm test is not a read-only command, so in Auto mode Codriod asks
        p.send(JSONObject().put("id", 99).put("method", "item/commandExecution/requestApproval").put("params", JSONObject()
            .put("threadId", "t2").put("turnId", "turn1").put("itemId", "c1").put("startedAtMs", 1).put("command", "npm test")))
        waitUntil("approval card") { s.approval != null }
        assertEquals("npm test", s.approval!!.command)
        assertEquals(listOf("decline", "acceptForSession", "accept"), s.approval!!.options.map { it.first })
        runBlocking(ui) { s.approval!!.answer("accept") }
        assertEquals("accept", p.nextResponse(99).getJSONObject("result").getString("decision"))
        // a safe read-only command is approved automatically
        p.send(JSONObject().put("id", 100).put("method", "item/commandExecution/requestApproval").put("params", JSONObject()
            .put("threadId", "t2").put("turnId", "turn1").put("itemId", "c0").put("startedAtMs", 1).put("command", "ls -la")))
        assertEquals("accept", p.nextResponse(100).getJSONObject("result").getString("decision"))
        note("item/commandExecution/outputDelta", JSONObject().put("itemId", "c1").put("delta", "PASS tests/a.test.js\n"))
        note("item/completed", JSONObject().put("item", JSONObject().put("type", "commandExecution").put("id", "c1").put("command", "npm test")
            .put("cwd", "/root/projects/p").put("status", "completed").put("exitCode", 0).put("aggregatedOutput", "PASS tests/a.test.js\nTests: 2 passed\n")))
        note("item/completed", JSONObject().put("item", JSONObject().put("type", "fileChange").put("id", "f1").put("status", "completed")
            .put("changes", JSONArray().put(JSONObject().put("path", "/root/projects/p/app.js").put("kind", JSONObject().put("type", "update"))
                .put("diff", "@@ -1 +1 @@\n-a\n+b")))))
        note("turn/plan/updated", JSONObject().put("turnId", "turn1").put("plan", JSONArray()
            .put(JSONObject().put("step", "Run tests").put("status", "completed")).put(JSONObject().put("step", "Fix").put("status", "inProgress"))))
        note("item/agentMessage/delta", JSONObject().put("itemId", "m1").put("delta", "All "))
        note("item/agentMessage/delta", JSONObject().put("itemId", "m1").put("delta", "tests pass."))
        note("thread/tokenUsage/updated", JSONObject().put("threadId", "t2").put("turnId", "turn1").put("tokenUsage", JSONObject()
            .put("total", JSONObject().put("totalTokens", 50000)).put("last", JSONObject().put("totalTokens", 40000)).put("modelContextWindow", 200000)))
        note("turn/completed", JSONObject().put("threadId", "t2").put("turn", JSONObject().put("id", "turn1").put("status", "completed")))
        p.reply(p.next("account/rateLimits/read"), JSONObject().put("rateLimits", JSONObject().put("primary", JSONObject().put("usedPercent", 64))))
        p.reply(p.next("thread/list"), JSONObject().put("data", JSONArray()))
        waitUntil("turn done") { !s.running && s.usage.primaryPercent == 64 }
        val command = s.items.filterIsInstance<CliItem.Command>().single()
        assertEquals(0, command.exitCode); assertEquals(ItemStatus.Done, command.status); assertTrue(command.output.contains("2 passed"))
        assertEquals("Checking package.json", s.items.filterIsInstance<CliItem.Reasoning>().single().text)
        assertEquals("app.js", s.items.filterIsInstance<CliItem.FileEdit>().single().changes.single().path)
        assertEquals("All tests pass.", s.items.filterIsInstance<CliItem.Agent>().single().text)
        assertEquals(listOf("completed", "in_progress"), s.items.filterIsInstance<CliItem.Plan>().single().steps.map { it.status })
        assertEquals(80, s.usage.contextLeftPercent)
        assertEquals("Run the tests", s.items.filterIsInstance<CliItem.User>().single().text)

        // ChatGPT sign-in opens OpenAI's page; completion refreshes the account
        val login = scope.launch(ui) { codex.login("chatgpt") }
        val loginStart = p.next("account/login/start")
        assertEquals("chatgpt", loginStart.getJSONObject("params").getString("type"))
        p.reply(loginStart, JSONObject().put("type", "chatgpt").put("loginId", "L1").put("authUrl", "https://auth.openai.com/x"))
        runBlocking { login.join() }
        assertEquals(listOf("https://auth.openai.com/x"), opened)
        assertNotNull(s.login)
        codex.stop()
    }

    @Test fun acpSessionWithPermissionsAndConfig() {
        val p = FakeProcess()
        val acp = AcpBackend(host(p), scope, AcpConfig("copilot", "GitHub Copilot", listOf(listOf("copilot", "--acp")), "copilot login", countsRequests = true), ui = ui)
        val s = acp.state
        val starting = scope.launch(ui) { acp.start() }
        val init = p.next("initialize")
        assertEquals("2.0", init.getString("jsonrpc"))
        assertEquals(1, init.getJSONObject("params").getInt("protocolVersion"))
        assertFalse(init.getJSONObject("params").getJSONObject("clientCapabilities").getJSONObject("fs").getBoolean("writeTextFile"))
        p.reply(init, JSONObject().put("protocolVersion", 1).put("agentCapabilities", JSONObject().put("loadSession", true)
            .put("promptCapabilities", JSONObject().put("image", true)).put("sessionCapabilities", JSONObject().put("list", JSONObject())))
            .put("authMethods", JSONArray()).put("agentInfo", JSONObject().put("name", "copilot").put("version", "1.0.108")))
        val newSession = p.next("session/new")
        assertEquals("/root/projects/p", newSession.getJSONObject("params").getString("cwd"))
        p.reply(newSession, JSONObject().put("sessionId", "s1")
            .put("modes", JSONObject().put("currentModeId", "agent").put("availableModes", JSONArray()
                .put(JSONObject().put("id", "agent").put("name", "Agent")).put(JSONObject().put("id", "plan").put("name", "Plan"))))
            .put("configOptions", JSONArray().put(JSONObject().put("id", "model").put("name", "Model").put("category", "model").put("type", "select")
                .put("currentValue", "claude-sonnet-4.5").put("options", JSONArray()
                    .put(JSONObject().put("value", "claude-sonnet-4.5").put("name", "Claude Sonnet 4.5"))
                    .put(JSONObject().put("value", "gpt-5").put("name", "GPT-5"))))))
        p.reply(p.next("session/list"), JSONObject().put("sessions", JSONArray().put(JSONObject().put("sessionId", "s0").put("cwd", "/root/projects/p")
            .put("title", "CI workflow").put("updatedAt", "2026-11-10T10:00:00Z"))))
        runBlocking { starting.join() }
        assertTrue(s.connected); assertTrue(s.features.images)
        assertEquals("claude-sonnet-4.5", s.model); assertEquals(2, s.models.size)
        assertEquals(listOf("agent", "plan"), s.modes.map { it.id })
        assertEquals("CI workflow", s.threads.single().title)

        val sending = scope.launch(ui) { acp.send("Add a CI workflow", emptyList()) }
        val prompt = p.next("session/prompt")
        assertEquals("Add a CI workflow", prompt.getJSONObject("params").getJSONArray("prompt").getJSONObject(0).getString("text"))
        fun update(u: JSONObject) = p.send(JSONObject().put("method", "session/update").put("params", JSONObject().put("sessionId", "s1").put("update", u)))
        update(JSONObject().put("sessionUpdate", "agent_thought_chunk").put("content", JSONObject().put("type", "text").put("text", "Node project")))
        update(JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "e1").put("title", "Create ci.yml").put("kind", "edit").put("status", "pending")
            .put("content", JSONArray().put(JSONObject().put("type", "diff").put("path", "/root/projects/p/.github/workflows/ci.yml").put("newText", "name: CI\non: push\n"))))
        // an edit inside the project is allowed automatically in Auto mode
        p.send(JSONObject().put("jsonrpc", "2.0").put("id", 7).put("method", "session/request_permission").put("params", JSONObject().put("sessionId", "s1")
            .put("toolCall", JSONObject().put("toolCallId", "e1").put("kind", "edit").put("content", JSONArray()
                .put(JSONObject().put("type", "diff").put("path", "/root/projects/p/.github/workflows/ci.yml").put("newText", "x"))))
            .put("options", JSONArray().put(JSONObject().put("optionId", "allow").put("name", "Allow").put("kind", "allow_once"))
                .put(JSONObject().put("optionId", "deny").put("name", "Deny").put("kind", "reject_once")))))
        val auto = p.nextResponse(7).getJSONObject("result").getJSONObject("outcome")
        assertEquals("selected", auto.getString("outcome")); assertEquals("allow", auto.getString("optionId"))
        // a dangerous command always asks, even on Autopilot
        runBlocking(ui) { s.permission = CliPermission.Autopilot }
        update(JSONObject().put("sessionUpdate", "tool_call").put("toolCallId", "x1").put("title", "rm -rf /").put("kind", "execute").put("status", "pending")
            .put("rawInput", JSONObject().put("command", "rm -rf /")))
        p.send(JSONObject().put("jsonrpc", "2.0").put("id", 8).put("method", "session/request_permission").put("params", JSONObject().put("sessionId", "s1")
            .put("toolCall", JSONObject().put("toolCallId", "x1").put("kind", "execute").put("rawInput", JSONObject().put("command", "rm -rf /")))
            .put("options", JSONArray().put(JSONObject().put("optionId", "ok").put("name", "Allow").put("kind", "allow_once"))
                .put(JSONObject().put("optionId", "no").put("name", "Reject").put("kind", "reject_once")))))
        waitUntil("dangerous approval") { s.approval != null }
        assertTrue(s.approval!!.dangerous)
        runBlocking(ui) { s.approval!!.answer("no") }
        assertEquals("no", p.nextResponse(8).getJSONObject("result").getJSONObject("outcome").getString("optionId"))
        update(JSONObject().put("sessionUpdate", "tool_call_update").put("toolCallId", "e1").put("status", "completed"))
        update(JSONObject().put("sessionUpdate", "plan").put("entries", JSONArray().put(JSONObject().put("content", "Add workflow").put("priority", "high").put("status", "completed"))))
        update(JSONObject().put("sessionUpdate", "agent_message_chunk").put("content", JSONObject().put("type", "text").put("text", "Added ci.yml.")))
        update(JSONObject().put("sessionUpdate", "usage_update").put("used", 30000).put("size", 128000))
        update(JSONObject().put("sessionUpdate", "available_commands_update").put("availableCommands", JSONArray().put(JSONObject().put("name", "usage").put("description", "Show usage"))))
        Thread.sleep(200)
        p.reply(prompt, JSONObject().put("stopReason", "end_turn"))
        runBlocking { sending.join() }
        assertFalse(s.running)
        assertEquals(1, s.usage.requestsThisSession)
        assertEquals(77, s.usage.contextLeftPercent)
        val edit = s.items.filterIsInstance<CliItem.FileEdit>().single()
        assertEquals(".github/workflows/ci.yml", edit.changes.single().path); assertEquals(ItemStatus.Done, edit.status)
        assertTrue(edit.changes.single().diff.contains("+name: CI"))
        assertEquals("rm -rf /", s.items.filterIsInstance<CliItem.Command>().single().command)
        assertEquals("Added ci.yml.", s.items.filterIsInstance<CliItem.Agent>().last().text)
        assertEquals("Node project", s.items.filterIsInstance<CliItem.Reasoning>().single().text)
        assertEquals("usage", s.commands.single().first)

        // switching model uses session/set_config_option
        val setting = scope.launch(ui) { acp.setModel("gpt-5") }
        val set = p.next("session/set_config_option")
        assertEquals("model", set.getJSONObject("params").getString("configId")); assertEquals("gpt-5", set.getJSONObject("params").getString("value"))
        p.reply(set, JSONObject())
        runBlocking { setting.join() }
        assertEquals("gpt-5", s.model)
        acp.stop()
    }

    @Test fun acpNeedsSignIn() {
        val p = FakeProcess()
        val acp = AcpBackend(host(p), scope, AcpConfig("gemini", "Gemini CLI", listOf(listOf("gemini", "--experimental-acp")), "gemini"), ui = ui)
        val starting = scope.launch(ui) { acp.start() }
        p.reply(p.next("initialize"), JSONObject().put("protocolVersion", 1).put("agentCapabilities", JSONObject())
            .put("authMethods", JSONArray().put(JSONObject().put("id", "oauth-personal").put("name", "Log in with Google"))))
        val newSession = p.next("session/new")
        p.send(JSONObject().put("jsonrpc", "2.0").put("id", newSession.get("id")).put("error", JSONObject().put("code", -32000).put("message", "Authentication required")))
        runBlocking { starting.join() }
        assertEquals(false, acp.state.account!!.signedIn)
        assertEquals(listOf("oauth-personal" to "Log in with Google"), acp.authOptions())
        val auth = scope.launch(ui) { acp.login("oauth-personal") }
        val authenticate = p.next("authenticate")
        assertEquals("oauth-personal", authenticate.getJSONObject("params").getString("methodId"))
        p.reply(authenticate, JSONObject())
        p.reply(p.next("session/new"), JSONObject().put("sessionId", "s9"))
        runBlocking { auth.join() }
        assertEquals(true, acp.state.account!!.signedIn)
        assertEquals("s9", acp.state.threadId)
        acp.stop()
    }

    @Test fun policyAndDiffs() {
        assertTrue(CliPolicy.autoCommand(CliPermission.Auto, "ls -la"))
        assertFalse(CliPolicy.autoCommand(CliPermission.Auto, "npm install"))
        assertTrue(CliPolicy.autoCommand(CliPermission.Autopilot, "npm install"))
        assertFalse(CliPolicy.autoCommand(CliPermission.Autopilot, "rm -rf ~"))
        assertFalse(CliPolicy.autoCommand(CliPermission.ReadOnly, "ls"))
        assertTrue(CliPolicy.autoEdit(CliPermission.Auto, listOf("/root/projects/p/a.js", "b.js"), "/root/projects/p"))
        assertFalse(CliPolicy.autoEdit(CliPermission.Auto, listOf("/etc/passwd"), "/root/projects/p"))
        assertFalse(CliPolicy.autoEdit(CliPermission.Auto, listOf("/root/projects/pp/x"), "/root/projects/p"))
        val d = acpDiffText("a\nb\n", "a\nc\n")
        assertTrue(d.contains("-b")); assertTrue(d.contains("+c"))
        assertEquals("+x", acpDiffText(null, "x").trim())
    }
}
