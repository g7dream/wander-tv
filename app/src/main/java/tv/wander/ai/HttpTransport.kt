package tv.wander.ai

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTP exchange, kept tiny so [AiClient] can be tested with a fake transport. */
internal class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val timeoutMs: Int,
)

internal class HttpResponse(val code: Int, val body: ByteArray)

internal fun interface HttpTransport {
    /** Blocking; called on a background thread. Throws IOException on network trouble. */
    @Throws(IOException::class)
    fun execute(request: HttpRequest): HttpResponse
}

/** HttpURLConnection, available on every Fire OS version; no extra dependencies. */
internal object UrlConnectionTransport : HttpTransport {
    private const val MAX_BODY_BYTES = 4 * 1024 * 1024

    override fun execute(request: HttpRequest): HttpResponse {
        val conn = URL(request.url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.method
            conn.connectTimeout = request.timeoutMs
            conn.readTimeout = request.timeoutMs
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.doInput = true
            for ((k, v) in request.headers) conn.setRequestProperty(k, v)
            val body = request.body
            if (body != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val bytes = stream?.use { readCapped(it) } ?: ByteArray(0)
            return HttpResponse(code, bytes)
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("response too large")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
