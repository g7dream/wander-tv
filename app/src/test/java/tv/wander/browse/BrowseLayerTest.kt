package tv.wander.browse

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import tv.wander.model.PageText
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.Verdict
import tv.wander.quest.QuestBoardView
import tv.wander.quest.QuestCatalog
import tv.wander.quest.QuestEngine
import tv.wander.quest.QuestOverlay
import tv.wander.testing.Snap
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class BrowseLayerTest {
    private class Recorder : BrowseLayer.Listener {
        val events = ArrayList<String>()
        override fun onPageStarted(url: String) { events.add("started $url") }
        override fun onPageFinished(url: String, title: String) { events.add("finished $url | $title") }
        override fun onUserClick() { events.add("click") }
        override fun onUserScroll() { events.add("scroll") }
        override fun onTextFieldFocus(focused: Boolean, label: String?) { events.add("field $focused $label") }
    }

    private lateinit var activity: Activity
    private lateinit var rec: Recorder
    private lateinit var layer: BrowseLayer
    private val maya = Player("p1", "Maya", 1)
    private val theo = Player("p2", "Theo", 2)

    @Before
    fun setUp() {
        activity = Snap.activity()
        rec = Recorder()
    }

    @After
    fun tearDown() {
        if (::layer.isInitialized) layer.destroy()
    }

    private fun show(page: View? = null) {
        layer = BrowseLayer(activity, rec)
        val root = FrameLayout(activity)
        if (page != null) {
            root.addView(page, FrameLayout.LayoutParams(-1, -1))
            layer.background = null // let the stand-in page show (WebView draws nothing under Robolectric)
        }
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        Snap.show(activity, root)
        layout(root)
        Snap.settle(700) // WebView warm-up runs shortly after attach
        layout(root) // lay the new WebView out too
        waitForScripts()
    }

    private fun layout(root: View) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 1080)
    }

    private fun waitForScripts() {
        repeat(100) {
            if (WebScripts.ready) return
            Thread.sleep(10)
            Snap.settle(10)
        }
    }

    private fun web(): WebView = (0 until layer.childCount).map { layer.getChildAt(it) }.filterIsInstance<WebView>().single()
    private fun cursor(): CursorView = (0 until layer.childCount).map { layer.getChildAt(it) }.filterIsInstance<CursorView>().single()
    private fun strip(): TitleStrip = (0 until layer.childCount).map { layer.getChildAt(it) }.filterIsInstance<TitleStrip>().single()

    private fun key(code: Int, action: Int, repeat: Int = 0): Boolean {
        val t = SystemClock.uptimeMillis()
        return layer.handleKey(KeyEvent(t, t, action, code, repeat))
    }

    private fun press(code: Int): Pair<Boolean, Boolean> = key(code, KeyEvent.ACTION_DOWN) to key(code, KeyEvent.ACTION_UP)

    private fun request(url: String, main: Boolean = true, gesture: Boolean = true) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = gesture
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    /** Plays the callbacks Chromium would make for a successful load of [url]. */
    private fun simulateLoad(url: String, title: String, progress: Int = 100) {
        val wv = web()
        val client = shadowOf(wv).webViewClient
        val chrome = shadowOf(wv).webChromeClient
        client.onPageStarted(wv, url, null)
        chrome.onProgressChanged(wv, 30)
        chrome.onReceivedTitle(wv, title)
        if (progress >= 100) {
            chrome.onProgressChanged(wv, 100)
            client.onPageFinished(wv, url)
        } else {
            chrome.onProgressChanged(wv, progress)
        }
    }

    /** Lets queued work (the cursor's hover check after a load) run, so lastEvaluatedJavascript is ours. */
    private fun quiet() = Snap.settle(250)

    // ------------------------------------------------------------------------------ snapshots

    @Test
    fun snapshotChromeOverALightArticle() {
        show(FakePage(activity, dark = false))
        layer.open("en.wikipedia.org/wiki/Cheetah", maya)
        simulateLoad("https://en.wikipedia.org/wiki/Cheetah", "Cheetah - Wikipedia", progress = 64)
        // Maya drags the cursor toward a link (one quick swipe; Robolectric frames are coarse, and her
        // name chip only shows for 1.5 s).
        repeat(12) { layer.moveCursor(10f, 3f, maya) }
        cursor().setHover(1)
        Snap.settle(300)
        val file = Snap.save(activity, "browse_chrome_light")
        assertTrue(file.length() > 20_000)
        assertTrue(strip().revealed)
    }

    @Test
    fun snapshotChromeOverADarkPageWithNotice() {
        show(FakePage(activity, dark = true))
        layer.open("moths that glow", null)
        simulateLoad("https://duckduckgo.com/?q=moths+that+glow&kp=1&kae=d", "moths that glow at DuckDuckGo")
        val wv = web()
        // A page link that wants another app: swallowed with a gentle notice.
        assertTrue(shadowOf(wv).webViewClient.shouldOverrideUrlLoading(wv, request("market://details?id=moth.app")))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        layer.moveCursor(-40f, 30f, theo) // Theo takes over: his name chip shows
        Snap.settle(120)
        cursor().setHover(2)
        Snap.settle(300)
        val file = Snap.save(activity, "browse_chrome_dark")
        assertTrue(file.length() > 20_000)
    }

    @Test
    fun snapshotTapRipple() {
        show(FakePage(activity, dark = false))
        layer.open("nasa.gov", null)
        simulateLoad("https://www.nasa.gov/", "NASA")
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        Snap.settle(100)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(140)
        Snap.save(activity, "browse_tap_ripple")
    }

    @Test
    fun snapshotChromeBesideTheQuestBoard() {
        show(FakePage(activity, dark = false))
        val root = layer.parent as FrameLayout
        val quest = QuestOverlay(activity, object : QuestOverlay.Listener {
            override fun onQuestPicked(quest: Quest) = Unit
            override fun onLumoPickRequested() = Unit
            override fun onQuestPickerClosed() = Unit
            override fun onIntroDismissed() = Unit
            override fun onFinaleDismissed() = Unit
        })
        root.addView(quest, FrameLayout.LayoutParams(-1, -1))
        val speed = QuestCatalog.byId("speed-safari")!!
        val players = listOf(maya, theo)
        val engine = QuestEngine().apply { start(speed) }
        engine.apply("p2", Verdict(true, "Cheetah", "Yes!", "Cheetahs reach 104 km/h.", fromAi = true), players)
        quest.showBoard(engine.board(players))
        // What MainActivity does while the scoreboard is up: keep the strip's right end clear of it.
        layer.setTopEndReserve(QuestBoardView.WIDTH_DP + 14f)
        layer.open("en.wikipedia.org/wiki/Peregrine_falcon", maya)
        simulateLoad("https://en.wikipedia.org/wiki/Peregrine_falcon", "Peregrine falcon - Wikipedia", progress = 64)
        Snap.settle(300)
        layout(root)
        Snap.settle(100)
        val board = (0 until quest.childCount).map { quest.getChildAt(it) }.first { it is QuestBoardView }
        assertTrue(board.visibility == View.VISIBLE)
        assertTrue("strip ends left of the board", strip().right <= board.left)
        assertTrue(strip().revealed)
        Snap.save(activity, "browse_chrome_beside_board")

        // In the narrow strip a notice takes the domain chip's place, so "Maya driving" still fits.
        layer.zoom(1)
        Snap.settle(300)
        layout(root)
        val chips = (0 until strip().childCount).map { strip().getChildAt(it) }.filterIsInstance<android.widget.TextView>()
        val domainChip = chips.first { it.text == "en.wikipedia.org" }
        val driving = chips.first { it.text == "driving" }
        assertEquals(View.GONE, domainChip.visibility)
        assertTrue("driving chip inside the strip", driving.right <= strip().width - strip().paddingRight)
        Snap.save(activity, "browse_chrome_beside_board_notice")

        // Board gone: the strip gets its full width back.
        layer.setTopEndReserve(0f)
        layout(root)
        assertEquals(1920 - 96, strip().right)
    }

    // ---------------------------------------------------------------------------- behaviour

    @Test
    fun webViewIsSetUpForTheCouch() {
        show()
        val s = web().settings
        assertTrue(s.javaScriptEnabled)
        assertTrue(s.domStorageEnabled)
        assertFalse(s.mediaPlaybackRequiresUserGesture)
        assertEquals(115, s.textZoom)
        assertTrue(s.useWideViewPort)
        assertTrue(s.loadWithOverviewMode)
        assertTrue(s.userAgentString.endsWith(" WanderTV/1.0"))
        assertNotNull(shadowOf(web()).getJavascriptInterface(WebScripts.BRIDGE_NAME))
        // No full-screen offscreen layer: Chromium draws straight into the window.
        assertEquals(View.LAYER_TYPE_NONE, web().layerType)
    }

    @Test
    fun openResolvesSearchesAndRemembersTheDriver() {
        show()
        layer.open("cheetah top speed", maya)
        assertEquals("https://duckduckgo.com/?q=cheetah+top+speed&kp=1&kae=d", shadowOf(web()).lastLoadedUrl)
        assertEquals("https://duckduckgo.com/?q=cheetah+top+speed&kp=1&kae=d", layer.currentUrl)
        layer.open("nasa.gov")
        assertEquals("https://nasa.gov", shadowOf(web()).lastLoadedUrl)
    }

    @Test
    fun pageEventsReachTheListenerOncePerLoad() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        shadowOf(web()).webViewClient.onPageFinished(web(), "https://www.nasa.gov/") // Chromium repeats itself
        assertEquals(listOf("started https://www.nasa.gov/", "finished https://www.nasa.gov/ | NASA"), rec.events.filter { it.startsWith("started") || it.startsWith("finished") })
        assertEquals("NASA", layer.currentTitle)
        assertEquals("https://www.nasa.gov/", layer.currentUrl)
        simulateLoad("https://en.wikipedia.org/wiki/Moth", "Moth - Wikipedia")
        assertEquals("Moth", layer.currentTitle) // the domain chip already says Wikipedia
    }

    @Test
    fun dpadMovesTheCursorAndConsumesBothEdges() {
        show()
        val c = cursor()
        val x0 = c.cursorX
        val y0 = c.cursorY
        val right = press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertTrue(right.first && right.second)
        val step = 14f * activity.resources.displayMetrics.density
        assertEquals(x0 + step, c.cursorX, 0.5f)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertEquals(y0 + step, c.cursorY, 0.5f)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(x0, c.cursorX, 0.5f)
        assertEquals(y0, c.cursorY, 0.5f)
    }

    @Test
    fun holdingAKeyGlidesFaster() {
        show()
        val c = cursor()
        val x0 = c.cursorX
        assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN))
        for (i in 1..12) {
            Snap.settle(50)
            assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN, repeat = i))
        }
        assertTrue(key(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP))
        val travelled = (c.cursorX - x0) / activity.resources.displayMetrics.density
        assertTrue("glided $travelled dp", travelled > 150f)
        val stopped = c.cursorX
        Snap.settle(200)
        assertEquals(stopped, c.cursorX, 0.01f)
    }

    @Test
    fun keysThatBelongToMainActivityAreNotHandled() {
        show()
        for (code in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_1)) {
            assertFalse("$code down", key(code, KeyEvent.ACTION_DOWN))
            assertFalse("$code up", key(code, KeyEvent.ACTION_UP))
        }
    }

    @Test
    fun centerTapsWithRealTouchEventsAtTheCursor() {
        show()
        val seen = ArrayList<MotionEvent>()
        web().setOnTouchListener { _, ev -> seen.add(MotionEvent.obtain(ev)); false }
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        val c = cursor()
        assertTrue(press(KeyEvent.KEYCODE_DPAD_CENTER).first)
        assertEquals(1, seen.size)
        Snap.settle(60)
        assertEquals(2, seen.size)
        val down = seen[0]
        val up = seen[1]
        assertEquals(MotionEvent.ACTION_DOWN, down.actionMasked)
        assertEquals(MotionEvent.ACTION_UP, up.actionMasked)
        assertEquals(InputDevice.SOURCE_TOUCHSCREEN, down.source)
        assertEquals(MotionEvent.TOOL_TYPE_FINGER, down.getToolType(0))
        assertEquals(c.cursorX, down.x, 0.5f)
        assertEquals(c.cursorY, down.y, 0.5f)
        assertEquals(down.downTime, up.downTime)
        assertTrue(up.eventTime - down.eventTime >= 30)
        assertTrue(rec.events.contains("click"))
    }

    @Test
    fun idleCursorHidesAndTheFirstPressOnlyRevealsIt() {
        show()
        val seen = ArrayList<Int>()
        web().setOnTouchListener { _, ev -> seen.add(ev.actionMasked); false }
        Snap.settle(6600)
        assertFalse(cursor().isAwake)
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(60)
        assertTrue(cursor().isAwake)
        assertTrue("no blind click", seen.isEmpty())
        press(KeyEvent.KEYCODE_DPAD_CENTER)
        Snap.settle(60)
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), seen)
    }

    @Test
    fun phoneMovesAreSmoothedAndTapLandsOnTheTarget() {
        show()
        val c = cursor()
        val x0 = c.cursorX
        layer.moveCursor(30f, 0f, maya)
        Snap.settle(400)
        assertTrue(c.cursorX > x0 + 30f)
        val seen = ArrayList<MotionEvent>()
        web().setOnTouchListener { _, ev -> seen.add(MotionEvent.obtain(ev)); false }
        layer.moveCursor(0f, 20f, maya)
        layer.tap(maya) // before smoothing finishes: snaps to where Maya aimed
        assertEquals(c.cursorY, seen.first().y, 0.5f)
        Snap.settle(60)
        assertTrue(rec.events.contains("click"))
    }

    @Test
    fun rewindAndFastForwardPageTheContent() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        quiet()
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD).first)
        assertTrue(shadowOf(web()).lastEvaluatedJavascript.contains("W.page("))
        assertTrue(shadowOf(web()).lastEvaluatedJavascript.contains(",1)"))
        assertTrue(rec.events.contains("scroll"))
        assertTrue(press(KeyEvent.KEYCODE_MEDIA_REWIND).first)
        assertTrue(shadowOf(web()).lastEvaluatedJavascript.contains(",-1)"))
    }

    @Test
    fun phoneScrollGoesToTheElementUnderTheCursor() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        quiet()
        layer.scroll(0f, 120f)
        Snap.settle(20)
        val js = shadowOf(web()).lastEvaluatedJavascript
        assertTrue(js, js.contains("W.scrollAt("))
        assertTrue(rec.events.contains("scroll"))
    }

    @Test
    fun fieldFocusFromThePageIsForwarded() {
        show()
        val bridge = shadowOf(web()).getJavascriptInterface(WebScripts.BRIDGE_NAME)
        val onField = bridge.javaClass.getMethod("onField", Boolean::class.javaPrimitiveType, String::class.java)
        onField.isAccessible = true
        onField.invoke(bridge, true, "Search Wikipedia")
        Snap.settle(20)
        onField.invoke(bridge, true, "Search Wikipedia") // repeats are ignored
        Snap.settle(20)
        onField.invoke(bridge, false, null)
        Snap.settle(20)
        assertEquals(listOf("field true Search Wikipedia", "field false null"), rec.events.filter { it.startsWith("field") })
        assertEquals(1, bridge.javaClass.declaredMethods.count { m -> m.isAnnotationPresent(android.webkit.JavascriptInterface::class.java) })
    }

    @Test
    fun typingWithNoFocusedFieldSearchesInstead() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        quiet()
        layer.typeText("moth facts", true)
        val shadow = shadowOf(web())
        assertTrue(shadow.lastEvaluatedJavascript.contains("W.type(\"moth facts\",true)"))
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue("\"none\"")
        assertEquals("https://duckduckgo.com/?q=moth+facts&kp=1&kae=d", shadow.lastLoadedUrl)
    }

    @Test
    fun typingIntoAForeignFrameStaysOnThePage() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        quiet()
        layer.typeText("Hi", true)
        shadowOf(web()).lastEvaluatedJavascriptCallback.onReceiveValue("\"frame\"")
        assertEquals("https://nasa.gov", shadowOf(web()).lastLoadedUrl) // typed with keys, no search
    }

    @Test
    fun keyEventsTypeTextAndPressEnter() {
        val downs = keyEventsFor("Hi 5", submit = true).filter { it.action == KeyEvent.ACTION_DOWN }.map { it.keyCode }
        assertTrue(downs.toString(), downs.containsAll(listOf(KeyEvent.KEYCODE_H, KeyEvent.KEYCODE_I, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_5)))
        assertEquals(KeyEvent.KEYCODE_ENTER, downs.last())
        assertTrue(keyEventsFor("", submit = false).isEmpty())
    }

    @Test
    fun typingIntoAFieldStaysOnThePage() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        quiet()
        layer.typeText("artemis", false)
        val shadow = shadowOf(web())
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue("\"typed\"")
        assertEquals("https://nasa.gov", shadow.lastLoadedUrl)
    }

    @Test
    fun extractInjectsReadabilityOnceAndDecodesTheResult() {
        show()
        layer.open("en.wikipedia.org/wiki/Cheetah")
        simulateLoad("https://en.wikipedia.org/wiki/Cheetah", "Cheetah - Wikipedia")
        quiet()
        var result: PageText? = null
        var calls = 0
        layer.extract { result = it; calls++ }
        Snap.settle(20)
        val shadow = shadowOf(web())
        assertTrue(shadow.lastEvaluatedJavascript.take(200), shadow.lastEvaluatedJavascript.contains("W.extract("))
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue("\"" + WebScripts.NO_READABILITY + "\"")
        // The layer injects the wrapped library, then asks again.
        assertTrue(shadow.lastEvaluatedJavascript.contains("W.extract("))
        val json = """{"url":"https://en.wikipedia.org/wiki/Cheetah","title":"Cheetah","text":"The cheetah is the fastest land animal[2].\n\nIt can run 112 km/h.","headings":["Contents","Speed","Diet"],"siteName":"Wikipedia","via":"readability"}"""
        shadow.lastEvaluatedJavascriptCallback.onReceiveValue(WebScripts.quote(json))
        repeat(100) {
            if (calls > 0) return@repeat
            Thread.sleep(10)
            Snap.settle(10)
        }
        assertEquals(1, calls)
        val p = result!!
        assertEquals("Cheetah", p.title)
        assertEquals("The cheetah is the fastest land animal. It can run 112 km/h.", p.text)
        assertEquals(listOf("Speed", "Diet"), p.headings)
        assertEquals("Wikipedia", p.siteName)
        Snap.settle(4500)
        assertEquals("exactly one callback", 1, calls)
    }

    @Test
    fun extractGivesUpWithNullAfterFourSeconds() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        var calls = 0
        var result: PageText? = PageText("x", "x", "x")
        layer.extract { result = it; calls++ }
        Snap.settle(3900)
        assertEquals(0, calls)
        Snap.settle(200)
        assertEquals(1, calls)
        assertNull(result)
    }

    @Test
    fun extractOnNoPageIsNull() {
        show()
        var calls = 0
        var result: PageText? = PageText("x", "x", "x")
        layer.extract { result = it; calls++ }
        Snap.settle(20)
        assertEquals(1, calls)
        assertNull(result)
    }

    @Test
    fun appLinksAreSwallowedAndWebLinksLoad() {
        show()
        val wv = web()
        val client = shadowOf(wv).webViewClient
        assertFalse(client.shouldOverrideUrlLoading(wv, request("https://example.com/")))
        assertFalse(client.shouldOverrideUrlLoading(wv, request("about:blank", main = false)))
        for (u in listOf("market://details?id=x", "tel:123", "mailto:a@b.c", "intent://scan/#Intent;scheme=zxing;end", "file:///sdcard/x")) {
            assertTrue(u, client.shouldOverrideUrlLoading(wv, request(u)))
        }
        // An intent link with a web fallback follows the fallback.
        val withFallback = "intent://open#Intent;scheme=moth;S.browser_fallback_url=https%3A%2F%2Fexample.com%2Fmoth;end"
        assertTrue(client.shouldOverrideUrlLoading(wv, request(withFallback)))
        assertEquals("https://example.com/moth", shadowOf(wv).lastLoadedUrl)
    }

    @Test
    fun mainFrameErrorsShowTheWanderOfflinePage() {
        show()
        layer.open("nowhere.example")
        val wv = web()
        val client = shadowOf(wv).webViewClient
        client.onPageStarted(wv, "https://nowhere.example/", null)
        @Suppress("DEPRECATION")
        client.onReceivedError(wv, -2, "net::ERR_NAME_NOT_RESOLVED", "https://nowhere.example/")
        val data = shadowOf(wv).lastLoadDataWithBaseURL
        assertNotNull(data)
        assertEquals("file:///android_asset/", data.baseUrl)
        assertTrue(data.data.contains("nowhere.example"))
        // The failed page's own "finished" isn't reported as a visit.
        client.onPageFinished(wv, "https://nowhere.example/")
        assertFalse(rec.events.any { it.startsWith("finished") })
        var extracted: PageText? = PageText("x", "x", "x")
        layer.extract { extracted = it }
        Snap.settle(20)
        assertNull(extracted)
    }

    @Test
    fun rendererCrashRebuildsTheWebViewInPlace() {
        show()
        layer.open("nasa.gov")
        simulateLoad("https://www.nasa.gov/", "NASA")
        val old = web()
        assertTrue(shadowOf(old).webViewClient.onRenderProcessGone(old, null))
        val fresh = web()
        assertNotSame(old, fresh)
        assertEquals("https://www.nasa.gov/", shadowOf(fresh).lastLoadedUrl)
        assertTrue(shadowOf(old).wasDestroyCalled())
    }

    @Test
    fun backLeavesFullscreenVideoFirst() {
        show()
        var hidden = 0
        val video = View(activity)
        shadowOf(web()).webChromeClient.onShowCustomView(video, object : WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() { hidden++ }
        })
        assertNotNull(video.parent)
        assertTrue(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN)) // plays/pauses the video now
        assertTrue(layer.goBack())
        assertEquals(1, hidden)
        assertNull(video.parent)
        assertFalse(key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN))
    }

    @Test
    fun goBackWithNoHistoryIsFalse() {
        show()
        assertFalse(layer.goBack())
    }

    @Test
    fun goBackFollowsInPageLinksBackToo() {
        show()
        val shadow = shadowOf(web())
        shadow.pushEntryToHistory("https://en.wikipedia.org/wiki/Moth")
        shadow.pushEntryToHistory("https://en.wikipedia.org/wiki/Moth#Diet")
        assertTrue(layer.goBack())
        assertEquals(1, shadow.goBackInvocations)
    }

    @Test
    fun goBackFromTheOfflinePageSkipsTheFailedEntry() {
        show()
        val wv = web()
        val shadow = shadowOf(wv)
        shadow.pushEntryToHistory("https://www.nasa.gov/")
        shadow.pushEntryToHistory("https://nowhere.example/")
        @Suppress("DEPRECATION")
        shadow.webViewClient.onReceivedError(wv, -2, "net::ERR_NAME_NOT_RESOLVED", "https://nowhere.example/")
        // What Chromium's list then holds: NASA, the failed load's error entry, our offline page.
        shadow.pushEntryToHistory("about:blank")
        assertTrue(layer.goBack())
        assertEquals("back past the error entry, straight to NASA", 2, shadow.goBackInvocations)
    }

    @Test
    fun zoomStaysBetween80And200() {
        show()
        repeat(15) { layer.zoom(1) }
        assertEquals(200, web().settings.textZoom)
        repeat(20) { layer.zoom(-1) }
        assertEquals(80, web().settings.textZoom)
        layer.zoom(3)
        assertEquals(110, web().settings.textZoom)
    }

    @Test
    fun destroyReleasesTheWebView() {
        show()
        val wv = web()
        layer.destroy()
        assertTrue(shadowOf(wv).wasDestroyCalled())
        assertNull(wv.parent)
        assertFalse(layer.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)))
        layer.open("nasa.gov") // harmless after destroy
        layer.moveCursor(5f, 5f, maya)
        layer.extract { assertNull(it) }
        Snap.settle(50)
    }

    @Test
    fun callsFromOtherThreadsArePostedToMain() {
        show()
        val t = Thread { layer.open("nasa.gov", maya); layer.moveCursor(3f, 3f, maya) }
        t.start()
        t.join()
        Snap.settle(50)
        assertEquals("https://nasa.gov", shadowOf(web()).lastLoadedUrl)
        assertTrue(abs(cursor().cursorX - 960f) > 0.1f || abs(cursor().cursorY - 1080f * 0.46f) > 0.1f)
    }
}

