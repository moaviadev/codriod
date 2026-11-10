package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Something that can answer a conversation; the real one is [LlmClient] bound to a provider and model. */
fun interface ChatBackend {
    suspend fun chat(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>, onText: ((String) -> Unit)?): LlmReply
}

/** Runs one user turn: ask the model, run the tools it requests, feed results back, until it answers. */
class AgentEngine(private val backend: ChatBackend, private val tools: AgentTools, private val maxSteps: Int = 25,
    private val contextBudget: Int = 320_000) {
    data class Usage(val input: Int, val output: Int)

    suspend fun run(history: MutableList<ChatMessage>, system: String, mode: AgentMode, useTools: Boolean,
        onChange: suspend () -> Unit, onUsage: (Usage) -> Unit = {}, onFilesChanged: suspend () -> Unit = {},
        onText: ((String) -> Unit)? = null, visionImages: Boolean = true) {
        val specs = if (useTools) tools.specs(mode) else emptyList()
        repeat(maxSteps) {
            val context = ContextCompactor.compact(history.filter { it.role != Role.Error }, contextBudget)
                .let { list -> if (visionImages) list else list.map { m -> if (m.images.isEmpty()) m else m.copy(images = emptyList(),
                    text = m.text + "\n(An image was attached, but the selected model cannot see images.)") } }
            val reply = backend.chat(system, context, specs, onText)
            onUsage(Usage(reply.inputTokens, reply.outputTokens))
            val calls = if (useTools) reply.toolCalls else emptyList()
            if (reply.text.isBlank() && calls.isEmpty()) {
                history += ChatMessage(Role.Error, "The model returned an empty answer" +
                    (reply.stopReason?.let { " ($it)" } ?: "") + ".")
                onChange(); return
            }
            history += ChatMessage(Role.Assistant, reply.text, calls)
            onChange()
            if (calls.isEmpty()) return
            var changed = false
            for (call in calls) {
                val outcome = tools.execute(call, mode)
                changed = changed || outcome.changedFiles
                history += ChatMessage(Role.Tool, outcome.content, toolCallId = call.id, toolName = call.name, summary = outcome.summary,
                    images = outcome.images, detail = outcome.detail)
                onChange()
            }
            if (changed) onFilesChanged()
        }
        history += ChatMessage(Role.Error, "Stopped after $maxSteps steps. Send “continue” to keep going.")
        onChange()
    }
}

/** Keeps long conversations inside the model's context window without breaking tool-call pairs. */
object ContextCompactor {
    private const val KEEP_RECENT = 14

    fun compact(messages: List<ChatMessage>, budget: Int): List<ChatMessage> {
        val recentFrom = (messages.size - KEEP_RECENT).coerceAtLeast(0)
        var out = messages.mapIndexed { i, m ->
            if (i >= recentFrom) m
            else m.copy(images = emptyList(), text = if (m.role == Role.Tool && m.text.length > 1500)
                m.text.take(1500) + "\n… (older output shortened)" else m.text)
        }
        fun size(list: List<ChatMessage>) = list.sumOf { it.text.length + it.toolCalls.sumOf { c -> c.arguments.length } + it.images.size * 2000 }
        while (size(out) > budget && out.size > 2) {
            // Drop the oldest exchange: everything up to the next user message.
            val next = out.drop(1).indexOfFirst { it.role == Role.User }
            if (next < 0) break
            out = out.drop(next + 1)
        }
        // A conversation must not start with tool results or an assistant tool call.
        while (out.isNotEmpty() && out.first().role != Role.User) out = out.drop(1)
        return out
    }
}

