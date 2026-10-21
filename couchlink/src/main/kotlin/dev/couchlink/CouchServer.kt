package dev.couchlink

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
 */
class CouchServer(
    val port: Int,
    private val assets: AssetSource,
    private val listener: CouchListener,
    private val log: (String) -> Unit = {},
) {
    val clientCount: Int get() = 0

    fun start() {}

    fun stop() {}

    fun send(clientId: Int, text: String): Boolean = false

    fun broadcast(text: String) {}
}
