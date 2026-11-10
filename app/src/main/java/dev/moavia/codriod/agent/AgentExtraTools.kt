package dev.moavia.codriod.agent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class StepStatus { Pending, InProgress, Done }

data class PlanStep(val text: String, val status: StepStatus)

object PlanJson {
    fun encode(title: String, steps: List<PlanStep>): String = JSONObject().put("title", title).put("steps", JSONArray().apply {
        steps.forEach { put(JSONObject().put("text", it.text).put("status", it.status.name)) }
    }).toString()

    fun decode(json: String?): Pair<String, List<PlanStep>>? = runCatching {
        val o = JSONObject(json ?: return null)
        val a = o.optJSONArray("steps") ?: JSONArray()
        o.optString("title") to (0 until a.length()).map { i ->
            a.getJSONObject(i).let { PlanStep(it.optString("text"), runCatching { StepStatus.valueOf(it.optString("status")) }.getOrDefault(StepStatus.Pending)) }
        }
    }.getOrNull()
}

/**
 * The agent's to-do list. In Plan mode it is the plan the user approves; in Agent and Autopilot modes the
 * agent keeps it up to date while it works, so the user can follow progress.
 */
class PlanToolset(private val onPlan: (String, List<PlanStep>) -> Unit) : AgentToolset {
    private val spec = ToolSpec("update_plan",
        "Create or update your step-by-step plan / to-do list. Send the whole list every time with each step's status " +
            "(pending, in_progress, done). Keep exactly one step in_progress while working. Use it for any task with 3+ steps.",
        JSONObject().put("type", "object").put("properties", JSONObject()
            .put("title", JSONObject().put("type", "string").put("description", "Short title of the task"))
            .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("text", JSONObject().put("type", "string"))
                .put("status", JSONObject().put("type", "string").put("enum", JSONArray(listOf("pending", "in_progress", "done")))))
                .put("required", JSONArray(listOf("text", "status"))))))
            .put("required", JSONArray(listOf("steps"))))

    override fun specs(mode: AgentMode) = if (mode == AgentMode.Ask) emptyList() else listOf(spec)
    override fun handles(name: String) = name == "update_plan"

    override suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome {
        val array = args.optJSONArray("steps") ?: return ToolOutcome("Error: steps is required", "Plan: invalid")
        val steps = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
            PlanStep(it.optString("text").take(200), when (it.optString("status")) {
                "done", "completed" -> StepStatus.Done; "in_progress" -> StepStatus.InProgress; else -> StepStatus.Pending
            })
        }.filter { it.text.isNotBlank() }.take(30)
        val title = args.optString("title").ifBlank { "Plan" }.take(80)
        onPlan(title, steps)
        val done = steps.count { it.status == StepStatus.Done }
        return ToolOutcome(if (mode == AgentMode.Plan) "Plan saved and shown to the user for approval. Stop now and wait; do not start the work."
            else "Plan updated ($done/${steps.size} done).", "Plan · $done/${steps.size}", detail = PlanJson.encode(title, steps))
    }
}

/** Reads web pages and documentation as plain text. */
class WebToolset(private val enabled: () -> Boolean, private val userAgent: () -> String = { "Mozilla/5.0 (Linux; Android) Codriod" }) : AgentToolset {
    private val spec = ToolSpec("fetch_url", "Download a web page or API response (http/https) and return its text. Use it to read docs, " +
        "READMEs, APIs or articles. For pages that need JavaScript or login, use the browser tools instead.",
        AgentTools.schema(Triple("url", "string", "Full http(s) URL"), required = listOf("url")))

    override fun specs(mode: AgentMode) = if (enabled()) listOf(spec) else emptyList()
    override fun handles(name: String) = name == "fetch_url"

    override suspend fun execute(name: String, args: JSONObject, mode: AgentMode): ToolOutcome {
        if (!enabled()) return ToolOutcome("Error: internet access is turned off in Agent settings.", "fetch_url blocked")
        val url = args.optString("url").trim()
        require(url.startsWith("https://") || url.startsWith("http://")) { "Use a full http(s) URL" }
        val (code, type, body) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { download(url) }
        val text = if (type.contains("html")) htmlToText(body) else body
        val clipped = if (text.length > 24_000) text.take(24_000) + "\n… (truncated)" else text
        return ToolOutcome("HTTP $code · $type\n$clipped", "Fetched ${url.removePrefix("https://").take(50)} · HTTP $code", detail = clipped.take(4000))
    }

    private fun download(url: String): Triple<Int, String, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000; connection.readTimeout = 25_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", userAgent())
            connection.setRequestProperty("Accept", "text/html,application/json,text/plain,*/*")
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input -> input.readBytes().let { if (it.size > 2_000_000) it.copyOf(2_000_000) else it } } ?: ByteArray(0)
            return Triple(code, connection.contentType ?: "", bytes.toString(Charsets.UTF_8))
        } finally { connection.disconnect() }
    }

    companion object {
        fun htmlToText(html: String): String {
            var s = html.replace(Regex("(?is)<(script|style|noscript|svg|template)[^>]*>.*?</\\1>"), " ")
            s = s.replace(Regex("(?is)<!--.*?-->"), " ")
            s = s.replace(Regex("(?i)<\\s*(br|/p|/div|/li|/h[1-6]|/tr|/section|/article|/header|/footer)\\s*/?>"), "\n")
            s = s.replace(Regex("(?i)<\\s*li[^>]*>"), "\n• ")
            s = s.replace(Regex("(?i)<\\s*h([1-6])[^>]*>"), "\n# ")
            s = s.replace(Regex("<[^>]+>"), " ")
            s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
            return s.lines().map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        }
    }
}
