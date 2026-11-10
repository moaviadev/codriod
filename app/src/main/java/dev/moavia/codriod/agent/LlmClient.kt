package dev.moavia.codriod.agent

import dev.moavia.codriod.git.Base64Codec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
    val time: Long = System.currentTimeMillis(),
    /** Image files (JPEG/PNG) sent to vision models: user attachments or tool screenshots. */
    val images: List<String> = emptyList(),
    /** Extra detail for the UI only (diff, command output, plan JSON). Never sent to the model. */
    val detail: String? = null
)

data class ToolSpec(val name: String, val description: String, val parameters: JSONObject)

data class LlmReply(val text: String, val toolCalls: List<ToolCall>,
    val inputTokens: Int = 0, val outputTokens: Int = 0, val stopReason: String? = null)

/** [vision] comes from the provider's model list when it says which inputs a model takes; null means unknown. */
data class ModelInfo(val id: String, val free: Boolean = false, val contextLength: Int = 0, val vision: Boolean? = null)

class LlmException(message: String) : IOException(message)

/** Loads an image file as (mime type, base64). */
fun interface ImageSource { fun load(path: String): Pair<String, String>? }

object FileImages : ImageSource {
    override fun load(path: String): Pair<String, String>? {
        val file = File(path)
        if (!file.isFile || file.length() > 8 * 1024 * 1024) return null
        val mime = if (path.endsWith(".png", true)) "image/png" else if (path.endsWith(".webp", true)) "image/webp" else "image/jpeg"
        return mime to Base64Codec.encode(file.readBytes())
    }
}

/** Best-effort guess of what a model can do, from its id, when the provider does not say. */
object ModelCaps {
    private val vision = listOf("gpt-4o", "gpt-4.1", "gpt-4-turbo", "gpt-4-vision", "chatgpt-4o", "gpt-5", "o1", "o3", "o4",
        "claude-3", "claude-sonnet", "claude-opus", "claude-haiku-4", "claude-4", "gemini", "gemma-3", "llava", "bakllava",
        "vision", "-vl", "_vl", "vl-", "pixtral", "llama-4", "llama4", "grok-4", "grok-2-vision", "qwen-vl", "qvq",
        "minicpm-v", "moondream", "mistral-medium", "mistral-small-3", "magistral-medium-2509", "magistral-small-2509",
        "phi-4-multimodal", "phi-3.5-vision", "kimi-vl", "kimi-k2.5", "glm-4.5v", "glm-4.6v", "glm-4v", "nova-lite", "nova-pro",
        "nova-premier", "internvl", "molmo", "aya-vision", "granite-vision", "cogvlm", "omni", "multimodal", "fuyu", "idefics",
        "smolvlm", "paligemma", "mllama")
    private val notVision = listOf("o1-mini", "o1-preview", "o3-mini", "gpt-4o-audio", "gpt-4o-realtime", "gpt-4o-search",
        "gpt-oss", "embed", "whisper", "tts", "gemini-embedding", "gemma-3-1b", "gemma-3n-e2b-it-text", "-text-only")

    fun vision(id: String): Boolean = notVision.none { id.contains(it, ignoreCase = true) } && vision.any { id.contains(it, ignoreCase = true) }

    /** Reads "can take images" from a model-list entry (OpenRouter, Hugging Face, GitHub Models, Mistral, Together, LM Studio …). */
    fun visionFromMetadata(item: JSONObject): Boolean? {
        fun has(array: JSONArray?) = array?.let { a -> (0 until a.length()).map { a.optString(it).lowercase() } }
        val lists = listOfNotNull(
            has(item.optJSONObject("architecture")?.optJSONArray("input_modalities")),
            has(item.optJSONArray("input_modalities")),
            has(item.optJSONArray("supported_input_modalities")),
            has(item.optJSONObject("modalities")?.optJSONArray("input")))
        if (lists.isNotEmpty()) return lists.any { list -> list.any { it == "image" || it == "images" || it == "vision" } }
        item.optJSONObject("architecture")?.optString("modality")?.takeIf { it.isNotBlank() }?.let {
            return it.substringBefore("->").contains("image", true)
        }
        item.optJSONObject("capabilities")?.let { caps ->
            if (caps.has("vision")) return caps.optBoolean("vision")
        }
        item.optJSONArray("capabilities")?.let { a -> return (0 until a.length()).any { a.optString(it).equals("vision", true) } }
        if (item.has("vision")) return item.optBoolean("vision")
        if (item.optString("type") == "vlm") return true
        return null
    }
}

