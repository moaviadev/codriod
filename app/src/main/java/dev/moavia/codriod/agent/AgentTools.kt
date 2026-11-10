package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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
    fun rename(from: String, to: String) { write(to, read(from)); delete(from) }
    /** The real file for [path], for binary files such as images; null when unavailable. */
    fun file(path: String): File? = null
}

sealed class ApprovalRequest {
    data class Edit(val path: String, val created: Boolean, val diff: List<DiffLine>) : ApprovalRequest()
    data class Delete(val path: String) : ApprovalRequest()
    data class Command(val command: String, val safe: Boolean = false, val dangerous: Boolean = false) : ApprovalRequest()
    /** A named action such as "Set up GitHub login" or "Push to origin". [risky] actions are never auto-approved. */
    data class Action(val title: String, val detail: String, val risky: Boolean = false) : ApprovalRequest()
}

/** A group of extra tools (Git, browser, web, plan) plugged into [AgentTools]. */
interface AgentToolset {
    fun specs(mode: AgentMode): List<ToolSpec>
    fun handles(name: String): Boolean
    suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome
}

enum class Decision { Allow, AlwaysAllow, Deny }

fun interface Approver { suspend fun approve(request: ApprovalRequest): Decision }

/** Records a file's content before the agent changes it, so a run can be undone. null = the file did not exist. */
fun interface ChangeRecorder { fun before(path: String, original: String?) }

data class ToolOutcome(val content: String, val summary: String, val changedFiles: Boolean = false,
    val images: List<String> = emptyList(), val detail: String? = null)

enum class AgentMode(val label: String, val hint: String) {
    Ask("Ask", "Answers questions and reads your project. Never changes anything."),
    Plan("Plan", "Studies the project and writes a step-by-step plan for you to approve first."),
    Agent("Agent", "Edits files, runs commands and uses the browser, asking before risky steps."),
    Autopilot("Autopilot", "Works on its own until the task is done. Only dangerous commands still ask.");

    val canEdit get() = this == Agent || this == Autopilot
}

enum class CommandPolicy(val label: String, val hint: String) {
    Ask("Ask every time", "Every command needs your approval"),
    Safe("Auto-run safe commands", "Read-only commands like ls, cat, grep run without asking"),
    Always("Run all commands", "Commands run without asking, except dangerous ones")
}

/** Classifies shell commands for automatic approval. */
object CommandSafety {
    private val safeHeads = setOf("ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "find", "stat", "du", "df", "date",
        "whoami", "id", "uname", "env", "printenv", "which", "file", "tree", "sort", "uniq", "cut", "diff", "cmp", "md5sum",
        "sha1sum", "sha256sum", "basename", "dirname", "realpath", "readlink", "getprop", "uptime", "true", "test")
    private val dangerous = listOf(
        Regex("""\bmkfs\b"""), Regex("""\bdd\s+[^|]*\bof=/dev/"""), Regex(""":\(\)\s*\{"""), Regex("""\b(reboot|shutdown|poweroff)\b"""),
        Regex("""(^|[;&|]\s*)su(\s|$)"""), Regex("""\bchmod\s+(-R\s+)?777\s+/"""), Regex(""">\s*/dev/(block|sd)"""),
        Regex("""\b(pm\s+(uninstall|clear)|cmd\s+package\s+uninstall)\b"""), Regex("""\bsetprop\b"""))

    private val rootTargets = setOf("/", "/*", "~", "~/", "~/*", "${'$'}HOME", "${'$'}HOME/", "*", ".", "./", "./*", "..", "../", "/sdcard", "/sdcard/", "/storage")

    /** rm -r on the root, home, everything, or the whole project — however the flags are written. */
    private fun recursiveRmOfRoot(command: String): Boolean = command.split(Regex("[;&|\n]+")).any { part ->
        val words = part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.dropWhile { it == "sudo" || it == "busybox" || it == "toybox" }
        if (words.firstOrNull() != "rm") return@any false
        val flags = words.drop(1).filter { it.startsWith("-") && it != "--" }.joinToString("")
        val recursive = flags.contains('r') || flags.contains('R') || words.contains("--recursive")
        recursive && words.drop(1).filterNot { it.startsWith("-") }.any { it.trim('\'', '"') in rootTargets }
    }

    fun isDangerous(command: String) = recursiveRmOfRoot(command) || dangerous.any { it.containsMatchIn(command) }

    /** Read-only and simple: one command, no redirection, chaining or substitution. */
    fun isSafe(command: String): Boolean {
        val c = command.trim()
        if (c.isEmpty() || c.any { it in ";&|><`\n" } || c.contains("$(")) return false
        val head = c.split(Regex("\\s+")).first()
        if (head == "codriod") return c.split(Regex("\\s+")).getOrNull(1) in setOf("help", "clipboard")  && !c.contains(" set")
        if (head == "find" && (c.contains("-delete") || c.contains("-exec"))) return false
        return head in safeHeads
    }
}

