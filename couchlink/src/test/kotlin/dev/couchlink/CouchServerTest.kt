package dev.couchlink

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class CouchServerTest {
    private val servers = CopyOnWriteArrayList<CouchServer>()
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val webSockets = CopyOnWriteArrayList<WebSocket>()

    private val files = mapOf(
        "/" to "<!doctype html><title>remote</title>".toByteArray(),
        "/phone.css" to "body{color:#F7EFE2}".toByteArray(),
        "/phone.js" to "console.log('hi')".toByteArray(),
        "/data.json" to "{}".toByteArray(),
        "/icon.svg" to "<svg/>".toByteArray(),
        "/icon.png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
        "/fonts/Young.woff2" to ByteArray(300) { it.toByte() },
        "/fonts/Atkinson.ttf" to ByteArray(10) { 1 },
        "/app.webmanifest" to "{\"name\":\"Wander\"}".toByteArray(),
        "/notes.txt" to "plain".toByteArray(),
    )
    private val requested = Collections.synchronizedList(ArrayList<String>())
    private val assets = AssetSource { path -> requested.add(path); files[path] }

    /** Records every callback; optionally echoes text back. */
    private inner class Recorder(val echo: Boolean = false) : CouchListener {
        lateinit var server: CouchServer
        val opens = LinkedBlockingQueue<Int>()
        val texts = LinkedBlockingQueue<Pair<Int, String>>()
        val closes = LinkedBlockingQueue<Int>()

        override fun onOpen(clientId: Int, remoteAddress: String) {
            opens.add(clientId)
        }

        override fun onText(clientId: Int, text: String) {
            texts.add(clientId to text)
            if (echo) server.send(clientId, text)
        }

        override fun onClose(clientId: Int) {
            closes.add(clientId)
        }
    }

    private fun startServer(listener: Recorder = Recorder(), tune: CouchServer.() -> Unit = {}): CouchServer {
        val s = CouchServer(0, assets, listener, log = { println("[couch] $it") })
        s.tune()
        listener.server = s
        s.start()
        servers.add(s)
        return s
    }

    @After
    fun tearDown() {
        webSockets.forEach { runCatching { it.abort() } }
        sockets.forEach { runCatching { it.close() } }
        servers.forEach { it.stop() }
    }

    // ------------------------------------------------------------------ HTTP

    @Test
    fun servesAssetsWithHeaders() {
        val s = startServer()
        val r = http(s, "GET /phone.css HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(200, r.status)
        assertEquals("text/css; charset=utf-8", r.header("content-type"))
        assertEquals(files["/phone.css"]!!.size.toString(), r.header("content-length"))
        assertEquals("no-cache", r.header("cache-control"))
        assertEquals("close", r.header("connection"))
        assertArrayEquals(files["/phone.css"], r.body)
    }

    @Test
    fun rootAndQueryStrings() {
        val s = startServer()
        val root = http(s, "GET / HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(200, root.status)
        assertEquals("text/html; charset=utf-8", root.header("content-type"))
        val withQuery = http(s, "GET /phone.js?v=3#x HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(200, withQuery.status)
        assertEquals("text/javascript; charset=utf-8", withQuery.header("content-type"))
        assertTrue(requested.contains("/phone.js"))
    }

    @Test
    fun contentTypesByExtension() {
        val s = startServer()
        val expected = mapOf(
            "/data.json" to "application/json; charset=utf-8",
            "/icon.svg" to "image/svg+xml",
            "/icon.png" to "image/png",
            "/fonts/Young.woff2" to "font/woff2",
            "/fonts/Atkinson.ttf" to "font/ttf",
            "/app.webmanifest" to "application/manifest+json; charset=utf-8",
            "/notes.txt" to "text/plain; charset=utf-8",
        )
        for ((path, type) in expected) {
            val r = http(s, "GET $path HTTP/1.1\r\nHost: tv\r\n\r\n")
            assertEquals(path, 200, r.status)
            assertEquals(path, type, r.header("content-type"))
            assertArrayEquals(path, files[path], r.body)
        }
        assertEquals("image/jpeg", Http.contentType("/a.JPG", ByteArray(0)))
        assertEquals("image/x-icon", Http.contentType("/favicon.ico", ByteArray(0)))
        assertEquals("font/woff", Http.contentType("/f.woff", ByteArray(0)))
        assertEquals("application/octet-stream", Http.contentType("/blob", byteArrayOf(1, 2)))
    }

    @Test
    fun missingFileIsAStyled404() {
        val s = startServer()
        val r = http(s, "GET /nope.js HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(404, r.status)
        assertEquals("text/html; charset=utf-8", r.header("content-type"))
        val page = String(r.body, Charsets.UTF_8)
        assertTrue(page.contains("wandered off"))
        assertTrue(page.contains("#120E1C"))
        assertEquals(r.body.size.toString(), r.header("content-length"))
    }

    @Test
    fun pathTraversalIsRejected() {
        val s = startServer()
        val evil = listOf(
            "/../secret.txt",
            "/%2e%2e/secret.txt",
            "/fonts/..%2f..%2fsecret.txt",
            "/fonts/%2E%2E/%2E%2E/etc/passwd",
            "/..\\secret.txt",
            "/%5c..%5csecret.txt",
            "/C:/Windows/win.ini",
            "/bad%00.txt",
            "/bad%zz",
            "/%C3",
            "relative.txt",
        )
        for (p in evil) {
            val r = http(s, "GET $p HTTP/1.1\r\nHost: tv\r\n\r\n")
            assertEquals(p, 400, r.status)
        }
        assertTrue("assets never see a dangerous path: $requested", requested.none { it.contains("..") || it.contains('\\') })
    }

    @Test
    fun sanitizePathNormalizes() {
        assertEquals("/", Http.sanitizePath("/"))
        assertEquals("/a/b/c", Http.sanitizePath("/a/./b//c"))
        assertEquals("/x", Http.sanitizePath("/x?y=../../z"))
        assertEquals("/\u2713 ok", Http.sanitizePath("/%E2%9C%93%20ok"))
        assertEquals("/phone.css", Http.sanitizePath("http://192.168.1.5:8080/phone.css"))
        assertEquals("/", Http.sanitizePath("http://192.168.1.5:8080"))
        assertNull(Http.sanitizePath("/a/../b"))
        assertNull(Http.sanitizePath("/%zz"))
        assertNull(Http.sanitizePath("/%C3%28"))
        assertNull(Http.sanitizePath("/a%0d%0aSet-Cookie:x"))
        assertNull(Http.sanitizePath("*"))
    }

    @Test
    fun headSendsHeadersOnly() {
        val s = startServer()
        val r = http(s, "HEAD /fonts/Young.woff2 HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(200, r.status)
        assertEquals("300", r.header("content-length"))
        assertEquals("font/woff2", r.header("content-type"))
        assertEquals(0, r.body.size)
        val missing = http(s, "HEAD /missing HTTP/1.1\r\nHost: tv\r\n\r\n")
        assertEquals(404, missing.status)
        assertEquals(0, missing.body.size)
    }

    @Test
    fun onlyGetAndHead() {
        val s = startServer()
        for (m in listOf("POST", "PUT", "DELETE", "OPTIONS")) {
            val r = http(s, "$m /phone.css HTTP/1.1\r\nHost: tv\r\nContent-Length: 0\r\n\r\n")
            assertEquals(m, 405, r.status)
            assertEquals("GET, HEAD", r.header("allow"))
        }
        assertEquals(400, http(s, "garbage\r\n\r\n").status)
        assertEquals(400, http(s, "GET /x SPDY/3\r\n\r\n").status)
    }

    @Test
    fun assetSourceErrorsBecome500() {
        val listener = Recorder()
        val s = CouchServer(0, { throw IllegalStateException("disk on fire") }, listener)
        listener.server = s
        s.start()
        servers.add(s)
        assertEquals(500, http(s, "GET /x.css HTTP/1.1\r\nHost: tv\r\n\r\n").status)
        // The server keeps serving after a failing asset.
        assertEquals(500, http(s, "GET /y.css HTTP/1.1\r\nHost: tv\r\n\r\n").status)
    }

    @Test
    fun startThrowsWhenPortIsBusy() {
        val first = startServer()
        val second = CouchServer(first.localPort, assets, Recorder())
        try {
            second.start()
            second.stop()
            fail("second server bound a busy port")
        } catch (expected: IOException) {
            // good
        }
        // The first one is unaffected.
        assertEquals(200, http(first, "GET / HTTP/1.1\r\nHost: tv\r\n\r\n").status)
    }

    @Test
    fun base64MatchesTheJdk() {
        val rnd = Random(42)
        for (n in 0..64) {
            val data = ByteArray(n) { rnd.nextInt().toByte() }
            assertEquals(java.util.Base64.getEncoder().encodeToString(data), Base64.encode(data))
        }
        // RFC 6455 section 1.3 example.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", Frames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    // ------------------------------------------------------------------ handshake

    @Test
    fun handshakeComputesAccept() {
        val listener = Recorder()
        val s = startServer(listener)
        val ws = RawWs(s)
        val head = ws.handshake(key = "dGhlIHNhbXBsZSBub25jZQ==")
        assertTrue(head, head.startsWith("HTTP/1.1 101 "))
        assertTrue(head, head.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo="))
        assertTrue(head.contains("Upgrade: websocket"))
        assertNotNull(listener.opens.poll(3, TimeUnit.SECONDS))
        assertEquals(1, s.clientCount)
    }

    @Test
    fun malformedHandshakesAreRejected() {
        val listener = Recorder()
        val s = startServer(listener)
        val port = s.localPort
        fun req(headers: String) = http(s, "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n$headers\r\n")
        val good = "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"

        assertEquals(426, req("Sec-WebSocket-Version: 13\r\n$good").status) // no Upgrade at all
        assertEquals(400, req("Upgrade: websocket\r\nSec-WebSocket-Version: 13\r\n$good").status) // no Connection
        assertEquals(400, req("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n").status) // no key
        assertEquals(400, req("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: abc\r\n").status)
        val badVersion = req("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 8\r\n$good")
        assertEquals(426, badVersion.status)
        assertEquals("13", badVersion.header("sec-websocket-version"))
        val crossOrigin = req("Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nOrigin: http://evil.example\r\n$good")
        assertEquals(403, crossOrigin.status)
        val wrongPath = http(s, "GET /socket HTTP/1.1\r\nHost: tv\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n$good\r\n")
        assertEquals(404, wrongPath.status)
        val http10 = http(s, "GET /ws HTTP/1.0\r\nHost: tv\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n$good\r\n")
        assertEquals(400, http10.status)

        assertNull("no client was opened", listener.opens.poll(200, TimeUnit.MILLISECONDS))
        assertEquals(0, s.clientCount)
    }

    @Test
    fun sameOriginBrowsersMayConnect() {
        val s = startServer()
        val ws = RawWs(s)
        val head = ws.handshake(extra = "Origin: http://127.0.0.1:${s.localPort}\r\nConnection: keep-alive, Upgrade\r\n", connection = null)
        assertTrue(head, head.startsWith("HTTP/1.1 101 "))
        assertTrue(Http.sameOrigin("http://192.168.1.5:8080", "192.168.1.5:8080"))
        assertTrue(Http.sameOrigin("http://Wander.local", "wander.local:80"))
        assertTrue(Http.sameOrigin("http://[::1]:8080", "[::1]:8080"))
        assertFalse(Http.sameOrigin("null", "192.168.1.5:8080"))
        assertFalse(Http.sameOrigin("http://192.168.1.5:9000", "192.168.1.5:8080"))
        assertFalse(Http.sameOrigin("https://evil.example", "192.168.1.5:8080"))
    }

    @Test
    fun dnsRebindingPagesCannotOpenASocket() {
        val listener = Recorder()
        val s = startServer(listener)
        val port = s.localPort
        val good = "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        // A page on evil.example whose DNS now points at the TV: Origin and Host agree, but it's a name.
        val rebound = http(s, "GET /ws HTTP/1.1\r\nHost: evil.example:$port\r\nOrigin: http://evil.example:$port\r\n$good\r\n")
        assertEquals(403, rebound.status)
        val mdns = http(s, "GET /ws HTTP/1.1\r\nHost: wander.local:$port\r\nOrigin: http://wander.local:$port\r\n$good\r\n")
        assertEquals(403, mdns.status)
        assertNull("no client was opened", listener.opens.poll(200, TimeUnit.MILLISECONDS))

        // The phone page as the QR code opens it (raw IP), localhost, and non-browser clients still work.
        fun statusLine(request: String): String {
            val sock = Socket().also { sockets.add(it) }
            sock.connect(InetSocketAddress("127.0.0.1", port), 2_000)
            sock.soTimeout = 5_000
            sock.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            return sock.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
        }
        val ws = RawWs(s)
        assertTrue(ws.handshake(extra = "Origin: http://127.0.0.1:$port\r\n").startsWith("HTTP/1.1 101 "))
        val local = statusLine("GET /ws HTTP/1.1\r\nHost: localhost:$port\r\nOrigin: http://localhost:$port\r\n$good\r\n")
        assertTrue(local, local.startsWith("HTTP/1.1 101 "))
        val noOrigin = statusLine("GET /ws HTTP/1.1\r\nHost: tv.example:$port\r\n$good\r\n")
        assertTrue(noOrigin, noOrigin.startsWith("HTTP/1.1 101 "))

        assertTrue(Http.isLiteralHost("192.168.1.5:8080"))
        assertTrue(Http.isLiteralHost("10.0.2.15"))
        assertTrue(Http.isLiteralHost("LOCALHOST:8080"))
        assertTrue(Http.isLiteralHost("[::1]:8080"))
        assertTrue(Http.isLiteralHost("[fe80::1%25wlan0]:8080"))
        assertTrue(Http.isLiteralHost("[::ffff:192.168.1.5]"))
        assertFalse(Http.isLiteralHost(null))
        assertFalse(Http.isLiteralHost(""))
        assertFalse(Http.isLiteralHost("evil.example:8080"))
        assertFalse(Http.isLiteralHost("wander.local"))
        assertFalse(Http.isLiteralHost("192.168.1.5.nip.io:8080"))
        assertFalse(Http.isLiteralHost("192.168.1:8080"))
        assertFalse(Http.isLiteralHost("192.168.1.256"))
        assertFalse(Http.isLiteralHost("192.168.1.5:"))
        assertFalse(Http.isLiteralHost("192.168.1.5:99999"))
        assertFalse(Http.isLiteralHost("localhost.evil.example"))
        assertFalse(Http.isLiteralHost("[::1"))
        assertFalse(Http.isLiteralHost("[evil.example]:8080"))
    }

    // ------------------------------------------------------------------ frames

    @Test
    fun echoesTextWithTheJdkClient() {
        val s = startServer(Recorder(echo = true))
        val c = JdkClient(s)
        c.ws.sendText("hello couch \u2728 caf\u00e9", true).join()
        assertEquals("hello couch \u2728 caf\u00e9", c.texts.poll(5, TimeUnit.SECONDS))
        c.ws.sendText("{\"t\":\"move\",\"dx\":12.5,\"dy\":-3}", true).join()
        assertEquals("{\"t\":\"move\",\"dx\":12.5,\"dy\":-3}", c.texts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun reassemblesFragmentedMessages() {
        val listener = Recorder(echo = true)
        val s = startServer(listener)
        val c = JdkClient(s)
        val big = buildString { repeat(40 * 1024) { append(('a' + it % 26)) } }
        val chunks = big.chunked(7_000)
        chunks.forEachIndexed { i, part -> c.ws.sendText(part, i == chunks.lastIndex).join() }
        val (_, got) = listener.texts.poll(5, TimeUnit.SECONDS)!!
        assertEquals(big, got)
        // And the echo (a 16-bit length frame) arrives intact.
        assertEquals(big, c.texts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun seventyKilobyteMessageIsClosedWith1009() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val part = "x".repeat(10 * 1024)
        try {
            for (i in 0 until 7) c.ws.sendText(part, i == 6).get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // The server may hang up before the last fragment goes out.
        }
        assertEquals(1009, c.closes.poll(5, TimeUnit.SECONDS))
        assertNotNull(listener.closes.poll(5, TimeUnit.SECONDS))
        assertTrue("nothing was delivered", listener.texts.isEmpty())
        assertEquals(0, s.clientCount)
    }

    @Test
    fun largeServerMessagesUse64BitLengths() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        val big = "\u00e9".repeat(70_000) // 140 KB of UTF-8, beyond a 16-bit length
        assertTrue(s.send(id, big))
        assertEquals(big, c.texts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun broadcastsToThreeClients() {
        val listener = Recorder()
        val s = startServer(listener)
        val clients = List(3) { JdkClient(s) }
        repeat(3) { assertNotNull(listener.opens.poll(3, TimeUnit.SECONDS)) }
        assertEquals(3, s.clientCount)
        s.broadcast("{\"t\":\"state\",\"mode\":\"home\"}")
        for (c in clients) assertEquals("{\"t\":\"state\",\"mode\":\"home\"}", c.texts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun concurrentSendsStayIntactAndOrderedPerSender() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        val threads = 4
        val each = 200
        val go = CountDownLatch(1)
        val workers = List(threads) { t ->
            Thread {
                go.await()
                repeat(each) { i -> s.send(id, "{\"thread\":$t,\"i\":$i,\"pad\":\"${"z".repeat(i % 50)}\"}") }
            }.apply { start() }
        }
        go.countDown()
        workers.forEach { it.join() }
        val next = IntArray(threads)
        repeat(threads * each) {
            val msg = c.texts.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("missing message $it")
            val m = Regex("""\{"thread":(\d+),"i":(\d+),"pad":"z*"\}""").matchEntire(msg) ?: throw AssertionError("corrupt: $msg")
            val t = m.groupValues[1].toInt()
            assertEquals("per-thread order", next[t], m.groupValues[2].toInt())
            next[t]++
        }
    }

    @Test
    fun closeHandshakeFiresCallbacks() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        c.ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
        assertEquals(id, listener.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(1000, c.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(0, s.clientCount)
        assertFalse(s.send(id, "late"))
    }

    @Test
    fun abruptDisconnectFiresOnClose() {
        val listener = Recorder()
        val s = startServer(listener)
        val ws = RawWs(s)
        ws.handshake()
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        ws.socket.close()
        assertEquals(id, listener.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(0, s.clientCount)
    }

    @Test
    fun serverCanCloseOneClient() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        assertTrue(s.close(id, 4000, "Replaced"))
        assertEquals(4000, c.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(id, listener.closes.poll(5, TimeUnit.SECONDS))
        assertFalse(s.close(id))
    }

    @Test
    fun answersPingsWithPongs() {
        val s = startServer()
        val c = JdkClient(s)
        c.ws.sendPing(ByteBuffer.wrap("lumo".toByteArray())).join()
        assertEquals("lumo", c.pongs.poll(5, TimeUnit.SECONDS))
        // Raw client: the pong carries the same payload, unmasked.
        val raw = RawWs(s)
        raw.handshake()
        raw.sendFrame(0x9, "moth".toByteArray())
        val (op, payload) = raw.readFrame()
        assertEquals(0xA, op)
        assertEquals("moth", String(payload))
    }

    @Test
    fun keepalivePingsAndDropsSilentClients() {
        val listener = Recorder()
        val s = startServer(listener) {
            pingIntervalMs = 100
            idleTimeoutMs = 600
        }
        // A well-behaved client (the JDK answers pings automatically) stays connected.
        val good = JdkClient(s)
        val goodId = listener.opens.poll(3, TimeUnit.SECONDS)!!
        // A raw client that never answers is dropped.
        val raw = RawWs(s)
        raw.handshake()
        val silentId = listener.opens.poll(3, TimeUnit.SECONDS)!!
        val (op, _) = raw.readFrame()
        assertEquals("server pings", 0x9, op)
        assertEquals(silentId, listener.closes.poll(5, TimeUnit.SECONDS))
        Thread.sleep(800)
        assertTrue("the answering client is still here", s.send(goodId, "still here"))
        assertEquals("still here", good.texts.poll(5, TimeUnit.SECONDS))
        assertEquals(1, s.clientCount)
    }

    @Test
    fun rejectsUnmaskedFramesWith1002() {
        val listener = Recorder()
        val s = startServer(listener)
        val raw = RawWs(s)
        raw.handshake()
        raw.sendFrame(0x1, "hi".toByteArray(), masked = false)
        val (op, payload) = raw.readFrame()
        assertEquals(0x8, op)
        assertEquals(1002, closeCode(payload))
        assertNotNull(listener.closes.poll(5, TimeUnit.SECONDS))
        assertTrue(listener.texts.isEmpty())
    }

    @Test
    fun rejectsBinaryWith1003() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        c.ws.sendBinary(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), true).join()
        assertEquals(1003, c.closes.poll(5, TimeUnit.SECONDS))
        assertNotNull(listener.closes.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun rejectsInvalidUtf8With1007() {
        val s = startServer()
        val raw = RawWs(s)
        raw.handshake()
        raw.sendFrame(0x1, byteArrayOf(0xC3.toByte(), 0x28))
        val (op, payload) = raw.readFrame()
        assertEquals(0x8, op)
        assertEquals(1007, closeCode(payload))
    }

    @Test
    fun rawFragmentsWithInterleavedPing() {
        val listener = Recorder()
        val s = startServer(listener)
        val raw = RawWs(s)
        raw.handshake()
        raw.sendFrame(0x1, "Brow".toByteArray(), fin = false)
        raw.sendFrame(0x9, "p".toByteArray())
        raw.sendFrame(0x0, "se tog".toByteArray(), fin = false)
        raw.sendFrame(0x0, "ether".toByteArray(), fin = true)
        assertEquals(0xA, raw.readFrame().first)
        assertEquals("Browse together", listener.texts.poll(5, TimeUnit.SECONDS)!!.second)
        // A continuation with nothing to continue is a protocol error.
        raw.sendFrame(0x0, "stray".toByteArray())
        val (op, payload) = raw.readFrame()
        assertEquals(0x8, op)
        assertEquals(1002, closeCode(payload))
    }

    @Test
    fun clientCloseIsEchoed() {
        val listener = Recorder()
        val s = startServer(listener)
        val raw = RawWs(s)
        raw.handshake()
        raw.sendFrame(0x8, byteArrayOf(0x03, 0xE8.toByte()) + "done".toByteArray())
        val (op, payload) = raw.readFrame()
        assertEquals(0x8, op)
        assertEquals(1000, closeCode(payload))
        assertNotNull(listener.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(-1, raw.input.read()) // and then the server hangs up
    }

    @Test
    fun listenerExceptionsDoNotKillTheConnection() {
        val received = LinkedBlockingQueue<String>()
        lateinit var server: CouchServer
        val listener = object : CouchListener {
            override fun onOpen(clientId: Int, remoteAddress: String) = throw IllegalStateException("boom")
            override fun onText(clientId: Int, text: String) {
                received.add(text)
                if (text == "throw") throw IllegalArgumentException("bad")
                server.send(clientId, "ok:$text")
            }
            override fun onClose(clientId: Int) = throw IllegalStateException("boom")
        }
        server = CouchServer(0, assets, listener)
        server.start()
        servers.add(server)
        val c = JdkClient(server)
        c.ws.sendText("throw", true).join()
        c.ws.sendText("again", true).join()
        assertEquals("ok:again", c.texts.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun stopIsIdempotentAndSaysGoodbye() {
        val listener = Recorder()
        val s = startServer(listener)
        val c = JdkClient(s)
        val id = listener.opens.poll(3, TimeUnit.SECONDS)!!
        val port = s.localPort
        s.stop()
        s.stop()
        assertEquals(0, s.clientCount)
        assertFalse(s.send(id, "after stop"))
        s.broadcast("after stop") // no-op, no crash
        assertEquals(1001, c.closes.poll(5, TimeUnit.SECONDS))
        assertEquals(id, listener.closes.poll(5, TimeUnit.SECONDS))
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
            fail("still accepting after stop")
        } catch (expected: IOException) {
            // good
        }
        // The same port can be reused right away.
        val again = CouchServer(port, assets, Recorder())
        again.start()
        servers.add(again)
        assertEquals(200, http(again, "GET / HTTP/1.1\r\nHost: tv\r\n\r\n").status)
    }

    // ------------------------------------------------------------------ helpers

    private class HttpResult(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        fun header(name: String): String? = headers[name.lowercase()]
    }

    private fun http(s: CouchServer, request: String): HttpResult {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", s.localPort), 2_000)
            sock.soTimeout = 5_000
            sock.getOutputStream().apply { write(request.toByteArray(Charsets.ISO_8859_1)); flush() }
            val all = sock.getInputStream().readBytes()
            val split = indexOf(all, "\r\n\r\n".toByteArray())
            assertTrue("response has a head: ${String(all)}", split >= 0)
            val head = String(all, 0, split, Charsets.ISO_8859_1).split("\r\n")
            val status = head[0].split(' ')[1].toInt()
            val headers = head.drop(1).associate { line ->
                val c = line.indexOf(':')
                line.substring(0, c).trim().lowercase() to line.substring(c + 1).trim()
            }
            return HttpResult(status, headers, all.copyOfRange(split + 4, all.size))
        }
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun closeCode(payload: ByteArray): Int = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)

    /** A hand-rolled client for frames the JDK client refuses to send. */
    private inner class RawWs(s: CouchServer) {
        val socket = Socket().also {
            it.connect(InetSocketAddress("127.0.0.1", s.localPort), 2_000)
            it.soTimeout = 5_000
            sockets.add(it)
        }
        val input: InputStream = socket.getInputStream().buffered()
        private val out = socket.getOutputStream()
        private val port = s.localPort

        fun handshake(key: String = "x3JJHMbDL1EzLkh9GBhXDw==", extra: String = "", connection: String? = "Upgrade"): String {
            val req = "GET /ws HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\n" +
                (if (connection != null) "Connection: $connection\r\n" else "") +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n$extra\r\n"
            out.write(req.toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (last4 != 0x0D0A0D0A) {
                val b = input.read()
                if (b < 0) break
                head.write(b)
                last4 = (last4 shl 8) or b
            }
            return head.toString(Charsets.ISO_8859_1)
        }

        fun sendFrame(opcode: Int, payload: ByteArray, masked: Boolean = true, fin: Boolean = true) {
            val f = ByteArrayOutputStream()
            f.write((if (fin) 0x80 else 0) or opcode)
            val maskBit = if (masked) 0x80 else 0
            when {
                payload.size < 126 -> f.write(maskBit or payload.size)
                payload.size <= 0xFFFF -> {
                    f.write(maskBit or 126); f.write(payload.size ushr 8); f.write(payload.size and 0xFF)
                }
                else -> {
                    f.write(maskBit or 127)
                    for (i in 7 downTo 0) f.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
                }
            }
            if (masked) {
                val mask = byteArrayOf(0x37, 0xFA.toByte(), 0x21, 0x3D)
                f.write(mask)
                f.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() })
            } else {
                f.write(payload)
            }
            out.write(f.toByteArray())
            out.flush()
        }

        fun readFrame(): Pair<Int, ByteArray> {
            val b0 = input.read()
            val b1 = input.read()
            assertTrue("frame header", b0 >= 0 && b1 >= 0)
            assertEquals("server frames are unmasked", 0, b1 and 0x80)
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) { len = 0; repeat(8) { len = (len shl 8) or input.read().toLong() } }
            val payload = ByteArray(len.toInt())
            var done = 0
            while (done < payload.size) {
                val r = input.read(payload, done, payload.size - done)
                assertTrue(r > 0)
                done += r
            }
            return (b0 and 0x0F) to payload
        }
    }

    /** java.net.http.WebSocket with queues for everything it receives. */
    private inner class JdkClient(s: CouchServer) {
        val texts = LinkedBlockingQueue<String>()
        val pongs = LinkedBlockingQueue<String>()
        val closes = LinkedBlockingQueue<Int>()
        val ws: WebSocket = HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:${s.localPort}/ws"), object : WebSocket.Listener {
                private val partial = StringBuilder()

                override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                    partial.append(data)
                    if (last) {
                        texts.add(partial.toString())
                        partial.setLength(0)
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onPong(webSocket: WebSocket, message: ByteBuffer): CompletionStage<*>? {
                    val bytes = ByteArray(message.remaining()).also { message.get(it) }
                    pongs.add(String(bytes))
                    webSocket.request(1)
                    return null
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    closes.add(statusCode)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    closes.add(-1)
                }
            }).get(5, TimeUnit.SECONDS).also { webSockets.add(it) }
    }
}
