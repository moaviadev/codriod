package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject

data class SearchMatch(val path: String, val line: Int, val text: String)
data class CommandResult(val exitCode: Int?, val output: String, val timedOut: Boolean)

/** What the agent may touch. The app implementation is bound to one project folder. */
interface AgentWorkspace {
    fun files(): List<String>
    fun read(path: String): String
    fun exists(path: String): Boolean
    fun write(path: String, text: String)
    fun delete(path: String)
    fun search(query: String): List<SearchMatch>
    fun run(command: String, timeoutSeconds: Int): CommandResult
}

sealed class ApprovalRequest {
    data class Edit(val path: String, val created: Boolean, val diff: List<DiffLine>) : ApprovalRequest()
    data class Delete(val path: String) : ApprovalRequest()
    data class Command(val command: String) : ApprovalRequest()
    /** A named action such as "Set up GitHub login" or "Push to origin". */
    data class Action(val title: String, val detail: String) : ApprovalRequest()
}

/** A group of extra tools, for example Git account tools, plugged into [AgentTools]. */
interface AgentToolset {
    fun specs(mode: AgentMode): List<ToolSpec>
    fun handles(name: String): Boolean
    suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome
}

enum class Decision { Allow, AlwaysAllow, Deny }

fun interface Approver { suspend fun approve(request: ApprovalRequest): Decision }

data class ToolOutcome(val content: String, val summary: String, val changedFiles: Boolean = false)

enum class AgentMode(val label: String, val hint: String) {
    Ask("Ask", "Reads your project and answers. Never changes files."),
    Agent("Agent", "Reads, edits files and runs commands after you approve.")
}

