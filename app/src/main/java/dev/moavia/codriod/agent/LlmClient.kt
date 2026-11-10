package dev.moavia.codriod.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

enum class Role { User, Assistant, Tool, Error }

/** One tool request from the model. [raw] keeps provider-specific fields (for example thought signatures). */
data class ToolCall(val id: String, val name: String, val arguments: String, val raw: String? = null)

data class ChatMessage(
    val role: Role,
    val text: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** Short human-readable line shown in the chat for tool results. */
    val summary: String? = null,
    val time: Long = System.currentTimeMillis()
)

data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class LlmReply(val text: String, val toolCalls: List<ToolCall>,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val stopReason: String? = null)

data class ModelInfo(val id: String, val free: Boolean = false, val contextLength: Int = 0)

class LlmException(message: String) : IOException(message)

/** Pure request/response mapping, kept separate from networking so it can be unit tested. */
object OpenAiProtocol {
    fun request(model: String, system: String, messages: List<ChatMessage>, tools: List<ToolSpec>): JSONObject {
        val out = JSONArray()
        if (system.isNotBlank()) out.put(JSONObject().put("role", "system").put("content", system))
        for (message in messages) when (message.role) {
            Role.User -> out.put(JSONObject().put("role", "user").put("content", message.text))
            Role.Assistant -> {
                val item = JSONObject().put("role", "assistant")
                if (message.toolCalls.isEmpty()) item.put("content", message.text)
                else {
                    item.put("content", if (message.text.isEmpty()) JSONObject.NULL else message.text)
                    item.put("tool_calls", JSONArray().apply { message.toolCalls.forEach { put(toolCallJson(it)) } })
                }
                out.put(item)
            }
            Role.Tool -> out.put(JSONObject().put("role", "tool")
                .put("tool_call_id", message.toolCallId).put("content", message.text))
            Role.Error -> Unit
        }
        val body = JSONObject().put("model", model).put("messages", out)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.name).put("description", tool.description)
                    .put("parameters", tool.parameters)))
            }
        })
        return body
    }

    private fun toolCallJson(call: ToolCall): JSONObject {
        val base = call.raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        base.put("id", call.id).put("type", "function")
        val function = base.optJSONObject("function") ?: JSONObject()
        function.put("name", call.name).put("arguments", call.arguments)
        return base.put("function", function)
    }

    fun parse(response: JSONObject): LlmReply {
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw LlmException(errorMessage(response) ?: "The provider returned no answer")
        val message = choice.optJSONObject("message") ?: JSONObject()
        val calls = mutableListOf<ToolCall>()
        message.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val function = call.optJSONObject("function") ?: continue
                val args = when (val value = function.opt("arguments")) {
                    is JSONObject -> value.toString()
                    is String -> value.ifBlank { "{}" }
                    else -> "{}"
                }
                val id = call.optString("id").ifBlank { "call_" + UUID.randomUUID().toString().take(8) }
                calls += ToolCall(id, function.optString("name"), args, call.toString())
            }
        }
        val usage = response.optJSONObject("usage")
        return LlmReply(textOf(message.opt("content")), calls,
            usage?.optInt("prompt_tokens") ?: 0, usage?.optInt("completion_tokens") ?: 0,
            choice.optString("finish_reason").ifBlank { null })
    }

    private fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) content.optJSONObject(i)?.optString("text")?.let(::append)
        }
        else -> ""
    }
}

object AnthropicProtocol {
    const val VERSION = "2023-06-01"

    fun request(model: String, system: String, messages: List<ChatMessage>, tools: List<ToolSpec>,
        maxTokens: Int = 8192): JSONObject {
        val out = JSONArray()
        var pendingResults: JSONArray? = null
        fun flushResults() {
            pendingResults?.let { out.put(JSONObject().put("role", "user").put("content", it)) }
            pendingResults = null
        }
        for (message in messages) when (message.role) {
            Role.Tool -> {
                val results = pendingResults ?: JSONArray().also { pendingResults = it }
                results.put(JSONObject().put("type", "tool_result")
                    .put("tool_use_id", message.toolCallId).put("content", message.text))
            }
            Role.User -> {
                flushResults()
                out.put(JSONObject().put("role", "user").put("content", message.text))
            }
            Role.Assistant -> {
                flushResults()
                val blocks = JSONArray()
                if (message.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", message.text))
                message.toolCalls.forEach { call ->
                    blocks.put(JSONObject().put("type", "tool_use").put("id", call.id).put("name", call.name)
                        .put("input", runCatching { JSONObject(call.arguments) }.getOrElse { JSONObject() }))
                }
                if (blocks.length() > 0) out.put(JSONObject().put("role", "assistant").put("content", blocks))
            }
            Role.Error -> Unit
        }
        flushResults()
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("messages", out)
        if (system.isNotBlank()) body.put("system", system)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            tools.forEach { put(JSONObject().put("name", it.name).put("description", it.description)
                .put("input_schema", it.parameters)) }
        })
        return body
    }

    fun parse(response: JSONObject): LlmReply {
        val content = response.optJSONArray("content")
            ?: throw LlmException(errorMessage(response) ?: "The provider returned no answer")
        val text = StringBuilder()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            when (block.optString("type")) {
                "text" -> text.append(block.optString("text"))
                "tool_use" -> calls += ToolCall(block.optString("id"), block.optString("name"),
                    (block.optJSONObject("input") ?: JSONObject()).toString())
            }
        }
        val usage = response.optJSONObject("usage")
        return LlmReply(text.toString(), calls, usage?.optInt("input_tokens") ?: 0,
            usage?.optInt("output_tokens") ?: 0, response.optString("stop_reason").ifBlank { null })
    }
}

