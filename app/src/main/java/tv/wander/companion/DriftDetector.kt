package tv.wander.companion

import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.SessionStats
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Spots aimless browsing from timing signals only (no URLs are kept; hosts are hashed in memory).
 * Thresholds adapt per household: each "we're fine" dismissal relaxes the rule that fired.
 *
 * Rules (SPEC section 7), normal / demo:
 * - RAPID_HOPPING: a run of >= 6 page loads within 5 min, each viewed < 20 s / >= 4 loads within 60 s.
 *   The current page counts as part of the run only while it has been open < 20 s, so a family that
 *   has settled on a page is never interrupted.
 * - ENDLESS_SCROLL: >= 3 min / 45 s of scrolling with no click. Pauses longer than 15 s (reading) and
 *   page loads end the streak.
 * - SITE_LOOP: the same host *opened* >= 5 times in 15 min / 3 times in 3 min. "Opened" means arriving
 *   from a different host, so following links inside one site (a Wikipedia rabbit hole) is not a loop.
 * - LATE_NIGHT: local hour >= 23 or < 5 and a session of >= 30 min. Ignored in demo mode. After it
 *   fires it re-arms only after another full session threshold.
 *
 * Global cooldown between signals: 10 min / 45 s. A rule only fires on evidence gathered after its
 * own previous signal. Redirects (two loads within 1.5 s) count as one page. A gap of 45 min with no
 * activity starts a new session.
 *
 * Adaptation: [dismissed] multiplies the fired rule's thresholds by 1.25 (capped at 3x): counts and
 * windows grow together, so the family has to keep it up longer before Lumo speaks. [accepted] moves
 * the multiplier halfway back to 1.0. Multipliers persist through [Store] under `drift.mult.<rule>`.
 *
 * Thread-safe; every public method may be called from any thread.
 */
