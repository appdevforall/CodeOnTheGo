package org.appdevforall.sqlitedebugger.mcp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * The MCP endpoint's HTTP side: a small HTTP/1.1 listener on 127.0.0.1 only, apart from the store's
 * own HttpServer, so it's never reachable from another device. Before [McpProtocol] sees a request,
 * it checks who's asking:
 *  - the peer is a loopback address (it can't be otherwise, but it's checked anyway);
 *  - Host names 127.0.0.1 or localhost, and Origin, if sent, does too, so a web page can't reach it
 *    by DNS rebinding;
 *  - Authorization: Bearer <token>, the token shown on the server screen, compared in constant time.
 *
 * POST /mcp carries JSON-RPC; DELETE /mcp ends a session; GET gets 405, since this server never
 * opens an event stream. Each connection has its own thread, as a tool may wait a while; at most
 * [MAX_CONNECTIONS] at once. Plain Kotlin, so unit tests can run it without Android.
 */
class McpHttp(
    private val protocol: McpProtocol,
    /** The current token: read on every request, so a new one takes effect at once. */
    private val token: () -> String,
    private val port: Int = DEFAULT_PORT,
    /** Problems worth a line in the log. Never given the token. */
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val DEFAULT_PORT = 6176
        const val PATH = "/mcp"
        const val MAX_CONNECTIONS = 8
        private const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_BODY_BYTES = 1024 * 1024
        /** A connection with no request for this long is closed. */
        const val IDLE_TIMEOUT_MS = 30_000
        private val STATUS = mapOf(
            200 to "OK", 202 to "Accepted", 204 to "No Content", 400 to "Bad Request", 401 to "Unauthorized",
            403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 413 to "Payload Too Large",
            415 to "Unsupported Media Type", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
            501 to "Not Implemented", 503 to "Service Unavailable",
        )
        private val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost")
    }

    private class Request(val method: String, val target: String, val version: String, val headers: Map<String, String>, val body: ByteArray)
    private class HttpError(val status: Int, message: String) : Exception(message)

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    private val open = AtomicInteger(0)
    private val clients: MutableSet<Socket> = Collections.newSetFromMap(ConcurrentHashMap())

    /** The port actually bound (useful when [port] is 0, as in tests). */
    val boundPort: Int get() = socket?.localPort ?: port

    /** Binds 127.0.0.1:[port]. Throws IOException with a readable message if the port is taken. */
    fun start() {
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 16)
        } catch (e: IOException) {
            try { s.close() } catch (ignored: IOException) { }
            throw IOException("Can't listen on 127.0.0.1:$port: ${e.message}", e)
        }
        socket = s
        running = true
        thread(name = "mcp-accept", isDaemon = true) { acceptLoop(s) }
    }

    /** Stops listening and closes every connection. */
    fun stop() {
        running = false
        try { socket?.close() } catch (ignored: IOException) { }
        socket = null
        clients.forEach { close(it) }
        clients.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running && !s.isClosed) {
            val c = try { s.accept() } catch (e: IOException) {
                if (running && !s.isClosed) log("couldn't accept a connection: ${e.message}")
                continue
            }
            if (!c.inetAddress.isLoopbackAddress) { close(c); continue }
            if (open.incrementAndGet() > MAX_CONNECTIONS) {
                open.decrementAndGet()
                try {
                    c.soTimeout = 1000
                    respond(c.getOutputStream(), 503, rpcError("Too many connections at once; try again."), emptyMap(), close = true)
                } catch (ignored: IOException) { }
                close(c)
                continue
            }
            clients += c
            thread(name = "mcp-connection", isDaemon = true) {
                try {
                    serve(c)
                } catch (ignored: IOException) {
                    // The client went away.
                } catch (e: Exception) {
                    log("a connection failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    clients -= c
                    close(c)
                    open.decrementAndGet()
                }
            }
        }
    }

    private fun serve(c: Socket) {
        c.soTimeout = IDLE_TIMEOUT_MS
        c.tcpNoDelay = true
        val input = BufferedInputStream(c.getInputStream())
        val out = c.getOutputStream()
        while (running) {
            val req = try {
                readRequest(input) ?: return
            } catch (e: SocketTimeoutException) {
                return
            } catch (e: HttpError) {
                respond(out, e.status, rpcError(e.message ?: "Bad request."), emptyMap(), close = true)
                return
            }
            if (!handle(req, out)) return
        }
    }

    /** Answers one request. Returns whether the connection stays open for another. */
    private fun handle(r: Request, out: OutputStream): Boolean {
        val close = r.version == "HTTP/1.0" || r.headers["connection"]?.lowercase()?.contains("close") == true
        fun send(status: Int, body: String?, headers: Map<String, String> = emptyMap()): Boolean {
            respond(out, status, body, headers, close)
            return !close
        }
        if (!hostAllowed(r.headers["host"])) return send(403, rpcError("The Host header must be 127.0.0.1 or localhost."))
        val origin = r.headers["origin"]
        if (origin != null && !originAllowed(origin)) return send(403, rpcError("Requests from web pages aren't accepted."))
        if (r.target.substringBefore('?') != PATH) return send(404, rpcError("Not found: the MCP endpoint is $PATH."))
        if (!authorized(r.headers["authorization"])) {
            return send(401, rpcError("Missing or wrong token. Code on the Go passes it to its Agent; send it as Authorization: Bearer <token>."),
                mapOf("WWW-Authenticate" to "Bearer realm=\"SQLite debugger\""))
        }
        val session = r.headers["mcp-session-id"]
        val reply = when (r.method) {
            "POST" -> {
                val type = r.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase()
                if (type != null && type != "application/json") return send(415, rpcError("Send the request as application/json."))
                try {
                    protocol.post(String(r.body, Charsets.UTF_8), session, r.headers["mcp-protocol-version"])
                } catch (e: Exception) {
                    log("a request failed: ${e.javaClass.simpleName}: ${e.message}")
                    McpProtocol.Reply(500, rpcError("The server failed: ${e.message ?: e.javaClass.simpleName}"))
                }
            }
            "DELETE" -> protocol.delete(session)
            "GET" -> return send(405, rpcError("This server doesn't offer an event stream: use POST."), mapOf("Allow" to "POST, DELETE"))
            else -> return send(405, rpcError("Use POST."), mapOf("Allow" to "POST, DELETE"))
        }
        return send(reply.status, reply.body, reply.headers)
    }

    // ---------------------------------------------------------------- checks

    /** 127.0.0.1 or localhost, on any port: adb forward may bring the request in from another port. */
    private fun hostAllowed(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        val name = if (h.count { it == ':' } == 1) h.substringBefore(':') else h
        if (h.count { it == ':' } == 1 && h.substringAfter(':').let { it.isEmpty() || it.any { ch -> !ch.isDigit() } }) return false
        return name in LOOPBACK_NAMES
    }

    private fun originAllowed(origin: String): Boolean = try {
        val u = URI(origin.trim())
        (u.scheme == "http" || u.scheme == "https") && u.host?.lowercase() in LOOPBACK_NAMES
    } catch (e: Exception) { false }

    private fun authorized(header: String?): Boolean {
        val expected = token()
        if (header == null || expected.isEmpty()) return false
        val h = header.trim()
        if (!h.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return false
        return MessageDigest.isEqual(h.substring(7).trim().toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- reading and writing HTTP

    /** One request, or null if the connection closed before one began. */
    private fun readRequest(input: InputStream): Request? {
        var budget = MAX_HEADER_BYTES
        fun line(): String? {
            val b = ByteArrayOutputStream()
            while (true) {
                val ch = input.read()
                if (ch < 0) { if (b.size() == 0) return null else throw HttpError(400, "The request ended early.") }
                if (--budget < 0) throw HttpError(431, "The request's headers are too long.")
                if (ch == '\n'.code) break
                b.write(ch)
            }
            return b.toString("ISO-8859-1").removeSuffix("\r")
        }
        var first = line() ?: return null
        while (first.isEmpty()) first = line() ?: return null   // stray line ends between requests
        val parts = first.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpError(400, "That isn't an HTTP/1.x request.")
        val headers = HashMap<String, String>()
        while (true) {
            val l = line() ?: throw HttpError(400, "The request ended early.")
            if (l.isEmpty()) break
            val colon = l.indexOf(':')
            if (colon <= 0) throw HttpError(400, "A header line without a colon.")
            val name = l.substring(0, colon).trim().lowercase()
            val value = l.substring(colon + 1).trim()
            // A repeated header is joined, so two Host headers fail the Host check.
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
        }
        return Request(parts[0], parts[1], parts[2], headers, readBody(input, headers))
    }

    private fun readBody(input: InputStream, h: Map<String, String>): ByteArray {
        val te = h["transfer-encoding"]
        if (te != null) {
            if (!te.equals("chunked", ignoreCase = true)) throw HttpError(501, "Transfer-Encoding $te isn't supported.")
            if (h.containsKey("content-length")) throw HttpError(400, "Both Content-Length and Transfer-Encoding were sent.")
            return readChunked(input)
        }
        val len = h["content-length"] ?: return ByteArray(0)
        val n = len.toLongOrNull()?.takeIf { it >= 0 } ?: throw HttpError(400, "Content-Length isn't a number.")
        if (n > MAX_BODY_BYTES) throw HttpError(413, "The request is larger than ${MAX_BODY_BYTES / 1024} KB.")
        return readExactly(input, n.toInt())
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = shortLine(input, 1024) ?: throw HttpError(400, "The request ended early.")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)?.takeIf { it >= 0 } ?: throw HttpError(400, "A bad chunk size.")
            if (out.size().toLong() + size > MAX_BODY_BYTES) throw HttpError(413, "The request is larger than ${MAX_BODY_BYTES / 1024} KB.")
            if (size == 0) {
                // Trailers, if any, up to a blank line.
                var lines = 0
                while (true) {
                    val t = shortLine(input, 1024) ?: break
                    if (t.isEmpty()) break
                    if (++lines > 32) throw HttpError(431, "Too many trailers.")
                }
                return out.toByteArray()
            }
            out.write(readExactly(input, size))
            if (shortLine(input, 2) != "") throw HttpError(400, "A chunk without its line end.")
        }
    }

    private fun shortLine(input: InputStream, max: Int): String? {
        val b = ByteArrayOutputStream()
        var left = max
        while (true) {
            val ch = input.read()
            if (ch < 0) return if (b.size() == 0) null else throw HttpError(400, "The request ended early.")
            if (--left < 0) throw HttpError(400, "A line in the request is too long.")
            if (ch == '\n'.code) break
            b.write(ch)
        }
        return b.toString("ISO-8859-1").removeSuffix("\r")
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            if (r < 0) throw HttpError(400, "The request ended early.")
            off += r
        }
        return b
    }

    private fun respond(out: OutputStream, status: Int, body: String?, headers: Map<String, String>, close: Boolean) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = StringBuilder("HTTP/1.1 $status ${STATUS[status] ?: "Status"}\r\n")
        if (body != null) head.append("Content-Type: application/json; charset=utf-8\r\n")
        if (status != 204) head.append("Content-Length: ${bytes.size}\r\n")
        head.append("Cache-Control: no-store\r\n")
        if (close) head.append("Connection: close\r\n")
        for ((k, v) in headers) head.append(k).append(": ").append(v.replace("\r", "").replace("\n", "")).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (status != 204) out.write(bytes)
        out.flush()
    }

    /** An HTTP-level refusal, as a JSON-RPC error with no id, which MCP clients know how to show. */
    private fun rpcError(message: String): String = JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL)
        .put("error", JSONObject().put("code", McpProtocol.INVALID_REQUEST).put("message", message)).toString()

    private fun close(s: Socket) { try { s.close() } catch (ignored: IOException) { } }
}