/** Pure request/response mapping, kept separate from networking so it can be unit tested. */
object OpenAiProtocol {
    fun request(model: String, system: String, messages: List<ChatMessage>, tools: List<ToolSpec>,
        images: ImageSource = FileImages, stream: Boolean = false): JSONObject {
        val out = JSONArray()
        if (system.isNotBlank()) out.put(JSONObject().put("role", "system").put("content", system))
        var pendingToolImages = mutableListOf<String>()
        fun flushToolImages() {
            if (pendingToolImages.isEmpty()) return
            out.put(JSONObject().put("role", "user").put("content", content("Images returned by the tools above:", pendingToolImages, images)))
            pendingToolImages = mutableListOf()
        }
        for (message in messages) when (message.role) {
            Role.User -> {
                flushToolImages()
                out.put(JSONObject().put("role", "user").put("content",
                    if (message.images.isEmpty()) message.text else content(message.text, message.images, images)))
            }
            Role.Assistant -> {
                flushToolImages()
                val item = JSONObject().put("role", "assistant")
                if (message.toolCalls.isEmpty()) item.put("content", message.text)
                else {
                    item.put("content", if (message.text.isEmpty()) JSONObject.NULL else message.text)
                    item.put("tool_calls", JSONArray().apply { message.toolCalls.forEach { put(toolCallJson(it)) } })
                }
                out.put(item)
            }
            Role.Tool -> {
                out.put(JSONObject().put("role", "tool").put("tool_call_id", message.toolCallId).put("content", message.text))
                pendingToolImages.addAll(message.images)
            }
            Role.Error -> Unit
        }
        flushToolImages()
        val body = JSONObject().put("model", model).put("messages", out)
        if (stream) body.put("stream", true)
        if (tools.isNotEmpty()) body.put("tools", JSONArray().apply {
            tools.forEach { tool ->
                put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", tool.name).put("description", tool.description)
                    .put("parameters", tool.parameters)))
            }
        })
        return body
    }

    private fun content(text: String, paths: List<String>, images: ImageSource): JSONArray = JSONArray().apply {
        if (text.isNotBlank()) put(JSONObject().put("type", "text").put("text", text))
        paths.forEach { path ->
            images.load(path)?.let { (mime, data) ->
                put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:$mime;base64,$data")))
            }
        }
    }

    internal fun toolCallJson(call: ToolCall): JSONObject {
        val base = call.raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        base.put("id", call.id).put("type", "function")
        base.remove("index")
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

    internal fun textOf(content: Any?): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) content.optJSONObject(i)?.optString("text")?.let(::append)
        }
        else -> ""
    }
}

/** Assembles an OpenAI-style server-sent event stream into one reply. */
class OpenAiStream(private val onText: (String) -> Unit) {
    private val text = StringBuilder()
    private class Partial(var id: String = "", var name: String = "", val args: StringBuilder = StringBuilder(), var raw: JSONObject? = null)
    private val calls = sortedMapOf<Int, Partial>()
    private var inputTokens = 0
    private var outputTokens = 0
    private var finish: String? = null

