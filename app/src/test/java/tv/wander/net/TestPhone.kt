package tv.wander.net

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * A minimal RFC 6455 client that behaves like the phone page: masked text frames out, JSON in,
 * pings answered. (java.net.http is not on the Android unit-test compile classpath, and couchlink's
 * own tests already cover interop with the JDK client.)
 */
internal class TestPhone(port: Int) : Closeable {
    private val socket = Socket()
    private val input: InputStream
    private val out by lazy { socket.getOutputStream() }
    private val writeLock = Any()

    val messages = LinkedBlockingQueue<JSONObject>()
    val closeCodes = LinkedBlockingQueue<Int>()

    init {
        socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        socket.tcpNoDelay = true
        input = BufferedInputStream(socket.getInputStream())
        val key = java.util.Base64.getEncoder().encodeToString(Random.nextBytes(16))
        val req = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (last4 != 0x0D0A0D0A) {
            val b = input.read()
            if (b < 0) throw EOFException("handshake cut short: $head")
            head.write(b)
            last4 = (last4 shl 8) or b
        }
        check(head.toString(Charsets.ISO_8859_1.name()).startsWith("HTTP/1.1 101")) { "no upgrade: $head" }
        Thread({ readLoop() }, "test-phone").apply { isDaemon = true }.start()
    }

    fun send(text: String) = frame(0x1, text.toByteArray(Charsets.UTF_8))

    fun send(json: JSONObject) = send(json.toString())

    fun hello(name: String?, color: Int?, pid: String? = null) = send(
        JSONObject().put("t", "hello")
            .put("name", name ?: JSONObject.NULL)
            .put("color", color ?: JSONObject.NULL)
            .put("pid", pid ?: JSONObject.NULL),
    )

    /** Next message (of [type], skipping others when given). Fails after [timeoutMs]. */
    fun next(type: String? = null, timeoutMs: Long = 3_000): JSONObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val m = messages.poll(maxOf(left, 1), TimeUnit.MILLISECONDS)
                ?: throw AssertionError("no ${type ?: "message"} within $timeoutMs ms")
            if (type == null || m.optString("t") == type) return m
        }
    }

    fun expectSilence(ms: Long = 250) {
        val m = messages.poll(ms, TimeUnit.MILLISECONDS)
        assertNull("expected nothing, got $m", m)
    }

    /** welcome -> players -> state, in that order, as SPEC section 5 promises. */
    fun expectWelcomeSequence(): JSONObject {
        val welcome = next()
        assertEquals("welcome", welcome.getString("t"))
        assertEquals("players", next().getString("t"))
        assertEquals("state", next().getString("t"))
        return welcome
    }

    override fun close() {
        try {
            socket.close()
        } catch (e: IOException) {
            // ignored
        }
    }

    private fun frame(opcode: Int, payload: ByteArray) {
        val f = ByteArrayOutputStream(payload.size + 14)
        f.write(0x80 or opcode)
        when {
            payload.size < 126 -> f.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                f.write(0x80 or 126)
                f.write(payload.size ushr 8)
                f.write(payload.size and 0xFF)
            }
            else -> {
                f.write(0x80 or 127)
                for (i in 7 downTo 0) f.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        val mask = Random.nextBytes(4)
        f.write(mask)
        for (i in payload.indices) f.write(payload[i].toInt() xor mask[i and 3].toInt())
        synchronized(writeLock) {
            out.write(f.toByteArray())
            out.flush()
        }
    }

    private fun readLoop() {
        try {
            while (true) {
                val b0 = input.read()
                if (b0 < 0) return
                val b1 = input.read()
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
                else if (len == 127L) {
                    len = 0
                    repeat(8) { len = (len shl 8) or input.read().toLong() }
                }
                val payload = ByteArray(len.toInt())
                var done = 0
                while (done < payload.size) {
                    val r = input.read(payload, done, payload.size - done)
                    if (r < 0) return
                    done += r
                }
                when (b0 and 0x0F) {
                    0x1 -> messages.add(JSONObject(String(payload, Charsets.UTF_8)))
                    0x9 -> frame(0xA, payload)
                    0x8 -> {
                        val code = if (payload.size >= 2) ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF) else 1005
                        closeCodes.add(code)
                        try {
                            frame(0x8, payload.copyOf(minOf(payload.size, 2)))
                        } catch (e: IOException) {
                            // already gone
                        }
                        return
                    }
                }
            }
        } catch (e: IOException) {
            // socket closed
        }
    }

    companion object {
        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        /** One HTTP GET over a raw socket: status line, headers and body. */
        fun httpGet(port: Int, path: String): Triple<Int, Map<String, String>, ByteArray> {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 2_000)
                s.soTimeout = 5_000
                s.getOutputStream().write("GET $path HTTP/1.1\r\nHost: tv\r\n\r\n".toByteArray())
                val all = s.getInputStream().readBytes()
                var split = -1
                for (i in 0..all.size - 4) {
                    if (all[i] == '\r'.code.toByte() && all[i + 1] == '\n'.code.toByte() && all[i + 2] == '\r'.code.toByte() && all[i + 3] == '\n'.code.toByte()) {
                        split = i
                        break
                    }
                }
                assertTrue("HTTP head", split > 0)
                val lines = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
                val headers = lines.drop(1).associate {
                    val c = it.indexOf(':')
                    it.substring(0, c).trim().lowercase() to it.substring(c + 1).trim()
                }
                return Triple(lines[0].split(' ')[1].toInt(), headers, all.copyOfRange(split + 4, all.size))
            }
        }
    }
}
