package tv.wander.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import tv.wander.ai.AiClient
import tv.wander.ui.Async
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Reads cards and Lumo's lines aloud: Amazon Polly through the backend, Android TextToSpeech as fallback.
 *
 * - [speak] replaces whatever is being said. With a configured backend it asks [AiClient.speak] for
 *   Polly MP3 bytes and plays them from a temp file in cacheDir; if that fails, returns nothing or
 *   takes longer than 4 s, it falls back to on-device TTS (lazily created; Lumo's pitch 1.15,
 *   rate 1.0, US English).
 * - [onSpeaking] gets true when audio actually starts and false when it ends, fails or is stopped,
 *   always on the main thread, never twice in a row with the same value.
 * - Late answers are ignored: every speak/stop bumps a token, and stale audio is dropped.
 * - Nothing here throws. A Fire TV without a TTS engine simply stays quiet.
 *
 * Call from the main thread.
 */
class Narrator(context: Context, private val ai: AiClient) {
    var onSpeaking: ((Boolean) -> Unit)? = null

    private val app: Context = context.applicationContext ?: context
    private var tts: TextToSpeech? = null
    private var ttsState = TTS_NONE
    private var pendingText: String? = null
    private var pendingToken = 0
    private var player: MediaPlayer? = null
    private var token = 0
    private var speaking = false
    private var closed = false
    private var fileSeq = 0
    private var waitingPolly = false
    private var pollyDeadline: Runnable? = null

    fun speak(text: String) {
        if (closed) return
        val clean = sanitize(text)
        if (clean.isEmpty()) return
        stop()
        val my = token
        if (!ai.configured) {
            speakOnDevice(my, clean)
            return
        }
        // Lumo should never pause awkwardly: if Polly is slow, the on-device voice takes the line.
        waitingPolly = true
        pollyDeadline = Async.mainDelayed(POLLY_WAIT_MS) {
            pollyDeadline = null
            if (waitingPolly && my == token) {
                waitingPolly = false
                speakOnDevice(my, clean)
            }
        }
        try {
            ai.speak(clean) { bytes ->
                // AiClient answers on the main thread; hop there anyway if it ever does not.
                if (Looper.myLooper() == Looper.getMainLooper()) onPolly(my, clean, bytes)
                else Async.main { onPolly(my, clean, bytes) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speak request failed", t)
            endPollyWait()
            speakOnDevice(my, clean)
        }
    }

    fun stop() {
        token++
        pendingText = null
        endPollyWait()
        releasePlayer()
        try {
            tts?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "tts stop failed", t)
        }
        setSpeaking(false)
    }

    fun shutdown() {
        if (closed) return
        stop()
        closed = true
        try {
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "tts shutdown failed", t)
        }
        tts = null
        ttsState = TTS_NONE
        onSpeaking = null
        val dir = app.cacheDir
        Async.io { for (i in 0 until FILES) File(dir, fileName(i)).delete() }
    }

    // ------------------------------------------------------------------ Polly (backend) audio

    private fun endPollyWait() {
        waitingPolly = false
        Async.cancel(pollyDeadline)
        pollyDeadline = null
    }

    private fun onPolly(my: Int, text: String, bytes: ByteArray?) {
        if (closed || my != token || !waitingPolly) return // stale, or the on-device voice already took over
        endPollyWait()
        if (bytes == null || bytes.size < MIN_MP3_BYTES) {
            speakOnDevice(my, text)
            return
        }
        val file = File(app.cacheDir, fileName(fileSeq++ % FILES))
        Async.io {
            val ok = try {
                file.writeBytes(bytes)
                true
            } catch (t: Throwable) {
                Log.w(TAG, "could not write voice file", t)
                false
            }
            Async.main {
                if (closed || my != token) return@main
                if (ok) play(my, text, file) else speakOnDevice(my, text)
            }
        }
    }

    private fun play(my: Int, text: String, file: File) {
        releasePlayer()
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            FileInputStream(file).use { mp.setDataSource(it.fd) }
            mp.setOnPreparedListener { p ->
                if (player !== p || my != token) return@setOnPreparedListener
                try {
                    p.start()
                    setSpeaking(true)
                } catch (t: Throwable) {
                    Log.w(TAG, "voice start failed", t)
                    releasePlayer()
                    speakOnDevice(my, text)
                }
            }
            mp.setOnCompletionListener { p ->
                if (player === p) {
                    releasePlayer()
                    if (my == token) setSpeaking(false)
                }
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.w(TAG, "voice playback error $what/$extra")
                if (player === p) {
                    releasePlayer()
                    if (my == token) speakOnDevice(my, text)
                }
                true
            }
            mp.prepareAsync()
        } catch (t: Throwable) {
            Log.w(TAG, "voice playback failed", t)
            releasePlayer()
            speakOnDevice(my, text)
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        try {
            p.setOnPreparedListener(null)
            p.setOnCompletionListener(null)
            p.setOnErrorListener(null)
            p.stop()
        } catch (_: Throwable) {
            // stop() throws in the idle/preparing states; release() below is what matters.
        }
        try {
            p.release()
        } catch (t: Throwable) {
            Log.w(TAG, "player release failed", t)
        }
    }