class AgentTools(
    private val workspace: AgentWorkspace,
    private val approver: Approver,
    private val autoApplyEdits: () -> Boolean,
    private val commandsAlwaysAllowed: () -> Boolean,
    private val onAlwaysAllowCommands: () -> Unit,
    private val toolsets: List<AgentToolset> = emptyList()
) {
    companion object {
        private const val MAX_READ_CHARS = 60_000
        private const val MAX_OUTPUT_CHARS = 16_000

        private fun schema(vararg props: Triple<String, String, String>, required: List<String>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply {
                props.forEach { (name, type, description) -> put(name, JSONObject().put("type", type).put("description", description)) }
            })
            .put("required", JSONArray(required))

        val readOnly = listOf(
            ToolSpec("list_files", "List every file and folder in the project (folders end with /).",
                schema(required = emptyList())),
            ToolSpec("read_file", "Read a UTF-8 text file from the project. Optionally a 1-based line range.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("start_line", "integer", "First line to read (optional)"),
                    Triple("end_line", "integer", "Last line to read (optional)"),
                    required = listOf("path"))),
            ToolSpec("search_files", "Case-insensitive text search across project files.",
                schema(Triple("query", "string", "Text to find"), required = listOf("query")))
        )

        val editing = listOf(
            ToolSpec("write_file", "Create a file or replace its whole content. Missing folders are created. The user reviews a diff first.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("content", "string", "The complete new file content"),
                    required = listOf("path", "content"))),
            ToolSpec("edit_file", "Replace an exact, unique piece of text in a file. Prefer this for small changes. The user reviews a diff first.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("old_text", "string", "Exact existing text; must appear once"),
                    Triple("new_text", "string", "Replacement text"),
                    required = listOf("path", "old_text", "new_text"))),
            ToolSpec("delete_file", "Delete a file or an empty folder after the user approves.",
                schema(Triple("path", "string", "Path relative to the project root"), required = listOf("path"))),
            ToolSpec("run_command", "Run a shell command in the project folder with Android's /system/bin/sh (not bash, no Python or Node unless installed). The user must approve.",
                schema(Triple("command", "string", "Shell command"), required = listOf("command")))
        )

        fun baseSpecs(mode: AgentMode) = if (mode == AgentMode.Agent) readOnly + editing else readOnly
    }

    fun specs(mode: AgentMode): List<ToolSpec> = baseSpecs(mode) + toolsets.flatMap { it.specs(mode) }

    suspend fun execute(call: ToolCall, mode: AgentMode): ToolOutcome {
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return ToolOutcome("Error: arguments were not valid JSON", "${call.name}: invalid arguments")
        if (mode == AgentMode.Ask && call.name in editing.map { it.name })
            return ToolOutcome("Error: ${call.name} is not available in Ask mode. Describe the change instead.",
                "${call.name} blocked (Ask mode)")
        return try {
            toolsets.firstOrNull { it.handles(call.name) }?.let { return it.execute(call.name, args, mode) }
            when (call.name) {
                "list_files" -> {
                    val files = workspace.files()
                    ToolOutcome(if (files.isEmpty()) "(empty project)" else files.joinToString("\n"), "Listed ${files.size} entries")
                }
                "read_file" -> readFile(args)
                "search_files" -> {
                    val query = args.optString("query")
                    val hits = workspace.search(query)
                    ToolOutcome(if (hits.isEmpty()) "No matches." else hits.joinToString("\n") { "${it.path}:${it.line}: ${it.text}" },
                        "Searched \"${query.take(40)}\" · ${hits.size} matches")
                }
                "write_file" -> {
                    val path = path(args)
                    val content = args.optString("content")
                    val created = !workspace.exists(path)
                    val old = if (created) "" else workspace.read(path)
                    applyEdit(path, old, content, created)
                }
                "edit_file" -> {
                    val path = path(args)
                    val oldText = args.optString("old_text")
                    require(oldText.isNotEmpty()) { "old_text must not be empty" }
                    val current = workspace.read(path)
                    val first = current.indexOf(oldText)
                    require(first >= 0) { "old_text was not found in $path. Read the file and copy the text exactly." }
                    require(current.indexOf(oldText, first + 1) < 0) { "old_text appears more than once in $path. Include more surrounding lines." }
                    val updated = current.replaceRange(first, first + oldText.length, args.optString("new_text"))
                    applyEdit(path, current, updated, false)
                }
                "delete_file" -> {
                    val path = path(args)
                    require(workspace.exists(path)) { "$path does not exist" }
                    if (approver.approve(ApprovalRequest.Delete(path)) == Decision.Deny)
                        ToolOutcome("The user declined deleting $path.", "Delete $path declined")
                    else { workspace.delete(path); ToolOutcome("Deleted $path.", "Deleted $path", changedFiles = true) }
                }
                "run_command" -> runCommand(args.optString("command").trim())
                else -> ToolOutcome("Error: unknown tool ${call.name}", "Unknown tool ${call.name}")
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolOutcome("Error: ${error.message ?: error.javaClass.simpleName}", "${call.name} failed: ${error.message?.take(80)}")
        }
    }

    private fun path(args: JSONObject): String {
        val path = args.optString("path").trim().removePrefix("./").trim('/')
        require(path.isNotEmpty()) { "path is required" }
        return path
    }

    private fun readFile(args: JSONObject): ToolOutcome {
        val path = path(args)
        val lines = workspace.read(path).split('\n')
        val start = args.optInt("start_line", 1).coerceIn(1, maxOf(1, lines.size))
        val end = args.optInt("end_line", lines.size).coerceIn(start, maxOf(start, lines.size))
        val body = StringBuilder()
        for (i in start - 1 until minOf(end, lines.size)) {
            if (body.length > MAX_READ_CHARS) { body.append("… truncated, request a line range after line ${i}\n"); break }
            body.append(i + 1).append(" | ").append(lines[i]).append('\n')
        }
        return ToolOutcome(body.toString().ifEmpty { "(empty file)" }, "Read $path ($start-${minOf(end, lines.size)})")
    }

    private suspend fun applyEdit(path: String, old: String, new: String, created: Boolean): ToolOutcome {
        if (old == new) return ToolOutcome("No change: $path already has this content.", "No change to $path")
        val diff = LineDiff.diff(old, new)
        val (added, removed) = LineDiff.stats(diff)
        val decision = if (autoApplyEdits()) Decision.Allow
            else approver.approve(ApprovalRequest.Edit(path, created, diff))
        if (decision == Decision.Deny)
            return ToolOutcome("The user rejected this change to $path. Ask what they want instead.", "Change to $path rejected")
        workspace.write(path, new)
        return ToolOutcome("${if (created) "Created" else "Updated"} $path (+$added -$removed).",
            "${if (created) "Created" else "Edited"} $path  +$added −$removed", changedFiles = true)
    }

    private suspend fun runCommand(command: String): ToolOutcome {
        require(command.isNotEmpty()) { "command is required" }
        if (!commandsAlwaysAllowed()) {
            when (approver.approve(ApprovalRequest.Command(command))) {
                Decision.Deny -> return ToolOutcome("The user declined running: $command", "Command declined: ${command.take(60)}")
                Decision.AlwaysAllow -> onAlwaysAllowCommands()
                Decision.Allow -> Unit
            }
        }
        val result = workspace.run(command, 120)
        val output = result.output.let { if (it.length > MAX_OUTPUT_CHARS) "…" + it.takeLast(MAX_OUTPUT_CHARS) else it }
        val status = when { result.timedOut -> "timed out after 120 s"; else -> "exit code ${result.exitCode}" }
        return ToolOutcome("$status\n$output".trimEnd(), "$ ${command.take(60)} · $status", changedFiles = true)
    }
}
