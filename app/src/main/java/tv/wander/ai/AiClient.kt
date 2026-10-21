package tv.wander.ai

import org.json.JSONObject
import tv.wander.model.CardDeck
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftSignal
import tv.wander.model.PageText
import tv.wander.model.Quest
import tv.wander.model.SessionStats
import tv.wander.model.Verdict
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Client for the Wander backend (API Gateway -> Lambda -> Bedrock / Polly / DynamoDB).
 * Every call answers exactly once, on the main thread via [post], and falls back to [OfflineAi]
 * when the backend is unset, slow or failing. Page text is only sent from cards/checkQuest.
 *
 * Guarantees:
 * - Never throws and never blocks the caller: work runs on a small daemon pool, results are posted.
 * - Each callback fires exactly once, at the latest when the SPEC timeout expires (8 s cards and quests,
 *   4 s companion and health, 10 s speech); a late network answer after that is dropped.
 * - Falls back on: no URL, network error, timeout, non-2xx, malformed JSON. A guardrail block
 *   ({"blocked":true}) returns a safe result that never echoes the page's own text.
 * - [online] is true when the most recent call that finished got a usable answer from the backend.
 * - Every fallback says why in logcat (tag WanderAi, at most once a minute per route and cause): the HTTP status
 *   with a hint (401 = wrong api_key, 404 = wrong api_url), the exception type, or the timeout. Guardrail blocks
 *   are logged at info level. The key, page text and response bodies are never logged.
 */
