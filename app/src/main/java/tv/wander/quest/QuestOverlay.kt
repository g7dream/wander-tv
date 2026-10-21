package tv.wander.quest

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import tv.wander.cards.KeySwallow
import tv.wander.cards.safely
import tv.wander.cards.uiSafe
import tv.wander.model.Player
import tv.wander.model.Quest
import tv.wander.model.QuestBoard
import tv.wander.model.QuestOutcome
import tv.wander.ui.Dimens
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.dp
import tv.wander.ui.dpi

/** Quest UI: picker, intro card, the scoreboard panel that rides along while browsing, results, finale. */
class QuestOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onQuestPicked(quest: Quest)
        fun onLumoPickRequested()
        fun onQuestPickerClosed()
        fun onIntroDismissed()
        fun onFinaleDismissed()
    }

    /** What the family chose on the finale screen; set just before [Listener.onFinaleDismissed]. */
    enum class FinaleChoice { ANOTHER_QUEST, BACK_TO_BROWSING }

    /** The finale button that was pressed (Back counts as [FinaleChoice.BACK_TO_BROWSING]); null until then. */
    var finaleChoice: FinaleChoice? = null
        private set

    private enum class Modal { NONE, PICKER, INTRO, FINALE }

    private var modal = Modal.NONE
    private var modalView: View? = null
    private val boardView = QuestBoardView(context)
    private val banner = QuestBannerView(context)
    private val checking = LinkedHashMap<String, Player>()
    private var boardData: QuestBoard? = null
    private var bannerHide: Runnable? = null
    private var checkingTimeout: Runnable? = null
    private val swallow = KeySwallow()

    /** True while the picker, intro or finale covers the screen (it then owns the keys). */
    val isModal: Boolean get() = modal != Modal.NONE

    /** True while the corner scoreboard is on screen. */
    val isBoardShowing: Boolean get() = boardView.visibility == View.VISIBLE

    init {
        clipChildren = false
        isClickable = false
        isFocusable = false
        boardView.visibility = View.GONE
        addView(boardView, LayoutParams(dpi(QuestBoardView.WIDTH_DP), LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dpi(Dimens.SAFE_V)
            rightMargin = dpi(Dimens.SAFE_H)
        })
        banner.visibility = View.GONE
        addView(banner, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dpi(Dimens.SAFE_V)
        })
    }

    fun showPicker(quests: List<Quest>) = uiSafe("showPicker") {
        val picker = QuestPickerView(
            context,
            quests,
            onPick = { q -> if (modal == Modal.PICKER) safely("onQuestPicked") { listener.onQuestPicked(q) } },
            onLumo = { if (modal == Modal.PICKER) safely("onLumoPickRequested") { listener.onLumoPickRequested() } },
        )
        setModal(Modal.PICKER, picker)
        picker.tiles.firstOrNull()?.let { focusOn(it) }
    }

    fun showIntro(quest: Quest) = uiSafe("showIntro") {
        val intro = QuestIntroView(context, quest) { dismissIntro() }
        setModal(Modal.INTRO, intro)
        focusOn(intro.goButton)
        Sfx.chime()
    }

    /** The scoreboard panel; null hides it. */
    fun showBoard(board: QuestBoard?) = uiSafe("showBoard") { applyBoard(board) }

    private fun applyBoard(board: QuestBoard?) {
        if (board == null || board.quest.id == NO_QUEST_ID) {
            boardData = null
            clearChecking()
            hideBoardPanel()
            return
        }
        boardData = board
        boardView.bind(board)
        refreshChecking()
        showBoardPanel()
    }

    fun showChecking(player: Player) = uiSafe("showChecking") {
        checking.remove(player.id)
        checking[player.id] = player
        if (boardData == null && !isBoardShowing) boardView.showBareChecking()
        refreshChecking()
        showBoardPanel()
        checkingTimeout?.let { removeCallbacks(it) }
        // The judge answers within ~8 s; never leave a spinner running if the answer got lost.
        checkingTimeout = Runnable { clearChecking() }.also { postDelayed(it, CHECKING_TIMEOUT_MS) }
    }

    fun showOutcome(outcome: QuestOutcome, player: Player) = uiSafe("showOutcome") {
        checking.remove(player.id)
        if (outcome.board.quest.id != NO_QUEST_ID) {
            applyBoard(outcome.board)
        } else {
            refreshChecking()
        }
        banner.bind(outcome, player)
        presentBanner(celebrate = outcome is QuestOutcome.Accepted)
        when (outcome) {
            is QuestOutcome.Accepted -> Sfx.success()
            is QuestOutcome.Duplicate -> Sfx.pop()
            is QuestOutcome.Rejected -> Sfx.fail()
        }
    }

    fun showFinale(board: QuestBoard) = uiSafe("showFinale") {
        finaleChoice = null
        dismissBanner(immediately = true)
        val finale = QuestFinaleView(context, board) { choice -> chooseFinale(choice) }
        setModal(Modal.FINALE, finale)
        focusOn(finale.anotherButton)
        finale.celebrate()
        Sfx.success()
    }

    fun hideModal() = uiSafe("hideModal") { closeModal() }

    private fun closeModal() {
        val v = modalView ?: run { modal = Modal.NONE; return }
        modal = Modal.NONE
        modalView = null
        v.findFocus()?.clearFocus()
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(180).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    v.animate().setListener(null)
                    removeView(v)
                }
            }).start()
        positionBanner()
    }

    /** Puts Lumo's pick card back to its inviting state (e.g. the AI quest couldn't be made). */
    fun resetLumoPick() = uiSafe("resetLumoPick") {
        (modalView as? QuestPickerView)?.resetLumo()
    }

    fun handleKey(event: KeyEvent): Boolean = try {
        routeKey(event)
    } catch (t: Throwable) {
        android.util.Log.e("Wander", "quest key failed", t)
        isModal
    }

    private fun routeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (swallow.swallows(event)) return true
        if (!isModal) return false
        if (code in PASS_THROUGH) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> moveFocus(View.FOCUS_UP)
            KeyEvent.KEYCODE_DPAD_DOWN -> moveFocus(View.FOCUS_DOWN)
            KeyEvent.KEYCODE_DPAD_LEFT -> moveFocus(View.FOCUS_LEFT)
            KeyEvent.KEYCODE_DPAD_RIGHT -> moveFocus(View.FOCUS_RIGHT)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            -> if (event.repeatCount == 0) {
                swallow.remember(event)
                activate()
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B,
            -> if (event.repeatCount == 0) {
                swallow.remember(event)
                back()
            }
        }
        return true
    }

    // ---- modal plumbing ---------------------------------------------------------------------------

    private fun setModal(kind: Modal, view: View) {
        val old = modalView
        if (old != null) {
            old.findFocus()?.clearFocus()
            old.animate().cancel()
            old.animate().setListener(null)
            removeView(old)
        }
        modal = kind
        modalView = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        view.alpha = 0f
        view.scaleX = 0.985f
        view.scaleY = 0.985f
        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        if (kind == Modal.FINALE) dismissBanner(immediately = true)
        positionBanner()
    }

    private fun dismissIntro() {
        if (modal != Modal.INTRO) return
        Sfx.select()
        closeModal()
        safely("onIntroDismissed") { listener.onIntroDismissed() }
    }

    private fun chooseFinale(choice: FinaleChoice) {
        if (modal != Modal.FINALE) return
        finaleChoice = choice
        Sfx.select()
        closeModal()
        safely("onFinaleDismissed") { listener.onFinaleDismissed() }
    }

    private fun activate() {
        when (modal) {
            Modal.INTRO -> dismissIntro()
            Modal.PICKER, Modal.FINALE -> {
                val root = modalView as? ViewGroup ?: return
                val focused = root.findFocus()
                if (focused == null || !focused.isClickable) {
                    firstFocusable(root)?.let { focusOn(it) }
                } else {
                    focused.performClick()
                }
            }
            Modal.NONE -> Unit
        }
    }

    private fun back() {
        when (modal) {
            Modal.PICKER -> {
                Sfx.back()
                closeModal()
                safely("onQuestPickerClosed") { listener.onQuestPickerClosed() }
            }
            Modal.INTRO -> dismissIntro()
            Modal.FINALE -> chooseFinale(FinaleChoice.BACK_TO_BROWSING)
            Modal.NONE -> Unit
        }
    }

    /** D-pad focus stays trapped inside the modal; at an edge it simply stays put. */
    private fun moveFocus(direction: Int) {
        val root = modalView as? ViewGroup ?: return
        val focused = root.findFocus()
        if (focused == null) {
            firstFocusable(root)?.let { focusOn(it) }
            return
        }
        val next = try {
            FocusFinder.getInstance().findNextFocus(root, focused, direction)
        } catch (e: RuntimeException) {
            null
        }
        next?.let { focusOn(it, direction) }
    }

    /**
     * Gives [view] D-pad focus. A Fire TV window is normally out of touch mode; if something put it in
     * touch mode (a mouse, adb input), plain requestFocus() is refused, so fall back to leaving it.
     */
    private fun focusOn(view: View, direction: Int = View.FOCUS_DOWN) {
        if (!view.requestFocus(direction)) view.requestFocusFromTouch()
    }

    private fun firstFocusable(root: ViewGroup): View? = when (val v = modalView) {
        is QuestPickerView -> v.tiles.firstOrNull()
        is QuestFinaleView -> v.anotherButton
        is QuestIntroView -> v.goButton
        else -> root.focusables().firstOrNull()
    }

    // ---- board + banner ---------------------------------------------------------------------------

    private fun showBoardPanel() {
        if (boardView.visibility == View.VISIBLE && boardView.translationX == 0f) {
            positionBanner()
            return
        }
        boardView.animate().cancel()
        boardView.animate().setListener(null)
        if (boardView.visibility != View.VISIBLE) {
            boardView.alpha = 0f
            boardView.translationX = dp(40f)
            boardView.visibility = View.VISIBLE
        }
        boardView.animate().alpha(1f).translationX(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        positionBanner()
    }

    private fun hideBoardPanel() {
        if (boardView.visibility != View.VISIBLE) return
        boardView.animate().cancel()
        boardView.animate().alpha(0f).translationX(dp(40f)).setDuration(200).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    boardView.animate().setListener(null)
                    if (boardData == null && checking.isEmpty()) {
                        boardView.visibility = View.GONE
                        boardView.reset()
                    }
                }
            }).start()
        positionBanner(boardVisible = false)
    }

    private fun refreshChecking() {
        val latest = checking.values.lastOrNull()
        if (latest == null) {
            boardView.hideChecking()
            checkingTimeout?.let { removeCallbacks(it) }
            checkingTimeout = null
            return
        }
        boardView.showChecking(displayName(latest), Palette.player(latest.colorIndex), checking.size - 1)
    }

    private fun clearChecking() {
        checking.clear()
        refreshChecking()
        if (boardData == null) hideBoardPanel()
    }

    /** Centers the banner at the top, in the space left of the scoreboard when the board is up. */
    private fun positionBanner(boardVisible: Boolean = isBoardShowing && boardData != null || isBoardShowing && checking.isNotEmpty()) {
        val lp = banner.layoutParams as LayoutParams
        val screenW = if (width > 0) width else resources.displayMetrics.widthPixels
        val safe = dpi(Dimens.SAFE_H)
        val boardSpace = dpi(QuestBoardView.WIDTH_DP) + dpi(18f)
        val maxW = if (boardVisible && modal == Modal.NONE) screenW - 2 * safe - boardSpace else (screenW - 2 * safe).coerceAtMost(dpi(660f))
        val shift = if (boardVisible && modal == Modal.NONE) boardSpace / 2 else 0
        banner.setMaxWidth(maxW)
        if (lp.rightMargin != shift || lp.leftMargin != 0) {
            lp.leftMargin = 0
            lp.rightMargin = shift
            banner.layoutParams = lp
        }
    }

    private fun presentBanner(celebrate: Boolean) {
        positionBanner()
        bannerHide?.let { removeCallbacks(it) }
        banner.animate().cancel()
        banner.animate().setListener(null)
        if (banner.visibility != View.VISIBLE) {
            banner.visibility = View.VISIBLE
            banner.alpha = 0f
            banner.translationY = -dp(150f)
        }
        banner.animate().alpha(1f).translationY(0f).setDuration(if (celebrate) 340 else 260)
            .setInterpolator(if (celebrate) OvershootInterpolator(1.3f) else DecelerateInterpolator())
            .start()
        bannerHide = Runnable { dismissBanner(immediately = false) }.also { postDelayed(it, BANNER_MS) }
    }

    private fun dismissBanner(immediately: Boolean) {
        bannerHide?.let { removeCallbacks(it) }
        bannerHide = null
        banner.animate().cancel()
        if (immediately || banner.visibility != View.VISIBLE) {
            banner.visibility = View.GONE
            return
        }
        banner.animate().alpha(0f).translationY(-dp(150f)).setDuration(220).setInterpolator(AccelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    banner.animate().setListener(null)
                    if (bannerHide == null) banner.visibility = View.GONE
                }
            }).start()
    }

    override fun onDetachedFromWindow() {
        bannerHide?.let { removeCallbacks(it) }
        checkingTimeout?.let { removeCallbacks(it) }
        bannerHide = null
        checkingTimeout = null
        banner.visibility = View.GONE
        checking.clear()
        boardView.hideChecking()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val NO_QUEST_ID = "none"
        const val BANNER_MS = 4000L
        const val CHECKING_TIMEOUT_MS = 12_000L
        val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
        )
    }
}