object AgentPrompt {
    fun system(projectName: String, files: List<String>, activeFile: Pair<String, String>?, rules: String?,
        customInstructions: String, mode: AgentMode, toolsAvailable: Boolean, browser: Boolean = false, internet: Boolean = false,
        vision: Boolean = false, extraContext: String = "", environment: String = ""): String = buildString {
        appendLine("You are Codriod Agent, an expert software engineer working inside Codriod, an IDE on an Android phone.")
        appendLine("Project: \"$projectName\". All paths are relative to the project root.")
        appendLine("Environment: files live on the phone; the shell is Android's /system/bin/sh (toybox), not bash. " +
            "Python, Node.js and Java are not installed unless the user says so. Git works through the git_* tools. " +
            "The project is served at http://localhost:3000 in Codriod's browser with live reload.")
        if (environment.isNotBlank()) append(environment.trimEnd()).append('\n')
        if (toolsAvailable) {
            appendLine(when (mode) {
                AgentMode.Ask -> "Mode: Ask. You can read and search the project but must not change it. Show proposed code in fenced blocks and suggest Agent mode to apply it."
                AgentMode.Plan -> "Mode: Plan. Investigate with read-only tools, then call update_plan once with a clear, numbered plan " +
                    "(files to create or edit, commands, checks) and a one-paragraph summary. Do not change anything; the user approves the plan first."
                AgentMode.Agent -> "Mode: Agent. Work autonomously until the task is done. Steps that change things may need the user's approval."
                AgentMode.Autopilot -> "Mode: Autopilot. The user is away: complete the whole task end to end without asking questions; " +
                    "make reasonable decisions, verify your work, and only stop when it is done or truly blocked."
            })
            if (mode.canEdit) {
                appendLine("How to work:")
                appendLine("- For tasks with 3+ steps, call update_plan first and keep it updated (one step in_progress).")
                appendLine("- Read files before editing. Use edit_file for focused changes, write_file for new files. Keep the existing style.")
                appendLine("- Use run_command freely for inspection and small jobs (ls, cat, grep, find, zip). Phone actions use the codriod command.")
                if (browser) appendLine("- After building web UI, verify it: browser_open the page (http://localhost:3000/...), check browser_console for errors, " +
                    "and use browser_screenshot to compare with what the user wanted. Fix problems and check again.")
                appendLine("- Finish with a short summary of what changed and how to try it.")
            }
            if (browser) appendLine("Browser tools drive Codriod's built-in browser: open pages, read them (browser_snapshot gives numbered " +
                "elements), click, type, scroll, run JavaScript, read console and network, and take screenshots.")
            if (internet) appendLine("fetch_url reads web pages and docs as text.")
        } else appendLine("Tools are not available with this model; answer from the context below.")
        if (vision) appendLine("You can see images the user attaches and screenshots from tools; describe and reproduce UI precisely " +
            "(layout, spacing, colours, fonts, icons).")
        appendLine("Never ask the user to paste passwords, tokens or private keys into the chat. For GitHub use the git_* / github_* tools.")
        appendLine("Reply in the user's language. Be concise and direct; use Markdown with fenced code blocks.")
        if (files.isNotEmpty()) {
            appendLine()
            appendLine("Project files (${files.size}${if (files.size > 200) ", first 200" else ""}):")
            files.take(200).forEach { appendLine(it) }
        }
        activeFile?.let { (path, text) ->
            appendLine()
            appendLine("The user has this file open: $path")
            appendLine("```")
            appendLine(if (text.length > 24_000) text.take(24_000) + "\n… (truncated, use read_file for more)" else text)
            appendLine("```")
        }
        if (extraContext.isNotBlank()) { appendLine(); appendLine("Context the user attached:"); appendLine(extraContext.take(30_000)) }
        rules?.takeIf { it.isNotBlank() }?.let {
            appendLine()
            appendLine("Project rules (from AGENTS.md / CODRIOD.md):")
            appendLine(it.take(8_000))
        }
        if (customInstructions.isNotBlank()) {
            appendLine()
            appendLine("User instructions:")
            appendLine(customInstructions.take(4_000))
        }
    }.trim()
}

