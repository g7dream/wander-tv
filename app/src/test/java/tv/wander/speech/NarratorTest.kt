package tv.wander.speech

import android.os.Looper
import android.speech.tts.TextToSpeech
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowTextToSpeech
import tv.wander.ai.AiClient
import tv.wander.ui.Async
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class NarratorTest {
    private val ctx get() = RuntimeEnvironment.getApplication()
    private val offline = AiClient("", "", post = { it() })

    @After
    fun tearDown() {
        ShadowTextToSpeech.reset()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun lastTts(): ShadowTextToSpeech? = ShadowTextToSpeech.getLastTextToSpeechInstance()?.let { shadowOf(it) }

    @Test
    fun speaksWithOnDeviceTtsAfterInit() {
        val n = Narrator(ctx, offline)
        val states = ArrayList<Boolean>()
        n.onSpeaking = { states += it }
        n.speak("Here's the page in 4 cards. 🦋")
        val tts = lastTts()
        assertNotNull("TTS is created lazily on first speak", tts)
        assertNull("nothing is spoken before init", tts!!.lastSpokenText)
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle() // Robolectric's engine reports start + done for the utterance
        assertEquals("Here's the page in 4 cards.", tts.lastSpokenText)
        assertEquals(listOf(true, false), states)
        assertEquals(java.util.Locale.US, tts.currentLanguage)
    }

    @Test
    fun configuredBackendWithoutAudioFallsBackToTts() {
        // Unreachable backend: either the client answers null, or the 4 s deadline hands the line to TTS.
        val ai = AiClient("https://example.invalid", "k", post = { r -> Async.main(r) })
        val n = Narrator(ctx, ai)
        n.speak("Hello from the couch")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_500))
        val tts = lastTts()
        assertNotNull("falls back to the on-device voice", tts)
        tts!!.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("Hello from the couch"), tts.spokenTextList)
        // A late Polly answer after the fallback is ignored: nothing is spoken twice.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(11_000))
        assertEquals(1, tts.spokenTextList.size)
        n.shutdown()
    }

    @Test
    fun newSpeechReplacesPendingSpeech() {
        val n = Narrator(ctx, offline)
        n.speak("First")
        n.speak("Second")
        val tts = lastTts()!!
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals(listOf("Second"), tts.spokenTextList)
    }

    @Test
    fun staleUtteranceEventsAreIgnored() {
        val n = Narrator(ctx, offline)
        val states = ArrayList<Boolean>()
        n.onSpeaking = { states += it }
        n.speak("One")
        val tts = lastTts()!!
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        n.speak("Two")
        idle()
        assertEquals(listOf(true, false, true, false), states)
        assertEquals(listOf("One", "Two"), tts.spokenTextList)
        // A late event from the first, replaced line must not flip the state.
        tts.utteranceProgressListener.onStart("lumo-1")
        idle()
        assertEquals(4, states.size)
        n.stop()
        tts.utteranceProgressListener.onStart("lumo-2")
        idle()
        assertEquals("a stopped line never reports speaking", 4, states.size)
    }

    @Test
    fun initFailureStaysQuietAndNeverCrashes() {
        val n = Narrator(ctx, offline)
        val states = ArrayList<Boolean>()
        n.onSpeaking = { states += it }
        n.speak("Anyone there?")
        lastTts()!!.onInitListener.onInit(TextToSpeech.ERROR)
        idle()
        n.speak("Still nothing")
        idle()
        n.stop()
        n.shutdown()
        n.speak("After shutdown")
        idle()
        assertTrue(states.isEmpty())
    }

    @Test
    fun shutdownReleasesTts() {
        val n = Narrator(ctx, offline)
        n.speak("Hi")
        val tts = lastTts()!!
        tts.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        n.shutdown()
        assertTrue(tts.isShutdown)
        n.shutdown()
        n.stop()
    }

    @Test
    fun blankTextIsIgnored() {
        val n = Narrator(ctx, offline)
        n.speak("   ")
        n.speak("🦋✨")
        assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @Test
    fun sanitizeReadsWell() {
        assertEquals("7 pages, 4 min, about 12 s each", Narrator.sanitize("7 pages · 4 min · ~12 s each"))
        assertEquals("Quest time: Speed Safari!", Narrator.sanitize("🐆 Quest time:   Speed Safari!"))
        assertEquals("Hi Maya!", Narrator.sanitize("Hi Maya! 👋🏽"))
        assertFalse(Narrator.sanitize("x".repeat(2000)).length > 603)
        val long = "This is a sentence. ".repeat(60)
        assertTrue(Narrator.sanitize(long).endsWith("."))
    }
}