/**
 * A stand-in for page content in snapshots (WebView renders nothing under Robolectric): a light
 * encyclopedia article or a dark search results page, drawn with plain shapes and text.
 */
internal class FakePage(context: Context, private val dark: Boolean) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val s = w / 1920f
        if (!dark) drawArticle(canvas, w, s) else drawResults(canvas, w, s)
    }

    private fun drawArticle(c: Canvas, w: Float, s: Float) {
        c.drawColor(Color.WHITE)
        p.color = 0xFFF6F6F6.toInt()
        c.drawRect(0f, 0f, w, 120f * s, p)
        p.color = 0xFFDDDDDD.toInt()
        c.drawRect(0f, 120f * s, w, 122f * s, p)
        p.color = 0xFF202122.toInt()
        p.typeface = Typeface.SERIF
        p.textSize = 64f * s
        c.drawText("Cheetah", 300f * s, 260f * s, p)
        p.color = 0xFFA2A9B1.toInt()
        c.drawRect(300f * s, 290f * s, 1560f * s, 292f * s, p)
        p.typeface = Typeface.SANS_SERIF
        p.textSize = 30f * s
        val lines = listOf(
            "The cheetah (Acinonyx jubatus) is a large cat and the fastest land animal.",
            "It has a tawny to creamy white or pale buff fur that is marked with evenly",
            "spaced, solid black spots. The head is small and rounded, with a short snout",
            "and black tear-like facial streaks. It reaches 67-94 cm at the shoulder.",
            "",
            "The cheetah is capable of running at 93 to 104 km/h; it has evolved",
            "specialized adaptations for speed, including a light build, long thin legs",
            "and a long tail. It typically stalks its prey within 60-100 m before",
            "charging towards it, trips it during the chase and bites its throat.",
            "",
            "Speed and acceleration",
            "Cheetahs have been recorded at 112 km/h over short distances, and they",
            "accelerate from standstill to over 100 km/h in about three seconds.",
        )
        var y = 360f * s
        for (line in lines) {
            if (line == "Speed and acceleration") {
                p.typeface = Typeface.SERIF
                p.textSize = 42f * s
                p.color = 0xFF202122.toInt()
                c.drawText(line, 300f * s, y + 10f * s, p)
                p.typeface = Typeface.SANS_SERIF
                p.textSize = 30f * s
                y += 70f * s
                continue
            }
            p.color = 0xFF202122.toInt()
            if (line.isNotEmpty()) c.drawText(line, 300f * s, y, p)
            y += 46f * s
        }
        // infobox
        p.color = 0xFFF8F9FA.toInt()
        c.drawRect(1380f * s, 330f * s, 1700f * s, 900f * s, p)
        p.color = 0xFFC8CCD1.toInt()
        p.style = Paint.Style.STROKE
        c.drawRect(1380f * s, 330f * s, 1700f * s, 900f * s, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFD9B77E.toInt()
        c.drawRect(1400f * s, 350f * s, 1680f * s, 560f * s, p)
        p.color = 0xFF202122.toInt()
        p.textSize = 24f * s
        c.drawText("Top speed  112 km/h", 1400f * s, 610f * s, p)
        p.color = 0xFF3366CC.toInt()
        c.drawText("Felidae", 1400f * s, 650f * s, p)
    }

    private fun drawResults(c: Canvas, w: Float, s: Float) {
        c.drawColor(0xFF1C1C1C.toInt())
        p.color = 0xFF2A2A2A.toInt()
        c.drawRect(0f, 0f, w, 150f * s, p)
        p.color = 0xFF3A3A3A.toInt()
        c.drawRoundRect(300f * s, 45f * s, 1300f * s, 110f * s, 32f * s, 32f * s, p)
        p.color = 0xFFEEEEEE.toInt()
        p.textSize = 30f * s
        p.typeface = Typeface.SANS_SERIF
        c.drawText("moths that glow", 340f * s, 88f * s, p)
        var y = 260f * s
        val results = listOf(
            "Bioluminescent insects: which ones really glow?" to "Fireflies get the fame, but a few moth larvae and beetles glow too.",
            "Why are moths attracted to light? New study" to "Moths tilt their backs toward the brightest source, which traps them.",
            "Luna moth: facts, photos and life cycle" to "A pale green giant with long tails that confuse bat sonar.",
            "Glow-worms of New Zealand caves" to "Fungus gnat larvae light up cave ceilings to lure their prey.",
        )
        for ((t, d) in results) {
            p.color = 0xFF8AB4F8.toInt()
            p.textSize = 34f * s
            c.drawText(t, 300f * s, y, p)
            p.color = 0xFFBBBBBB.toInt()
            p.textSize = 26f * s
            c.drawText(d, 300f * s, y + 44f * s, p)
            y += 170f * s
        }
    }
}