/** Chat history on disk as JSON. */
object ChatHistoryJson {
    fun encode(messages: List<ChatMessage>): String = JSONArray().apply {
        messages.forEach { m ->
            put(JSONObject().put("role", m.role.name).put("text", m.text).put("time", m.time)
                .apply {
                    m.toolCallId?.let { put("toolCallId", it) }
                    m.toolName?.let { put("toolName", it) }
                    m.summary?.let { put("summary", it) }
                    m.detail?.let { put("detail", it.take(20_000)) }
                    if (m.images.isNotEmpty()) put("images", JSONArray(m.images))
                    if (m.toolCalls.isNotEmpty()) put("toolCalls", JSONArray().apply {
                        m.toolCalls.forEach { c -> put(JSONObject().put("id", c.id).put("name", c.name)
                            .put("arguments", c.arguments).apply { c.raw?.let { put("raw", it) } }) }
                    })
                })
        }
    }.toString()

    fun decode(text: String): List<ChatMessage> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val role = runCatching { Role.valueOf(o.optString("role")) }.getOrNull() ?: return@mapNotNull null
            val calls = o.optJSONArray("toolCalls")?.let { a ->
                (0 until a.length()).mapNotNull { j -> a.optJSONObject(j)?.let { c ->
                    ToolCall(c.optString("id"), c.optString("name"), c.optString("arguments"), c.optString("raw").ifEmpty { null })
                } }
            }.orEmpty()
            val images = o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            ChatMessage(role, o.optString("text"), calls, o.optString("toolCallId").ifEmpty { null },
                o.optString("toolName").ifEmpty { null }, o.optString("summary").ifEmpty { null }, o.optLong("time"),
                images, o.optString("detail").ifEmpty { null })
        }
    }

    /** Drops a dangling tool call at the end (for example after Stop), which providers would reject. */
    fun repair(messages: List<ChatMessage>): List<ChatMessage> {
        val out = messages.toMutableList()
        while (out.isNotEmpty()) {
            val lastAssistant = out.indexOfLast { it.role == Role.Assistant }
            if (lastAssistant < 0) break
            val calls = out[lastAssistant].toolCalls
            if (calls.isEmpty()) break
            val answered = out.drop(lastAssistant + 1).filter { it.role == Role.Tool }.mapNotNull { it.toolCallId }.toSet()
            if (calls.all { it.id in answered }) break
            val missing = calls.filter { it.id !in answered }
            val insertAt = out.size
            missing.forEach { out.add(insertAt, ChatMessage(Role.Tool, "Cancelled by the user.", toolCallId = it.id,
                toolName = it.name, summary = "${it.name} cancelled")) }
            break
        }
        return out
    }
}

/** Shell commands for the agent, run in the project folder with a time limit and capped output. */
class ShellRunner(private val shell: String = "/system/bin/sh", private val maxOutput: Int = 64_000,
    private val env: Map<String, String> = emptyMap(), private val preamble: String? = null) {
    fun run(root: File, command: String, timeoutSeconds: Int): CommandResult {
        val script = if (preamble != null) "$preamble\n$command" else command
        val process = ProcessBuilder(shell, "-c", script).directory(root).redirectErrorStream(true)
            .apply { environment()["HOME"] = root.absolutePath; environment().putAll(env) }.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        synchronized(output) { if (output.length < maxOutput) output.append(buffer, 0, minOf(n, maxOutput - output.length)) }
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // Process.waitFor(timeout) needs Android 8; poll instead so this works on every supported version.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds.toLong())
        var finished = false
        while (!finished && System.currentTimeMillis() < deadline) {
            finished = runCatching { process.exitValue(); true }.getOrDefault(false)
            if (!finished) Thread.sleep(50)
        }
        if (!finished) process.destroy()
        reader.join(2_000)
        return CommandResult(if (finished) process.exitValue() else null, synchronized(output) { output.toString() }, !finished)
    }
}
