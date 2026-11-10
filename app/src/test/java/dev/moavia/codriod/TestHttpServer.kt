package dev.moavia.codriod

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 server for unit tests. Android unit tests compile against android.jar, which hides
 * the JDK's com.sun.net.httpserver, so this uses plain sockets. One request per connection.
 */
class TestHttpServer(private val handler: (Request) -> Response) : AutoCloseable {
    class Request(val method: String, val path: String, private val headers: Map<String, String>, val body: String) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    class Response(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = socket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port"

    init {
        thread(isDaemon = true, name = "test-http") {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { client.use(::serve) }
            }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream().buffered()
        fun readLine(): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }
        val start = readLine().split(' ')
        if (start.size < 2) return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) { val n = input.read(body, read, length - read); if (n < 0) break; read += n }
        val response = handler(Request(start[0], start[1], headers, String(body, 0, read, Charsets.UTF_8)))
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 ${response.code} X\r\n")
        response.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
        head.append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
        client.getOutputStream().apply { write(head.toString().toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
    }

    override fun close() = socket.close()
}