class AiClient internal constructor(
    baseUrl: String,
    private val apiKey: String,
    private val post: (() -> Unit) -> Unit,
    private val transport: HttpTransport,
    private val background: (() -> Unit) -> Unit,
    private val timeouts: Timeouts,
) {
    constructor(baseUrl: String, apiKey: String, post: (() -> Unit) -> Unit) :
        this(baseUrl, apiKey, post, UrlConnectionTransport, { block -> IO.execute(block) }, Timeouts.SPEC)

    /** Per-call deadlines in milliseconds (SPEC section 6). */
    internal data class Timeouts(val cards: Int, val quest: Int, val companion: Int, val health: Int, val speak: Int) {
        companion object {
            val SPEC = Timeouts(cards = 8_000, quest = 8_000, companion = 4_000, health = 4_000, speak = 10_000)
        }
    }

    private val base: String? = normalizeBase(baseUrl)

    @Volatile
    private var lastOk = false

    /** Last time (ms, monotonic) each diagnostic line was written, so a failing backend can't flood logcat. */
    private val lastDiagnostic = ConcurrentHashMap<String, Long>()

    init {
        // Say once why the backend will not be used, so "Lumo is offline" has a logcat explanation.
        when {
            base == null && baseUrl.isNotBlank() ->
                diagnose("config", "api_url ignored: it must start with http:// or https://. Using on-device answers.")
            base == null -> diagnose("config", "No api_url set. Using on-device answers.", warn = false)
            apiKey.isBlank() -> diagnose("config", "api_url is set but api_key is empty: the backend will answer HTTP 401.")
        }
    }

    val configured: Boolean get() = base != null

    /** True after the last call reached the backend successfully. */
    val online: Boolean get() = lastOk

    fun health(cb: (Boolean) -> Unit) {
        call("GET", "/health", null, timeouts.health, cb,
            offline = { false },
            parse = { json -> if (json.optBoolean("ok", false)) true else null },
            blocked = { false })
    }

    fun cards(page: PageText, cb: (CardDeck) -> Unit) {
        call("POST", "/cards", { AiJson.cardsRequest(page) }, timeouts.cards, cb,
            offline = { OfflineAi.cards(page) },
            parse = { json -> AiJson.parseCards(json, page) },
            blocked = { json -> AiJson.blockedCards(json, page) })
    }

    fun checkQuest(quest: Quest, page: PageText, alreadyFound: List<String>, cb: (Verdict) -> Unit) {
        val found = alreadyFound.toList()
        call("POST", "/quest/check", { AiJson.checkRequest(quest, page, found) }, timeouts.quest, cb,
            offline = { OfflineAi.checkQuest(quest, page, found) },
            parse = { json -> AiJson.parseVerdict(json) },
            blocked = { json -> AiJson.blockedVerdict(json) })
    }

    fun newQuest(recentTitles: List<String>, cb: (Quest?) -> Unit) {
        val titles = recentTitles.toList()
        call("POST", "/quest/new", { AiJson.newQuestRequest(titles) }, timeouts.quest, cb,
            offline = { OfflineAi.newQuest(titles) },
            parse = { json -> AiJson.parseQuest(json) },
            // Blocked titles: a generic quest that is not derived from them (and the TV can judge offline).
            blocked = { OfflineAi.newQuest(emptyList()) })
    }

    fun advise(stats: SessionStats, signal: DriftSignal, cb: (CompanionAdvice) -> Unit) {
        call("POST", "/companion", { AiJson.companionRequest(stats, signal) }, timeouts.companion, cb,
            offline = { OfflineAi.advise(stats, signal) },
            parse = { json -> AiJson.parseAdvice(json, OfflineAi.advise(stats, signal)) },
            blocked = { OfflineAi.advise(stats, signal) })
    }

    /** MP3 bytes from Amazon Polly, or null (the caller then uses on-device TTS). */
    fun speak(text: String, cb: (ByteArray?) -> Unit) {
        if (text.isBlank()) {
            post { safely { cb(null) } }
            return
        }
        call<ByteArray?>("POST", "/speak", { AiJson.speakRequest(text) }, timeouts.speak, cb,
            offline = { null },
            parse = { json -> decodeAudio(AiJson.str(json, "audio")) },
            blocked = { null })
    }

    // ------------------------------------------------------------------ plumbing

    private inner class Once<T>(private val cb: (T) -> Unit) {
        private val done = AtomicBoolean(false)
        val isDone: Boolean get() = done.get()

        fun deliver(value: T, ok: Boolean) {
            if (!done.compareAndSet(false, true)) return
            lastOk = ok
            try {
                post { safely { cb(value) } }
            } catch (t: Throwable) {
                log("could not post a result", t)
            }
        }
    }

    private fun <T> call(
        method: String,
        path: String,
        body: (() -> JSONObject)?,
        timeoutMs: Int,
        cb: (T) -> Unit,
        offline: () -> T,
        parse: (JSONObject) -> T?,
        blocked: (JSONObject) -> T,
    ) {
        val once = Once(cb)
        val url = base
        if (url == null) {
            runInBackground(once, offline) { once.deliver(offline(), ok = false) }
            return
        }
        val route = "$method $path"
        val deadline: ScheduledFuture<*>? = try {
            TIMER.schedule(Runnable {
                if (!once.isDone) {
                    diagnose("$route timeout", "$route: no answer within $timeoutMs ms, using the on-device answer")
                    once.deliver(offline(), ok = false)
                }
            }, timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            null
        }
        runInBackground(once, offline) {
            var result: T? = null
            var ok = false
            // Why the backend's answer was not used. Never includes the key, the page text or a response body.
            var problem: String? = null
            try {
                val bytes = body?.invoke()?.toString()?.toByteArray(Charsets.UTF_8)
                val response = transport.execute(HttpRequest(method, url + path, headers(bytes != null), bytes, timeoutMs))
                if (response.code in 200..299) {
                    val json = JSONObject(String(response.body, Charsets.UTF_8))
                    if (json.optBoolean("blocked", false)) {
                        result = blocked(json)
                        ok = true
                        diagnose("$route blocked", "$route: the family guardrail blocked this answer; showing the safe one", warn = false)
                    } else {
                        val parsed = parse(json)
                        if (parsed != null) {
                            result = parsed
                            ok = true
                        } else {
                            problem = "HTTP ${response.code} without the expected fields"
                        }
                    }
                } else {
                    problem = "HTTP ${response.code}${statusHint(response.code)}"
                }
            } catch (t: Throwable) {
                // network error, timeout, malformed JSON: the offline answer below covers it
                problem = "failed with ${t.javaClass.simpleName}"
            }
            deadline?.cancel(false)
            if (problem != null) diagnose("$route $problem", "$route: $problem, using the on-device answer")
            @Suppress("UNCHECKED_CAST")
            if (!once.isDone) once.deliver(if (ok) result as T else offline(), ok)
        }
    }

    /**
     * A logcat line (tag WanderAi) explaining why the backend was not used. The same [key] is written at most
     * once a minute. Callers pass only the route, status code or exception type: never the key or any text.
     */
    private fun diagnose(key: String, message: String, warn: Boolean = true) {
        val now = System.nanoTime() / 1_000_000
        val last = lastDiagnostic[key]
        if (last != null && now - last < DIAGNOSTIC_INTERVAL_MS) return
        if (lastDiagnostic.size > 64) lastDiagnostic.clear()
        lastDiagnostic[key] = now
        try {
            if (warn) android.util.Log.w(TAG, message) else android.util.Log.i(TAG, message)
        } catch (ignored: Throwable) {
            // plain JVM without Android logging
        }
    }

    private fun <T> runInBackground(once: Once<T>, offline: () -> T, block: () -> Unit) {
        try {
            background {
                try {
                    block()
                } catch (t: Throwable) {
                    log("ai call failed", t)
                    if (!once.isDone) once.deliver(offline(), ok = false)
                }
            }
        } catch (t: Throwable) {
            // The pool refused the task (it never should): answer anyway.
            log("background pool rejected a call", t)
            if (!once.isDone) once.deliver(offline(), ok = false)
        }
    }

    private fun headers(hasBody: Boolean): Map<String, String> {
        val h = linkedMapOf(
            "x-wander-key" to apiKey,
            "accept" to "application/json",
            "user-agent" to "WanderTV/1.0 (Fire TV)",
        )
        if (hasBody) h["content-type"] = "application/json; charset=utf-8"
        return h
    }

    private fun decodeAudio(b64: String): ByteArray? {
        if (b64.isEmpty()) return null
        return try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT).takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            log("AI callback threw", t)
        }
    }

    private fun log(message: String, t: Throwable?) {
        try {
            android.util.Log.w(TAG, message, t)
        } catch (ignored: Throwable) {
            // plain JVM without Android logging
        }
    }

    private companion object {
        const val TAG = "WanderAi"
        const val DIAGNOSTIC_INTERVAL_MS = 60_000L

        /** What a status code usually means for this backend (API Gateway HTTP API in front of the Lambda). */
        fun statusHint(code: Int): String = when {
            code == 401 -> " (wrong or missing api_key)"
            code == 403 || code == 404 -> " (check api_url: it should be the stack's ApiUrl output)"
            code == 413 -> " (request too large)"
            code == 429 -> " (throttled by API Gateway)"
            code >= 500 -> " (backend error: see the Lambda's CloudWatch logs)"
            else -> ""
        }

        private val DAEMON = ThreadFactory { r -> Thread(r, "wander-ai").apply { isDaemon = true } }

        /** Up to 4 calls in flight; idle threads exit after 30 s. */
        val IO: ExecutorService = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(), DAEMON)
            .apply { allowCoreThreadTimeOut(true) }

        /** Deadlines: answers the caller with the offline result when the backend is too slow. */
        val TIMER = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "wander-ai-timer").apply { isDaemon = true } })
            .apply { removeOnCancelPolicy = true }

        fun normalizeBase(url: String): String? {
            val u = url.trim().trimEnd('/')
            if (u.isEmpty()) return null
            if (!u.startsWith("https://", ignoreCase = true) && !u.startsWith("http://", ignoreCase = true)) return null
            return u
        }
    }
}
