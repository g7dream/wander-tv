package tv.wander

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SparseArray
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import dev.couchlink.AssetSource
import tv.wander.ai.AiClient
import tv.wander.browse.BrowseLayer
import tv.wander.cards.CardsOverlay
import tv.wander.companion.CompanionLayer
import tv.wander.companion.CompanionPanel
import tv.wander.companion.DriftDetector
import tv.wander.companion.Lines
import tv.wander.home.Detours
import tv.wander.home.Favorite
import tv.wander.home.HomeView
import tv.wander.home.LumoActionBar
import tv.wander.home.ToastChip
import tv.wander.model.Card
import tv.wander.model.CompanionAdvice
import tv.wander.model.DriftReason
import tv.wander.model.DriftSignal
import tv.wander.model.Mode
import tv.wander.model.Mood
import tv.wander.model.PageText
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.QuestOutcome
import tv.wander.model.RescueOption
import tv.wander.model.TvState
import tv.wander.model.Verdict
import tv.wander.net.LanAddress
import tv.wander.net.PhoneCommand
import tv.wander.net.PhoneHub
import tv.wander.net.Qr
import tv.wander.quest.QuestBoardView
import tv.wander.quest.QuestCatalog
import tv.wander.quest.QuestEngine
import tv.wander.quest.QuestOverlay
import tv.wander.rescue.RescueOverlay
import tv.wander.speech.Narrator
import tv.wander.ui.Async
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import java.util.Calendar
import kotlin.math.abs

/** Single activity. Stacks Home, Browse, overlays and Lumo, routes remote keys and phone commands by mode. */
class MainActivity : Activity() {
    // ---- layers (bottom to top) -------------------------------------------------------------------
    private lateinit var root: FrameLayout
    private lateinit var home: HomeView
    private var browse: BrowseLayer? = null
    private lateinit var questOverlay: QuestOverlay
    private lateinit var cards: CardsOverlay
    private lateinit var rescue: RescueOverlay
    private lateinit var panel: CompanionPanel
    private lateinit var actionBar: LumoActionBar
    private lateinit var lumo: CompanionLayer
    private lateinit var toast: ToastChip

    // ---- services ---------------------------------------------------------------------------------
    private lateinit var config: Config
    private var hub: PhoneHub? = null
    private lateinit var ai: AiClient
    private var aiUrl = ""
    private var aiKey = ""
    private var narrator: Narrator? = null
    private lateinit var drift: DriftDetector
    private val quests = QuestEngine()

    // ---- state ------------------------------------------------------------------------------------
    private var alive = false
    /** False while something else covers the app (Alexa, settings): no nudges then. */
    private var resumed = false
    /** True between onStop and onStart: another app has the screen, so nothing polls. */
    private var stopped = false
    private var mode = Mode.HOME
    /** The screen under any overlay: HOME or BROWSE. */
    private var base = Mode.HOME
    private var aiOnline = false
    private var joinUrl: String? = null
    private var homeFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    private var browseFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS

    private var fieldFocused = false
    private var fieldLabel: String? = null
    private var homeSearchOpen = false
    private var cardIndex = 0
    private var cardCount = 0
    private var cardTitle: String? = null
    private var cardsRequest = 0
    private var checking = false
    private var questRequest = 0
    private var pickPending = false
    /** Bumped whenever a Lumo's pick in flight stops mattering (another quest started, picker closed). */
    private var pickToken = 0
    private var freshQuestStart = false
    private var finaleShowing = false
    private var boardKey: String? = null
    private var cardsLoading = false
    private var cardsWatch: Runnable? = null
    private var checkWatch: Runnable? = null
    private var advising = false
    private var rescueSignal: DriftSignal? = null
    private var rescueOptions: List<RescueOption> = emptyList()
    private var remotePlayer: Player? = null
    private val trail = ArrayDeque<String>()
    private var lumoPresent = true

    /** True while the phone hub is serving; it keeps retrying while its port is held by someone else. */
    private var hubReady = false
    private var hubTries = 0
    private val backgroundToldAt = HashMap<String, Long>()

    /** Pages opened since the family left Home, so Back returns Home instead of into last night's history. */
    private var pagesSinceHome = 0
    private var navigatingBack = false
    private var lastBackAt = 0L
    private var lastFieldHintAt = 0L

    /** Where each key's DOWN went, so its UP goes to the same place even if layers changed in between. */
    private val downSink = SparseArray<Sink>()

    private enum class Sink { PANEL, RESCUE, QUEST, CARDS, BAR, BROWSE, SHELL, SYSTEM }

    // Touchpad-as-D-pad for screens without a cursor.
    private var padX = 0f
    private var padY = 0f
    private var padAt = 0L

    private var statePending = false
    private var stateTask: Runnable? = null
    private var healthTask: Runnable? = null
    private var driftTask: Runnable? = null
    private var lanTask: Runnable? = null
    private var finaleTask: Runnable? = null
    private var greetTask: Runnable? = null
    private var hubTask: Runnable? = null
    private var voiceCheck: Runnable? = null

    // =================================================================================================
    // Lifecycle
    // =================================================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(this)
        Sfx.init(this)
        config = Config(this)
        config.applyIntent(intent)
        alive = true

        buildLayers()
        setContentView(root)
        startServices()

        setMode(Mode.HOME)
        root.post { if (alive && mode == Mode.HOME) home.focusDefault() }
        greetTask = Async.mainDelayed(900) { guard("greet") { greet() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        guard("newIntent") {
            config.applyIntent(intent)
            drift.demoMode = config.demoMode
            drift.enabled = config.nudgesOn
            if (config.apiUrl != aiUrl || config.apiKey != aiKey) {
                narrator?.shutdown()
                makeAi()
                checkHealth()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!alive || !stopped) return
        stopped = false
        guard("start") {
            checkHealth()
            refreshJoinInfo()
            publishState()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        guard("resume") { if (base == Mode.BROWSE) browse?.onResume() }
    }

    override fun onPause() {
        resumed = false
        guard("pause") {
            stopVoice()
            browse?.onPause()
        }
        super.onPause()
    }

    override fun onStop() {
        stopped = true
        // Another app has the screen: stop polling the backend and the network. Phones stay connected
        // (so they come straight back), but their commands wait until Wander is on screen again.
        Async.cancel(healthTask)
        Async.cancel(lanTask)
        healthTask = null
        lanTask = null
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        listOf(stateTask, healthTask, driftTask, lanTask, finaleTask, greetTask, cardsWatch, checkWatch, hubTask, voiceCheck).forEach { Async.cancel(it) }
        guard("destroy.hub") { hub?.stop() }
        guard("destroy.narrator") { narrator?.shutdown() }
        // Sfx is shared by the whole process; it only lets go once no activity is using it.
        guard("destroy.sfx") { Sfx.release() }
        guard("destroy.browse") { browse?.destroy() }
        hub = null
        narrator = null
        super.onDestroy()
    }

    private fun buildLayers() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.INK) }
        val match = { FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }

        home = HomeView(this, homeListener)
        home.onSearchFieldChanged = { open ->
            homeSearchOpen = open
            publishState()
        }
        root.addView(home, match())
        homeFocusability = home.descendantFocusability

        browse = try {
            BrowseLayer(this, browseListener)
        } catch (t: Throwable) {
            // No WebView on this device (or it's being updated). Home still works; we explain on first use.
            Log.e(TAG, "WebView unavailable", t)
            null
        }
        browse?.let {
            it.visibility = View.INVISIBLE
            root.addView(it, match())
            browseFocusability = it.descendantFocusability
        }

        questOverlay = QuestOverlay(this, questListener)
        cards = CardsOverlay(this, cardsListener)
        rescue = RescueOverlay(this, rescueListener)
        panel = CompanionPanel(this, panelListener)
        actionBar = LumoActionBar(this, actionBarListener)
        lumo = CompanionLayer(this)
        toast = ToastChip(this)
        listOf(questOverlay, cards, rescue, panel, actionBar, lumo, toast).forEach { root.addView(it, match()) }
    }