    /** Feeds one line of the stream. Returns false once the stream says it is done. */
    fun feed(line: String): Boolean {
        if (!line.startsWith("data:")) return true
        val data = line.removePrefix("data:").trim()
        if (data == "[DONE]") return false
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return true
        json.optJSONObject("error")?.let { throw LlmException(errorMessage(json) ?: "Stream error") }
        json.optJSONObject("usage")?.let { inputTokens = it.optInt("prompt_tokens", inputTokens); outputTokens = it.optInt("completion_tokens", outputTokens) }
        val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return true
        choice.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }?.let { finish = it }
        val delta = choice.optJSONObject("delta") ?: choice.optJSONObject("message") ?: return true
        val piece = OpenAiProtocol.textOf(delta.opt("content"))
        if (piece.isNotEmpty()) { text.append(piece); onText(piece) }
        delta.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val call = array.optJSONObject(i) ?: continue
                val index = if (call.has("index")) call.optInt("index") else calls.size
                val partial = calls.getOrPut(index) { Partial() }
                call.optString("id").takeIf { it.isNotBlank() }?.let { partial.id = it }
                if (partial.raw == null) partial.raw = JSONObject(call.toString())
                else call.keys().forEach { key -> if (key != "function" && key != "index") partial.raw!!.put(key, call.get(key)) }
                call.optJSONObject("function")?.let { f ->
                    f.optString("name").takeIf { it.isNotBlank() }?.let { partial.name = it }
                    when (val a = f.opt("arguments")) {
                        is String -> partial.args.append(a)
                        is JSONObject -> partial.args.append(a.toString())
                    }
                }
            }
        }
        return true
    }

    fun result(): LlmReply = LlmReply(text.toString(), calls.values.filter { it.name.isNotBlank() }.map { p ->
        val id = p.id.ifBlank { "call_" + UUID.randomUUID().toString().take(8) }
        ToolCall(id, p.name, p.args.toString().ifBlank { "{}" }, p.raw?.apply { remove("index") }?.toString())
    }, inputTokens, outputTokens, finish)
}

object AnthropicProtocol {
    const val VERSION = "2023-06-01"

