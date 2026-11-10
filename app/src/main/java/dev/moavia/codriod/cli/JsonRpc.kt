package dev.moavia.codriod.cli

import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RpcError(val code: Int, message: String, val data: Any? = null) : Exception(message)

/**
 * Newline-delimited JSON-RPC over a process's stdin/stdout, both directions:
 * our requests ([request]), our notifications ([notify]), and the CLI's notifications and requests (approvals,
 * file reads…) handled by [onNotification] / [onRequest].
 *
 * Codex's app-server leaves out the "jsonrpc" field; ACP agents use JSON-RPC 2.0 ([jsonrpcField] = true).
 */
class JsonRpcPeer(
    input: InputStream,
    output: OutputStream,
    private val scope: CoroutineScope,
    private val jsonrpcField: Boolean,
    private val onNotification: suspend (method: String, params: JSONObject) -> Unit,
    private val onRequest: suspend (method: String, params: JSONObject) -> Any?,
    private val log: (String) -> Unit = {}
) {
    private val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)
    private val writer: Writer = output.bufferedWriter(Charsets.UTF_8)
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<Any?>>()
    @Volatile var closed = false
        private set
    var onClosed: (() -> Unit)? = null
    private var readJob: Job? = null

    fun start() {
        readJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val message = runCatching { JSONObject(line) }.getOrNull()
                    if (message == null) { log("« $line"); continue }
                    handle(message)
                }
            } catch (_: Exception) {
            } finally {
                closed = true
                pending.values.forEach { it.completeExceptionally(RpcError(-32000, "The CLI stopped")) }
                pending.clear()
                onClosed?.invoke()
            }
        }
    }

    private fun handle(message: JSONObject) {
        val method = message.optString("method").takeIf { it.isNotEmpty() && message.has("method") }
        val hasId = message.has("id") && !message.isNull("id")
        when {
            method != null && hasId -> {
                val id = message.get("id")
                val params = message.optJSONObject("params") ?: JSONObject()
                scope.launch {
                    val reply = JSONObject().put("id", id)
                    try { reply.put("result", onRequest(method, params) ?: JSONObject.NULL) }
                    catch (error: RpcError) { reply.put("error", JSONObject().put("code", error.code).put("message", error.message)) }
                    catch (error: CancellationException) { throw error }
                    catch (error: Exception) { reply.put("error", JSONObject().put("code", -32603).put("message", error.message ?: "error")) }
                    send(reply)
                }
            }
            method != null -> {
                val params = message.optJSONObject("params") ?: JSONObject()
                scope.launch { runCatching { onNotification(method, params) }.onFailure { log("notification $method failed: ${it.message}") } }
            }
            hasId -> {
                val id = message.optLong("id", -1)
                val waiter = pending.remove(id) ?: return
                val error = message.optJSONObject("error")
                if (error != null) waiter.completeExceptionally(RpcError(error.optInt("code"), error.optString("message", "error"), error.opt("data")))
                else waiter.complete(message.opt("result"))
            }
        }
    }

    @Synchronized
    private fun send(message: JSONObject) {
        if (closed) throw RpcError(-32000, "The CLI is not running")
        if (jsonrpcField) message.put("jsonrpc", "2.0")
        writer.write(message.toString())
        writer.write("\n")
        writer.flush()
    }

    /** Sends a request and waits for its result (a JSONObject, JSONArray, primitive or null). */
    suspend fun request(method: String, params: Any? = JSONObject(), timeoutMs: Long = 120_000): Any? {
        val id = ids.getAndIncrement()
        val waiter = CompletableDeferred<Any?>()
        pending[id] = waiter
        val message = JSONObject().put("id", id).put("method", method)
        if (params != null) message.put("params", params)
        withContext(Dispatchers.IO) { send(message) }
        return try { withTimeout(timeoutMs) { waiter.await() } }
        catch (error: TimeoutCancellationException) { pending.remove(id); throw RpcError(-32001, "$method timed out") }
    }

    suspend fun requestObject(method: String, params: Any? = JSONObject(), timeoutMs: Long = 120_000): JSONObject =
        request(method, params, timeoutMs) as? JSONObject ?: JSONObject()

    suspend fun notify(method: String, params: Any? = null) {
        val message = JSONObject().put("method", method)
        if (params != null) message.put("params", params)
        withContext(Dispatchers.IO) { send(message) }
    }

    fun close() {
        closed = true
        readJob?.cancel()
        runCatching { writer.close() }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it)?.toString() }
internal fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null