class AgentTools(
    private val workspace: AgentWorkspace,
    private val approver: Approver,
    private val toolsets: List<AgentToolset> = emptyList(),
    private val recorder: ChangeRecorder? = null
) {
    companion object {
        private const val MAX_READ_CHARS = 60_000
        private const val MAX_OUTPUT_CHARS = 16_000

        internal fun schema(vararg props: Triple<String, String, String>, required: List<String>) = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply {
                props.forEach { (name, type, description) -> put(name, JSONObject().put("type", type).put("description", description)) }
            })
            .put("required", JSONArray(required))

        val readOnly = listOf(
            ToolSpec("list_files", "List every file and folder in the project (folders end with /).",
                schema(required = emptyList())),
            ToolSpec("read_file", "Read a UTF-8 text file from the project, with line numbers. Optionally a 1-based line range.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("start_line", "integer", "First line to read (optional)"),
                    Triple("end_line", "integer", "Last line to read (optional)"),
                    required = listOf("path"))),
            ToolSpec("search_files", "Case-insensitive text search across project files. Returns path:line: text.",
                schema(Triple("query", "string", "Text to find"), required = listOf("query"))),
            ToolSpec("view_image", "Look at an image file in the project (png, jpg, webp). Only useful with vision models.",
                schema(Triple("path", "string", "Path relative to the project root"), required = listOf("path")))
        )

        val editing = listOf(
            ToolSpec("write_file", "Create a file or replace its whole content. Missing folders are created.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("content", "string", "The complete new file content"),
                    required = listOf("path", "content"))),
            ToolSpec("edit_file", "Replace an exact piece of text in a file. Prefer this for small changes. old_text must appear once unless replace_all is true.",
                schema(Triple("path", "string", "Path relative to the project root"),
                    Triple("old_text", "string", "Exact existing text, copied from read_file without line numbers"),
                    Triple("new_text", "string", "Replacement text"),
                    Triple("replace_all", "boolean", "Replace every occurrence (optional)"),
                    required = listOf("path", "old_text", "new_text"))),
            ToolSpec("rename_file", "Rename or move a file inside the project.",
                schema(Triple("from", "string", "Current path"), Triple("to", "string", "New path"), required = listOf("from", "to"))),
            ToolSpec("delete_file", "Delete a file or an empty folder.",
                schema(Triple("path", "string", "Path relative to the project root"), required = listOf("path"))),
            ToolSpec("run_command", "Run a shell command in the project folder with Android's /system/bin/sh (toybox: ls, cat, grep, " +
                "find, sed, sort, zip/unzip on most phones; no bash, Python or Node unless installed). Codriod phone commands are " +
                "available: `codriod open-url URL`, `codriod share FILE|TEXT [--app whatsapp]`, `codriod clipboard get|set TEXT`, " +
                "`codriod notify TEXT`, `codriod toast TEXT`, `codriod browse URL`, `codriod screenshot FILE`, `codriod help`.",
                schema(Triple("command", "string", "Shell command"),
                    Triple("timeout_seconds", "integer", "Time limit, default 120, max 600 (optional)"),
                    required = listOf("command")))
        )

        fun baseSpecs(mode: AgentMode) = if (mode.canEdit) readOnly + editing else readOnly
    }

    fun specs(mode: AgentMode): List<ToolSpec> = baseSpecs(mode) + toolsets.flatMap { it.specs(mode) }

    suspend fun execute(call: ToolCall, mode: AgentMode): ToolOutcome {
        val args = runCatching { JSONObject(call.arguments.ifBlank { "{}" }) }.getOrNull()
            ?: return ToolOutcome("Error: arguments were not valid JSON", "${call.name}: invalid arguments")
        if (!mode.canEdit && call.name in editing.map { it.name })
            return ToolOutcome("Error: ${call.name} is not available in ${mode.label} mode. " +
                if (mode == AgentMode.Plan) "Put it in the plan instead." else "Describe the change instead.",
                "${call.name} blocked (${mode.label} mode)")
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
                "view_image" -> {
                    val path = path(args)
                    val file = workspace.file(path)?.takeIf { it.isFile } ?: error("$path is not an image file in this project")
                    require(file.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp")) { "Only png, jpg and webp images can be viewed" }
                    ToolOutcome("Image $path (${file.length() / 1024} KB) is attached.", "Viewed $path", images = listOf(file.absolutePath))
                }
                "write_file" -> {
                    val path = path(args)
                    val created = !workspace.exists(path)
                    applyEdit(path, if (created) "" else workspace.read(path), args.optString("content"), created)
                }
                "edit_file" -> {
                    val path = path(args)
                    val oldText = args.optString("old_text")
                    require(oldText.isNotEmpty()) { "old_text must not be empty" }
                    val current = workspace.read(path)
                    val first = current.indexOf(oldText)
                    require(first >= 0) { "old_text was not found in $path. Read the file and copy the text exactly (without line numbers)." }
                    val all = args.optBoolean("replace_all", false)
                    require(all || current.indexOf(oldText, first + 1) < 0) {
                        "old_text appears more than once in $path. Include more surrounding lines or set replace_all."
                    }
                    val updated = if (all) current.replace(oldText, args.optString("new_text"))
                        else current.replaceRange(first, first + oldText.length, args.optString("new_text"))
                    applyEdit(path, current, updated, false)
                }
                "rename_file" -> {
                    val from = args.optString("from").trim().removePrefix("./").trim('/')
                    val to = args.optString("to").trim().removePrefix("./").trim('/')
                    require(from.isNotEmpty() && to.isNotEmpty()) { "from and to are required" }
                    require(workspace.exists(from)) { "$from does not exist" }
                    require(!workspace.exists(to)) { "$to already exists" }
                    if (approver.approve(ApprovalRequest.Action("Rename file", "$from → $to")) == Decision.Deny)
                        ToolOutcome("The user declined renaming $from.", "Rename declined")
                    else {
                        val content = runCatching { workspace.read(from) }.getOrNull()
                        recorder?.before(from, content); recorder?.before(to, null)
                        workspace.rename(from, to)
                        ToolOutcome("Renamed $from to $to.", "Renamed $from → $to", changedFiles = true)
                    }
                }
                "delete_file" -> {
                    val path = path(args)
                    require(workspace.exists(path)) { "$path does not exist" }
                    if (approver.approve(ApprovalRequest.Delete(path)) == Decision.Deny)
                        ToolOutcome("The user declined deleting $path.", "Delete $path declined")
                    else {
                        recorder?.before(path, runCatching { workspace.read(path) }.getOrNull())
                        workspace.delete(path)
                        ToolOutcome("Deleted $path.", "Deleted $path", changedFiles = true)
                    }
                }
                "run_command" -> runCommand(args.optString("command").trim(), args.optInt("timeout_seconds", 120).coerceIn(5, 600))
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
            if (body.length > MAX_READ_CHARS) { body.append("… truncated, request a line range after line $i\n"); break }
            body.append(i + 1).append(" | ").append(lines[i]).append('\n')
        }
        return ToolOutcome(body.toString().ifEmpty { "(empty file)" }, "Read $path ($start-${minOf(end, lines.size)})")
    }

    private suspend fun applyEdit(path: String, old: String, new: String, created: Boolean): ToolOutcome {
        if (old == new) return ToolOutcome("No change: $path already has this content.", "No change to $path")
        val diff = LineDiff.diff(old, new)
        val (added, removed) = LineDiff.stats(diff)
        if (approver.approve(ApprovalRequest.Edit(path, created, diff)) == Decision.Deny)
            return ToolOutcome("The user rejected this change to $path. Ask what they want instead.", "Change to $path rejected")
        recorder?.before(path, if (created) null else old)
        workspace.write(path, new)
        return ToolOutcome("${if (created) "Created" else "Updated"} $path (+$added -$removed).",
            "${if (created) "Created" else "Edited"} $path  +$added −$removed", changedFiles = true, detail = diffDetail(path, diff, added, removed))
    }

    private suspend fun runCommand(command: String, timeout: Int): ToolOutcome {
        require(command.isNotEmpty()) { "command is required" }
        val request = ApprovalRequest.Command(command, CommandSafety.isSafe(command), CommandSafety.isDangerous(command))
        if (approver.approve(request) == Decision.Deny)
            return ToolOutcome("The user declined running: $command", "Command declined: ${command.take(60)}")
        val result = workspace.run(command, timeout)
        val output = result.output.let { if (it.length > MAX_OUTPUT_CHARS) "…" + it.takeLast(MAX_OUTPUT_CHARS) else it }
        val status = if (result.timedOut) "timed out after $timeout s" else "exit code ${result.exitCode}"
        return ToolOutcome("$status\n$output".trimEnd(), "$ ${command.take(60)} · $status", changedFiles = true,
            detail = "$ $command\n$output".trimEnd())
    }
}

/** UI diff text: a header line, then lines prefixed with +, -, space or … */
fun diffDetail(path: String, diff: List<DiffLine>, added: Int, removed: Int): String = buildString {
    append("DIFF ").append(path).append(" +").append(added).append(" -").append(removed).append('\n')
    diff.take(400).forEach { line ->
        append(when (line.kind) { DiffKind.Added -> "+"; DiffKind.Removed -> "-"; DiffKind.Same -> " "; DiffKind.Gap -> "…" })
        append(line.text).append('\n')
    }
}.trimEnd()