    private fun startServices() {
        val assetManager = applicationContext.assets
        hub = try {
            PhoneHub(hubPort, AssetSource { p -> runCatching { assetManager.open(p).use { it.readBytes() } }.getOrNull() }, hubListener, Async::main)
        } catch (t: Throwable) {
            Log.e(TAG, "phone hub failed to build", t)
            null
        }
        startHub()

        makeAi()
        drift = DriftDetector(
            store = PrefsStore(config.prefs),
            hourOf = { ms -> Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.HOUR_OF_DAY) },
        ).apply {
            demoMode = config.demoMode
            enabled = config.nudgesOn
        }

        refreshJoinInfo()
        checkHealth()
        scheduleDrift()
    }

    /** The phone port: [Config.PORT], unless a test points the hub at a free one. */
    private val hubPort: Int get() = Config.portOverride ?: Config.PORT

    /**
     * Starts serving phones. If the port is still held (typically by the Wander that was closing as this
     * one opened), keeps trying: quickly at first, then on a slow beat, and shows the join code only once
     * phones can really connect.
     */
    private fun startHub() {
        Async.cancel(hubTask)
        hubTask = null
        val h = hub
        val ok = h != null && runCatching { h.start() }.getOrElse { Log.w(TAG, "phone hub failed to start", it); false }
        val wasReady = hubReady
        hubReady = ok
        home.setPhonesReady(ok)
        if (ok) {
            hubTries = 0
            if (!wasReady) publishState()
            return
        }
        if (h == null) {
            toast.show("Phones can't join on this TV. The remote still works.", ToastChip.Kind.BAD, 6000)
            return
        }
        hubTries++
        if (hubTries == HUB_QUICK_TRIES) {
            toast.show("Phones can't join yet: port $hubPort is busy. The remote still works.", ToastChip.Kind.BAD, 6000)
        }
        hubTask = Async.mainDelayed(if (hubTries < HUB_QUICK_TRIES) HUB_RETRY_MS else HUB_SLOW_RETRY_MS) {
            if (alive) guard("hub.retry") { startHub() }
        }
    }

    private fun makeAi() {
        aiUrl = config.apiUrl
        aiKey = config.apiKey
        ai = AiClient(aiUrl, aiKey, Async::main)
        narrator = try {
            Narrator(this, ai).also { n -> n.onSpeaking = { on -> guard("narrator.speaking") { onNarratorSpeaking(on) } } }
        } catch (t: Throwable) {
            Log.e(TAG, "narrator unavailable", t)
            null
        }
    }

    private class PrefsStore(private val prefs: android.content.SharedPreferences) : DriftDetector.Store {
        override fun getFloat(key: String, def: Float): Float = runCatching { prefs.getFloat("drift.$key", def) }.getOrDefault(def)
        override fun putFloat(key: String, value: Float) {
            prefs.edit().putFloat("drift.$key", value).apply()
        }
    }

    // =================================================================================================
    // Background loops: AI health, LAN address, drift
    // =================================================================================================

    private fun checkHealth() {
        Async.cancel(healthTask)
        val client = ai
        client.health { ok ->
            if (alive && client === ai) setAiOnline(ok)
        }
        healthTask = Async.mainDelayed(HEALTH_EVERY_MS) { if (alive && !stopped) checkHealth() }
    }

    private fun setAiOnline(online: Boolean) {
        if (online == aiOnline) return
        aiOnline = online
        home.setAiOnline(online)
        publishState()
    }

    /** Finds the TV's LAN address off the main thread and renders the join QR; re-checked so Wi-Fi can come later. */
    private fun refreshJoinInfo() {
        Async.cancel(lanTask)
        val size = home.qrSizePx.coerceAtLeast(64)
        val port = hubPort
        Async.io {
            val ip = runCatching { LanAddress.ipv4() }.getOrNull()
            val url = ip?.let { "http://$it:$port" }
            Async.main {
                if (!alive || url == joinUrl) return@main
                joinUrl = url
                if (url == null) {
                    home.setJoinInfo(null, null)
                    return@main
                }
                Async.io {
                    // Dark modules on a cream tile: the contrast phone cameras expect.
                    val qr = runCatching { Qr.encode(url, size, Palette.INK, Palette.CREAM) }.getOrNull()
                    Async.main { if (alive && joinUrl == url) home.setJoinInfo(url, qr) }
                }
            }
        }
        lanTask = Async.mainDelayed(LAN_EVERY_MS) { if (alive && !stopped) refreshJoinInfo() }
    }

    private fun scheduleDrift() {
        Async.cancel(driftTask)
        driftTask = Async.mainDelayed(DRIFT_EVERY_MS) {
            if (!alive) return@mainDelayed
            guard("drift") { checkDrift() }
            scheduleDrift()
        }
    }

    private fun checkDrift() {
        if (!resumed || mode != Mode.BROWSE || !config.nudgesOn || advising || browse == null) return
        // A family hunting for a quest answer is browsing on purpose, not drifting.
        if (checking || questHunting()) return
        val signal = drift.check() ?: return
        advising = true
        try {
            ai.advise(drift.stats(), signal) { advice ->
                advising = false
                if (alive) guard("advise") { showRescue(signal, advice) }
            }
        } catch (t: Throwable) {
            advising = false
            throw t
        }
    }

    /** A quest is running and not finished yet. */
    private fun questHunting(): Boolean {
        if (quests.active == null) return false
        return quests.board(questPlayers())?.complete != true
    }

    // =================================================================================================
    // Mode machine
    // =================================================================================================

    private fun setMode(next: Mode) {
        mode = next
        base = when (next) {
            Mode.HOME -> Mode.HOME
            Mode.BROWSE, Mode.CARDS, Mode.RESCUE -> Mode.BROWSE
            else -> base
        }
        if (browse == null && base == Mode.BROWSE) {
            // Nothing to browse with: stay Home.
            base = Mode.HOME
            if (next == Mode.BROWSE || next == Mode.CARDS || next == Mode.RESCUE) mode = Mode.HOME
        }

        // Close whatever doesn't belong to the new mode. Their callbacks see the new mode and only tidy up.
        if (mode != Mode.BROWSE) actionBar.hide()
        if (mode != Mode.CARDS && (cards.isShowing || cardsLoading)) {
            if (cards.isShowing) cards.close()
            endCards()
        }
        if (mode != Mode.RESCUE && rescue.isShowing) rescue.hide()
        if (mode != Mode.COMPANION && panel.isShowing) panel.hide()
        if (mode != Mode.QUEST_PICKER && mode != Mode.QUEST_INTRO && questOverlay.isModal) questOverlay.hideModal()
        if (finaleShowing && mode != Mode.QUEST_INTRO) endCompletedQuest()
        if (mode != Mode.CARDS) {
            cardIndex = 0
            cardCount = 0
            cardTitle = null
        }

        val homeVisible = base == Mode.HOME
        home.visibility = if (homeVisible) View.VISIBLE else View.INVISIBLE
        browse?.let { b ->
            val wasVisible = b.visibility == View.VISIBLE
            b.visibility = if (homeVisible) View.INVISIBLE else View.VISIBLE
            // Never wake the page while another app has the screen (a phone can switch modes then).
            if (homeVisible && wasVisible) b.onPause() else if (!homeVisible && !wasVisible && resumed) b.onResume()
        }
        // Only the screen the family is looking at may hold focus; overlays keep D-pad focus to themselves.
        home.descendantFocusability = if (mode == Mode.HOME) homeFocusability else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        browse?.descendantFocusability = if (mode == Mode.BROWSE) browseFocusability else ViewGroup.FOCUS_BLOCK_DESCENDANTS
        // A blocked layer must not keep the focus it had, or OK would still click a tile behind an overlay.
        if (mode != Mode.HOME) home.findFocus()?.clearFocus()
        if (mode != Mode.BROWSE) browse?.findFocus()?.clearFocus()

        syncBoard()
        syncLumo()
        updateKeepScreenOn()

        if (mode == Mode.HOME) {
            pagesSinceHome = 0
            root.post { if (alive && mode == Mode.HOME) home.focusDefault() }
        }
        publishState()
    }

    /**
     * Lumo's size, presence, bubble and resting mood for the screen that is up. Full-screen quest screens
     * have their own moth, so Lumo steps out; everywhere else the bubble only goes where it covers nothing.
     */
    private fun syncLumo() {
        val present = !(mode == Mode.QUEST_PICKER || (mode == Mode.QUEST_INTRO && !finaleShowing))
        setLumoPresent(present)
        if (present) lumo.setBig(mode == Mode.HOME || mode == Mode.RESCUE || mode == Mode.COMPANION)
        lumo.setPlacement(
            when {
                mode == Mode.HOME -> CompanionLayer.Placement.FOOTER
                mode == Mode.BROWSE && !actionBar.isShowing -> CompanionLayer.Placement.CORNER
                else -> CompanionLayer.Placement.QUIET
            },
        )
        val rest = if (mode == Mode.RESCUE || (mode == Mode.HOME && isLateNight())) Mood.SLEEPY else Mood.CURIOUS
        if (lumo.mood != rest) lumo.mood = rest
    }

    private fun setLumoPresent(present: Boolean) {
        if (present == lumoPresent) return
        lumoPresent = present
        lumo.animate().cancel()
        if (present) {
            lumo.visibility = View.VISIBLE
            lumo.animate().alpha(1f).setDuration(220).start()
        } else {
            lumo.animate().alpha(0f).setDuration(160).withEndAction { if (!lumoPresent) lumo.visibility = View.INVISIBLE }.start()
        }
    }

    /** Lumo "thinks" while cards load, a quest answer is being judged or a quest is being dreamed up. */
    private fun syncThinking() {
        lumo.thinking(cardsLoading || checking || pickPending)
    }

    /** The toast chip drops in where it covers nothing the family needs on the screen that is up. */
    private fun syncToastAnchor() {
        when (mode) {
            // Between the tagline and the tiles: never over the clock, the AI chip or the join panel.
            Mode.HOME -> toast.setAnchor(Dimens.SAFE_H, TOAST_HOME_END_DP, TOAST_HOME_TOP_DP)
            // Below the page's title strip, and left of the quest board when one is up.
            Mode.BROWSE ->
                if (boardKey != null) toast.setAnchor(Dimens.SAFE_H, Dimens.SAFE_H + TOAST_BOARD_SPACE_DP, TOAST_BROWSE_TOP_DP)
                else toast.setAnchor(TOAST_SIDE_DP, TOAST_SIDE_DP, TOAST_BROWSE_TOP_DP)
            // Under the choices and their key hints, left of big Lumo.
            Mode.RESCUE -> toast.setAnchor(Dimens.SAFE_H, TOAST_RESCUE_END_DP, TOAST_RESCUE_TOP_DP)
            // In the free column right of the panel, above big Lumo: never over the toggles or the AI chip.
            Mode.COMPANION -> toast.setAnchor(TOAST_PANEL_START_DP, Dimens.SAFE_H, Dimens.SAFE_V + 4f)
            else -> toast.setAnchor(TOAST_SIDE_DP, TOAST_SIDE_DP, Dimens.SAFE_V + 4f)
        }
    }

    private fun updateKeepScreenOn() {
        val keep = base == Mode.BROWSE || (hub?.players?.isNotEmpty() == true)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun goHome() {
        if (mode != Mode.HOME) Sfx.back()
        stopVoice()
        setMode(Mode.HOME)
    }

    /** Opens [input] (a URL or a search) in the browser and switches to it. */
    private fun openInBrowse(input: String, by: Player?, fromHome: Boolean) {
        val b = browse ?: return noWebView(by)
        val text = input.trim()
        if (text.isEmpty()) return
        if (fromHome || base == Mode.HOME) pagesSinceHome = 0
        setMode(Mode.BROWSE)
        b.open(text, by)
    }

    private fun noWebView(by: Player? = null) {
        // One line, so Home's footer bubble stays below the favorites; the toast names the fix.
        val msg = "This TV's web engine needs an update before I can open pages."
        lumo.say(msg, Mood.CONCERNED, 9000)
        toast.show("Web engine missing: update Android System WebView", ToastChip.Kind.BAD, 6000)
        by?.let { hub?.toast("The TV can't open pages yet: its web engine needs an update.", "bad", it.id) }
    }

    // =================================================================================================
    // Remote keys
    // =================================================================================================

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!alive) return super.dispatchKeyEvent(event)
        return try {
            routeKey(normalize(event), fromPhone = false)
        } catch (t: Throwable) {
            Log.e(TAG, "key ${event.keyCode} failed", t)
            true
        }
    }

    /** Game controllers and keyboards: A = OK, B / Esc = Back. */
    private fun normalize(e: KeyEvent): KeyEvent {
        val mapped = when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> KeyEvent.KEYCODE_BACK
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            else -> return e
        }
        return KeyEvent(e.downTime, e.eventTime, e.action, mapped, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)
    }

    private fun routeKey(e: KeyEvent, fromPhone: Boolean): Boolean {
        val code = e.keyCode
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                val sink = routeDown(e, fromPhone)
                if (e.repeatCount == 0 || downSink[code] == null) downSink.put(code, sink)
                return if (sink == Sink.SYSTEM) system(e, fromPhone) else true
            }
            KeyEvent.ACTION_UP -> {
                val sink = downSink[code]
                downSink.remove(code)
                return when (sink) {
                    null -> if (isOwnedKey(code)) true else system(e, fromPhone)
                    Sink.SYSTEM -> system(e, fromPhone)
                    Sink.PANEL -> { panel.handleKey(e); true }
                    Sink.RESCUE -> { rescue.handleKey(e); true }
                    Sink.QUEST -> { questOverlay.handleKey(e); true }
                    Sink.CARDS -> { cards.handleKey(e); true }
                    Sink.BAR -> { actionBar.handleKey(e); true }
                    Sink.BROWSE -> { browse?.handleKey(if (isOkKey(code)) asCenter(e) else e); true }
                    Sink.SHELL -> true
                }
            }
            else -> return system(e, fromPhone)
        }
    }

    /** Top-most visible layer first; each returns where the key went. */
    private fun routeDown(e: KeyEvent, fromPhone: Boolean): Sink {
        val code = e.keyCode
        val first = e.repeatCount == 0
        recoverStaleMode()

        if (panel.isShowing) {
            if (panel.handleKey(e)) return Sink.PANEL
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (first) {
                    Sfx.back()
                    panel.hide()
                    closeCompanionPanel()
                }
                return Sink.SHELL
            }
            return if (isNavKey(code) || isOkKey(code)) Sink.SYSTEM else Sink.SHELL
        }
        if (rescue.isShowing) {
            if (rescue.handleKey(e)) return Sink.RESCUE
            if (code == KeyEvent.KEYCODE_BACK) {
                if (first) {
                    rescue.choose(-1)
                    if (mode == Mode.RESCUE) {
                        rescue.hide()
                        rescueDismissed()
                    }
                }
                return Sink.SHELL
            }
            return if (isNavKey(code) || isOkKey(code)) Sink.SYSTEM else Sink.SHELL
        }
        if (questOverlay.isModal) {
            if (questOverlay.handleKey(e)) return Sink.QUEST
            if (code == KeyEvent.KEYCODE_BACK) {
                if (first) closeQuestModal()
                return Sink.SHELL
            }
            return if (isNavKey(code) || isOkKey(code)) Sink.SYSTEM else Sink.SHELL
        }
        if (cards.isShowing) {
            if (cards.handleKey(e)) return Sink.CARDS
            when (code) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU -> if (first) {
                    Sfx.back()
                    cards.close()
                    cardsClosed()
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> if (first) cards.prev()
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (first) cards.next()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (first) cards.current?.let { readCard(it) }
            }
            // Nothing under the cards may move while they're up.
            return if (isOwnedKey(code)) Sink.SHELL else Sink.SYSTEM
        }
        if (actionBar.isShowing && actionBar.handleKey(e)) return Sink.BAR

        return when (base) {
            Mode.BROWSE -> browseKey(e)
            else -> homeKey(e, fromPhone)
        }
    }

    private fun browseKey(e: KeyEvent): Sink {
        val code = e.keyCode
        val first = e.repeatCount == 0
        val b = browse ?: return Sink.SHELL
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (first) browseBack(b)
                return Sink.SHELL
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) {
                    stopVoice()
                    // The bar sits along the bottom: Lumo keeps quiet while it's up.
                    lumo.hush()
                    actionBar.show(questActive = quests.active != null)
                    syncLumo()
                }
                return Sink.SHELL
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                // A fullscreen video takes Play/Pause for itself; otherwise it makes cards.
                if (b.handleKey(e)) return Sink.BROWSE
                if (first) makeCards(null)
                return Sink.SHELL
            }
        }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            // A keyboard's Enter belongs to the page field being typed in; otherwise it's OK on the cursor.
            if (fieldFocused) return Sink.SYSTEM
            return if (b.handleKey(asCenter(e))) Sink.BROWSE else Sink.SHELL
        }
        if (b.handleKey(e)) return Sink.BROWSE
        // The page never sees raw D-pad keys: the cursor is the only way in.
        return if (isOwnedKey(code)) Sink.SHELL else Sink.SYSTEM
    }

    private fun asCenter(e: KeyEvent): KeyEvent =
        if (e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) e
        else KeyEvent(e.downTime, e.eventTime, e.action, KeyEvent.KEYCODE_DPAD_CENTER, e.repeatCount, e.metaState, e.deviceId, e.scanCode, e.flags, e.source)

    private fun browseBack(b: BrowseLayer) {
        if (isVideoFullscreen(b)) {
            // Back leaves fullscreen first, whatever page we're on; it isn't a history step.
            b.goBack()
            Sfx.back()
            return
        }
        if (pagesSinceHome > 1) {
            navigatingBack = true
            if (b.goBack()) {
                pagesSinceHome--
                Sfx.back()
                return
            }
            navigatingBack = false
        }
        goHome()
    }

    /** True while an HTML5 video is fullscreen in the browser. */
    private fun isVideoFullscreen(b: BrowseLayer): Boolean = b.isFullscreen

    private fun homeKey(e: KeyEvent, fromPhone: Boolean): Sink {
        val code = e.keyCode
        val first = e.repeatCount == 0
        when (code) {
            KeyEvent.KEYCODE_MENU -> {
                if (first) openCompanionPanel()
                return Sink.SHELL
            }
            KeyEvent.KEYCODE_BACK -> {
                if (first && !home.handleBack()) {
                    if (fromPhone) lumo.say("We're already home. Pick a tile!", Mood.CURIOUS, 3500) else confirmExit()
                }
                return Sink.SHELL
            }
        }
        // Someone is moving around Home: Lumo's line steps aside so the favorites row is clear.
        if (first && isNavKey(code)) lumo.hush()
        return Sink.SYSTEM
    }

    /** Leaving would disconnect every phone, so Back on Home asks twice. */
    private fun confirmExit() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBackAt < EXIT_WINDOW_MS) {
            finish()
            return
        }
        lastBackAt = now
        val phones = hub?.players?.size ?: 0
        toast.show(if (phones > 0) "Press Back again to leave. Phones will disconnect." else "Press Back again to leave Wander.", ToastChip.Kind.INFO, EXIT_WINDOW_MS)
    }

    /** Hands the key to the window; for phone keys it also performs the focus move the system would have. */
    private fun system(e: KeyEvent, fromPhone: Boolean): Boolean {
        val handled = super.dispatchKeyEvent(e)
        if (handled || !fromPhone || e.action != KeyEvent.ACTION_DOWN) return handled
        val dir = when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
            else -> return false
        }
        val focused = window.decorView.findFocus()
        if (focused == null) {
            if (mode == Mode.HOME) home.focusDefault() else window.decorView.requestFocus(dir)
            return true
        }
        focused.focusSearch(dir)?.takeIf { it !== focused }?.requestFocus(dir)
        return true
    }

    /** If an overlay closed itself without telling us, fall back to the screen underneath. */
    private fun recoverStaleMode() {
        val stale = when (mode) {
            Mode.COMPANION -> !panel.isShowing
            Mode.QUEST_PICKER, Mode.QUEST_INTRO -> !questOverlay.isModal
            Mode.CARDS -> !cards.isShowing && !cardsLoading
            Mode.RESCUE -> !rescue.isShowing
            else -> false
        }
        if (stale) setMode(base)
    }

    private fun isNavKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
        code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT

    private fun isOkKey(code: Int) = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** Remote keys Wander gives meaning to; these never fall through to a page. */
    private fun isOwnedKey(code: Int) = isNavKey(code) || isOkKey(code) || when (code) {
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT, KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
        -> true
        else -> false
    }

    /** Phones press remote keys through exactly the same routing as the real remote. */
    private fun injectKey(code: Int) {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        routeKey(down, fromPhone = true)
        routeKey(up, fromPhone = true)
    }

    // =================================================================================================
    // Features
    // =================================================================================================

    private fun greet() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val late = isLateNight()
        Sfx.chime()
        lumo.say(Lines.greeting(hour), if (late) Mood.SLEEPY else Mood.CURIOUS, 7000)
    }

    private fun isLateNight(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 23 || h < 5
    }

    // ---- cards ------------------------------------------------------------------------------------

    private fun makeCards(by: Player?) {
        val b = browse
        if (b == null || base != Mode.BROWSE || b.currentUrl.isBlank()) {
            tell(by, "Open a page first, then I can turn it into cards.", Mood.CURIOUS)
            return
        }
        if (cards.isShowing) return
        if (checking) {
            tell(by, "One sec, I'm still checking a quest answer.", Mood.CURIOUS)
            return
        }
        if (mode != Mode.BROWSE) {
            tell(by, "Finish what's on screen first, then try cards.", Mood.CURIOUS)
            return
        }
        val req = ++cardsRequest
        val title = b.currentTitle.ifBlank { "this page" }
        setMode(Mode.CARDS)
        cards.showLoading(title)
        cardsLoading = true
        syncThinking()
        Async.cancel(cardsWatch)
        cardsWatch = Async.mainDelayed(WATCHDOG_MS) {
            guard("cards.watchdog") {
                if (alive && req == cardsRequest && mode == Mode.CARDS && cardsLoading) {
                    cardsLoading = false
                    syncThinking()
                    cards.showError("That page took too long to read. Try again in a moment?")
                    Sfx.fail()
                    hub?.toast("That page took too long to read. Close the cards and try again?", "bad")
                }
            }
        }
        by?.let { hub?.toast("Making cards from \"${title.take(40)}\"...", "info", it.id) }
        b.extract { page ->
            guard("cards.extract") {
                if (!alive || req != cardsRequest || mode != Mode.CARDS) return@guard
                if (page == null || page.text.isBlank()) {
                    cardsLoading = false
                    syncThinking()
                    // The overlay explains it on the TV; phones would otherwise wait on "making cards" forever.
                    cards.showError("I couldn't find much to read here. Articles work best!")
                    Sfx.fail()
                    hub?.toast("Lumo couldn't find much to read on this page. Articles work best!", "bad")
                    return@guard
                }
                ai.cards(page) { deck ->
                    guard("cards.deck") {
                        if (!alive || req != cardsRequest || mode != Mode.CARDS || !cardsLoading) return@guard
                        cardsLoading = false
                        syncThinking()
                        noteAi()
                        if (deck.cards.isEmpty()) {
                            cards.showError("I couldn't turn this one into cards. Try another page!")
                            Sfx.fail()
                            hub?.toast("Lumo couldn't turn this page into cards. Try another one!", "bad")
                            return@guard
                        }
                        // The deck lands with its own chime and hints. A single card is the "skip this
                        // page" or "not much here" card, and it speaks for itself.
                        cards.showDeck(deck)
                        publishState()
                    }
                }
            }
        }
    }

    /** Stops a cards load or deck in flight, however it ended. Safe to call more than once. */
    private fun endCards() {
        cardsRequest++
        Async.cancel(cardsWatch)
        cardsWatch = null
        cardsLoading = false
        syncThinking()
        stopVoice()
    }

    private fun cardsClosed() {
        endCards()
        if (mode == Mode.CARDS) setMode(Mode.BROWSE)
    }

    private fun readCard(card: Card) {
        val text = listOf(card.title, card.line).filter { it.isNotBlank() }.joinToString(". ")
        if (text.isBlank()) return
        val n = narrator
        if (n == null) {
            toast.show(VOICE_MISSING, ToastChip.Kind.INFO, 4500)
            return
        }
        n.speak(text)
        // A TV with no working voice would otherwise just stay silent.
        Async.cancel(voiceCheck)
        voiceCheck = Async.mainDelayed(VOICE_CHECK_MS) {
            voiceCheck = null
            if (alive && mode == Mode.CARDS) toast.show(VOICE_MISSING, ToastChip.Kind.INFO, 4500)
        }
    }

    private fun onNarratorSpeaking(on: Boolean) {
        if (!alive) return
        if (on) {
            Async.cancel(voiceCheck)
            voiceCheck = null
        }
        lumo.speaking(on)
    }

    private fun stopVoice() {
        Async.cancel(voiceCheck)
        voiceCheck = null
        narrator?.stop()
    }

    // ---- quests -----------------------------------------------------------------------------------

    private fun openQuestPicker() {
        if (mode == Mode.QUEST_PICKER) return
        actionBar.hide()
        lumo.hush()
        questOverlay.showPicker(QuestCatalog.all)
        setMode(Mode.QUEST_PICKER)
    }

    private fun closeQuestModal() {
        Sfx.back()
        val was = mode
        questOverlay.hideModal()
        if (mode != was) return // the overlay already reported how it closed
        when (was) {
            Mode.QUEST_INTRO ->
                if (finaleShowing || quests.board(questPlayers())?.complete == true) finaleDone(QuestOverlay.FinaleChoice.BACK_TO_BROWSING)
                else enterQuestBrowse()
            Mode.QUEST_PICKER -> {
                cancelLumoPick()
                setMode(base)
            }
            else -> setMode(base)
        }
    }

    private fun startQuestById(id: String, by: Player?, fresh: Boolean = false) {
        if (id == LUMO_PICK) {
            lumoPick(by, fresh)
            return
        }
        val q = QuestCatalog.byId(id)
        if (q == null) {
            tell(by, "I don't know that quest. Pick one from the list!", Mood.CONCERNED)
            return
        }
        startQuest(q, by, fresh)
    }

    /**
     * Asks the AI for a quest made from tonight's trail. Its answer is dropped if, meanwhile, another
     * quest started, or (when asked from the picker) the family left the picker.
     */
    private fun lumoPick(by: Player?, fresh: Boolean = false, fromPicker: Boolean = false) {
        if (pickPending) return
        pickPending = true
        val token = ++pickToken
        syncThinking()
        // On the picker, the "Lumo's pick" card shows its own dreaming state.
        if (!fromPicker) lumo.say("Let me dream one up from tonight's trail...", Mood.CURIOUS)
        hub?.toast("Lumo is dreaming up a quest...", "info")
        try {
            ai.newQuest(trail.toList()) { q ->
                guard("lumoPick") {
                    if (!alive || token != pickToken) return@guard
                    pickPending = false
                    syncThinking()
                    noteAi()
                    if (fromPicker && mode != Mode.QUEST_PICKER) return@guard
                    if (q == null) {
                        val line = "My imagination is napping. Try one of the classic quests!"
                        if (fromPicker) {
                            questOverlay.resetLumoPick()
                            toast.show(line, ToastChip.Kind.INFO)
                        } else {
                            tell(by, line, Mood.SLEEPY)
                        }
                        return@guard
                    }
                    startQuest(q, by, fresh, fromPick = true)
                }
            }
        } catch (t: Throwable) {
            pickPending = false
            syncThinking()
            throw t
        }
    }

    /** A Lumo's pick still being dreamed up no longer matters: its answer must not start a quest. */
    private fun cancelLumoPick() {
        pickToken++
        if (pickPending) {
            pickPending = false
            syncThinking()
        }
    }

    /** [fresh] opens the quest's starting search after the intro even if a page is already open. */
    private fun startQuest(q: Quest, by: Player?, fresh: Boolean = false, fromPick: Boolean = false) {
        if (!fromPick) cancelLumoPick()
        freshQuestStart = fresh || base == Mode.HOME
        quests.start(q)
        checking = false
        questRequest++
        finaleShowing = false
        Async.cancel(checkWatch)
        Async.cancel(finaleTask)
        syncThinking()
        if (cards.isShowing) cards.close()
        lumo.hush()
        questOverlay.showIntro(q) // with its own chime
        setMode(Mode.QUEST_INTRO)
        home.setQuestLine(questLine())
        hub?.toast("Quest started: ${q.emoji} ${q.title}", "good")
        by?.let { hub?.toast("You started ${q.title}!", "good", it.id) }
    }

    /** After the intro: start hunting on the current page, or on a search for the quest if we came from Home. */
    private fun enterQuestBrowse() {
        val b = browse
        val q = quests.active
        if (b == null) {
            setMode(Mode.HOME)
            noWebView()
            return
        }
        val fresh = freshQuestStart
        freshQuestStart = false
        if (q != null && (fresh || base == Mode.HOME || b.currentUrl.isBlank())) {
            pagesSinceHome = 0
            setMode(Mode.BROWSE)
            b.open(Detours.startQuery(q), null)
        } else {
            setMode(Mode.BROWSE)
        }
        // Said here, not over the intro card: now the family can actually see Lumo and act on it.
        if (q != null) lumo.say("${q.emoji} ${q.title}: find ${q.goal}! Found one? Press Menu, or Submit on a phone.", Mood.HAPPY)
    }

    private fun submitQuest(by: Player?) {
        val q = quests.active
        if (q == null) {
            tell(by, "No quest running yet. Start one from Quests!", Mood.CURIOUS)
            return
        }
        if (quests.board(questPlayers())?.complete == true) {
            tell(by, "${q.title} is already complete. Start another quest!", Mood.HAPPY)
            return
        }
        val b = browse
        if (b == null || base != Mode.BROWSE || b.currentUrl.isBlank()) {
            tell(by, "Open a page that answers the quest, then send it to me.", Mood.CURIOUS)
            return
        }
        if (checking) {
            by?.let { hub?.toast("Lumo is still checking the last page...", "info", it.id) }
            return
        }
        if (mode == Mode.CARDS) setMode(Mode.BROWSE)
        if (mode != Mode.BROWSE) {
            tell(by, "Finish what's on screen first, then send the page.", Mood.CURIOUS)
            return
        }
        val player = by ?: couchPlayer()
        val req = ++questRequest
        checking = true
        questOverlay.showChecking(player)
        syncThinking()
        lumo.say("${player.name} thinks this page has it. Let me read...", Mood.CURIOUS)
        by?.let { hub?.toast("Lumo is checking your page...", "info", it.id) }
        publishState()
        Async.cancel(checkWatch)
        checkWatch = Async.mainDelayed(WATCHDOG_MS) {
            guard("quest.watchdog") {
                if (alive && checking && req == questRequest && quests.active?.id == q.id) {
                    questRequest++
                    val board = quests.board(questPlayers(player)) ?: QuestBoard(q, quests.found, questPlayers(player), false)
                    finishCheck(QuestOutcome.Rejected("I lost my place reading that page. Send it again?", board), player)
                }
            }
        }
        b.extract { page ->
            guard("quest.extract") {
                if (!alive || req != questRequest || quests.active?.id != q.id) return@guard
                if (page == null || page.text.isBlank()) {
                    val board = quests.board(questPlayers(player)) ?: QuestBoard(q, quests.found, questPlayers(player), false)
                    finishCheck(QuestOutcome.Rejected("I couldn't read this page. Try another one!", board), player)
                    return@guard
                }
                judge(q, page, player, req)
            }
        }
    }

    private fun judge(q: Quest, page: PageText, player: Player, req: Int) {
        ai.checkQuest(q, page, quests.found.map { it.item }) { verdict ->
            guard("quest.verdict") {
                if (!alive || req != questRequest || quests.active?.id != q.id) return@guard
                noteAi()
                val outcome = applyVerdict(player, verdict)
                finishCheck(outcome, player)
            }
        }
    }

    private fun applyVerdict(player: Player, verdict: Verdict): QuestOutcome =
        try {
            quests.apply(player.id, verdict, questPlayers(player))
        } catch (t: Throwable) {
            Log.e(TAG, "quest engine failed", t)
            val q = quests.active!!
            QuestOutcome.Rejected(verdict.reason.ifBlank { "Hmm, I'm not sure about that one." }, QuestBoard(q, quests.found, questPlayers(player), false))
        }

    private fun finishCheck(outcome: QuestOutcome, player: Player) {
        checking = false
        Async.cancel(checkWatch)
        syncThinking()
        questOverlay.showOutcome(outcome, player) // plays success / pop / fail itself
        syncBoard(force = true)
        val phone = hub?.player(player.id)
        when (outcome) {
            is QuestOutcome.Accepted -> {
                val item = outcome.found.item
                lumo.celebrate(Palette.player(player.colorIndex))
                lumo.say("Yes! ${player.name} found $item. +${outcome.points}!", Mood.HAPPY)
                if (phone != null) hub?.celebrate(phone.id, "+${outcome.points} $item!")
                hub?.toast("${player.name} found $item!", "good")
            }
            is QuestOutcome.Duplicate -> {
                val first = outcome.board.found.firstOrNull { it.item.equals(outcome.item, ignoreCase = true) }
                val who = first?.let { nameOf(it.playerId) } ?: "Someone"
                lumo.say("$who already found ${outcome.item}! Can you find a different one?", Mood.CURIOUS)
                phone?.let { hub?.toast("$who found ${outcome.item} first. Try another!", "bad", it.id) }
            }
            is QuestOutcome.Rejected -> {
                lumo.say(outcome.reason.ifBlank { "Not quite. Keep looking!" }, Mood.CONCERNED)
                phone?.let { hub?.toast(outcome.reason.ifBlank { "Not quite. Keep looking!" }, "bad", it.id) }
            }
        }
        home.setQuestLine(questLine())
        publishState()
        if (outcome.board.complete) {
            Async.cancel(finaleTask)
            val id = outcome.board.quest.id
            finaleTask = Async.mainDelayed(FINALE_DELAY_MS) {
                guard("finale") {
                    if (alive && quests.active?.id == id) showFinale()
                }
            }
        }
    }

    private fun showFinale() {
        val board = quests.board(questPlayers()) ?: return
        if (mode == Mode.CARDS) cards.close()
        if (rescue.isShowing || panel.isShowing) setMode(base)
        actionBar.hide()
        // The finale celebrates on its own (confetti, podium, fanfare); Lumo cheers from the corner.
        lumo.hush()
        questOverlay.showFinale(board)
        finaleShowing = true
        setMode(Mode.QUEST_INTRO)
        val winner = board.players.maxByOrNull { it.score }
        if (winner != null && winner.score > 0) {
            lumo.celebrate(Palette.player(winner.colorIndex))
            hub?.toast("Quest complete! ${winner.name} leads with ${winner.score} points.", "good")
        } else {
            hub?.toast("Quest complete! What a team.", "good")
        }
    }

    /** The finale was closed with [choice]: "Another quest" opens the picker, anything else goes back. */
    private fun finaleDone(choice: QuestOverlay.FinaleChoice?) {
        val wasShowing = finaleShowing
        endCompletedQuest()
        if (!wasShowing || mode != Mode.QUEST_INTRO) return
        if (choice == QuestOverlay.FinaleChoice.ANOTHER_QUEST) {
            openQuestPicker()
        } else {
            setMode(if (browse?.currentUrl.isNullOrBlank()) Mode.HOME else base)
        }
    }

    /** A finished quest leaves the board once its finale has been seen (or skipped). */
    private fun endCompletedQuest() {
        if (!finaleShowing && quests.board(questPlayers())?.complete != true) return
        finaleShowing = false
        quests.end()
        home.setQuestLine(null)
        syncBoard()
        publishState()
    }

    /**
     * The scoreboard rides along only while browsing. It's re-sent only when something on it changed, so
     * opening page after page never makes it flicker.
     */
    private fun syncBoard(force: Boolean = false) {
        val board = if (mode == Mode.BROWSE) quests.board(questPlayers()) else null
        // The title strip stops short of the scoreboard so its domain and "driving" chips stay visible.
        browse?.setTopEndReserve(if (board != null) QuestBoardView.WIDTH_DP + 14f else 0f)
        val key = board?.let { b ->
            "${b.quest.id}|${b.found.size}|${b.complete}|" + b.players.joinToString(",") { "${it.id}:${it.score}:${it.colorIndex}" }
        }
        if (force || key != boardKey) {
            boardKey = key
            questOverlay.showBoard(board)
        }
        syncToastAnchor()
    }

    private fun questLine(): String? {
        val q = quests.active ?: return null
        val n = quests.found.size
        return if (n >= q.goal) "${q.emoji} ${q.title}: complete!" else "${q.emoji} ${q.title}: $n of ${q.goal} found"
    }

    /** The remote's own player, so a family with no phones can still play. */
    private fun couchPlayer(): Player {
        remotePlayer?.let { return it }
        val used = hub?.players?.map { ((it.colorIndex % 4) + 4) % 4 }?.toSet().orEmpty()
        val color = (0 until 4).firstOrNull { it !in used } ?: 0
        return Player(COUCH_ID, "The couch", color).also { remotePlayer = it }
    }

    private fun questPlayers(extra: Player? = null): List<Player> {
        val list = hub?.players.orEmpty().toMutableList()
        remotePlayer?.let { rp -> if ((rp.score > 0 || rp === extra) && list.none { it.id == rp.id }) list += rp }
        if (extra != null && list.none { it.id == extra.id }) list += extra
        return list
    }

    private fun nameOf(playerId: String): String =
        hub?.player(playerId)?.name ?: remotePlayer?.takeIf { it.id == playerId }?.name ?: "Someone"

    // ---- rescue -----------------------------------------------------------------------------------

    private fun showRescue(signal: DriftSignal, advice: CompanionAdvice) {
        if (mode != Mode.BROWSE) return
        val options = rescueChoices(signal, advice)
        rescueSignal = signal
        rescueOptions = options
        actionBar.hide()
        lumo.hush()
        rescue.show(signal, advice.line.ifBlank { signal.headline }, options)
        setMode(Mode.RESCUE) // Lumo turns sleepy for it
        Sfx.chime()
        noteAi()
    }

    /** Two ways out: a quest (the AI's, the running one, or a catalog pick) and a curated curious detour. */
    private fun rescueChoices(signal: DriftSignal, advice: CompanionAdvice): List<RescueOption> {
        val here = browse?.currentUrl
        val suggestion = advice.suggestion
        if (signal.reason == DriftReason.LATE_NIGHT) {
            val calm = suggestion?.takeIf { it.kind == RescueOption.Kind.WIND_DOWN } ?: Detours.goodnight
            val other = suggestion?.takeIf { it.kind == RescueOption.Kind.DETOUR && it.url != null } ?: Detours.next(here)
            return listOf(calm, other)
        }
        val quest = suggestion?.takeIf { it.kind == RescueOption.Kind.QUEST } ?: questChoice()
        val detour = suggestion?.takeIf { it.kind == RescueOption.Kind.DETOUR && !it.url.isNullOrBlank() } ?: Detours.next(here)
        return listOf(quest, detour)
    }

    private fun questChoice(): RescueOption {
        quests.active?.let { q ->
            val n = quests.found.size
            if (n < q.goal) return RescueOption(RescueOption.Kind.QUEST, "Back to ${q.title} ${q.emoji}", "$n of ${q.goal} found. Lumo has a hunch where to look.", questId = q.id)
        }
        val pick = QuestCatalog.all.filter { it.id != quests.active?.id }.randomOrNull()
            ?: return RescueOption(RescueOption.Kind.QUEST, "Let Lumo pick a quest ✨", "A treasure hunt made from tonight's trail.", questId = LUMO_PICK)
        return RescueOption(RescueOption.Kind.QUEST, "Start ${pick.title} ${pick.emoji}", pick.prompt, questId = pick.id)
    }

    private fun rescueChosen(option: RescueOption) {
        if (mode != Mode.RESCUE) return
        val signal = rescueSignal
        rescueSignal = null
        rescueOptions = emptyList()
        signal?.let { drift.accepted(it) }
        // The overlay already played its select sound; leaving RESCUE wakes Lumo up again.
        when (option.kind) {
            RescueOption.Kind.QUEST -> {
                val active = quests.active
                val id = option.questId ?: LUMO_PICK
                setMode(Mode.BROWSE)
                if (active != null && active.id == id) {
                    // Stay on this page: it may be the very one they were about to send.
                    lumo.say("Back on the trail! ${quests.found.size} of ${active.goal} found.", Mood.HAPPY)
                } else {
                    startQuestById(id, null, fresh = true)
                }
            }
            RescueOption.Kind.DETOUR -> {
                val url = option.url
                if (url.isNullOrBlank()) {
                    setMode(Mode.BROWSE)
                } else {
                    openInBrowse(url, null, fromHome = false)
                    lumo.say("Ooh, good pick. Let's look closely at this one.", Mood.HAPPY)
                }
            }
            RescueOption.Kind.WIND_DOWN -> {
                goHome()
                lumo.say("Sleep well. I'll keep the lamp on for next time.", Mood.SLEEPY, 8000)
            }
        }
    }

    private fun rescueDismissed() {
        if (mode != Mode.RESCUE) return
        rescueSignal?.let { drift.dismissed(it) }
        rescueSignal = null
        rescueOptions = emptyList()
        setMode(Mode.BROWSE)
        lumo.say("Okay, carry on! I'll nudge less for that one.", Mood.CURIOUS, 4000)
    }

    // ---- Lumo panel -------------------------------------------------------------------------------

    private fun openCompanionPanel() {
        if (panel.isShowing) return
        actionBar.hide()
        lumo.hush()
        panel.show(drift.stats(), trail.toList(), aiOnline, config.nudgesOn, config.demoMode)
        setMode(Mode.COMPANION)
    }

    private fun closeCompanionPanel() {
        if (mode == Mode.COMPANION) setMode(base)
    }

    // ---- phones -----------------------------------------------------------------------------------

    private fun handleCommand(player: Player, command: PhoneCommand) {
        when (command) {
            is PhoneCommand.Move ->
                if (cursorMode()) browse?.moveCursor(command.dx, command.dy, player) else touchpadAsDpad(command.dx, command.dy)
            PhoneCommand.Tap ->
                if (cursorMode()) browse?.tap(player) else injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
            is PhoneCommand.Scroll -> when (mode) {
                Mode.BROWSE -> browse?.scroll(command.dx, command.dy)
                Mode.CARDS -> if (abs(command.dx) > abs(command.dy) && abs(command.dx) > 40f) {
                    if (command.dx > 0) cards.next() else cards.prev()
                }
                else -> Unit
            }
            is PhoneCommand.Go -> openInBrowse(command.text, player, fromHome = base == Mode.HOME)
            is PhoneCommand.Type -> typeFromPhone(player, command.text, command.submit)
            is PhoneCommand.Key -> phoneKey(player, command.key)
            PhoneCommand.MakeCards -> makeCards(player)
            PhoneCommand.QuestSubmit -> submitQuest(player)
            is PhoneCommand.QuestStart -> startQuestById(command.questId, player)
            PhoneCommand.ReadAloud -> {
                val card = cards.current
                if (cards.isShowing && card != null) readCard(card)
                else hub?.toast("Make cards first, then I can read them aloud.", "info", player.id)
            }
            is PhoneCommand.Rescue ->
                if (rescue.isShowing) rescue.choose(command.choice)
                else hub?.toast("Nothing to choose right now.", "info", player.id)
            is PhoneCommand.Zoom ->
                if (base == Mode.BROWSE) browse?.zoom(command.delta)
        }
    }

    /** The phone touchpad drives the page cursor only when nothing is on top of the page. */
    private fun cursorMode() = mode == Mode.BROWSE && !actionBar.isShowing

    private fun typeFromPhone(player: Player, text: String, submit: Boolean) {
        val b = browse
        if (mode == Mode.BROWSE && b != null) {
            // Into the focused page field; with none focused the browser decides (it may search).
            b.typeText(text, submit)
            return
        }
        if (mode == Mode.HOME || base == Mode.HOME) {
            if (submit && text.isNotBlank()) {
                if (mode != Mode.HOME) setMode(Mode.HOME)
                openInBrowse(text, player, fromHome = true)
            } else {
                if (mode != Mode.HOME) setMode(Mode.HOME)
                home.openSearch(text)
            }
            return
        }
        if (fieldFocused) {
            // Something covers the page the field is on; typing must never turn into a web search.
            hub?.toast("Close what's on the TV first, then type into the page.", "info", player.id)
            return
        }
        if (submit && text.isNotBlank()) openInBrowse(text, player, fromHome = false)
    }

    private fun phoneKey(player: Player, key: String) {
        when (key.lowercase()) {
            "back" -> injectKey(KeyEvent.KEYCODE_BACK)
            "home" -> goHome()
            "menu" -> injectKey(KeyEvent.KEYCODE_MENU)
            "up" -> injectKey(KeyEvent.KEYCODE_DPAD_UP)
            "down" -> injectKey(KeyEvent.KEYCODE_DPAD_DOWN)
            "left" -> injectKey(KeyEvent.KEYCODE_DPAD_LEFT)
            "right" -> injectKey(KeyEvent.KEYCODE_DPAD_RIGHT)
            "enter", "ok", "select" -> phoneEnter()
            "playpause" -> injectKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            else -> hub?.toast("That button isn't wired up yet.", "info", player.id)
        }
    }

    /** Enter in the field being typed into (a page field, or Home's search); anywhere else it's OK. */
    private fun phoneEnter() {
        val b = browse
        when {
            // Straight to the page field (the WebView takes focus back if an overlay had it).
            mode == Mode.BROWSE && fieldFocused && b != null -> b.pressEnter()
            mode == Mode.HOME && homeSearchOpen -> injectKey(KeyEvent.KEYCODE_ENTER)
            else -> injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
        }
    }

    /** On screens without a cursor, a swipe on the phone's touchpad moves focus like the D-pad. */
    private fun touchpadAsDpad(dx: Float, dy: Float) {
        val now = SystemClock.uptimeMillis()
        if (now - padAt > PAD_IDLE_MS) {
            padX = 0f
            padY = 0f
        }
        padAt = now
        padX += dx
        padY += dy
        val code = when {
            abs(padX) >= PAD_STEP && abs(padX) >= abs(padY) -> if (padX > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
            abs(padY) >= PAD_STEP -> if (padY > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
            else -> return
        }
        padX = 0f
        padY = 0f
        injectKey(code)
    }

    /** While another app has the TV, phone presses wait; tell the phone why (now and then, not per swipe). */
    private fun tellInBackground(player: Player) {
        val now = SystemClock.uptimeMillis()
        val last = backgroundToldAt[player.id]
        if (last != null && now - last < BACKGROUND_NOTE_EVERY_MS) return
        backgroundToldAt[player.id] = now
        hub?.toast("Wander isn't on the TV screen right now. Open it on the TV to keep going.", "info", player.id)
    }

    private fun playersChanged() {
        val players = hub?.players.orEmpty()
        home.setPlayers(players)
        updateKeepScreenOn()
        syncBoard()
        publishState()
    }

    /** Says [text] on the TV through Lumo, and on the phone that asked (if any). */
    private fun tell(by: Player?, text: String, mood: Mood) {
        lumo.say(text, mood, 5000)
        by?.let { hub?.toast(text, "info", it.id) }
    }

    private fun noteAi() {
        setAiOnline(ai.online)
    }

    // ---- trail ------------------------------------------------------------------------------------

    private fun addTrail(title: String) {
        val t = title.trim()
        if (t.isEmpty()) return
        if (trail.lastOrNull()?.equals(t, ignoreCase = true) == true) return
        trail.addLast(t.take(120))
        while (trail.size > TRAIL_MAX) trail.removeFirst()
    }

    // =================================================================================================
    // State for phones
    // =================================================================================================

    private fun publishState() {
        if (statePending || !alive) return
        statePending = true
        stateTask = Async.mainDelayed(STATE_DEBOUNCE_MS) {
            statePending = false
            if (!alive) return@mainDelayed
            guard("publish") { hub?.publish(buildState()) }
        }
    }

    private fun buildState(): TvState {
        val browsing = base == Mode.BROWSE
        val b = browse
        // A field only counts as "being typed into" while its screen is the one in front.
        val typing = when (mode) {
            Mode.BROWSE -> fieldFocused
            Mode.HOME -> homeSearchOpen
            else -> false
        }
        return TvState(
            mode = mode,
            title = if (browsing) b?.currentTitle.orEmpty() else "",
            url = if (browsing) b?.currentUrl.orEmpty() else "",
            quest = quests.board(questPlayers()),
            cardIndex = if (mode == Mode.CARDS) cardIndex else 0,
            cardCount = if (mode == Mode.CARDS) cardCount else 0,
            cardTitle = if (mode == Mode.CARDS) cardTitle else null,
            rescue = if (mode == Mode.RESCUE) rescueSignal else null,
            rescueOptions = if (mode == Mode.RESCUE) rescueOptions else emptyList(),
            textFieldFocused = typing,
            fieldLabel = when {
                !typing -> null
                mode == Mode.BROWSE -> fieldLabel
                else -> "Search"
            },
            checking = checking,
            aiOnline = aiOnline,
            quests = QuestCatalog.all,
        )
    }

    // =================================================================================================
    // Listeners (every entry point is guarded: a bug in one feature must never take the TV down)
    // =================================================================================================

    private inline fun guard(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Log.e(TAG, "$where failed", t)
        }
    }

    private val homeListener = object : HomeView.Listener {
        override fun onSearchQuery(query: String) = guard("home.search") { openInBrowse(query, null, fromHome = true) }
        override fun onOpenQuests() = guard("home.quests") { openQuestPicker() }
        override fun onOpenFavorite(favorite: Favorite) = guard("home.favorite") { openInBrowse(favorite.url, null, fromHome = true) }
        override fun onOpenCompanion() = guard("home.lumo") { openCompanionPanel() }
    }

    private val browseListener = object : BrowseLayer.Listener {
        override fun onPageStarted(url: String) = guard("browse.started") {
            if (!navigatingBack) pagesSinceHome++
            fieldFocused = false
            fieldLabel = null
            publishState()
        }

        override fun onPageFinished(url: String, title: String) = guard("browse.finished") {
            navigatingBack = false
            // Quest hunting hops from page to page on purpose; it doesn't count toward drift.
            if (!questHunting()) drift.onPageLoad(url)
            addTrail(title)
            publishState()
        }

        override fun onUserClick() = guard("browse.click") { drift.onClick() }
        override fun onUserScroll() = guard("browse.scroll") { if (!questHunting()) drift.onScroll() }

        override fun onTextFieldFocus(focused: Boolean, label: String?) = guard("browse.field") {
            fieldFocused = focused
            fieldLabel = if (focused) label else null
            val now = SystemClock.uptimeMillis()
            if (focused && mode == Mode.BROWSE && (hub?.players?.isNotEmpty() == true) && now - lastFieldHintAt > FIELD_HINT_EVERY_MS) {
                lastFieldHintAt = now
                toast.show("Type on your phone: it goes straight into ${label?.takeIf { it.isNotBlank() }?.let { "\"${it.take(24)}\"" } ?: "this box"}.", ToastChip.Kind.INFO)
            }
            publishState()
        }
    }

    private val cardsListener = object : CardsOverlay.Listener {
        override fun onCardsClosed() = guard("cards.closed") { cardsClosed() }

        override fun onCardChanged(index: Int, count: Int, card: Card) = guard("cards.changed") {
            stopVoice()
            cardIndex = index
            cardCount = count
            cardTitle = card.title
            publishState()
        }

        override fun onReadAloud(card: Card) = guard("cards.read") { readCard(card) }
    }

    private val questListener = object : QuestOverlay.Listener {
        // The picker only opens from Home or after a finale: either way the quest gets its own starting search.
        override fun onQuestPicked(quest: Quest) = guard("quest.picked") { startQuest(quest, null, fresh = true) }
        override fun onLumoPickRequested() = guard("quest.lumo") { lumoPick(null, fresh = true, fromPicker = true) }
        override fun onQuestPickerClosed() = guard("quest.pickerClosed") {
            cancelLumoPick()
            if (mode == Mode.QUEST_PICKER) setMode(base)
        }
        override fun onIntroDismissed() = guard("quest.intro") { if (mode == Mode.QUEST_INTRO) enterQuestBrowse() }
        override fun onFinaleDismissed() = guard("quest.finale") {
            // Read the choice now (it resets on the next finale); act once the overlay has finished closing.
            val choice = questOverlay.finaleChoice
            root.post { guard("quest.finale.post") { if (alive) finaleDone(choice) } }
        }
    }

    private val rescueListener = object : RescueOverlay.Listener {
        override fun onRescueChoice(option: RescueOption) = guard("rescue.choice") { rescueChosen(option) }
        override fun onRescueDismiss() = guard("rescue.dismiss") { rescueDismissed() }
    }

    private val panelListener = object : CompanionPanel.Listener {
        override fun onToggleNudges(on: Boolean) = guard("panel.nudges") {
            config.nudgesOn = on
            drift.enabled = on
            toast.show(if (on) "Nudges on." else "Nudges off. Lumo stays quiet.", ToastChip.Kind.INFO)
        }

        override fun onToggleDemo(on: Boolean) = guard("panel.demo") {
            config.demoMode = on
            drift.demoMode = on
            toast.show(if (on) "Demo mode on: quick nudges." else "Demo mode off.", ToastChip.Kind.INFO)
        }

        override fun onCompanionPanelClosed() = guard("panel.closed") { closeCompanionPanel() }
    }

    private val actionBarListener = object : LumoActionBar.Listener {
        override fun onAction(action: LumoActionBar.Action) = guard("bar.action") {
            when (action) {
                LumoActionBar.Action.CARDS -> makeCards(null)
                LumoActionBar.Action.QUEST_SUBMIT -> submitQuest(null)
                LumoActionBar.Action.ZOOM_OUT -> browse?.zoom(-1)
                LumoActionBar.Action.ZOOM_IN -> browse?.zoom(1)
                LumoActionBar.Action.HOME -> goHome()
                LumoActionBar.Action.LUMO_PANEL -> openCompanionPanel()
            }
        }

        // Lumo's bubble may come back to the corner.
        override fun onActionBarHidden() = guard("bar.hidden") { if (alive) syncLumo() }
    }

    private val hubListener = object : PhoneHub.Listener {
        override fun onPlayerJoined(player: Player) = guard("hub.joined") {
            if (!alive) return@guard
            if (resumed) Sfx.join()
            lumo.say(Lines.playerJoined(player.name), Mood.HAPPY, 4000)
            lumo.celebrate(Palette.player(player.colorIndex))
            playersChanged()
        }

        override fun onPlayerLeft(player: Player) = guard("hub.left") {
            if (!alive) return@guard
            playersChanged()
        }

        override fun onCommand(player: Player, command: PhoneCommand) = guard("hub.command") {
            if (!alive || isFinishing) return@guard
            if (!resumed) {
                tellInBackground(player)
                return@guard
            }
            handleCommand(player, command)
        }
    }

    private companion object {
        const val TAG = "Wander"
        const val LUMO_PICK = "lumo"
        const val COUCH_ID = "couch"
        const val STATE_DEBOUNCE_MS = 50L
        const val HEALTH_EVERY_MS = 120_000L
        const val LAN_EVERY_MS = 30_000L
        const val DRIFT_EVERY_MS = 5_000L
        const val FINALE_DELAY_MS = 2_600L
        const val EXIT_WINDOW_MS = 2_500L
        const val FIELD_HINT_EVERY_MS = 90_000L
        const val TRAIL_MAX = 30
        /** Extraction or AI that never answers must not leave Lumo "thinking" forever. */
        const val WATCHDOG_MS = 25_000L
        const val PAD_STEP = 90f
        const val PAD_IDLE_MS = 350L
        /** Phone hub retries: every 2 s for ~20 s, then every 30 s. */
        const val HUB_RETRY_MS = 2_000L
        const val HUB_QUICK_TRIES = 10
        const val HUB_SLOW_RETRY_MS = 30_000L
        const val BACKGROUND_NOTE_EVERY_MS = 5_000L
        /** Polly waits up to 4 s before the TV voice takes over; past this, no voice is coming. */
        const val VOICE_CHECK_MS = 7_000L
        const val VOICE_MISSING = "Lumo's voice isn't working on this TV right now. Read along on the card!"
        // Toast chip anchors (dp), per screen.
        const val TOAST_SIDE_DP = Dimens.SAFE_H + 200f
        const val TOAST_HOME_END_DP = 288f
        const val TOAST_HOME_TOP_DP = 108f
        const val TOAST_BROWSE_TOP_DP = Dimens.SAFE_V + 58f + 8f
        const val TOAST_BOARD_SPACE_DP = 278f
        /** The Lumo panel ends at x 697dp. */
        const val TOAST_PANEL_START_DP = 712f
        const val TOAST_RESCUE_END_DP = 240f
        const val TOAST_RESCUE_TOP_DP = 455f
    }
}
