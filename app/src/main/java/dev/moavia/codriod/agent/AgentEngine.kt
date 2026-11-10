package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Something that can answer a conversation; the real one is [LlmClient] bound to a provider and model. */
fun interface ChatBackend {
    suspend fun chat(system: String, messages: List<ChatMessage>, tools: List<ToolSpec>): LlmReply
}

/** Runs one user turn: ask the model, run the tools it requests, feed results back, until it answers. */
class AgentEngine(private val backend: ChatBackend, private val tools: AgentTools, private val maxSteps: Int = 25) {
    data class Usage(val input: Int, val output: Int)

    suspend fun run(history: MutableList<ChatMessage>, system: String, mode: AgentMode, useTools: Boolean,
        onChange: suspend () -> Unit, onUsage: (Usage) -> Unit = {}, onFilesChanged: suspend () -> Unit = {}) {
        val specs = if (useTools) tools.specs(mode) else emptyList()
        repeat(maxSteps) {
            val reply = backend.chat(system, history.filter { it.role != Role.Error }, specs)
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
                history += ChatMessage(Role.Tool, outcome.content, toolCallId = call.id, toolName = call.name, summary = outcome.summary)
                onChange()
            }
            if (changed) onFilesChanged()
        }
        history += ChatMessage(Role.Error, "Stopped after $maxSteps steps. Send a message to continue.")
        onChange()
    }
}

object AgentPrompt {
    fun system(projectName: String, files: List<String>, activeFile: Pair<String, String>?, rules: String?,
        customInstructions: String, mode: AgentMode, toolsAvailable: Boolean): String = buildString {
        appendLine("You are Codriod Agent, a coding assistant inside Codriod, an IDE running on an Android phone.")
        appendLine("Project: \"$projectName\". All paths are relative to the project root.")
        appendLine("Environment: files live on the phone; the shell is Android's /system/bin/sh (toybox), not bash. " +
            "Python, Node.js and Java are not installed unless the user says so. Git is available through the git_* tools.")
        if (toolsAvailable) {
            if (mode == AgentMode.Agent) appendLine("Mode: Agent. Read files before changing them. Prefer edit_file for small " +
                "changes and write_file for new files. The user approves every change and command, so explain briefly what " +
                "you are about to do. After changes, summarize what changed.")
            else appendLine("Mode: Ask. You can read and search the project but cannot change it. Show proposed code in " +
                "fenced blocks and suggest switching to Agent mode to apply it.")
        } else appendLine("Tools are not available with this model; answer from the context below.")
        appendLine("Never ask the user to paste passwords, tokens or private keys into the chat. " +
            "For GitHub login use the git_account_status / github_login_* tools.")
        appendLine("Reply in the user's language. Be concise; use Markdown with fenced code blocks.")
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
            ChatMessage(role, o.optString("text"), calls, o.optString("toolCallId").ifEmpty { null },
                o.optString("toolName").ifEmpty { null }, o.optString("summary").ifEmpty { null }, o.optLong("time"))
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
class ShellRunner(private val shell: String = "/system/bin/sh", private val maxOutput: Int = 64_000) {
    fun run(root: File, command: String, timeoutSeconds: Int): CommandResult {
        val process = ProcessBuilder(shell, "-c", command).directory(root).redirectErrorStream(true)
            .apply { environment()["HOME"] = root.absolutePath }.start()
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
