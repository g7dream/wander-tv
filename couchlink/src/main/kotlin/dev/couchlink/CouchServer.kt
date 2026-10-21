package dev.couchlink

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Serves static files for GET requests. Return null for 404. Paths start with "/". */
fun interface AssetSource {
    fun open(path: String): ByteArray?
}

interface CouchListener {
    fun onOpen(clientId: Int, remoteAddress: String)
    fun onText(clientId: Int, text: String)
    fun onClose(clientId: Int)
}

/**
 * Zero-dependency HTTP/1.1 + WebSocket (RFC 6455) server on a plain ServerSocket.
 * GET /ws with an Upgrade header becomes a WebSocket; any other GET is served from [assets].
 * Listener callbacks run on connection threads; callers hop to their UI thread themselves.
 *
 * Threading: one accept thread, one reader thread per connection and one writer thread per WebSocket
 * client. [send] and [broadcast] only enqueue, so they never block the caller (safe on a UI thread).
 * A housekeeping timer pings every client every 15 s and drops clients that stay silent for 45 s.
 *
 * Paths handed to [assets] are already decoded and normalized: they start with "/", contain no "."
 * or ".." segments, no backslashes, colons or control characters, and never carry a query string.
 */
class CouchServer(
    val port: Int,
    private val assets: AssetSource,
    private val listener: CouchListener,
    private val log: (String) -> Unit = {},
) {
    private val lifecycle = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var housekeeper: ScheduledExecutorService? = null
    private val clients = ConcurrentHashMap<Int, Connection>()
    private val openSockets = AtomicInteger()
    private val nextClientId = AtomicInteger(1)

    // Tunables. Internal so tests can shrink the timers; production code uses the defaults.
    internal var pingIntervalMs = 15_000L
    internal var idleTimeoutMs = 45_000L
    internal var closeGraceMs = 2_000L
    internal var sameOriginOnly = true

    /** The bound port (useful when constructed with port 0), or -1 when not running. */
    internal val localPort: Int get() = serverSocket?.localPort ?: -1

    /** Connected WebSocket clients (plain HTTP requests are not counted). */
    val clientCount: Int get() = clients.size

    /**
     * Binds 0.0.0.0:[port] and starts serving. Calling it while already running does nothing.
     * @throws IOException when the port is busy or cannot be bound.
     */
    @Throws(IOException::class)
    fun start() {
        synchronized(lifecycle) {
            if (serverSocket != null) return
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(0, 0, 0, 0)), port), BACKLOG)
            } catch (e: IOException) {
                closeQuietly(ss)
                say("cannot listen on port $port: ${e.message}")
                throw e
            }
            serverSocket = ss
            val tick = (pingIntervalMs / 2).coerceIn(25L, 1_000L)
            housekeeper = ScheduledThreadPoolExecutor(1) { r -> daemon(r, "couchlink-housekeeper") }.also {
                it.scheduleWithFixedDelay({ housekeep() }, tick, tick, TimeUnit.MILLISECONDS)
            }
            daemon({ acceptLoop(ss) }, "couchlink-accept-${ss.localPort}").start()
            say("listening on 0.0.0.0:${ss.localPort}")
        }
    }

    /**
     * Stops accepting, says goodbye (close 1001) to every WebSocket client and closes all sockets.
     * Safe to call more than once and from any thread; it does not wait for clients to answer.
     * [CouchListener.onClose] still fires for each client, from its connection thread.
     */
    fun stop() {
        val goodbye: List<Connection>
        synchronized(lifecycle) {
            val ss = serverSocket ?: return
            serverSocket = null
            closeQuietly(ss)
            housekeeper?.shutdownNow()
            housekeeper = null
            goodbye = ArrayList(clients.values)
            clients.clear()
        }
        for (c in goodbye) c.sendClose(1001, "Server stopping")
        if (goodbye.isNotEmpty()) {
            // Give writers a moment to flush the close frames, then make sure every socket is gone.
            daemon({
                val deadline = System.currentTimeMillis() + STOP_GRACE_MS
                for (c in goodbye) c.awaitWriter(deadline - System.currentTimeMillis())
                for (c in goodbye) c.terminate()
            }, "couchlink-stop").start()
        }
        say("stopped")
    }

    /** Queues a text message for one client. False when the client is unknown or closing. */
    fun send(clientId: Int, text: String): Boolean {
        val c = clients[clientId] ?: return false
        return c.enqueue(Frames.encode(OP_TEXT, text.toByteArray(Charsets.UTF_8)))
    }

    /** Queues a text message for every connected client (the frame is encoded once). */
    fun broadcast(text: String) {
        if (clients.isEmpty()) return
        val frame = Frames.encode(OP_TEXT, text.toByteArray(Charsets.UTF_8))
        for (c in clients.values) c.enqueue(frame)
    }

    /**
     * Starts the closing handshake with one client ([code] 1000 normal, 4000-4999 app-defined). The
     * socket is dropped after [closeGraceMs] if the client does not answer. False if unknown.
     */
    fun close(clientId: Int, code: Int = 1000, reason: String = ""): Boolean {
        val c = clients[clientId] ?: return false
        c.sendClose(code, reason)
        return true
    }

    // ---------------------------------------------------------------- accept + HTTP

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            val socket = try {
                ss.accept()
            } catch (e: SocketException) {
                if (ss.isClosed) break
                say("accept failed: ${e.message}")
                pause(50)
                continue
            } catch (e: IOException) {
                say("accept failed: ${e.message}")
                pause(50)
                continue
            } catch (t: Throwable) {
                say("accept loop error: $t")
                pause(50)
                continue
            }
            if (openSockets.incrementAndGet() > MAX_SOCKETS) {
                openSockets.decrementAndGet()
                say("too many connections, refusing ${socket.remoteText()}")
                closeQuietly(socket)
                continue
            }
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HEAD_TIMEOUT_MS
                daemon({ serve(socket) }, "couchlink-conn").start()
            } catch (t: Throwable) {
                openSockets.decrementAndGet()
                say("could not start connection thread: $t")
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
            val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
            val request = try {
                Http.readRequest(input) ?: return
            } catch (e: HttpError) {
                respondError(out, e.status, false, e.message)
                return
            }
            handle(socket, input, out, request)
        } catch (e: SocketTimeoutException) {
            // A client that opened a socket and never sent a request. Nothing to say.
        } catch (e: IOException) {
            // Client went away mid-request.
        } catch (t: Throwable) {
            say("connection error: $t")
        } finally {
            openSockets.decrementAndGet()
            closeQuietly(socket)
        }
    }

    private fun handle(socket: Socket, input: InputStream, out: OutputStream, req: Request) {
        val head = req.method == "HEAD"
        if (req.method != "GET" && !head) {
            respondError(out, 405, head, "only GET and HEAD", listOf("Allow" to "GET, HEAD"))
            return
        }
        val path = Http.sanitizePath(req.target)
        if (path == null) {
            respondError(out, 400, head, "bad path ${req.target.take(80)}")
            return
        }
        val wantsUpgrade = Http.hasToken(req.headers["upgrade"], "websocket")
        if (path == WS_PATH) {
            if (!wantsUpgrade) {
                respondError(out, 426, head, "plain request to $WS_PATH", listOf("Upgrade" to "websocket", "Connection" to "Upgrade"))
                return
            }
            upgrade(socket, input, out, req)
            return
        }
        if (wantsUpgrade) {
            respondError(out, 404, head, "WebSocket requested on $path")
            return
        }
        val body = try {
            assets.open(path)
        } catch (t: Throwable) {
            say("asset $path failed: $t")
            respondError(out, 500, head, "asset error")
            return
        }
        if (body == null) {
            respondError(out, 404, head, "no asset $path")
            return
        }
        writeResponse(out, 200, Http.contentType(path, body), body, head)
        say("${req.method} $path 200 (${body.size} B)")
    }

    private fun respondError(
        out: OutputStream,
        status: Int,
        head: Boolean,
        why: String?,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        try {
            writeResponse(out, status, "text/html; charset=utf-8", Http.errorPage(status), head, extra)
        } catch (e: IOException) {
            // The client is already gone.
        }
        say("HTTP $status${if (why != null) ": $why" else ""}")
    }

    private fun writeResponse(
        out: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        head: Boolean,
        extra: List<Pair<String, String>> = emptyList(),
    ) {
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(Http.reason(status)).append("\r\n")
        sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Cache-Control: no-cache\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        for ((k, v) in extra) sb.append(k).append(": ").append(v).append("\r\n")
        if (extra.none { it.first.equals("Connection", ignoreCase = true) }) sb.append("Connection: close\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        if (!head) out.write(body)
        out.flush()
    }

    // ---------------------------------------------------------------- WebSocket

    private fun upgrade(socket: Socket, input: InputStream, out: OutputStream, req: Request) {
        if (req.method != "GET" || !req.version.equals("HTTP/1.1", ignoreCase = true)) {
            respondError(out, 400, req.method == "HEAD", "WebSocket needs GET over HTTP/1.1")
            return
        }
        if (!Http.hasToken(req.headers["connection"], "upgrade")) {
            respondError(out, 400, false, "missing Connection: Upgrade")
            return
        }
        if (req.headers["sec-websocket-version"]?.trim() != "13") {
            respondError(out, 426, false, "unsupported WebSocket version", listOf("Sec-WebSocket-Version" to "13"))
            return
        }
        val key = req.headers["sec-websocket-key"]?.trim()
        if (key == null || !Http.isValidKey(key)) {
            respondError(out, 400, false, "bad Sec-WebSocket-Key")
            return
        }
        if (sameOriginOnly && !Http.sameOrigin(req.headers["origin"], req.headers["host"])) {
            respondError(out, 403, false, "cross-origin WebSocket from ${req.headers["origin"]?.take(80)}")
            return
        }
        val accept = Frames.acceptKey(key)
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"

        val id = nextClientId.getAndIncrement()
        val remote = socket.remoteText()
        val conn = Connection(id, socket, out, remote)
        synchronized(conn.writeLock) {
            out.write(response.toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        socket.soTimeout = 0 // idle clients are handled by the keepalive timer
        val registered = synchronized(lifecycle) {
            if (serverSocket != null) clients[id] = conn
            serverSocket != null
        }
        if (!registered) {
            // stop() raced with this handshake: refuse politely instead of leaking a client.
            conn.sendClose(1001, "Server stopping")
            conn.startWriter()
            conn.awaitWriter(500)
            conn.terminate()
            return
        }
        conn.startWriter()
        say("client $id connected from $remote")
        safely("onOpen") { listener.onOpen(id, remote) }
        try {
            conn.readLoop(input)
        } finally {
            conn.terminate()
            say("client $id closed${conn.closeCode?.let { " ($it)" } ?: ""}")
            safely("onClose") { listener.onClose(id) }
        }
    }

    private fun housekeep() {
        try {
            val now = System.nanoTime()
            for (c in clients.values) c.housekeep(now)
        } catch (t: Throwable) {
            say("housekeeping error: $t")
        }
    }

    /** One WebSocket client: a reader loop (on the connection thread) plus a writer thread and queue. */
    private inner class Connection(
        val id: Int,
        private val socket: Socket,
        private val out: OutputStream,
        private val remote: String,
    ) {
        val writeLock = Any()
        private val queue = LinkedBlockingQueue<ByteArray>()
        private val queuedBytes = AtomicLong()
        private val terminated = AtomicBoolean(false)
        private val closing = AtomicBoolean(false)

        @Volatile private var writer: Thread? = null

        @Volatile private var lastHeard = System.nanoTime()
        @Volatile private var lastPing = System.nanoTime()
        @Volatile private var closeDeadline = 0L
        @Volatile private var closeReceived = false

        /** Close code we sent or received, for logs. */
        @Volatile var closeCode: Int? = null

        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun startWriter() {
            val t = daemon({ writeLoop() }, "couchlink-ws-$id-out")
            writer = t
            t.start()
        }

        fun enqueue(frame: ByteArray): Boolean {
            if (terminated.get() || closing.get()) return false
            return offer(frame)
        }

        private fun offer(frame: ByteArray): Boolean {
            val total = queuedBytes.addAndGet(frame.size.toLong())
            if (total > MAX_QUEUED_BYTES) {
                say("client $id is not reading (${total / 1024} KB queued), dropping it")
                terminate()
                return false
            }
            queue.offer(frame)
            return true
        }

        /** Queues a close frame (once). Nothing else is sent after it. */
        fun sendClose(code: Int, reason: String) {
            if (terminated.get() || !closing.compareAndSet(false, true)) return
            if (closeCode == null) closeCode = code
            closeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeGraceMs)
            offer(Frames.close(code, reason))
        }

        private fun writeLoop() {
            try {
                while (!terminated.get()) {
                    var frame: ByteArray? = queue.take()
                    var wroteClose = false
                    synchronized(writeLock) {
                        while (frame != null) {
                            val f = frame!!
                            out.write(f)
                            queuedBytes.addAndGet(-f.size.toLong())
                            if (Frames.isClose(f)) {
                                wroteClose = true
                                break
                            }
                            frame = queue.poll()
                        }
                        out.flush()
                    }
                    if (wroteClose) {
                        // Our close went out. If theirs already arrived the handshake is done.
                        if (closeReceived) terminate()
                        return
                    }
                }
            } catch (e: InterruptedException) {
                // terminate() interrupts us.
            } catch (e: IOException) {
                terminate()
            } catch (t: Throwable) {
                say("client $id writer error: $t")
                terminate()
            }
        }

        fun awaitWriter(ms: Long) {
            val t = writer ?: return
            if (ms <= 0) return
            try {
                t.join(ms)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** Hard close: removes the client, closes the socket and stops the writer. Idempotent. */
        fun terminate() {
            if (!terminated.compareAndSet(false, true)) return
            clients.remove(id, this)
            closeQuietly(socket)
            writer?.interrupt()
            queue.clear()
        }

        fun housekeep(now: Long) {
            if (terminated.get()) return
            if (closing.get()) {
                if (now - closeDeadline > 0) {
                    say("client $id did not finish closing, dropping it")
                    terminate()
                }
                return
            }
            if (now - lastHeard > TimeUnit.MILLISECONDS.toNanos(idleTimeoutMs)) {
                say("client $id silent for ${TimeUnit.NANOSECONDS.toSeconds(now - lastHeard)} s, dropping it")
                terminate()
                return
            }
            if (now - lastPing >= TimeUnit.MILLISECONDS.toNanos(pingIntervalMs)) {
                lastPing = now
                enqueue(PING_FRAME)
            }
        }

        /** Reads frames until the socket closes or the closing handshake completes. */
        fun readLoop(input: InputStream) {
            var message = ByteArray(4 * 1024)
            var messageLen = 0
            var inMessage = false
            val mask = ByteArray(4)
            val control = ByteArray(125)
            try {
                while (!terminated.get()) {
                    val b0 = input.read()
                    if (b0 < 0) return
                    val b1 = readByte(input)
                    val fin = b0 and 0x80 != 0
                    val opcode = b0 and 0x0F
                    if (b0 and 0x70 != 0) return fail(input, 1002, "Reserved bits are not supported")
                    if (b1 and 0x80 == 0) return fail(input, 1002, "Client frames must be masked")
                    var len = (b1 and 0x7F).toLong()
                    if (len == 126L) {
                        len = ((readByte(input) shl 8) or readByte(input)).toLong()
                    } else if (len == 127L) {
                        len = 0L
                        repeat(8) { len = (len shl 8) or readByte(input).toLong() }
                        if (len < 0) return fail(input, 1002, "Frame length overflow")
                    }
                    val isControl = opcode and 0x08 != 0
                    if (isControl) {
                        if (opcode !in OP_CLOSE..OP_PONG) return fail(input, 1002, "Unknown opcode $opcode")
                        if (!fin || len > 125) return fail(input, 1002, "Bad control frame")
                    } else {
                        when (opcode) {
                            OP_CONT -> if (!inMessage) return fail(input, 1002, "Continuation without a message")
                            OP_TEXT -> if (inMessage) return fail(input, 1002, "New message inside a fragmented one")
                            OP_BINARY -> return fail(input, 1003, "Text messages only")
                            else -> return fail(input, 1002, "Unknown opcode $opcode")
                        }
                        if (messageLen + len > MAX_MESSAGE_BYTES) return fail(input, 1009, "Message too big")
                    }
                    readFully(input, mask, 0, 4)
                    val n = len.toInt()
                    lastHeard = System.nanoTime()
                    if (isControl) {
                        readFully(input, control, 0, n)
                        unmask(control, 0, n, mask)
                        when (opcode) {
                            OP_PING -> enqueue(Frames.encode(OP_PONG, control, 0, n))
                            OP_PONG -> Unit
                            OP_CLOSE -> {
                                onCloseFrame(control, n)
                                return
                            }
                        }
                        continue
                    }
                    if (messageLen + n > message.size) {
                        message = message.copyOf(maxOf(messageLen + n, minOf(message.size * 2, MAX_MESSAGE_BYTES)))
                    }
                    readFully(input, message, messageLen, n)
                    unmask(message, messageLen, n, mask)
                    messageLen += n
                    inMessage = !fin
                    if (fin) {
                        val text = try {
                            decoder.reset()
                            decoder.decode(ByteBuffer.wrap(message, 0, messageLen)).toString()
                        } catch (e: CharacterCodingException) {
                            return fail(input, 1007, "Messages must be UTF-8")
                        }
                        messageLen = 0
                        if (message.size > SHRINK_ABOVE) message = ByteArray(4 * 1024)
                        if (!closing.get()) safely("onText") { listener.onText(id, text) }
                    }
                }
            } catch (e: IOException) {
                // EOF, reset or socket closed by terminate(): the finally in upgrade() cleans up.
            }
        }

        private fun onCloseFrame(payload: ByteArray, n: Int) {
            closeReceived = true
            val code = if (n >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
            if (closeCode == null) closeCode = code
            if (closing.get()) {
                // We started the handshake; their answer completes it.
                return
            }
            val reply = when {
                n == 1 -> 1002
                n == 0 -> 1000
                Frames.isValidCloseCode(code) -> code
                else -> 1002
            }
            sendClose(reply, "")
            awaitWriter(1_000)
        }

        /**
         * Protocol error: send the close frame and let it flush. The rest of the stream can no longer be
         * parsed, so discard whatever the client still sends until it hangs up (or the grace period
         * ends). Closing with unread input would make the OS send a TCP reset, and the client could
         * lose our close frame and never learn why.
         */
        private fun fail(input: InputStream, code: Int, reason: String) {
            say("client $id: $reason (closing $code)")
            sendClose(code, reason)
            awaitWriter(1_000)
            try {
                socket.soTimeout = closeGraceMs.toInt().coerceAtLeast(1)
                val sink = ByteArray(4 * 1024)
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(closeGraceMs)
                while (!terminated.get() && System.nanoTime() - deadline < 0) {
                    if (input.read(sink) < 0) break
                }
            } catch (e: IOException) {
                // Timed out or closed: either way we are done.
            }
        }
    }

    // ---------------------------------------------------------------- utilities

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            say("listener $what threw: $t")
        }
    }

    private fun say(message: String) {
        try {
            log(message)
        } catch (t: Throwable) {
            // Logging must never take the server down.
        }
    }

    private fun daemon(r: Runnable, name: String): Thread = Thread(r, name).apply { isDaemon = true }

    private fun pause(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun Socket.remoteText(): String =
        (remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "unknown"

    internal companion object {
        const val WS_PATH = "/ws"
        const val MAX_MESSAGE_BYTES = 64 * 1024
        const val MAX_SOCKETS = 64
        const val BACKLOG = 50
        const val HEAD_TIMEOUT_MS = 10_000
        const val STOP_GRACE_MS = 300L
        const val MAX_QUEUED_BYTES = 1024L * 1024L
        const val SHRINK_ABOVE = 16 * 1024

        const val OP_CONT = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA

        val PING_FRAME: ByteArray = Frames.encode(OP_PING, "couch".toByteArray(Charsets.US_ASCII))

        fun closeQuietly(c: java.io.Closeable?) {
            try {
                c?.close()
            } catch (t: Throwable) {
                // ignored
            }
        }

        fun readByte(input: InputStream): Int {
            val b = input.read()
            if (b < 0) throw EOFException()
            return b
        }

        fun readFully(input: InputStream, into: ByteArray, off: Int, len: Int) {
            var done = 0
            while (done < len) {
                val r = input.read(into, off + done, len - done)
                if (r < 0) throw EOFException()
                done += r
            }
        }

        fun unmask(data: ByteArray, off: Int, len: Int, mask: ByteArray) {
            for (i in 0 until len) data[off + i] = (data[off + i].toInt() xor mask[i and 3].toInt()).toByte()
        }
    }
}

/** A parsed HTTP request head. Header names are lowercase; repeated headers are joined with ", ". */
internal class Request(val method: String, val target: String, val version: String, val headers: Map<String, String>)

internal class HttpError(val status: Int, message: String) : Exception(message)

/** HTTP parsing helpers, kept pure so they can be unit tested. */
internal object Http {
    private const val MAX_HEAD_BYTES = 16 * 1024
    private const val MAX_HEADERS = 64

    /** Reads one request head. Null when the client closed the socket without sending anything. */
    fun readRequest(input: InputStream): Request? {
        val buf = ByteArrayOutputStream(512)
        var lineLen = 0
        var started = false
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (!started) return null
                throw HttpError(400, "truncated request")
            }
            if (b == '\r'.code) continue
            if (b == '\n'.code) {
                if (!started) continue // tolerate blank lines before the request line
                if (lineLen == 0) break
                lineLen = 0
                buf.write(b)
                continue
            }
            started = true
            lineLen++
            buf.write(b)
            if (buf.size() > MAX_HEAD_BYTES) throw HttpError(400, "request head too large")
        }
        val lines = String(buf.toByteArray(), Charsets.ISO_8859_1).split('\n')
        val parts = lines[0].split(' ')
        if (parts.size != 3) throw HttpError(400, "bad request line")
        val (method, target, version) = parts
        if (method.isEmpty() || method.any { it !in 'A'..'Z' }) throw HttpError(400, "bad method")
        if (!version.startsWith("HTTP/1.")) throw HttpError(400, "unsupported HTTP version")
        val headers = LinkedHashMap<String, String>()
        var last: String? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && last != null) {
                headers[last] = headers[last] + " " + line.trim()
                continue
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400, "bad header line")
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            headers[name] = headers[name]?.let { "$it, $value" } ?: value
            last = name
            if (headers.size > MAX_HEADERS) throw HttpError(400, "too many headers")
        }
        return Request(method, target, version, headers)
    }

    /**
     * Turns a request target into a safe, normalized absolute path, or null when it is not acceptable:
     * bad percent escapes, invalid UTF-8, "..", backslashes, colons, NUL or other control characters.
     */
    fun sanitizePath(target: String): String? {
        var t = target
        val scheme = t.indexOf("://")
        if (scheme in 1..8 && t.substring(0, scheme).all { it.isLetter() }) {
            val slash = t.indexOf('/', scheme + 3)
            t = if (slash < 0) "/" else t.substring(slash)
        }
        val cut = t.indexOfAny(charArrayOf('?', '#'))
        if (cut >= 0) t = t.substring(0, cut)
        if (!t.startsWith("/")) return null
        val decoded = percentDecode(t) ?: return null
        if (decoded.any { it < ' ' || it == '\u007f' || it == '\\' }) return null
        val segments = decoded.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." || it.contains(':') }) return null
        return "/" + segments.joinToString("/")
    }

    private fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val bytes = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                if (i + 2 >= s.length) return null
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                bytes.write((hi shl 4) or lo)
                i += 3
            } else {
                val chunk = c.toString().toByteArray(Charsets.UTF_8)
                bytes.write(chunk, 0, chunk.size)
                i++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    /** True when the comma-separated header [value] contains [token] (case-insensitive). */
    fun hasToken(value: String?, token: String): Boolean =
        value != null && value.split(',').any { it.trim().equals(token, ignoreCase = true) }

    /** A Sec-WebSocket-Key is the base64 of 16 bytes: exactly 22 base64 characters plus "==". */
    fun isValidKey(key: String): Boolean =
        key.length == 24 && key.endsWith("==") && key.substring(0, 22).all { it in BASE64_CHARS }

    /**
     * Browsers always send Origin on WebSocket handshakes. Only same-origin pages (the phone page we
     * served) may connect, so a random web page cannot drive the TV. Non-browser clients send no
     * Origin and are allowed.
     */
    fun sameOrigin(origin: String?, host: String?): Boolean {
        if (origin == null) return true
        if (host == null) return false
        return try {
            val uri = URI(origin.trim())
            val scheme = uri.scheme?.lowercase() ?: return false
            val originHost = uri.host?.removePrefix("[")?.removeSuffix("]")?.lowercase() ?: return false
            val originPort = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
            val h = host.trim()
            val hostName: String
            val hostPort: Int
            if (h.startsWith("[")) {
                val end = h.indexOf(']')
                if (end < 0) return false
                hostName = h.substring(1, end).lowercase()
                hostPort = if (h.length > end + 2 && h[end + 1] == ':') h.substring(end + 2).toIntOrNull() ?: return false else 80
            } else {
                val colon = h.lastIndexOf(':')
                hostName = (if (colon >= 0) h.substring(0, colon) else h).lowercase()
                hostPort = if (colon >= 0) h.substring(colon + 1).toIntOrNull() ?: return false else 80
            }
            originHost == hostName && originPort == hostPort
        } catch (e: Exception) {
            false
        }
    }

    fun contentType(path: String, body: ByteArray): String {
        val name = path.substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (path == "/" || name.isEmpty() || dot < 0) {
            val first = body.firstOrNull { it.toInt().toChar() !in " \t\r\n" }?.toInt()?.toChar()
            return if (first == '<') "text/html; charset=utf-8" else "application/octet-stream"
        }
        return when (name.substring(dot + 1).lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "json", "map" -> "application/json; charset=utf-8"
            "webmanifest" -> "application/manifest+json; charset=utf-8"
            "txt" -> "text/plain; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ico" -> "image/x-icon"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    fun reason(status: Int): String = when (status) {
        101 -> "Switching Protocols"
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        426 -> "Upgrade Required"
        500 -> "Internal Server Error"
        else -> "Error"
    }

    /** A small, self-contained error page in the lamp-lit palette. Never echoes request data. */
    fun errorPage(status: Int): ByteArray {
        val (title, line) = when (status) {
            404 -> "This page wandered off." to "Nothing lives at that address on this TV."
            405 -> "Look, don't poke." to "This TV only answers simple page requests."
            426 -> "Wrong door." to "This address is for the live remote connection."
            403 -> "Not from here." to "Only the remote page served by this TV can connect."
            500 -> "Something slipped." to "The TV dropped that file. Try again in a moment."
            else -> "That got tangled." to "The TV could not understand that request."
        }
        val html = """<!doctype html><html lang="en"><head><meta charset="utf-8">""" +
            """<meta name="viewport" content="width=device-width,initial-scale=1"><title>$status · Wander</title>""" +
            "<style>html,body{height:100%;margin:0}body{display:flex;align-items:center;justify-content:center;" +
            "background:radial-gradient(circle at 15% 0%,rgba(255,181,71,.22),transparent 60%),#120E1C;color:#F7EFE2;" +
            "font:18px/1.5 system-ui,-apple-system,sans-serif;text-align:center}" +
            "main{box-sizing:border-box;width:100%;max-width:440px;padding:32px 24px}" +
            "b{display:block;font-size:13px;letter-spacing:.2em;color:#B9AECC;margin-bottom:14px}" +
            "h1{font:400 32px/1.2 Georgia,serif;margin:0 0 10px;color:#FFB547}p{color:#B9AECC;margin:0 0 28px}" +
            "a{display:inline-block;color:#120E1C;background:#FFB547;padding:14px 26px;border-radius:999px;" +
            "text-decoration:none;font-weight:700}</style></head><body><main><b>$status</b><h1>$title</h1>" +
            "<p>$line</p><a href=\"/\">Back to the remote</a></main></body></html>"
        return html.toByteArray(Charsets.UTF_8)
    }

    private const val BASE64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
}

/** WebSocket framing helpers (server side: frames we send are never masked). */
internal object Frames {
    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    fun encode(opcode: Int, payload: ByteArray, off: Int = 0, len: Int = payload.size): ByteArray {
        val headerLen = when {
            len < 126 -> 2
            len <= 0xFFFF -> 4
            else -> 10
        }
        val frame = ByteArray(headerLen + len)
        frame[0] = (0x80 or opcode).toByte()
        when (headerLen) {
            2 -> frame[1] = len.toByte()
            4 -> {
                frame[1] = 126.toByte()
                frame[2] = (len ushr 8).toByte()
                frame[3] = len.toByte()
            }
            else -> {
                frame[1] = 127.toByte()
                val l = len.toLong()
                for (i in 0 until 8) frame[2 + i] = (l ushr (8 * (7 - i))).toByte()
            }
        }
        System.arraycopy(payload, off, frame, headerLen, len)
        return frame
    }

    fun close(code: Int, reason: String): ByteArray {
        var r = reason.toByteArray(Charsets.UTF_8)
        if (r.size > 123) r = reason.take(40).toByteArray(Charsets.UTF_8)
        val payload = ByteArray(2 + r.size)
        payload[0] = (code ushr 8).toByte()
        payload[1] = code.toByte()
        System.arraycopy(r, 0, payload, 2, r.size)
        return encode(CouchServer.OP_CLOSE, payload)
    }

    fun isClose(frame: ByteArray): Boolean = frame.isNotEmpty() && (frame[0].toInt() and 0x0F) == CouchServer.OP_CLOSE

    fun isValidCloseCode(code: Int): Boolean =
        code in 1000..1003 || code in 1007..1011 || code in 3000..4999

    fun acceptKey(key: String): String =
        Base64.encode(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))
}

/** Standard base64 with padding. Hand-written because java.util.Base64 needs API 26. */
internal object Base64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(data: ByteArray): String {
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8) or (data[i + 2].toInt() and 0xFF)
            out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                .append(ALPHABET[(n ushr 6) and 63]).append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63]).append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                out.append(ALPHABET[(n ushr 18) and 63]).append(ALPHABET[(n ushr 12) and 63])
                    .append(ALPHABET[(n ushr 6) and 63]).append('=')
            }
        }
        return out.toString()
    }
}
