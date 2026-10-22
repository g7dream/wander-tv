package tv.wander.cards

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.model.Card
import tv.wander.model.CardDeck
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** "Make it a card": a long page becomes 3-5 big cards the whole room can read. Left/right flips. */
class CardsOverlay(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onCardsClosed()
        fun onCardChanged(index: Int, count: Int, card: Card)
        fun onReadAloud(card: Card)
    }

    private enum class State { HIDDEN, LOADING, DECK, ERROR }

    private var state = State.HIDDEN
    private var deck: CardDeck? = null
    private var cards: List<Card> = emptyList()
    private var currentIndex = 0

    /** Carousel position; equals [currentIndex] at rest, fractional while flipping. */
    private var pos = 0f
    private var enter = 1f
    private var pulse = 0f

    private val header = LinearLayout(context)
    private val fromLabel = Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body, "From:")
    private val fromTitle = Ui.text(context, TypeScale.H2, Palette.CREAM, Fonts.display)
    private val provenance = Craft.chip(context, "", Palette.LAMP, 0, 0)
    private val stage = FrameLayout(context)
    private val cardViews = ArrayList<FieldCardView>()
    private val dots = DotsView(context)
    private val loadingRow = LinearLayout(context)
    private val loadingMoth = MothMarkView(context, Palette.LAMP)
    private val loadingText = Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body)
    private val errorPanel = LinearLayout(context)
    private val errorText = Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body)
    private val hintHolder = FrameLayout(context)
    private var okKey: KeycapView? = null

    private var flipAnim: ValueAnimator? = null
    private var enterAnim: ValueAnimator? = null
    private var pulseAnim: ValueAnimator? = null
    private var shimmerAnim: ValueAnimator? = null
    private var keyLightAnim: ValueAnimator? = null

    private val swallow = KeySwallow()

    val isShowing: Boolean get() = state != State.HIDDEN
    val index: Int get() = if (state == State.DECK) currentIndex else 0
    val count: Int get() = if (state == State.DECK) cards.size else 0
    val current: Card? get() = if (state == State.DECK) cards.getOrNull(currentIndex) else null

    init {
        visibility = View.GONE
        isClickable = true
        isFocusable = false
        clipChildren = false
        clipToPadding = false
        background = ScrimGlowDrawable(glowY = 0.47f)
        buildHeader()
        stage.clipChildren = false
        addView(stage, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, dpi(14f), Gravity.BOTTOM).apply { bottomMargin = dpi(84f) })
        buildLoadingRow()
        buildErrorPanel()
        addView(hintHolder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dpi(Dimens.SAFE_V + 6f)
        })
    }

    private fun buildHeader() {
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val from = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            addView(fromLabel)
            addView(fromTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dpi(10f) })
        }
        fromTitle.maxLines = 1
        fromTitle.ellipsize = TextUtils.TruncateAt.END
        header.addView(from, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(provenance, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dpi(20f)
        })
        provenance.compoundDrawablePadding = dpi(8f)
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = dpi(Dimens.SAFE_H)
            rightMargin = dpi(Dimens.SAFE_H)
            topMargin = dpi(Dimens.SAFE_V + 10f)
        })
    }

    private fun buildLoadingRow() {
        loadingRow.orientation = LinearLayout.HORIZONTAL
        loadingRow.gravity = Gravity.CENTER
        loadingRow.addView(loadingMoth, LinearLayout.LayoutParams(dpi(34f), dpi(30f)).apply { rightMargin = dpi(12f) })
        loadingText.maxLines = 1
        loadingText.ellipsize = TextUtils.TruncateAt.MIDDLE
        loadingRow.addView(loadingText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(loadingRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = dpi(76f)
            leftMargin = dpi(120f)
            rightMargin = dpi(120f)
        })
    }

    private fun buildErrorPanel() {
        errorPanel.orientation = LinearLayout.VERTICAL
        errorPanel.gravity = Gravity.CENTER_HORIZONTAL
        errorPanel.background = Ui.roundRect(context, Palette.DUSK, Dimens.RADIUS_BIG, Palette.withAlpha(0xFF6B5C93.toInt(), 0.55f), 1.5f)
        errorPanel.setPadding(dpi(44f), dpi(36f), dpi(44f), dpi(36f))
        errorPanel.addView(MothMarkView(context, Palette.CORAL), LinearLayout.LayoutParams(dpi(64f), dpi(56f)).apply { bottomMargin = dpi(18f) })
        errorPanel.addView(Ui.text(context, 30f, Palette.CREAM, Fonts.display, "This page won't fold into cards").apply {
            gravity = Gravity.CENTER
        })
        errorText.gravity = Gravity.CENTER
        errorText.maxLines = 3
        errorText.ellipsize = TextUtils.TruncateAt.END
        errorPanel.addView(errorText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpi(12f)
        })
        addView(errorPanel, LayoutParams(dpi(600f), LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { bottomMargin = dpi(20f) })
    }

    fun showLoading(pageTitle: String) = uiSafe("showLoading") {
        stopFlip()
        state = State.LOADING
        deck = null
        cards = emptyList()
        currentIndex = 0
        pos = 0f
        val title = pageTitle.trim().ifEmpty { "this page" }
        fromTitle.text = title
        provenance.visibility = View.GONE
        errorPanel.visibility = View.GONE
        dots.visibility = View.GONE
        loadingRow.visibility = View.VISIBLE
        loadingText.text = "Lumo is reading “${title.take(60)}”..."
        loadingMoth.flutter(true)
        setHints(listOf(Craft.Hint(listOf(Craft.Key.Label("Back")), "stop")))
        rebuildCards(3, skeleton = true)
        startShimmer()
        startEnter()
        appear()
    }

    fun showDeck(deck: CardDeck) = uiSafe("showDeck") {
        val usable = deck.cards.filter { it.title.isNotBlank() || it.line.isNotBlank() }.take(MAX_CARDS)
        if (usable.isEmpty()) {
            showError("There wasn't enough to read on this page. Try a longer article!")
            return@uiSafe
        }
        stopFlip()
        stopShimmer()
        loadingMoth.flutter(false)
        state = State.DECK
        this.deck = deck
        cards = usable
        currentIndex = 0
        pos = 0f
        val domain = Craft.domainOf(deck.sourceUrl)
        // "Natural satellite", not "Natural satellite - Simple English Wikipedia, the free encyclopedia".
        val title = deck.sourceTitle.trim().let { if (it.isEmpty()) it else tv.wander.browse.PageTexts.displayTitle(it, deck.sourceUrl) }
        fromTitle.text = title.ifEmpty { domain.ifEmpty { "this page" } }
        if (deck.fromAi) {
            provenance.text = "Made by Lumo with Amazon Bedrock"
            provenance.setTextColor(Palette.LAMP)
            provenance.background = Ui.roundRect(context, Palette.withAlpha(Palette.LAMP, 0.12f), 999f, Palette.withAlpha(Palette.LAMP, 0.45f), 1.5f)
            provenance.setCompoundDrawables(MothDrawable(Palette.LAMP, dpi(20f)), null, null, null)
        } else {
            provenance.text = "Made on this TV"
            provenance.setTextColor(Palette.CREAM)
            provenance.background = Ui.roundRect(context, Palette.withAlpha(Palette.DUSK, 0.9f), 999f, Palette.HAIRLINE, 1.5f)
            provenance.setCompoundDrawables(MothDrawable(Palette.MUTED, dpi(20f)), null, null, null)
        }
        provenance.visibility = View.VISIBLE
        errorPanel.visibility = View.GONE
        loadingRow.visibility = View.GONE
        rebuildCards(cards.size, skeleton = false)
        cardViews.forEachIndexed { i, v -> v.bind(cards[i], i + 1, cards.size, Palette.player(i), domain) }
        dots.setup(cards.size)
        dots.visibility = if (cards.size > 1) View.VISIBLE else View.INVISIBLE
        val okKeys = ArrayList<KeycapView>()
        setHints(
            listOf(
                Craft.Hint(listOf(Craft.Key.Left, Craft.Key.Right), "flip"),
                Craft.Hint(listOf(Craft.Key.Label("OK")), "read aloud"),
                Craft.Hint(listOf(Craft.Key.Label("Back")), "close"),
            ),
            okKeys,
        )
        okKey = okKeys.getOrNull(2)
        startEnter()
        appear()
        Sfx.chime()
        notifyChanged()
    }

    fun showError(message: String) = uiSafe("showError") {
        stopFlip()
        stopShimmer()
        loadingMoth.flutter(false)
        state = State.ERROR
        deck = null
        cards = emptyList()
        currentIndex = 0
        pos = 0f
        rebuildCards(0, skeleton = false)
        provenance.visibility = View.GONE
        dots.visibility = View.GONE
        loadingRow.visibility = View.GONE
        errorText.text = message.trim().ifEmpty { "Lumo couldn't read this one. The page is still right here." }
        errorPanel.visibility = View.VISIBLE
        errorPanel.alpha = 0f
        errorPanel.translationY = dp(18f)
        errorPanel.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        setHints(listOf(Craft.Hint(listOf(Craft.Key.Label("Back")), "back to the page")))
        appear()
    }

    fun next() = uiSafe("next") {
        if (state != State.DECK) return@uiSafe
        if (currentIndex >= cards.lastIndex) {
            bounce(+1)
        } else {
            currentIndex++
            flipTo(currentIndex)
        }
    }

    fun prev() = uiSafe("prev") {
        if (state != State.DECK) return@uiSafe
        if (currentIndex <= 0) {
            bounce(-1)
        } else {
            currentIndex--
            flipTo(currentIndex)
        }
    }

    fun handleKey(event: KeyEvent): Boolean = try {
        routeKey(event)
    } catch (t: Throwable) {
        android.util.Log.e("Wander", "cards key failed", t)
        isShowing
    }

    private fun routeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (swallow.swallows(event)) return true
        if (!isShowing) return false
        if (code in PASS_THROUGH) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> next()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_BUTTON_A,
            -> if (event.repeatCount == 0) {
                when (state) {
                    State.DECK -> readAloud()
                    State.ERROR -> { swallow.remember(event); close() }
                    else -> Unit
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BUTTON_B,
            -> if (event.repeatCount == 0) {
                swallow.remember(event)
                Sfx.back()
                close()
            }
        }
        return true
    }

    fun close() = uiSafe("close") {
        if (state == State.HIDDEN) return@uiSafe
        state = State.HIDDEN
        stopFlip()
        stopShimmer()
        pulseAnim?.cancel()
        keyLightAnim?.cancel()
        loadingMoth.flutter(false)
        animate().cancel()
        animate().alpha(0f).setDuration(180).setInterpolator(DecelerateInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    animate().setListener(null)
                    if (state == State.HIDDEN) {
                        visibility = View.GONE
                        rebuildCards(0, skeleton = false)
                        deck = null
                        cards = emptyList()
                    }
                }
            }).start()
        safely("onCardsClosed") { listener.onCardsClosed() }
    }

    // ---- internals -------------------------------------------------------------------------------

    private fun appear() {
        animate().setListener(null)
        animate().cancel()
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun readAloud() {
        val card = current ?: return
        Sfx.select()
        pulseAnim?.cancel()
        pulseAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            addUpdateListener { pulse = sin((it.animatedValue as Float) * PI).toFloat(); applyTransforms() }
            start()
        }
        val key = okKey
        if (key != null) {
            keyLightAnim?.cancel()
            keyLightAnim = ValueAnimator.ofFloat(1f, 1f, 0f).apply {
                duration = 1400
                addUpdateListener { key.lit = it.animatedValue as Float }
                start()
            }
        }
        safely("onReadAloud") { listener.onReadAloud(card) }
    }

    private fun notifyChanged() {
        val card = current ?: return
        safely("onCardChanged") { listener.onCardChanged(currentIndex, cards.size, card) }
    }

    private fun flipTo(target: Int) {
        Sfx.whoosh()
        notifyChanged()
        flipAnim?.cancel()
        flipAnim = ValueAnimator.ofFloat(pos, target.toFloat()).apply {
            duration = FLIP_MS
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { pos = it.animatedValue as Float; applyTransforms() }
            start()
        }
    }

    /** A small nudge past the end and back: there's no card there, and no wrap-around. */
    private fun bounce(direction: Int) {
        if (flipAnim?.isRunning == true) return
        val base = currentIndex.toFloat()
        flipAnim = ValueAnimator.ofFloat(base, base + direction * 0.07f, base).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener { pos = it.animatedValue as Float; applyTransforms() }
            start()
        }
    }

    private fun stopFlip() {
        flipAnim?.cancel()
        flipAnim = null
        pos = currentIndex.toFloat()
    }

    private fun startEnter() {
        enterAnim?.cancel()
        enter = 0f
        enterAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener { enter = it.animatedValue as Float; applyTransforms() }
            start()
        }
        applyTransforms()
    }

    private fun startShimmer() {
        stopShimmer()
        shimmerAnim = Craft.loop(1500) { t ->
            cardViews.forEachIndexed { i, v -> v.shimmer = (t + i * 0.18f) % 1f }
        }.also { it.start() }
    }

    private fun stopShimmer() {
        shimmerAnim?.cancel()
        shimmerAnim = null
    }

    private fun rebuildCards(n: Int, skeleton: Boolean) {
        if (cardViews.size != n) {
            stage.removeAllViews()
            cardViews.clear()
            repeat(n) {
                val v = FieldCardView(context)
                v.cameraDistance = 9000f * resources.displayMetrics.density
                stage.addView(v, LayoutParams(dpi(FieldCardView.WIDTH), dpi(FieldCardView.HEIGHT), Gravity.CENTER).apply {
                    bottomMargin = dpi(CARD_LIFT * 2)
                })
                cardViews.add(v)
            }
        }
        cardViews.forEachIndexed { i, v ->
            v.skeleton = skeleton
            if (skeleton) v.bind(null, i + 1, n, Palette.HAIRLINE, "")
        }
        applyTransforms()
    }

    private fun applyTransforms() {
        val step = dp(FieldCardView.WIDTH) / 2f + dp(32f) + dp(FieldCardView.WIDTH) * NEIGHBOR_SCALE / 2f
        val rise = (1f - enter) * dp(26f)
        val skeleton = state == State.LOADING
        cardViews.forEachIndexed { i, v ->
            // Skeleton cards sit at -1, 0, 1; real cards follow the carousel position.
            val d = if (skeleton) (i - 1).toFloat() else i - pos
            val near = min(abs(d), 1f)
            val scale = (1f - (1f - NEIGHBOR_SCALE) * near) * (1f + 0.035f * pulse * (1f - near))
            v.translationX = d * step
            v.translationY = rise * (1f + near * 0.6f)
            v.scaleX = scale
            v.scaleY = scale
            v.rotationY = if (skeleton) 0f else -16f * sin(PI * d.coerceIn(-1f, 1f)).toFloat()
            v.translationZ = (1f - near) * dp(10f)
            v.dim = near * 0.85f
            val far = (abs(d) - 1f).coerceIn(0f, 1f)
            v.alpha = (1f - far) * enter.coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
            v.visibility = if (abs(d) >= 2f) View.INVISIBLE else View.VISIBLE
        }
        dots.position = pos
        invalidate()
    }

    private fun setHints(hints: List<Craft.Hint>, keycaps: MutableList<KeycapView>? = null) {
        hintHolder.removeAllViews()
        hintHolder.addView(Craft.hintRow(context, hints, keycaps), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        if (keycaps == null) okKey = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Coming back while still reading: the shimmer and the moth were stopped on detach.
        if (state == State.LOADING) {
            startShimmer()
            loadingMoth.flutter(true)
        }
    }

    override fun onDetachedFromWindow() {
        flipAnim?.cancel()
        enterAnim?.cancel()
        pulseAnim?.cancel()
        keyLightAnim?.cancel()
        stopShimmer()
        enter = 1f
        pulse = 0f
        pos = currentIndex.toFloat()
        super.onDetachedFromWindow()
    }

    /** Page dots under the carousel; the active one stretches into a pill in that card's color. */
    private class DotsView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()
        private var n = 0
        var position: Float = 0f
            set(value) { field = value; invalidate() }

        fun setup(count: Int) {
            n = count
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (n <= 0) return
            val dot = dp(10f)
            val pill = dp(32f)
            val gap = dp(10f)
            val total = (n - 1) * (dot + gap) + pill
            var x = (width - total) / 2f
            val cy = height / 2f
            for (i in 0 until n) {
                val t = (1f - abs(i - position)).coerceIn(0f, 1f)
                val w = dot + (pill - dot) * t
                paint.color = blend(Palette.withAlpha(Palette.CREAM, 0.22f), Palette.player(i), t)
                rect.set(x, cy - dot / 2f, x + w, cy + dot / 2f)
                canvas.drawRoundRect(rect, dot / 2f, dot / 2f, paint)
                x += w + gap
            }
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(c: Int, s: Int) = (c shr s) and 0xFF
            fun mix(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).roundToInt().coerceIn(0, 255)
            return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }

    private companion object {
        const val MAX_CARDS = 8
        const val FLIP_MS = 260L
        const val NEIGHBOR_SCALE = 0.82f
        const val CARD_LIFT = 8f
        val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP,
        )
    }
}