    fun request(model: String, system: String, messages: List<ChatMessage>, tools: List<ToolSpec>,
        maxTokens: Int = 8192, images: ImageSource = FileImages, stream: Boolean = false): JSONObject {
        val out = JSONArray()
        var pendingResults: JSONArray? = null
        fun flushResults() {
            pendingResults?.let { out.put(JSONObject().put("role", "user").put("content", it)) }
            pendingResults = null
        }
        fun imageBlocks(paths: List<String>) = paths.mapNotNull { path ->
            images.load(path)?.let { (mime, data) ->
                JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", mime).put("data", data))
            }
        }
        for (message in messages) when (message.role) {
            Role.Tool -> {
                val results = pendingResults ?: JSONArray().also { pendingResults = it }
                val result = JSONObject().put("type", "tool_result").put("tool_use_id", message.toolCallId)
                val blocks = imageBlocks(message.images)
                if (blocks.isEmpty()) result.put("content", message.text)
                else result.put("content", JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", message.text.ifBlank { "(image)" })); blocks.forEach { put(it) }
                })
                results.put(result)
            }
            Role.User -> {
                flushResults()
                val blocks = imageBlocks(message.images)
                out.put(JSONObject().put("role", "user").put("content", if (blocks.isEmpty()) message.text else JSONArray().apply {
                    blocks.forEach { put(it) }
                    put(JSONObject().put("type", "text").put("text", message.text.ifBlank { "See the image." }))
                }))
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
        if (stream) body.put("stream", true)
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

/** Assembles an Anthropic Messages event stream into one reply. */
class AnthropicStream(private val onText: (String) -> Unit) {
    private val text = StringBuilder()
    private class Block(val type: String, val id: String, val name: String, val json: StringBuilder = StringBuilder())
    private val blocks = sortedMapOf<Int, Block>()
    private var inputTokens = 0
    private var outputTokens = 0
    private var stop: String? = null
    private var whole: LlmReply? = null

    fun feed(line: String): Boolean {
        if (!line.startsWith("data:")) return true
        val json = runCatching { JSONObject(line.removePrefix("data:").trim()) }.getOrNull() ?: return true
        when (json.optString("type")) {
            "message" -> { whole = AnthropicProtocol.parse(json).also { if (it.text.isNotEmpty()) onText(it.text) }; return false }
            "error" -> throw LlmException(errorMessage(json) ?: "Stream error")
            "message_start" -> json.optJSONObject("message")?.optJSONObject("usage")?.let { inputTokens = it.optInt("input_tokens") }
            "content_block_start" -> json.optJSONObject("content_block")?.let { b ->
                blocks[json.optInt("index")] = Block(b.optString("type"), b.optString("id"), b.optString("name"))
            }
            "content_block_delta" -> json.optJSONObject("delta")?.let { d ->
                when (d.optString("type")) {
                    "text_delta" -> d.optString("text").let { if (it.isNotEmpty()) { text.append(it); onText(it) } }
                    "input_json_delta" -> blocks[json.optInt("index")]?.json?.append(d.optString("partial_json"))
                }
            }
            "message_delta" -> {
                json.optJSONObject("delta")?.optString("stop_reason")?.takeIf { it.isNotBlank() && it != "null" }?.let { stop = it }
                json.optJSONObject("usage")?.let { outputTokens = it.optInt("output_tokens", outputTokens) }
            }
            "message_stop" -> return false
        }
        return true
    }

    fun result(): LlmReply = whole ?: LlmReply(text.toString(),
        blocks.values.filter { it.type == "tool_use" }.map { ToolCall(it.id, it.name, it.json.toString().ifBlank { "{}" }) },
        inputTokens, outputTokens, stop)
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
            models += ModelInfo(id, free, item.optInt("context_length", item.optInt("context_window", item.optInt("inputTokenLimit", 0))),
                ModelCaps.visionFromMetadata(item))
        }
        return models.distinctBy { it.id }
            .filter { model -> nonChat.none { model.id.contains(it, ignoreCase = true) } || model.id.contains("vision", true) }
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
class LlmClient(private val connectTimeoutMs: Int = 20_000, private val readTimeoutMs: Int = 300_000) {

    suspend fun listModels(spec: ProviderSpec, key: String, baseUrl: String): List<ModelInfo> {
        val url = spec.modelsUrl ?: (baseUrl.trimEnd('/') + "/models")
        val body = send("GET", url, headers(spec, key), null)
        return ModelListParser.parse(body).ifEmpty { throw LlmException("The provider returned no chat models") }
    }

    /** One model turn. With [onText] the answer is streamed; providers that refuse streaming fall back to a normal call. */
    suspend fun chat(spec: ProviderSpec, key: String, baseUrl: String, model: String, system: String,
        messages: List<ChatMessage>, tools: List<ToolSpec>, onText: ((String) -> Unit)? = null): LlmReply {
        val base = baseUrl.trimEnd('/')
        if (onText != null) {
            try { return stream(spec, key, base, model, system, messages, tools, onText) }
            catch (error: LlmException) {
                val message = error.message.orEmpty()
                if (!(message.startsWith("HTTP 400") || message.startsWith("HTTP 422")) || !message.contains("stream", true)) throw error
            }
        }
        return when (spec.style) {
            ApiStyle.OpenAI -> OpenAiProtocol.parse(JSONObject(send("POST", "$base/chat/completions",
                headers(spec, key), OpenAiProtocol.request(model, system, messages, tools).toString())))
            ApiStyle.Anthropic -> AnthropicProtocol.parse(JSONObject(send("POST", "$base/messages",
                headers(spec, key), AnthropicProtocol.request(model, system, messages, tools).toString())))
        }
    }

    private suspend fun stream(spec: ProviderSpec, key: String, base: String, model: String, system: String,
        messages: List<ChatMessage>, tools: List<ToolSpec>, onText: (String) -> Unit): LlmReply = when (spec.style) {
        ApiStyle.OpenAI -> {
            val parser = OpenAiStream(onText)
            val body = OpenAiProtocol.request(model, system, messages, tools, stream = true)
            if (spec.id == "openai") body.put("stream_options", JSONObject().put("include_usage", true))
            sendLines("$base/chat/completions", headers(spec, key, stream = true), body.toString(), parser::feed)
            parser.result()
        }
        ApiStyle.Anthropic -> {
            val parser = AnthropicStream(onText)
            sendLines("$base/messages", headers(spec, key, stream = true),
                AnthropicProtocol.request(model, system, messages, tools, stream = true).toString(), parser::feed)
            parser.result()
        }
    }

    /** Speech to text with an OpenAI-compatible `/audio/transcriptions` endpoint (OpenAI, Groq). */
    suspend fun transcribe(spec: ProviderSpec, key: String, baseUrl: String, model: String, audio: File, language: String?,
        prompt: String? = null): String {
        val form = Multipart()
        form.field("model", model)
        form.field("response_format", "json")
        if (!language.isNullOrBlank()) form.field("language", language)
        if (!prompt.isNullOrBlank()) form.field("prompt", prompt)
        form.file("file", audio.name, "audio/mp4", audio.readBytes())
        val headers = headers(spec, key).toMutableMap()
        headers["Content-Type"] = "multipart/form-data; boundary=${form.boundary}"
        val text = sendBytes("POST", baseUrl.trimEnd('/') + "/audio/transcriptions", headers, form.finish()).toString(Charsets.UTF_8)
        return runCatching { JSONObject(text).optString("text") }.getOrDefault(text).trim()
    }

    /** Text to speech with OpenAI's `/audio/speech`; returns MP3 bytes. */
    suspend fun speech(spec: ProviderSpec, key: String, baseUrl: String, model: String, voice: String, text: String, speed: Float): ByteArray {
        val body = JSONObject().put("model", model).put("voice", voice).put("input", text)
            .put("response_format", "mp3").put("speed", speed.toDouble().coerceIn(0.25, 4.0))
        val headers = headers(spec, key).toMutableMap()
        headers["Accept"] = "audio/mpeg"
        return sendBytes("POST", baseUrl.trimEnd('/') + "/audio/speech", headers, body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun headers(spec: ProviderSpec, key: String, stream: Boolean = false): Map<String, String> {
        val result = linkedMapOf("Content-Type" to "application/json", "Accept" to if (stream) "text/event-stream" else "application/json")
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

    private suspend fun sendLines(url: String, headers: Map<String, String>, body: String, onLine: (String) -> Boolean) {
        require(url.startsWith("https://") || url.startsWith("http://")) { "Set a valid server URL first" }
        val connection = URL(url).openConnection() as HttpURLConnection
        coroutineScope {
            val request = async(Dispatchers.IO) {
                connection.requestMethod = "POST"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = readTimeoutMs
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val text = connection.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                    val detail = runCatching { errorMessage(JSONObject(text)) }.getOrNull() ?: text.take(300).ifBlank { connection.responseMessage ?: "" }
                    throw LlmException("HTTP $code: $detail")
                }
                val contentType = connection.contentType.orEmpty()
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    if (!contentType.contains("event-stream") && contentType.contains("json")) {
                        // Some servers ignore "stream" and answer with one JSON body.
                        val whole = reader.readText()
                        onLine("data: $whole"); onLine("data: [DONE]")
                        return@use
                    }
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (!onLine(line)) break
                    }
                }
            }
            try { request.await() }
            catch (error: CancellationException) { connection.disconnect(); throw error }
            finally { connection.disconnect() }
        }
    }

    private suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): String =
        sendBytes(method, url, headers, body?.toByteArray(Charsets.UTF_8)).toString(Charsets.UTF_8)

    private suspend fun sendBytes(method: String, url: String, headers: Map<String, String>, body: ByteArray?): ByteArray {
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
                    connection.outputStream.use { it.write(body) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                if (code !in 200..299) {
                    val text = bytes.toString(Charsets.UTF_8)
                    val detail = runCatching { errorMessage(JSONObject(text)) }.getOrNull()
                        ?: text.take(300).ifBlank { connection.responseMessage ?: "" }
                    throw LlmException("HTTP $code: $detail")
                }
                bytes
            }
            try { request.await() }
            catch (error: CancellationException) { connection.disconnect(); throw error }
            finally { connection.disconnect() }
        }
    }
}

/** A small multipart/form-data writer for file uploads. */
class Multipart(val boundary: String = "----codriod" + System.nanoTime().toString(16)) {
    private val out = java.io.ByteArrayOutputStream()
    private fun line(text: String = "") { out.write((text + "\r\n").toByteArray(Charsets.UTF_8)) }
    fun field(name: String, value: String) {
        line("--$boundary"); line("Content-Disposition: form-data; name=\"$name\""); line(); line(value)
    }
    fun file(name: String, fileName: String, type: String, bytes: ByteArray) {
        line("--$boundary"); line("Content-Disposition: form-data; name=\"$name\"; filename=\"$fileName\""); line("Content-Type: $type"); line()
        out.write(bytes); line()
    }
    fun finish(): ByteArray { line("--$boundary--"); return out.toByteArray() }
}