class DriftDetector(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hourOf: (Long) -> Int = { 12 },
) {
    interface Store {
        fun getFloat(key: String, def: Float): Float
        fun putFloat(key: String, value: Float)
    }

    private val lock = Any()

    @Volatile
    var demoMode: Boolean = false

    /** Off = check() stays quiet. Switching it back on discards evidence gathered while it was off. */
    var enabled: Boolean = true
        set(value) {
            synchronized(lock) {
                if (value && !field) forgetEvidence(clock())
                field = value
            }
        }

    // -------------------------------------------------------------- session state (all guarded by lock)

    private class Load(var at: Long, var host: Long, var entry: Boolean)

    private val loads = ArrayDeque<Load>()
    private val salt = Random.nextLong()
    private var sessionStart = NONE
    private var lastActivity = NONE
    private var pagesTotal = 0
    private var scrollStart = NONE
    private var lastScrollAt = NONE
    private var lastSignalAt = NONE

    /** Per rule: evidence at or before this time was already used by a signal. */
    private val consumedUntil = LongArray(RULES.size) { NONE }
    private var multipliers: FloatArray? = null

    // -------------------------------------------------------------- events

    fun onPageLoad(url: String) {
        val host = hostOf(url) ?: return
        synchronized(lock) {
            val now = clock()
            touch(now)
            val h = hash(host)
            val last = loads.lastOrNull()
            if (last != null && now - last.at in 0 until REDIRECT_MS) {
                // A redirect or a duplicate "page finished": same navigation, keep its original time.
                val before = if (loads.size >= 2) loads[loads.size - 2] else null
                last.host = h
                last.entry = before == null || before.host != h
            } else {
                loads.addLast(Load(now, h, last == null || last.host != h))
                pagesTotal++
            }
            scrollStart = NONE
            prune(now)
        }
    }

    fun onClick() {
        synchronized(lock) {
            touch(clock())
            scrollStart = NONE
        }
    }

    fun onScroll() {
        synchronized(lock) {
            val now = clock()
            touch(now)
            if (scrollStart == NONE || now - lastScrollAt > SCROLL_IDLE_MS) scrollStart = now
            lastScrollAt = now
        }
    }

    // -------------------------------------------------------------- the check

    /** Call every few seconds. Returns a signal at most once per cooldown. */
    fun check(): DriftSignal? = synchronized(lock) {
        if (!enabled) return null
        val now = clock()
        if (lastSignalAt != NONE && now - lastSignalAt < cooldownMs()) return null
        if (sessionStart == NONE) return null
        val signal = checkHopping(now) ?: checkSiteLoop(now) ?: checkScrolling(now) ?: checkLateNight(now)
        if (signal != null) lastSignalAt = now
        signal
    }

    private fun checkHopping(now: Long): DriftSignal? {
        val rule = DriftReason.RAPID_HOPPING
        val m = mult(rule)
        val minLoads = scaled(if (demoMode) 4 else 6, m)
        val window = ((if (demoMode) 60_000L else 5 * MINUTE) * m).toLong()
        val consumed = consumedUntil[rule.ordinal]
        val last = loads.lastOrNull() ?: return null
        if (last.at <= consumed || now - last.at >= HOP_DWELL_MS) return null
        var count = 1
        var first = last.at
        for (i in loads.size - 2 downTo 0) {
            val l = loads[i]
            if (l.at <= consumed) break
            if (loads[i + 1].at - l.at >= HOP_DWELL_MS) break
            if (now - l.at > window) break
            count++
            first = l.at
        }
        if (count < minLoads) return null
        consumedUntil[rule.ordinal] = now
        val spanSec = secs(now - first)
        val each = if (count > 1) secs((last.at - first) / (count - 1)) else spanSec
        return DriftSignal(
            rule,
            Lines.rescueHeadline(rule, count, spanSec),
            "$count pages · ${Lines.shortDuration(spanSec)} · ~${each.coerceAtLeast(1)} s each",
        )
    }

    private fun checkScrolling(now: Long): DriftSignal? {
        val rule = DriftReason.ENDLESS_SCROLL
        if (scrollStart == NONE || now - lastScrollAt > SCROLL_IDLE_MS) return null
        val needed = (if (demoMode) 45_000L else 3 * MINUTE) * mult(rule)
        val streak = lastScrollAt - scrollStart
        if (streak < needed) return null
        consumedUntil[rule.ordinal] = now
        scrollStart = lastScrollAt
        val sec = secs(streak)
        return DriftSignal(
            rule,
            Lines.rescueHeadline(rule, 0, sec),
            "${Lines.shortDuration(sec)} of scrolling · no clicks",
        )
    }

    private fun checkSiteLoop(now: Long): DriftSignal? {
        val rule = DriftReason.SITE_LOOP
        val m = mult(rule)
        val minEntries = scaled(if (demoMode) 3 else 5, m)
        val window = ((if (demoMode) 3 * MINUTE else 15 * MINUTE) * m).toLong()
        val from = maxOf(now - window, consumedUntil[rule.ordinal])
        var bestCount = 0
        var bestFirst = now
        // host hash -> [visits, first visit time]
        val visits = HashMap<Long, LongArray>()
        for (l in loads) {
            if (!l.entry || l.at <= from) continue
            val v = visits.getOrPut(l.host) { longArrayOf(0L, l.at) }
            v[0]++
            if (v[0] > bestCount) {
                bestCount = v[0].toInt()
                bestFirst = v[1]
            }
        }
        if (bestCount < minEntries) return null
        consumedUntil[rule.ordinal] = now
        val spanSec = secs(now - bestFirst)
        return DriftSignal(
            rule,
            Lines.rescueHeadline(rule, bestCount, spanSec),
            "$bestCount visits · same site · ${Lines.shortDuration(spanSec)}",
        )
    }

    private fun checkLateNight(now: Long): DriftSignal? {
        if (demoMode) return null
        val rule = DriftReason.LATE_NIGHT
        if (now - lastActivity > SESSION_GAP_MS) return null
        val hour = safeHour(now)
        if (hour in 5..22) return null
        val since = now - maxOf(sessionStart, consumedUntil[rule.ordinal])
        if (since < 30 * MINUTE * mult(rule)) return null
        consumedUntil[rule.ordinal] = now
        val sessionSec = secs(now - sessionStart)
        return DriftSignal(
            rule,
            Lines.rescueHeadline(rule, pagesTotal, sessionSec, hour),
            "Past ${Lines.clockHour(if (hour >= 23) 23 else hour)} · ${Lines.shortDuration(sessionSec)} tonight · $pagesTotal ${if (pagesTotal == 1) "page" else "pages"}",
        )
    }

    // -------------------------------------------------------------- adaptation

    fun dismissed(signal: DriftSignal) {
        synchronized(lock) {
            val i = signal.reason.ordinal
            val m = mults()
            m[i] = (m[i] * RELAX).coerceAtMost(MAX_MULT)
            persist(signal.reason, m[i])
        }
    }

    fun accepted(signal: DriftSignal) {
        synchronized(lock) {
            val i = signal.reason.ordinal
            val m = mults()
            var v = 1f + (m[i] - 1f) / 2f
            if (v < 1.01f) v = 1f
            m[i] = v
            persist(signal.reason, v)
        }
    }

    /** Current threshold multiplier for [reason] (1.0 = the SPEC defaults, up to 3.0). */
    fun multiplier(reason: DriftReason): Float = synchronized(lock) { mults()[reason.ordinal] }

    // -------------------------------------------------------------- stats

    fun stats(): SessionStats = synchronized(lock) {
        val now = clock()
        val hour = safeHour(now)
        if (sessionStart == NONE) return SessionStats(0, 0, 0, 0, 0, hour, 0)
        val active = now - lastActivity <= SESSION_GAP_MS
        val end = if (active) now else lastActivity
        val sessionMinutes = ((end - sessionStart) / MINUTE).toInt()
        var last5 = 0
        for (l in loads) if (l.at > now - 5 * MINUTE) last5++

        // Average dwell over the last ten pages; the open page counts with its time so far.
        var dwellSum = 0L
        var dwellN = 0
        val startIdx = maxOf(0, loads.size - 10)
        for (i in startIdx until loads.size) {
            val next = if (i + 1 < loads.size) loads[i + 1].at else if (active) now else lastActivity
            dwellSum += (next - loads[i].at).coerceAtLeast(0)
            dwellN++
        }
        val avgDwell = if (dwellN == 0) 0 else secs(dwellSum / dwellN)

        val scroll = if (scrollStart != NONE && now - lastScrollAt <= SCROLL_IDLE_MS) secs(lastScrollAt - scrollStart) else 0

        val counts = HashMap<Long, Int>()
        var repeats = 0
        for (l in loads) {
            if (!l.entry || l.at <= now - 15 * MINUTE) continue
            val c = (counts[l.host] ?: 0) + 1
            counts[l.host] = c
            if (c > repeats) repeats = c
        }
        SessionStats(
            sessionMinutes = sessionMinutes,
            pagesLast5Min = last5,
            avgDwellSec = avgDwell,
            scrollSecondsNoClick = scroll,
            sameSiteRepeats = repeats,
            localHour = hour,
            pagesTotal = pagesTotal,
        )
    }

    // -------------------------------------------------------------- internals

    private fun touch(now: Long) {
        if (sessionStart == NONE || now - lastActivity > SESSION_GAP_MS) {
            sessionStart = now
            loads.clear()
            pagesTotal = 0
            scrollStart = NONE
            lastScrollAt = NONE
            consumedUntil.fill(NONE)
        }
        lastActivity = now
    }

    private fun forgetEvidence(now: Long) {
        consumedUntil.fill(now)
        scrollStart = NONE
    }

    private fun prune(now: Long) {
        val keep = (15 * MINUTE * MAX_MULT).toLong()
        while (loads.isNotEmpty() && (now - loads.first().at > keep || loads.size > MAX_LOADS)) loads.removeFirst()
    }

    private fun cooldownMs(): Long = if (demoMode) 45_000L else 10 * MINUTE

    private fun mult(rule: DriftReason): Float = mults()[rule.ordinal]

    private fun mults(): FloatArray {
        multipliers?.let { return it }
        val loaded = FloatArray(RULES.size) { i ->
            val v = runCatching { store.getFloat(key(RULES[i]), 1f) }.getOrDefault(1f)
            if (v.isNaN() || v.isInfinite()) 1f else v.coerceIn(1f, MAX_MULT)
        }
        multipliers = loaded
        return loaded
    }

    private fun persist(rule: DriftReason, value: Float) {
        runCatching { store.putFloat(key(rule), value) }
    }

    private fun safeHour(now: Long): Int = runCatching { hourOf(now) }.getOrDefault(12).let { ((it % 24) + 24) % 24 }

    private fun scaled(base: Int, m: Float): Int = ceil(base * m - 0.0001f).toInt().coerceAtLeast(base)

    private fun secs(ms: Long): Int = (ms / 1000f).roundToInt().coerceAtLeast(0)

    private fun hash(host: String): Long {
        // FNV-1a over a per-run salt: hosts can be compared, never read back.
        var h = -0x340d631b7bdddcdbL xor salt
        for (ch in host) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h
    }

    companion object {
        private const val NONE = Long.MIN_VALUE
        private const val MINUTE = 60_000L
        private const val HOP_DWELL_MS = 20_000L
        private const val SCROLL_IDLE_MS = 15_000L
        private const val REDIRECT_MS = 1_500L
        private const val SESSION_GAP_MS = 45 * MINUTE
        private const val RELAX = 1.25f
        private const val MAX_MULT = 3f
        private const val MAX_LOADS = 400
        private val RULES = DriftReason.values()

        /** The [Store] key holding the adaptive multiplier for [reason]. Stable across versions. */
        fun key(reason: DriftReason): String = "drift.mult." + reason.name.lowercase()

        /** Local hour of day for a wall-clock time, for the `hourOf` constructor parameter. */
        fun localHour(timeMs: Long): Int = Calendar.getInstance().apply { timeInMillis = timeMs }.get(Calendar.HOUR_OF_DAY)

        /**
         * The host of a web URL, lowercased and without "www."/"m." prefixes, or null for anything that
         * is not a web page (about:blank, data:, file:, javascript:...).
         */
        internal fun hostOf(url: String): String? {
            val u = url.trim()
            if (u.isEmpty()) return null
            var rest = u
            val schemeEnd = u.indexOf("://")
            if (schemeEnd > 0) {
                val scheme = u.substring(0, schemeEnd).lowercase()
                if (scheme != "http" && scheme != "https") return null
                rest = u.substring(schemeEnd + 3)
            } else {
                val colon = u.indexOf(':')
                val slash = u.indexOf('/')
                if (colon > 0 && (slash < 0 || colon < slash)) {
                    val next = u.getOrNull(colon + 1)
                    if (next == null || !next.isDigit()) return null // about:, data:, javascript:, mailto:
                }
            }
            var end = rest.length
            for (c in charArrayOf('/', '?', '#')) {
                val i = rest.indexOf(c)
                if (i in 0 until end) end = i
            }
            var host = rest.substring(0, end)
            val at = host.lastIndexOf('@')
            if (at >= 0) host = host.substring(at + 1)
            host = if (host.startsWith("[")) {
                host.substringBefore(']') + "]"
            } else {
                host.substringBefore(':')
            }
            host = host.lowercase().trim('.')
            for (prefix in arrayOf("www.", "m.", "mobile.")) {
                if (host.startsWith(prefix) && host.indexOf('.', prefix.length) > 0) {
                    host = host.substring(prefix.length)
                    break
                }
            }
            return host.ifEmpty { null }
        }
    }
}