object ModelListParser {
    private val nonChat = listOf("embed", "whisper", "tts", "dall-e", "moderation", "transcribe",
        "rerank", "image", "audio", "realtime", "search-preview", "imagen", "veo", "aqa", "speech", "babbage", "davinci")

    fun parse(body: String): List<ModelInfo> {
        val trimmed = body.trim()
        val items: JSONArray = if (trimmed.startsWith("[")) JSONArray(trimmed) else {
            val root = JSONObject(trimmed)
            root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        }
        val models = mutableListOf<ModelInfo>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id").ifBlank { item.optString("name") }.removePrefix("models/")
            if (id.isBlank()) continue
            val pricing = item.optJSONObject("pricing")
            val free = id.endsWith(":free") || (pricing != null &&
                pricing.optString("prompt") in listOf("0", "0.0") && pricing.optString("completion") in listOf("0", "0.0"))
            models += ModelInfo(id, free, item.optInt("context_length", item.optInt("context_window", 0)))
        }
        return models.distinctBy { it.id }
            .filter { model -> nonChat.none { model.id.contains(it, ignoreCase = true) } }
            .sortedWith(compareByDescending<ModelInfo> { it.free }.thenBy { it.id.lowercase() })
    }
}

internal fun errorMessage(json: JSONObject?): String? {
    if (json == null) return null
    val error = json.opt("error")
    return when (error) {
        is JSONObject -> error.optString("message").ifBlank { error.toString() }
        is String -> error
        else -> json.optString("message").ifBlank { null }
    }
}

/** Minimal HTTP client using the platform's HttpURLConnection, so no extra dependencies are needed. */
class LlmClient(private val connectTimeoutMs: Int = 20_000, private val readTimeoutMs: Int = 240_000) {

    suspend fun listModels(spec: ProviderSpec, key: String, baseUrl: String): List<ModelInfo> {
        val url = spec.modelsUrl ?: (baseUrl.trimEnd('/') + "/models")
        val body = send("GET", url, headers(spec, key), null)
        return ModelListParser.parse(body).ifEmpty { throw LlmException("The provider returned no chat models") }
    }

    suspend fun chat(spec: ProviderSpec, key: String, baseUrl: String, model: String, system: String,
        messages: List<ChatMessage>, tools: List<ToolSpec>): LlmReply {
        val base = baseUrl.trimEnd('/')
        return when (spec.style) {
            ApiStyle.OpenAI -> OpenAiProtocol.parse(JSONObject(send("POST", "$base/chat/completions",
                headers(spec, key), OpenAiProtocol.request(model, system, messages, tools).toString())))
            ApiStyle.Anthropic -> AnthropicProtocol.parse(JSONObject(send("POST", "$base/messages",
                headers(spec, key), AnthropicProtocol.request(model, system, messages, tools).toString())))
        }
    }

    private fun headers(spec: ProviderSpec, key: String): Map<String, String> {
        val result = linkedMapOf("Content-Type" to "application/json", "Accept" to "application/json")
        when (spec.style) {
            ApiStyle.Anthropic -> {
                result["x-api-key"] = key
                result["anthropic-version"] = AnthropicProtocol.VERSION
            }
            ApiStyle.OpenAI -> if (key.isNotBlank()) result["Authorization"] = "Bearer $key"
        }
        if (spec.id == "openrouter") result["X-Title"] = "Codriod"
        return result
    }

    private suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): String {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Set a valid server URL first" }
        val connection = URL(url).openConnection() as HttpURLConnection
        return coroutineScope {
            val request = async(Dispatchers.IO) {
                connection.requestMethod = method
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                if (body != null) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                if (code !in 200..299) {
                    val detail = runCatching { errorMessage(JSONObject(text)) }.getOrNull()
                        ?: text.take(300).ifBlank { connection.responseMessage ?: "" }
                    throw LlmException("HTTP $code: $detail")
                }
                text
            }
            try { request.await() }
            catch (error: CancellationException) { connection.disconnect(); throw error }
            finally { connection.disconnect() }
        }
    }
}