    // ------------------------------------------------------------------ on-device TTS

    private fun speakOnDevice(my: Int, text: String) {
        if (closed || my != token) return
        ensureTts()
        when (ttsState) {
            TTS_READY -> speakNow(my, text)
            TTS_STARTING -> {
                pendingText = text
                pendingToken = my
            }
            else -> setSpeaking(false)
        }
    }

    private fun ensureTts() {
        if (tts != null || ttsState == TTS_FAILED) return
        ttsState = TTS_STARTING
        try {
            // The init callback may arrive on another thread (or before the constructor returns), so hop to main.
            tts = TextToSpeech(app) { status -> Async.main { onTtsInit(status) } }
        } catch (t: Throwable) {
            Log.w(TAG, "no text-to-speech engine", t)
            tts = null
            ttsState = TTS_FAILED
        }
    }

    private fun onTtsInit(status: Int) {
        val engine = tts
        if (closed || engine == null) return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "text-to-speech init failed: $status")
            ttsState = TTS_FAILED
            pendingText = null
            try {
                engine.shutdown()
            } catch (_: Throwable) {
            }
            tts = null
            return
        }
        try {
            val lang = engine.setLanguage(Locale.US)
            if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.getDefault())
            }
            engine.setPitch(PITCH)
            engine.setSpeechRate(RATE)
            engine.setOnUtteranceProgressListener(progress)
        } catch (t: Throwable) {
            Log.w(TAG, "text-to-speech setup failed", t)
        }
        ttsState = TTS_READY
        val text = pendingText
        pendingText = null
        if (text != null && pendingToken == token) speakNow(pendingToken, text)
    }

    private fun speakNow(my: Int, text: String) {
        val engine = tts ?: return
        try {
            val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId(my))
            if (result == TextToSpeech.ERROR) setSpeaking(false)
        } catch (t: Throwable) {
            Log.w(TAG, "text-to-speech speak failed", t)
            setSpeaking(false)
        }
    }

    private val progress = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            Async.main { if (utteranceId == utteranceId(token)) setSpeaking(true) }
        }

        override fun onDone(utteranceId: String?) {
            Async.main { if (utteranceId == utteranceId(token)) setSpeaking(false) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            Async.main { if (utteranceId == utteranceId(token)) setSpeaking(false) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            Async.main { if (utteranceId == utteranceId(token)) setSpeaking(false) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            Async.main { if (utteranceId == utteranceId(token)) setSpeaking(false) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun setSpeaking(on: Boolean) {
        if (speaking == on) return
        speaking = on
        try {
            onSpeaking?.invoke(on)
        } catch (t: Throwable) {
            Log.w(TAG, "onSpeaking listener failed", t)
        }
    }

    private fun utteranceId(my: Int) = "lumo-$my"

    private fun fileName(i: Int) = "lumo-voice-$i.mp3"

    internal companion object {
        private const val TAG = "WanderNarrator"
        private const val TTS_NONE = 0
        private const val TTS_STARTING = 1
        private const val TTS_READY = 2
        private const val TTS_FAILED = 3
        private const val PITCH = 1.15f
        private const val RATE = 1.0f
        private const val FILES = 2
        private const val MIN_MP3_BYTES = 64
        private const val MAX_CHARS = 600
        private const val POLLY_WAIT_MS = 4_000L

        /**
         * Makes text pleasant to hear: drops emoji and pictographs, reads "·" as a pause and "~" as
         * "about", collapses whitespace, and trims very long text at a sentence boundary.
         */
        fun sanitize(text: String): String {
            val sb = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    cp == '·'.code || cp == '•'.code || cp == '|'.code -> sb.append(", ")
                    cp == '~'.code -> sb.append(" about ")
                    cp == '×'.code -> sb.append(" times ")
                    cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF -> Unit
                    Character.getType(cp) == Character.OTHER_SYMBOL.toInt() -> Unit
                    Character.getType(cp) == Character.PRIVATE_USE.toInt() -> Unit
                    Character.getType(cp) == Character.SURROGATE.toInt() -> Unit
                    Character.isISOControl(cp) -> sb.append(' ')
                    else -> sb.appendCodePoint(cp)
                }
            }
            var out = sb.toString()
                .replace(Regex("\\s+"), " ")
                .replace(Regex("\\s+([,.!?;:])"), "$1")
                .replace(Regex("(,\\s*)+,"), ",")
                .trim()
                .trim(',')
                .trim()
            if (out.length > MAX_CHARS) {
                val cut = out.lastIndexOfAny(charArrayOf('.', '!', '?'), MAX_CHARS)
                out = if (cut > MAX_CHARS / 2) out.substring(0, cut + 1) else out.substring(0, MAX_CHARS).substringBeforeLast(' ') + "..."
            }
            return out
        }
    }
}
