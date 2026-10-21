package tv.wander.browse

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject
import tv.wander.BuildConfig
import tv.wander.model.PageText
import tv.wander.model.Player
import tv.wander.ui.Async
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.exp

/**
 * The browser: a WebView, the shared cursor and a slim title strip. Driven by the remote and by phones.
 *
 * Integration notes:
 * - Route every remote key through [handleKey] from Activity.dispatchKeyEvent before calling super:
 *   the WebView takes focus when the cursor taps (so page text fields and the TV keyboard work), and
 *   must never see raw D-pad keys. Back, Menu and Play/Pause come back unhandled (false), except
 *   Play/Pause while a video is fullscreen. On Back call [goBack]; when it returns false, go Home.
 * - Methods are meant for the main thread; calls from other threads are posted there. [goBack] and
 *   [handleKey] return a result, so call those on the main thread.
 * - Forward Activity onResume/onPause/onDestroy to [onResume]/[onPause]/[destroy].
 * - Never throws: failures are logged and degrade gracefully (offline page, rebuilt renderer, null text).
 */
class BrowseLayer(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onPageStarted(url: String)
        fun onPageFinished(url: String, title: String)
        fun onUserClick()
        fun onUserScroll()
        fun onTextFieldFocus(focused: Boolean, label: String?)
    }

    // Cached so the getters are safe from any thread (WebView itself is main-thread only).
    @Volatile private var pageUrl = ""
    @Volatile private var pageTitle = ""

    val currentUrl: String get() = pageUrl
    val currentTitle: String get() = pageTitle

    private var web: WebView? = null
    private var engineMissingView: View? = null
    private var engineFailedAt = 0L
    private val cursor = CursorView(context)
    private val strip = TitleStrip(context)
    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Fullscreen HTML5 video.
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    // Cursor.
    private val motion = CursorMotion()
    private var cursorPlaced = false
    private var smoothing = false
    private var targetX = 0f
    private var targetY = 0f

    // Who is driving: null = the remote.
    private var driver: Player? = null
    private var driverKey = REMOTE_KEY

    private var textZoom = prefs.getInt(PREF_ZOOM, DEFAULT_ZOOM).coerceIn(MIN_ZOOM, MAX_ZOOM)
    private var paused = false
    private var destroyed = false

    // Navigation bookkeeping.
    private var navId = 0
    private var reportedFinish = -1
    private var loading = false
    private var offlineFor: String? = null
    private var offlineShownAt = 0L
    private var lastRequestedUrl: String? = null
    private var pendingNavUrl: String? = null
    private val failedUrls = LinkedHashSet<String>()
    private val crashTimes = ArrayList<Long>()

    // Text fields.
    private var fieldFocused = false
    private var fieldLabel: String? = null
    private var lastPhoneTapAt = 0L

    // Frame loop and scrolling.
    private var looping = false
    private var lastFrameAt = 0L
    private var nativeAccX = 0f
    private var nativeAccY = 0f
    private var jsAccX = 0f
    private var jsAccY = 0f
    private var lastJsScrollAt = 0L
    private var phoneScrollX = 0f
    private var phoneScrollY = 0f
    private var lastScrollReport = 0L
    private var lastPageKeyAt = 0L

    // Hover hint (is the cursor over a link / a text field?).
    private var hoverInFlightSince = 0L
    private var hoverDirty = false
    private var hoverPosted = false

    // A synthesized touch waiting for its ACTION_UP.
    private var pendingUp: PendingUp? = null

    private class PendingUp(val target: View, val downTime: Long, val x: Float, val y: Float)

    private val frameCallback = Choreographer.FrameCallback { onFrame() }
    private val upRunnable = Runnable { sendPendingUp() }
    private val hoverRunnable = Runnable { hoverPosted = false; queryHover() }
    private val stripHideRunnable = Runnable { maybeConcealStrip() }
    private val noticeHideRunnable = Runnable { noticeActive = false; strip.setNotice(null); maybeConcealStrip() }
    private val warmUpRunnable = Runnable { ensureWebView() }
    private var noticeActive = false

    init {
        setBackgroundColor(Palette.INK)
        isFocusable = false
        clipChildren = true
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, dpi(STRIP_HEIGHT_DP), Gravity.TOP).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            rightMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V)
        })
        addView(cursor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        WebScripts.preload(context)
    }

    // ------------------------------------------------------------------------------------------ API

    /** Opens a URL, or searches when [input] doesn't look like one. [by] tints the "driving" chip. */
    fun open(input: String, by: Player? = null) = onMain {
        if (destroyed) return@onMain
        setDriver(by)
        val target = SearchUrls.resolve(input)
        val wv = ensureWebView() ?: return@onMain
        offlineFor = null
        lastRequestedUrl = target
        pendingNavUrl = target
        pageUrl = target
        pageTitle = ""
        strip.setPage("", target)
        strip.setIcon(null)
        strip.setProgress(0)
        revealStrip()
        wv.loadUrl(target)
    }

    /** WebView history back. False when there is nothing to go back to. */
    fun goBack(): Boolean = guard("goBack", false) {
        if (destroyed) return@guard false
        if (customView != null) {
            exitFullscreen(userAsked = true)
            return@guard true
        }
        val wv = web ?: return@guard false
        val list = wv.copyBackForwardList()
        val cur = list.currentIndex
        val failing = offlineFor
        for (i in cur - 1 downTo 0) {
            val u = list.getItemAtIndex(i)?.url ?: continue
            if (!isWeb(u)) continue // our offline pages, about:blank
            if (failing != null && sameUrl(u, failing)) continue // Chromium's error entry behind our offline page
            if (u in failedUrls) continue // other pages that failed and haven't loaded since
            offlineFor = null
            wv.goBackOrForward(i - cur)
            revealStrip()
            return@guard true
        }
        false
    }

    /** Remote keys while browsing: D-pad moves the cursor, center taps, rewind/fast-forward page. */
    fun handleKey(event: KeyEvent): Boolean = guard("handleKey", false) {
        if (destroyed) return@guard false
        val down = event.action == KeyEvent.ACTION_DOWN
        val up = event.action == KeyEvent.ACTION_UP
        if (!down && !up) return@guard false
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> CursorMotion.Dir.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> CursorMotion.Dir.RIGHT
            KeyEvent.KEYCODE_DPAD_UP -> CursorMotion.Dir.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> CursorMotion.Dir.DOWN
            else -> null
        }
        if (dir != null) {
            if (down) onDirectionDown(dir, event.repeatCount) else motion.release(dir)
            return@guard true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                if (down && event.repeatCount == 0) remoteTap()
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_PAGE_UP -> {
                if (down) pageKey(-1, event.repeatCount)
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_DOWN -> {
                if (down) pageKey(1, event.repeatCount)
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // Only while a video is fullscreen; otherwise Play/Pause belongs to MainActivity.
                if (customView == null) return@guard false
                if (down && event.repeatCount == 0) mediaAction("toggle")
                true
            }
            else -> false
        }
    }

    /** Phone touchpad delta in phone CSS pixels. */
    fun moveCursor(dx: Float, dy: Float, by: Player?) = onMain {
        if (destroyed || width == 0 || height == 0) return@onMain
        if (!dx.isFinite() || !dy.isFinite()) return@onMain
        setDriver(by)
        val d = motion.phoneMove(dx, dy, SystemClock.uptimeMillis())
        if (!smoothing) {
            targetX = cursor.cursorX
            targetY = cursor.cursorY
            smoothing = true
        }
        targetX = clampX(targetX + d.x * density)
        targetY = clampY(targetY + d.y * density)
        cursor.wake()
        startLoop()
    }

    fun tap(by: Player?) = onMain {
        if (destroyed) return@onMain
        setDriver(by)
        lastPhoneTapAt = SystemClock.uptimeMillis()
        if (!cursor.isAwake) {
            cursor.wake() // never click where nobody can see; this tap just shows the cursor
            return@onMain
        }
        tapAtCursor()
    }

    fun scroll(dx: Float, dy: Float) = onMain {
        if (destroyed || height == 0 || width == 0) return@onMain
        if (!dx.isFinite() || !dy.isFinite()) return@onMain
        phoneScrollX += dx * PHONE_SCROLL_GAIN
        phoneScrollY += dy * PHONE_SCROLL_GAIN
        startLoop()
        reportScroll(force = false)
    }

    /**
     * Types into the focused page field; [submit] presses Enter / submits its form.
     * The field ends up saying exactly [text] (phones mirror their text box, so resending the whole
     * text is safe). With no text field focused, [text] is opened as an address or a search instead.
     */
    fun typeText(text: String, submit: Boolean) = onMain {
        if (destroyed) return@onMain
        val wv = web
        if (wv == null || offlineFor != null || !isWeb(pageUrl)) {
            if (text.isNotBlank()) open(text, driver)
            return@onMain
        }
        runJs(WebScripts.type(text, submit)) { raw ->
            when (WebScripts.decodeString(raw)) {
                "none" -> if (text.isNotBlank()) open(text, driver)
                "frame" -> if (web === wv) typeWithKeys(wv, text, submit)
                null -> if (!fieldFocused && text.isNotBlank()) open(text, driver)
            }
        }
    }

    fun zoom(delta: Int) = onMain {
        if (destroyed || delta == 0) return@onMain
        val next = (textZoom + ZOOM_STEP * delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val changed = next != textZoom
        textZoom = next
        web?.settings?.textZoom = next
        if (changed) prefs.edit().putInt(PREF_ZOOM, next).apply()
        showNotice(
            when {
                !changed && delta > 0 -> "Text size $next%, as big as it gets"
                !changed -> "Text size $next%, as small as it gets"
                else -> "Text size $next%"
            },
        )
    }

    /** Readable page text via Readability.js. Callback on the main thread; null if extraction failed. */
    fun extract(callback: (PageText?) -> Unit) {
        onMain { startExtract(callback) }
    }

    fun onResume() = onMain {
        paused = false
        web?.let { wv ->
            runCatching { wv.onResume() }
            runCatching { wv.resumeTimers() }
        }
    }

    fun onPause() = onMain {
        paused = true
        motion.releaseAll()
        sendPendingUp()
        web?.let { wv ->
            runCatching { wv.onPause() }
            runCatching { wv.pauseTimers() }
        }
        runCatching { CookieManager.getInstance().flush() }
    }

    fun destroy() = onMain {
        if (destroyed) return@onMain
        destroyed = true
        motion.releaseAll()
        stopLoop()
        removeCallbacks(upRunnable)
        removeCallbacks(hoverRunnable)
        removeCallbacks(stripHideRunnable)
        removeCallbacks(noticeHideRunnable)
        removeCallbacks(warmUpRunnable)
        pendingUp = null
        exitFullscreen(userAsked = true)
        if (fieldFocused) setField(false, null)
        web?.let { releaseWebView(it) }
        web = null
    }

    // ------------------------------------------------------------------------------------- WebView

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Creating Chromium is slow; do it just after the first frame so Home appears instantly.
        if (web == null && !destroyed) postDelayed(warmUpRunnable, WARM_UP_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(warmUpRunnable)
        motion.releaseAll()
        stopLoop()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) motion.releaseAll() // a dialog or the keyboard took the keys
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != View.VISIBLE) motion.releaseAll()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!cursorPlaced) {
            cursorPlaced = true
            cursor.place(w / 2f, h * 0.46f)
        } else {
            cursor.place(clampX(cursor.cursorX), clampY(cursor.cursorY))
        }
        targetX = clampX(targetX)
        targetY = clampY(targetY)
    }

    private fun ensureWebView(): WebView? {
        web?.let { return it }
        if (destroyed) return null
        if (engineMissingView != null && SystemClock.uptimeMillis() - engineFailedAt < ENGINE_RETRY_MS) return null
        val wv = try {
            createWebView()
        } catch (t: Throwable) {
            // No WebView provider (being updated, or missing on this device). Say so kindly.
            Log.e(TAG, "WebView unavailable", t)
            engineFailedAt = SystemClock.uptimeMillis()
            showEngineMissing()
            return null
        }
        engineMissingView?.let { removeView(it) }
        engineMissingView = null
        web = wv
        addView(wv, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        if (paused) runCatching { wv.onPause() } else runCatching { wv.resumeTimers() }
        return wv
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(): WebView {
        if (BuildConfig.DEBUG && !debuggingEnabled) {
            debuggingEnabled = true
            runCatching { WebView.setWebContentsDebuggingEnabled(true) }
        }
        val wv = WebView(context)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.textZoom = textZoom
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(false) // target=_blank and window.open stay in this one view
        s.allowFileAccess = false // file:///android_asset still works for our offline page
        s.allowContentAccess = false
        s.setGeolocationEnabled(false)
        s.loadsImagesAutomatically = true
        val ua = s.userAgentString.orEmpty()
        if (!ua.contains(UA_SUFFIX.trim())) s.userAgentString = ua + UA_SUFFIX
        if (Build.VERSION.SDK_INT >= 26) runCatching { s.safeBrowsingEnabled = true }
        if (Build.VERSION.SDK_INT >= 27 && !safeBrowsingStarted) {
            safeBrowsingStarted = true
            runCatching { WebView.startSafeBrowsing(context.applicationContext, null) }
        }

        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        wv.setBackgroundColor(Palette.INK)
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { _, _, _, _, _ -> showNotice(NOTICE_DOWNLOAD) }
        wv.addJavascriptInterface(FieldBridge(this), WebScripts.BRIDGE_NAME)

        runCatching {
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(wv, false)
        }
        return wv
    }

    private fun releaseWebView(wv: WebView) {
        runCatching { removeView(wv) }
        runCatching { wv.stopLoading() }
        runCatching { wv.removeJavascriptInterface(WebScripts.BRIDGE_NAME) }
        runCatching { wv.setDownloadListener(null) }
        runCatching { wv.webChromeClient = null }
        runCatching { wv.webViewClient = WebViewClient() }
        runCatching { wv.destroy() }
        hoverInFlightSince = 0L
    }

    private fun showEngineMissing() {
        if (engineMissingView != null) return
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = LampBackdrop(11)
        }
        box.addView(Ui.text(context, TypeScale.H1, Palette.CREAM, Fonts.display, "The TV's web engine is napping"))
        box.addView(
            Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body, "It may be updating. Give it a minute, then open the page again.").apply {
                setPadding(0, dpi(14f), 0, 0)
            },
        )
        engineMissingView = box
        addView(box, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** The renderer died (out of memory on a small stick, usually). Rebuild in place, never crash. */
    private fun recoverFromRendererCrash(dead: WebView) {
        if (dead !== web) return
        val resume = (offlineFor ?: pageUrl).takeIf { isWeb(it) } ?: lastRequestedUrl
        exitFullscreen(userAsked = false)
        sendPendingUp()
        releaseWebView(dead)
        web = null
        loading = false
        if (fieldFocused) setField(false, null)
        val now = SystemClock.uptimeMillis()
        crashTimes.add(now)
        crashTimes.removeAll { now - it > CRASH_WINDOW_MS }
        val wv = ensureWebView() ?: return
        if (resume == null) return
        if (crashTimes.size >= CRASH_LIMIT) {
            showOffline(OfflinePage.Kind.CRASHED, resume, null)
        } else {
            offlineFor = null
            pendingNavUrl = resume
            wv.loadUrl(resume)
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            guard("shouldOverride", true) {
                val url = request.url?.toString() ?: return@guard true
                routeUrl(view, url, request.isForMainFrame, request.hasGesture())
            }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) = guardUnit("onPageStarted") {
            if (view !== web) return@guardUnit
            val u = url.orEmpty()
            injectLib(view)
            if (!isWeb(u)) return@guardUnit // our offline page or about:blank
            if (offlineFor != null && sameUrl(u, offlineFor)) return@guardUnit // late echo of the failed load
            offlineFor = null
            navId++
            loading = true
            pendingNavUrl = u
            pageUrl = u
            pageTitle = ""
            view.setBackgroundColor(Color.WHITE) // pages that don't paint a background expect white
            strip.setPage("", u)
            strip.setIcon(favicon)
            revealStrip()
            if (fieldFocused) setField(false, null)
            safeListener { onPageStarted(u) }
        }

        override fun onPageCommitVisible(view: WebView, url: String?) = guardUnit("onPageCommitVisible") {
            if (view === web) injectLib(view)
        }

        override fun onPageFinished(view: WebView, url: String?) = guardUnit("onPageFinished") {
            if (view !== web) return@guardUnit
            val u = url.orEmpty()
            injectLib(view)
            if (!isWeb(u)) {
                // Our offline page finished painting.
                loading = false
                strip.setProgress(100)
                scheduleStripHide(STRIP_HIDE_MS)
                return@guardUnit
            }
            if (offlineFor != null && sameUrl(u, offlineFor)) return@guardUnit
            loading = false
            pageUrl = u
            // view.title can lag behind onReceivedTitle; keep the better of the two.
            val fromView = PageTexts.collapse(view.title.orEmpty())
            pageTitle = if (fromView.isNotEmpty() && !isWeb(fromView)) cleanTitle(fromView, u) else pageTitle.ifEmpty { cleanTitle(null, u) }
            strip.setPage(pageTitle, u)
            strip.setProgress(100)
            scheduleStripHide(STRIP_HIDE_MS)
            scheduleHover()
            if (reportedFinish != navId) {
                reportedFinish = navId
                failedUrls.remove(u)
                val t = pageTitle
                safeListener { onPageFinished(u, t) }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) = guardUnit("history") {
            // Single-page sites change the URL without a new load; keep the strip honest.
            val u = url.orEmpty()
            if (view !== web || !isWeb(u) || offlineFor != null) return@guardUnit
            if (!sameUrl(u, pageUrl)) {
                pageUrl = u
                strip.setPage(pageTitle, u)
            }
        }

        // WebViewClient's own onReceivedError(view, request, error) forwards main-frame failures (and only
        // those) to this callback, so subresource errors never replace the page.
        @Deprecated("Reached through WebViewClient's main-frame forwarding on every API level")
        override fun onReceivedError(view: WebView, errorCode: Int, description: String?, failingUrl: String?) = guardUnit("onReceivedError") {
            val failing = failingUrl.orEmpty()
            if (view !== web || !isWeb(failing)) return@guardUnit
            rememberFailure(failing)
            showOffline(OfflinePage.kindFor(errorCode, isOnline()), failing, description)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            runCatching { handler.cancel() } // never proceed past a bad certificate
            guardUnit("onReceivedSslError") {
                if (view !== web) return@guardUnit
                val u = error.url.orEmpty()
                val mainFrame = sameUrl(u, pendingNavUrl) || sameUrl(u, lastRequestedUrl) || sameUrl(u, view.url)
                if (mainFrame && isWeb(u)) {
                    rememberFailure(u)
                    showOffline(OfflinePage.Kind.SECURITY, u, "Certificate problem")
                }
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail?): Boolean {
            Log.w(TAG, "renderer gone (crashed=${if (Build.VERSION.SDK_INT >= 26) detail?.didCrash() else null})")
            guardUnit("onRenderProcessGone") { recoverFromRendererCrash(view) }
            return true
        }
    }

    private val blankPoster: Bitmap? by lazy {
        runCatching { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.TRANSPARENT) } }.getOrNull()
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) = guardUnit("progress") {
            if (view !== web) return@guardUnit
            strip.setProgress(newProgress)
            if (newProgress < 100 && loading) revealStrip()
        }

        override fun onReceivedTitle(view: WebView, title: String?) = guardUnit("title") {
            if (view !== web || offlineFor != null || !isWeb(pageUrl)) return@guardUnit
            val t = cleanTitle(title, pageUrl)
            if (t.isEmpty()) return@guardUnit
            pageTitle = t
            strip.setPage(t, pageUrl)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) = guardUnit("icon") {
            if (view === web && offlineFor == null) strip.setIcon(icon)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) = guardUnit("fullscreen") {
            enterFullscreen(view, callback)
        }

        override fun onHideCustomView() = guardUnit("exitFullscreen") { exitFullscreen(userAsked = false) }

        // Chromium's default poster is a grey play button; a transparent pixel looks calmer.
        override fun getDefaultVideoPoster(): Bitmap? = blankPoster

        // Page dialogs would steal the remote from the cursor. Alerts become a notice; the rest decline.
        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            runCatching { result?.confirm() }
            message?.let { m -> PageTexts.collapse(m).takeIf { it.isNotEmpty() }?.let { onMain { showNotice("The page says: ${it.take(48)}") } } }
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            runCatching { result?.cancel() }
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            runCatching { result?.cancel() }
            return true
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            runCatching { result?.confirm() } // "Leave site?": yes, the couch has decided
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            runCatching { request?.deny() } // no camera or microphone for web pages on the family TV
        }

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            runCatching { callback?.invoke(origin, false, false) }
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean = false

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
    }

    /** Which URLs load in the WebView. Web pages do; app links are swallowed with a gentle notice. */
    private fun routeUrl(view: WebView, url: String, mainFrame: Boolean, gesture: Boolean): Boolean {
        val scheme = Uri.parse(url).scheme?.lowercase()
        when (scheme) {
            "http", "https" -> {
                if (mainFrame) {
                    offlineFor = null
                    pendingNavUrl = url
                    lastRequestedUrl = url
                }
                return false
            }
            "about", "data", "blob" -> return false
            "intent" -> {
                // Many "open in app" links carry a web fallback. Use it when they do.
                val fallback = runCatching {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                }.getOrNull()
                if (mainFrame && fallback != null && isWeb(fallback)) {
                    view.loadUrl(fallback)
                    return true
                }
            }
        }
        if (mainFrame || gesture) showNotice(NOTICE_OTHER_APP)
        return true
    }

    private fun showOffline(kind: OfflinePage.Kind, failingUrl: String, detail: String?) {
        val wv = web ?: return
        val now = SystemClock.uptimeMillis()
        if (offlineFor != null && sameUrl(offlineFor, failingUrl) && now - offlineShownAt < OFFLINE_DEDUPE_MS) return
        offlineFor = failingUrl
        offlineShownAt = now
        loading = false
        pageUrl = failingUrl
        pageTitle = OfflinePage.title(kind)
        strip.setPage(pageTitle, failingUrl)
        strip.setIcon(null)
        revealStrip()
        if (fieldFocused) setField(false, null)
        wv.setBackgroundColor(Palette.INK)
        wv.loadDataWithBaseURL(OfflinePage.BASE_URL, OfflinePage.html(kind, failingUrl, detail), "text/html", "utf-8", null)
    }

    /** Remembers a URL that failed (so Back can skip its error entry); keeps only the latest few. */
    private fun rememberFailure(url: String) {
        failedUrls.remove(url)
        failedUrls.add(url)
        while (failedUrls.size > MAX_FAILED_URLS) failedUrls.remove(failedUrls.first())
    }

    private fun isOnline(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        @Suppress("DEPRECATION")
        cm?.activeNetworkInfo?.isConnected ?: true
    } catch (t: Throwable) {
        true
    }

    // ---------------------------------------------------------------------------------- fullscreen

    private fun enterFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) return
        if (customView != null) {
            runCatching { callback?.onCustomViewHidden() }
            return
        }
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        customView = view
        customCallback = callback
        view.setBackgroundColor(Color.BLACK)
        addView(view, indexOfChild(strip).coerceAtLeast(0), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        strip.conceal()
        cursor.fadeOut() // a clean picture; any D-pad press brings the cursor back
        cursor.setHover(0)
    }

    private fun exitFullscreen(userAsked: Boolean) {
        val v = customView ?: return
        val cb = customCallback
        customView = null
        customCallback = null
        runCatching { removeView(v) }
        if (userAsked) runCatching { cb?.onCustomViewHidden() }
        scheduleHover()
    }

    private fun mediaAction(action: String) {
        runJs(WebScripts.media(action))
    }

    // --------------------------------------------------------------------------------- remote keys

    private fun onDirectionDown(dir: CursorMotion.Dir, repeatCount: Int) {
        val now = SystemClock.uptimeMillis()
        setDriver(null)
        if (repeatCount > 0 || motion.isHeld(dir)) {
            motion.keepAlive(dir, now)
            cursor.wake()
            startLoop()
            return
        }
        val wasAwake = cursor.isAwake
        val step = motion.press(dir, now)
        startLoop()
        if (!wasAwake) {
            cursor.wake() // first press only shows where the cursor is
            return
        }
        snapSmoothing()
        val beforeX = cursor.cursorX
        val beforeY = cursor.cursorY
        moveCursorTo(beforeX + step.x * density, beforeY + step.y * density)
        val moved = abs(cursor.cursorX - beforeX) + abs(cursor.cursorY - beforeY)
        if (moved < motion.stepDp * density * 0.4f) {
            // Pressed against an edge: nudge the page that way instead.
            nudgeScroll(dir)
        }
    }

    private fun remoteTap() {
        setDriver(null)
        if (!cursor.isAwake) {
            cursor.wake() // show where we are before clicking anything
            return
        }
        tapAtCursor()
    }

    private fun pageKey(dir: Int, repeatCount: Int) {
        val now = SystemClock.uptimeMillis()
        if (repeatCount > 0 && now - lastPageKeyAt < PAGE_REPEAT_MS) return
        lastPageKeyAt = now
        if (customView != null) {
            mediaAction(if (dir < 0) "back" else "fwd")
            return
        }
        val wv = web ?: return
        val (fx, fy) = cursorFractions(wv)
        runJs(WebScripts.page(fx, fy, dir)) { raw ->
            if (WebScripts.decodeString(raw) == null && web === wv) {
                runCatching { if (dir < 0) wv.pageUp(false) else wv.pageDown(false) }
            }
        }
        reportScroll(force = true)
    }

    // ------------------------------------------------------------------------------------- cursor

    private fun clampX(x: Float): Float {
        val inset = dp(CURSOR_INSET_DP)
        return if (width <= 0) x else x.coerceIn(inset, (width - inset).coerceAtLeast(inset))
    }

    private fun clampY(y: Float): Float {
        val inset = dp(CURSOR_INSET_DP)
        return if (height <= 0) y else y.coerceIn(inset, (height - inset).coerceAtLeast(inset))
    }

    private fun moveCursorTo(x: Float, y: Float) {
        val nx = clampX(x)
        val ny = clampY(y)
        if (nx == cursor.cursorX && ny == cursor.cursorY) {
            cursor.wake()
            return
        }
        cursor.moveTo(nx, ny)
        updateStripDim()
        scheduleHover()
    }

    private fun snapSmoothing() {
        if (!smoothing) return
        smoothing = false
        moveCursorTo(targetX, targetY)
    }

    private fun setDriver(p: Player?) {
        val key = p?.id ?: REMOTE_KEY
        val name = p?.name?.trim()?.takeIf { it.isNotEmpty() } ?: if (p == null) "Remote" else Palette.PLAYER_COLOR_NAMES[((p.colorIndex % 4) + 4) % 4]
        val color = if (p == null) Palette.LAMP else Palette.player(p.colorIndex)
        driver = p
        cursor.setColor(color)
        strip.setDriver(name, color)
        if (key != driverKey) {
            driverKey = key
            cursor.showName(name, color)
        }
    }

    private fun updateStripDim() {
        if (!strip.revealed) {
            strip.setDimmed(false)
            return
        }
        val m = dp(18f)
        val over = cursor.cursorY < strip.bottom + m && cursor.cursorX > strip.left - m && cursor.cursorX < strip.right + m
        strip.setDimmed(over)
    }

    private fun cursorFractions(wv: View): Pair<Float, Float> {
        val w = wv.width.takeIf { it > 0 } ?: width.coerceAtLeast(1)
        val h = wv.height.takeIf { it > 0 } ?: height.coerceAtLeast(1)
        val fx = ((cursor.cursorX - wv.left) / w).coerceIn(0f, 1f)
        val fy = ((cursor.cursorY - wv.top) / h).coerceIn(0f, 1f)
        return fx to fy
    }

    // ---------------------------------------------------------------------------------- tapping

    private fun tapAtCursor() {
        snapSmoothing()
        val target = customView ?: web ?: return
        sendPendingUp()
        if (target === web && !target.hasFocus()) runCatching { target.requestFocus() }
        val x = cursor.cursorX - target.left - target.translationX
        val y = cursor.cursorY - target.top - target.translationY
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(target, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        pendingUp = PendingUp(target, downTime, x, y)
        postDelayed(upRunnable, TAP_UP_DELAY_MS)
        cursor.ripple()
        safeListener { onUserClick() }
    }

    private fun sendPendingUp() {
        val p = pendingUp ?: return
        pendingUp = null
        removeCallbacks(upRunnable)
        dispatchTouch(p.target, p.downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, p.x, p.y)
        scheduleHover()
    }

    private fun dispatchTouch(target: View, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f })
        val ev = MotionEvent.obtain(downTime, eventTime, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try {
            target.dispatchTouchEvent(ev)
        } catch (t: Throwable) {
            Log.w(TAG, "touch dispatch failed", t)
        } finally {
            ev.recycle()
        }
    }

    // -------------------------------------------------------------------------------- frame loop

    private fun startLoop() {
        if (looping || destroyed) return
        looping = true
        lastFrameAt = SystemClock.uptimeMillis()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) }
    }

    private fun onFrame() {
        if (!looping) return
        looping = false
        if (destroyed) return
        var more = false
        try {
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrameAt).toFloat().coerceIn(1f, MAX_FRAME_MS)
            lastFrameAt = now

            if (motion.active) {
                val d = motion.frame(now, dt)
                if (!d.isZero) {
                    snapSmoothing()
                    moveCursorTo(cursor.cursorX + d.x * density, cursor.cursorY + d.y * density)
                }
                if (customView == null && width > 0 && height > 0) {
                    val es = motion.edgeScroll(now, cursor.cursorX / density, cursor.cursorY / density, width / density, height / density)
                    if (!es.isZero) {
                        scrollPageBy(es.x * dt / 1000f * density, es.y * dt / 1000f * density)
                        reportScroll(force = false)
                    }
                }
                more = motion.active
            }

            if (smoothing) {
                val k = 1f - exp(-dt / PHONE_SMOOTH_TAU_MS)
                val nx = cursor.cursorX + (targetX - cursor.cursorX) * k
                val ny = cursor.cursorY + (targetY - cursor.cursorY) * k
                if (abs(targetX - nx) < 0.5f && abs(targetY - ny) < 0.5f) {
                    smoothing = false
                    moveCursorTo(targetX, targetY)
                } else {
                    moveCursorTo(nx, ny)
                    more = true
                }
            }

            if (phoneScrollX != 0f || phoneScrollY != 0f) {
                flushPhoneScroll()
            }
            if (jsAccX != 0f || jsAccY != 0f) {
                if (now - lastJsScrollAt >= JS_SCROLL_INTERVAL_MS) flushJsScroll(now) else more = true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame failed", t)
        }
        if (more) {
            looping = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    // --------------------------------------------------------------------------------- scrolling

    /** Edge scrolling: native when the page's own scroller can move (cheapest), else via JS. */
    private fun scrollPageBy(dx: Float, dy: Float) {
        val wv = web ?: return
        val vOk = dy == 0f || wv.canScrollVertically(if (dy > 0) 1 else -1)
        val hOk = dx == 0f || wv.canScrollHorizontally(if (dx > 0) 1 else -1)
        if (vOk && hOk) {
            nativeAccX += dx
            nativeAccY += dy
            val ix = nativeAccX.toInt()
            val iy = nativeAccY.toInt()
            nativeAccX -= ix
            nativeAccY -= iy
            if (ix != 0 || iy != 0) wv.scrollBy(ix, iy)
        } else {
            jsAccX += dx
            jsAccY += dy
            val now = SystemClock.uptimeMillis()
            if (now - lastJsScrollAt >= JS_SCROLL_INTERVAL_MS) flushJsScroll(now)
        }
    }

    /** The page's on-screen size in px (the layer's while the WebView hasn't been laid out yet). */
    private fun viewportPx(wv: View): Pair<Float, Float>? {
        val w = (if (wv.width > 0) wv.width else width).toFloat()
        val h = (if (wv.height > 0) wv.height else height).toFloat()
        return if (w > 0f && h > 0f) w to h else null
    }

    private fun flushJsScroll(now: Long) {
        val dx = jsAccX
        val dy = jsAccY
        jsAccX = 0f
        jsAccY = 0f
        lastJsScrollAt = now
        val wv = web ?: return
        val (w, h) = viewportPx(wv) ?: return
        val (fx, fy) = cursorFractions(wv)
        runJs(WebScripts.scrollAt(fx, fy, dx / w, dy / h, smooth = false))
    }

    private fun flushPhoneScroll() {
        // Phone deltas are CSS px on the phone; scale them like a TV-sized finger (dp) on this screen.
        val dxDp = phoneScrollX
        val dyDp = phoneScrollY
        phoneScrollX = 0f
        phoneScrollY = 0f
        val wv = web ?: return
        if (customView != null) return
        val (w, h) = viewportPx(wv) ?: return
        val (fx, fy) = cursorFractions(wv)
        runJs(WebScripts.scrollAt(fx, fy, dxDp * density / w, dyDp * density / h, smooth = false))
    }

    private fun nudgeScroll(dir: CursorMotion.Dir) {
        val wv = web ?: return
        if (customView != null) return
        val (w, h) = viewportPx(wv) ?: return
        val (fx, fy) = cursorFractions(wv)
        val d = NUDGE_DP * density
        runJs(WebScripts.scrollAt(fx, fy, dir.sx * d / w, dir.sy * d / h, smooth = true))
        reportScroll(force = true)
    }

    private fun reportScroll(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastScrollReport < SCROLL_REPORT_MS) return
        lastScrollReport = now
        safeListener { onUserScroll() }
    }

    // ------------------------------------------------------------------------------------- hover

    private fun scheduleHover() {
        hoverDirty = true
        if (hoverPosted) return
        hoverPosted = true
        postDelayed(hoverRunnable, HOVER_INTERVAL_MS)
    }

    private fun queryHover() {
        val wv = web
        if (wv == null || customView != null || destroyed) {
            cursor.setHover(0)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (hoverInFlightSince != 0L && now - hoverInFlightSince < HOVER_STALE_MS) return // the answer will re-check
        hoverDirty = false
        hoverInFlightSince = now
        val (fx, fy) = cursorFractions(wv)
        runJs(WebScripts.hit(fx, fy), retryOnNoLib = false) { raw ->
            hoverInFlightSince = 0L
            if (web !== wv) return@runJs
            cursor.setHover(WebScripts.decodeInt(raw))
            if (hoverDirty) scheduleHover()
        }
    }

    // ------------------------------------------------------------------------------- javascript

    private fun injectLib(wv: WebView) {
        val lib = WebScripts.lib
        if (lib != null) {
            runCatching { wv.evaluateJavascript(lib, null) }
        } else {
            WebScripts.preload(context) { if (web === wv) WebScripts.lib?.let { s -> runCatching { wv.evaluateJavascript(s, null) } } }
        }
    }

    /** Evaluates [script]; when the page lost our helper, injects it and tries once more. */
    private fun runJs(script: String, retryOnNoLib: Boolean = true, onResult: ((String?) -> Unit)? = null) {
        val wv = web
        if (wv == null) {
            onResult?.invoke(null)
            return
        }
        try {
            wv.evaluateJavascript(script) { raw ->
                guardUnit("js result") {
                    if (raw == NO_LIB_RESULT) {
                        if (web !== wv) {
                            onResult?.invoke(null)
                        } else {
                            injectLib(wv)
                            if (retryOnNoLib) runJs(script, false, onResult) else onResult?.invoke(null)
                        }
                    } else {
                        onResult?.invoke(raw)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "evaluateJavascript failed", t)
            onResult?.invoke(null)
        }
    }

    // ---------------------------------------------------------------------------------- extract

    private fun startExtract(callback: (PageText?) -> Unit) {
        // Async (not View.post): a detached view would hold its posts until re-attached.
        var done = false
        var timeout: Runnable? = null
        val deliver: (PageText?) -> Unit = { result ->
            if (!done) {
                done = true
                Async.cancel(timeout)
                try {
                    callback(result)
                } catch (t: Throwable) {
                    Log.e(TAG, "extract callback failed", t)
                }
            }
        }
        timeout = Async.mainDelayed(EXTRACT_TIMEOUT_MS) { deliver(null) }
        val wv = web
        if (destroyed || wv == null || offlineFor != null || !isWeb(pageUrl)) {
            Async.main { deliver(null) }
            return
        }
        WebScripts.preload(context) { runExtract(wv, 0, deliver) }
    }

    private fun runExtract(wv: WebView, attempt: Int, deliver: (PageText?) -> Unit) {
        if (wv !== web || destroyed) {
            deliver(null)
            return
        }
        val fallbackUrl = pageUrl
        try {
            wv.evaluateJavascript(WebScripts.extract(EXTRACT_JS_CHARS)) { raw ->
                Async.io {
                    val decoded = WebScripts.decodeString(raw)
                    when (decoded) {
                        WebScripts.NO_LIB -> Async.main {
                            if (attempt < 2 && wv === web) {
                                injectLib(wv)
                                runExtract(wv, attempt + 1, deliver)
                            } else {
                                deliver(null)
                            }
                        }
                        WebScripts.NO_READABILITY -> Async.main {
                            if (attempt < 2 && wv === web) {
                                runCatching { wv.evaluateJavascript(WebScripts.MARK_READABILITY_TRIED, null) }
                                WebScripts.readability?.let { src -> runCatching { wv.evaluateJavascript(src, null) } }
                                runExtract(wv, attempt + 1, deliver)
                            } else {
                                deliver(null)
                            }
                        }
                        else -> {
                            val page = decoded?.let { parsePageText(it, fallbackUrl) }
                            Async.main { deliver(page) }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "extract failed", t)
            deliver(null)
        }
    }

    private fun parsePageText(json: String, fallbackUrl: String): PageText? = try {
        val o = JSONObject(json)
        val url = o.optString("url").takeIf { isWeb(it) } ?: fallbackUrl
        val heads = ArrayList<String>()
        o.optJSONArray("headings")?.let { arr -> for (i in 0 until arr.length()) arr.optString(i)?.let { heads.add(it) } }
        val site = if (o.isNull("siteName")) null else o.optString("siteName")
        PageTexts.build(url, o.optString("title"), o.optString("text"), heads, site)
    } catch (t: Throwable) {
        Log.w(TAG, "bad extract result", t)
        null
    }

    // ------------------------------------------------------------------------------- text fields

    /**
     * For a field inside a cross-origin frame (which our script can't reach): real key events, sent to
     * the WebView, land wherever the page's focus is. Characters a keyboard can't produce are skipped.
     */
    private fun typeWithKeys(wv: WebView, text: String, submit: Boolean) {
        if (!wv.hasFocus()) runCatching { wv.requestFocus() }
        for (e in keyEventsFor(text, submit)) runCatching { wv.dispatchKeyEvent(e) }
    }

    /** Called on the main thread with the page's latest focus state. */
    private fun onFieldFromPage(focused: Boolean, label: String?) {
        if (destroyed) return
        if (focused && SystemClock.uptimeMillis() - lastPhoneTapAt < PHONE_TAP_IME_WINDOW_MS) {
            // A phone tapped this field, so the phone is the keyboard: keep the TV's own one away.
            post { hideIme() }
        }
        setField(focused, label)
    }

    private fun setField(focused: Boolean, label: String?) {
        val l = if (focused) label else null
        if (focused == fieldFocused && l == fieldLabel) return
        fieldFocused = focused
        fieldLabel = l
        safeListener { onTextFieldFocus(focused, l) }
    }

    private fun hideIme() {
        val wv = web ?: return
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(wv.windowToken, 0)
        }
    }

    /** The one object pages can reach. It only ever reports "a text field is (not) focused". */
    private class FieldBridge(layer: BrowseLayer) {
        private val ref = WeakReference(layer)
        private val latest = AtomicReference<Pair<Boolean, String?>>(false to null)
        private val posted = AtomicBoolean(false)

        @JavascriptInterface
        fun onField(focused: Boolean, label: String?) {
            latest.set(focused to sanitizeLabel(label))
            if (posted.compareAndSet(false, true)) {
                Async.main {
                    posted.set(false)
                    val (f, l) = latest.get()
                    try {
                        ref.get()?.onFieldFromPage(f, l)
                    } catch (t: Throwable) {
                        Log.w(TAG, "field update failed", t)
                    }
                }
            }
        }

        private fun sanitizeLabel(label: String?): String? {
            if (label == null) return null
            val clean = buildString { for (c in label) if (!c.isISOControl()) append(c) }.trim().take(40)
            return clean.ifEmpty { "text box" }
        }
    }

    // ----------------------------------------------------------------------------------- strip

    private fun revealStrip() {
        if (customView != null) return
        removeCallbacks(stripHideRunnable)
        strip.reveal()
        updateStripDim()
    }

    private fun scheduleStripHide(delay: Long) {
        removeCallbacks(stripHideRunnable)
        postDelayed(stripHideRunnable, delay)
    }

    private fun maybeConcealStrip() {
        if (loading || noticeActive) return
        strip.conceal()
        strip.setDimmed(false)
    }

    private fun showNotice(text: String) {
        if (destroyed) return
        noticeActive = true
        strip.setNotice(text)
        if (customView == null) revealStrip()
        removeCallbacks(noticeHideRunnable)
        postDelayed(noticeHideRunnable, NOTICE_MS)
    }

    // ---------------------------------------------------------------------------------- helpers

    /** A title worth showing: no bare URLs, no trailing " - Wikipedia" (the domain chip says that). */
    private fun cleanTitle(raw: String?, url: String): String {
        val t = PageTexts.collapse(raw.orEmpty())
        if (t.isEmpty() || isWeb(t) || sameUrl(t, url)) return PageTexts.hostLabel(url)
        return PageTexts.displayTitle(t, url)
    }

    private fun safeListener(block: Listener.() -> Unit) {
        try {
            listener.block()
        } catch (t: Throwable) {
            Log.e(TAG, "listener failed", t)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try {
                block()
            } catch (t: Throwable) {
                Log.e(TAG, "browse action failed", t)
            }
        } else {
            Async.main {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.e(TAG, "browse action failed", t)
                }
            }
        }
    }

    private inline fun <T> guard(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        Log.e(TAG, "$what failed", t)
        fallback
    }

    private inline fun guardUnit(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$what failed", t)
        }
    }

    private companion object {
        const val TAG = "WanderBrowse"
        const val PREFS = "wander_browse"
        const val PREF_ZOOM = "text_zoom"
        const val UA_SUFFIX = " WanderTV/1.0"
        const val REMOTE_KEY = "remote"
        const val NO_LIB_RESULT = "\"" + WebScripts.NO_LIB + "\""

        const val DEFAULT_ZOOM = 115
        const val MIN_ZOOM = 80
        const val MAX_ZOOM = 200
        const val ZOOM_STEP = 10

        const val STRIP_HEIGHT_DP = 58f
        const val STRIP_HIDE_MS = 3000L
        const val NOTICE_MS = 3200L
        const val WARM_UP_DELAY_MS = 450L
        const val ENGINE_RETRY_MS = 10_000L
        const val CRASH_WINDOW_MS = 60_000L
        const val CRASH_LIMIT = 3
        const val OFFLINE_DEDUPE_MS = 1500L
        const val MAX_FAILED_URLS = 32

        const val CURSOR_INSET_DP = 2f
        const val TAP_UP_DELAY_MS = 40L
        const val MAX_FRAME_MS = 50f
        const val PHONE_SMOOTH_TAU_MS = 28f
        const val PHONE_SCROLL_GAIN = 2.2f
        const val PHONE_TAP_IME_WINDOW_MS = 2000L
        const val NUDGE_DP = 110f
        const val JS_SCROLL_INTERVAL_MS = 33L
        const val SCROLL_REPORT_MS = 250L
        const val PAGE_REPEAT_MS = 420L
        const val HOVER_INTERVAL_MS = 90L
        const val HOVER_STALE_MS = 800L
        const val EXTRACT_TIMEOUT_MS = 4000L
        const val EXTRACT_JS_CHARS = 30000

        const val NOTICE_OTHER_APP = "That link wants another app"
        const val NOTICE_DOWNLOAD = "That's a download. TVs don't keep files"

        var debuggingEnabled = false
        var safeBrowsingStarted = false

        fun isWeb(url: String?): Boolean =
            url != null && (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

        /** Same page, ignoring a trailing slash and the #fragment. */
        fun sameUrl(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            fun norm(s: String) = s.substringBefore('#').trimEnd('/')
            return norm(a).equals(norm(b), ignoreCase = false)
        }
    }
}

/**
 * The key presses that type [text] on a virtual keyboard (characters it can't produce are skipped),
 * plus Enter when [submit]. Used for fields our page script can't reach.
 */
internal fun keyEventsFor(text: String, submit: Boolean): List<KeyEvent> {
    val out = ArrayList<KeyEvent>()
    val map = runCatching { KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD) }.getOrNull()
    if (map != null) {
        for (c in text) {
            val events = runCatching { map.getEvents(charArrayOf(c)) }.getOrNull() ?: continue
            out.addAll(events)
        }
    }
    if (submit) {
        val t = SystemClock.uptimeMillis()
        out.add(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
        out.add(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
    }
    return out
}
