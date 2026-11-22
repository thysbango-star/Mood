package com.rovian.mood.cast.media

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Something the phone can stream to a receiver. */
interface ServableContent {
    val mimeType: String
    /** Byte length, or null if unknown (then Range requests are not offered). */
    val length: Long?
    fun open(offset: Long): InputStream
}

class ByteArrayContent(private val bytes: ByteArray, override val mimeType: String) : ServableContent {
    override val length: Long get() = bytes.size.toLong()
    override fun open(offset: Long): InputStream =
        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
}

/** Result of interpreting an HTTP Range header against a known length. */
sealed interface ByteRange {
    data object Full : ByteRange
    data class Partial(val start: Long, val endInclusive: Long) : ByteRange {
        val count: Long get() = endInclusive - start + 1
    }
    data object Unsatisfiable : ByteRange

    companion object {
        /** Single-range `bytes=` parsing per RFC 9110. Multi-range requests are served in full. */
        fun parse(header: String?, length: Long?): ByteRange {
            if (header == null || length == null) return Full
            val spec = header.trim()
            if (!spec.startsWith("bytes=", ignoreCase = true)) return Full
            val ranges = spec.substring(6).split(',')
            if (ranges.size != 1) return Full
            val r = ranges[0].trim()
            val dash = r.indexOf('-')
            if (dash < 0) return Full
            val a = r.substring(0, dash).trim()
            val b = r.substring(dash + 1).trim()
            if (length == 0L) return Unsatisfiable
            return when {
                a.isEmpty() -> { // suffix: last N bytes
                    val n = b.toLongOrNull() ?: return Full
                    if (n <= 0) Unsatisfiable else Partial(maxOf(0, length - n), length - 1)
                }
                else -> {
                    val start = a.toLongOrNull() ?: return Full
                    val end = if (b.isEmpty()) length - 1 else (b.toLongOrNull() ?: return Full).coerceAtMost(length - 1)
                    if (start >= length || end < start) Unsatisfiable else Partial(start, end)
                }
            }
        }
    }
}

/**
 * Minimal HTTP/1.1 server that lets a receiver stream media stored on this phone.
 *
 * Security model:
 * - Bound only to the phone's address on the casting Wi-Fi network.
 * - Serves only items Mood explicitly shared, under an unguessable 128-bit
 *   per-session token: `/m/<token>/<id>/<name>`. Everything else is 404.
 * - [revokeAll] rotates the token and forgets every item; the engine calls it
 *   whenever a session ends.
 *
 * Supports GET/HEAD and byte ranges, which video seeking on TVs depends on, and
 * sends the DLNA transfer headers some smart TVs require.
 */
class LocalMediaServer {
    private val random = SecureRandom()
    private val items = ConcurrentHashMap<String, ServableContent>()
    private val ids = AtomicInteger(0)
    @Volatile private var token = newToken()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var boundAddress: InetAddress? = null
    private var workers: ExecutorService? = null

    val isRunning: Boolean get() = server?.isClosed == false

    /** Starts (or keeps) the server on [address]. Returns false if it can't bind. */
    @Synchronized
    fun start(address: InetAddress): Boolean {
        if (isRunning && boundAddress == address) return true
        stop()
        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, 0), 32)
            }
            val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "mood-media-http").apply { isDaemon = true } }
            server = socket
            boundAddress = address
            workers = pool
            Thread({ acceptLoop(socket, pool) }, "mood-media-accept").apply { isDaemon = true }.start()
            Log.i(TAG, "Serving media on ${address.hostAddress}:${socket.localPort}")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Could not start media server", e)
            false
        }
    }

    /** Makes [content] fetchable and returns its URL, or null if the server isn't running. */
    fun share(content: ServableContent, displayName: String): String? {
        val socket = server ?: return null
        val host = boundAddress?.hostAddress ?: return null
        val id = ids.incrementAndGet().toString(36)
        items[id] = content
        return "http://$host:${socket.localPort}/m/$token/$id/${safeName(displayName)}"
    }

    /** Forgets every shared item and invalidates every URL handed out so far. */
    fun revokeAll() {
        items.clear()
        token = newToken()
    }

    @Synchronized
    fun stop() {
        revokeAll()
        runCatching { server?.close() }
        server = null
        boundAddress = null
        workers?.shutdownNow()
        workers = null
    }

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            try {
                pool.execute { handle(client) }
            } catch (e: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                s.tcpNoDelay = true
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val request = readRequest(input) ?: return
                serve(request, out)
                out.flush()
            } catch (e: SocketException) {
                // Receiver closed the connection (normal while seeking).
            } catch (e: IOException) {
                Log.d(TAG, "Media request ended: ${e.message}")
            }
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Request? {
        val head = StringBuilder()
        var last4 = 0
        while (head.length < 16 * 1024) {
            val b = input.read()
            if (b < 0) return null
            head.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
        }
        val lines = head.toString().split("\r\n")
        val parts = lines.firstOrNull()?.split(' ') ?: return null
        if (parts.size < 2) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
        }.toMap()
        return Request(parts[0].uppercase(), parts[1], headers)
    }

    private fun serve(request: Request, out: OutputStream) {
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", mapOf("Allow" to "GET, HEAD", "Content-Length" to "0"))
            return
        }
        val content = lookup(request.path)
        if (content == null) {
            writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))
            return
        }
        val length = content.length
        val range = ByteRange.parse(request.headers["range"], length)
        val common = linkedMapOf(
            "Content-Type" to content.mimeType,
            "Accept-Ranges" to if (length != null) "bytes" else "none",
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-store",
            "transferMode.dlna.org" to if (content.mimeType.startsWith("image/")) "Interactive" else "Streaming",
            "contentFeatures.dlna.org" to DLNA_FEATURES,
        )
        when (range) {
            ByteRange.Unsatisfiable -> {
                writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
            }
            is ByteRange.Partial -> {
                common["Content-Range"] = "bytes ${range.start}-${range.endInclusive}/$length"
                common["Content-Length"] = range.count.toString()
                writeHead(out, 206, "Partial Content", common)
                if (request.method == "GET") content.open(range.start).use { copy(it, out, range.count) }
            }
            ByteRange.Full -> {
                if (length != null) common["Content-Length"] = length.toString()
                writeHead(out, 200, "OK", common)
                if (request.method == "GET") content.open(0).use { copy(it, out, length ?: Long.MAX_VALUE) }
            }
        }
    }

    private fun lookup(path: String): ServableContent? {
        val segments = path.substringBefore('?').trimStart('/').split('/')
        if (segments.size < 3 || segments[0] != "m") return null
        if (!constantTimeEquals(segments[1], token)) return null
        return items[segments[2]]
    }

    private fun writeHead(out: OutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $code $reason\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("Server: Mood/0.2 UPnP/1.0 DLNADOC/1.50\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, out: OutputStream, limit: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = limit
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun safeName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifEmpty { "media" }
        return URLEncoder.encode(cleaned, "UTF-8")
    }

    companion object {
        private const val TAG = "MoodMediaServer"
        const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
    }
}
