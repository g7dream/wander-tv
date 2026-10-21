package tv.wander.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import tv.wander.R

/** Short UI sounds (SoundPool over res/raw). All calls are safe before init and when audio fails. */
object Sfx {
    private enum class Sound(val res: Int, val volume: Float, val minGapMs: Long) {
        FOCUS(R.raw.sfx_focus, 0.2f, 45),
        SELECT(R.raw.sfx_select, 0.45f, 60),
        BACK(R.raw.sfx_back, 0.4f, 60),
        JOIN(R.raw.sfx_join, 0.6f, 120),
        SUCCESS(R.raw.sfx_success, 0.65f, 120),
        FAIL(R.raw.sfx_fail, 0.5f, 120),
        WHOOSH(R.raw.sfx_whoosh, 0.45f, 120),
        POP(R.raw.sfx_pop, 0.45f, 60),
        CHIME(R.raw.sfx_chime, 0.55f, 120),
    }

    private var pool: SoundPool? = null
    private val sampleIds = IntArray(Sound.values().size)
    private val ready = BooleanArray(Sound.values().size)
    private val lastPlayed = LongArray(Sound.values().size)

    /**
     * How many init() calls are still waiting for their release(). The pool is shared by the whole
     * process: a relaunched activity can init before the closing one's onDestroy releases, and that late
     * release must not silence the new one.
     */
    private var users = 0

    /** Creates the pool and starts decoding the sounds in the background; a sound plays once it's loaded. */
    fun init(context: Context) {
        users++
        if (pool != null) return
        try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val p = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(attrs).build()
            ready.fill(false)
            p.setOnLoadCompleteListener { sp, sampleId, status ->
                if (status != 0 || sp !== pool) return@setOnLoadCompleteListener
                val i = sampleIds.indexOf(sampleId)
                if (i >= 0) ready[i] = true
            }
            pool = p
            val app = context.applicationContext
            for (s in Sound.values()) {
                sampleIds[s.ordinal] = runCatching { p.load(app, s.res, 1) }.getOrDefault(0)
            }
        } catch (t: Throwable) {
            Log.w("Wander", "sound effects unavailable", t)
            releasePool()
        }
    }

    fun focus() = play(Sound.FOCUS)
    fun select() = play(Sound.SELECT)
    fun back() = play(Sound.BACK)
    fun join() = play(Sound.JOIN)
    fun success() = play(Sound.SUCCESS)
    fun fail() = play(Sound.FAIL)
    fun whoosh() = play(Sound.WHOOSH)
    fun pop() = play(Sound.POP)
    fun chime() = play(Sound.CHIME)

    /** Pairs with [init]; the sounds are freed only when nobody is left using them. */
    fun release() {
        if (users > 0) users--
        if (users > 0) return
        releasePool()
    }

    private fun releasePool() {
        val p = pool
        pool = null
        ready.fill(false)
        sampleIds.fill(0)
        runCatching { p?.release() }
    }

    private fun play(sound: Sound) {
        val p = pool ?: return
        val i = sound.ordinal
        if (!ready[i] || sampleIds[i] == 0) return
        // Holding the D-pad fires focus changes faster than a tick can ring; keep it from buzzing.
        val now = SystemClock.uptimeMillis()
        if (now - lastPlayed[i] < sound.minGapMs) return
        lastPlayed[i] = now
        try {
            p.play(sampleIds[i], sound.volume, sound.volume, 1, 0, 1f)
        } catch (_: Throwable) {
            // Audio focus loss or a dead audio server must never break the UI.
        }
    }
}
