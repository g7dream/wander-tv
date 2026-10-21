package tv.wander.home

import android.content.Context
import android.graphics.Bitmap
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.wander.model.Player
import tv.wander.ui.Dimens
import tv.wander.ui.Fonts
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette
import tv.wander.ui.Sfx
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dp
import tv.wander.ui.dpi
import kotlin.math.min

/** Home screen: four big tiles (Search, Quests, Favorites, Lumo), favorites row, and the "join the couch" QR panel. */
class HomeView(context: Context, private val listener: Listener) : FrameLayout(context) {
    interface Listener {
        fun onSearchQuery(query: String)
        fun onOpenQuests()
        fun onOpenFavorite(favorite: Favorite)
        fun onOpenCompanion()
    }

    /** Called with true when the inline search field opens and false when it closes (phones can type into it). */
    var onSearchFieldChanged: ((Boolean) -> Unit)? = null

    /** True while the inline search field is showing. */
    val isSearchOpen: Boolean get() = searchOpen

    /** Pixel size MainActivity should render the QR bitmap at, so it maps 1:1 onto screen pixels. */
    val qrSizePx: Int get() = dpi(JoinPanel.QR_DP)

    // The 960x540dp stage; scaled to fit if the screen reports a different size.
    private val stage = FrameLayout(context)
    private val questPill = Ui.text(context, TypeScale.CAPTION, Palette.LAMP, Fonts.bodyBold).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f))
        background = Ui.roundRect(context, Palette.withAlpha(Palette.LAMP, 0.12f), 16f, Palette.withAlpha(Palette.LAMP, 0.45f), 1f)
        visibility = View.GONE
    }
    private val aiDot = View(context)
    private val aiLabel = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, "Offline mode")
    private val joinPanel = JoinPanel(context)
    private val heroRow = FrameLayout(context)
    private val tiles: List<HeroTile>
    private val favTiles: List<FavoriteTile>
    private val footer = Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.body).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val searchStrip = FrameLayout(context)
    private val searchField = EditText(context)
    private val searchBox = FrameLayout(context)
    private val suggestions: List<TextView>

    private var searchOpen = false
    private var lastFocused: View? = null
    private var aiOnline = false

    init {
        background = LampBackdrop()
        clipChildren = false
        stage.clipChildren = false
        stage.clipToPadding = false
        addView(stage, LayoutParams(dpi(STAGE_W), dpi(STAGE_H), Gravity.CENTER))

        // Header: the wordmark sits in the lamp's glow.
        val wordmark = Ui.text(context, TypeScale.DISPLAY, Palette.CREAM, Fonts.display, "Wander").apply {
            setShadowLayer(dp(18f), 0f, 0f, Palette.withAlpha(Palette.LAMP, 0.55f))
        }
        stage.place(wordmark, Dimens.SAFE_H, Dimens.SAFE_V)
        stage.place(Ui.text(context, TypeScale.BODY, Palette.MUTED, Fonts.body, "Browse together. Wander well."), Dimens.SAFE_H + 2f, 84f)
        stage.place(questPill, Dimens.SAFE_H, 116f, w = WRAP)

        // Status: clock with the AI chip under it, right-aligned to the content column.
        val status = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        status.addView(ClockText(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(10f), dpi(5f), dpi(12f), dpi(5f))
            background = Ui.roundRect(context, Palette.withAlpha(Palette.DUSK, 0.85f), 14f, Palette.HAIRLINE, 1f)
            addView(aiDot, LinearLayout.LayoutParams(dpi(8f), dpi(8f)).apply { marginEnd = dpi(7f) })
            addView(aiLabel)
        }
        status.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8f) })
        stage.place(status, STAGE_W - COLUMN_RIGHT, Dimens.SAFE_V + 2f, fromRight = true)

        // Right column: join panel on top; the bottom-right corner stays free for big Lumo.
        stage.place(joinPanel, Dimens.SAFE_H, Dimens.SAFE_V, JoinPanel.WIDTH, JoinPanel.HEIGHT, fromRight = true)

        // Hero tiles.
        heroRow.clipChildren = false
        tiles = listOf(
            HeroTile(context, "🔎", "Search", "Type on your phone or hold the mic", Palette.LAMP),
            HeroTile(context, "🧭", "Quests", "Family treasure hunts", Palette.BERRY),
            HeroTile(context, "⭐", "Favorites", "Sites that read well on TV", Palette.MINT),
            HeroTile(context, "🦋", "Lumo", "Your browsing buddy", Palette.SKY),
        )
        val tileW = (COLUMN_RIGHT - Dimens.SAFE_H - TILE_GAP * 3) / 4f
        tiles.forEachIndexed { i, t ->
            t.id = View.generateViewId()
            heroRow.place(t, i * (tileW + TILE_GAP), 0f, tileW, TILE_H)
        }
        stage.place(heroRow, Dimens.SAFE_H, HERO_Y, COLUMN_RIGHT - Dimens.SAFE_H, TILE_H)

        // Search strip: replaces the hero row while open.
        buildSearchStrip()
        suggestions = SUGGESTIONS.map { makeSuggestion(it) }
        stage.place(searchStrip, Dimens.SAFE_H, HERO_Y, COLUMN_RIGHT - Dimens.SAFE_H, TILE_H)
        searchStrip.visibility = View.GONE

        // Favorites row.
        stage.place(
            Ui.text(context, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, "GOOD PLACES TO WANDER").apply { letterSpacing = 0.12f },
            Dimens.SAFE_H + 2f, FAV_LABEL_Y,
        )
        val favRow = FrameLayout(context).apply { clipChildren = false }
        val favW = (FAV_RIGHT - Dimens.SAFE_H - FAV_GAP * 5) / 6f
        favTiles = Favorites.all.take(6).mapIndexed { i, f ->
            FavoriteTile(context, f).also {
                it.id = View.generateViewId()
                favRow.place(it, i * (favW + FAV_GAP), 0f, favW, FAV_H)
            }
        }
        stage.place(favRow, Dimens.SAFE_H, FAV_Y, FAV_RIGHT - Dimens.SAFE_H, FAV_H)
        stage.place(footer, Dimens.SAFE_H + 2f, FOOTER_Y, FAV_RIGHT - Dimens.SAFE_H)

        layoutSuggestions()
        wireFocus()
        setAiOnline(false)
        showHint(null)
    }

    /** [url] is the phone-join address (null when the TV has no LAN address); [qr] encodes it. */
    fun setJoinInfo(url: String?, qr: Bitmap?) {
        joinPanel.setJoinInfo(url, qr)
    }

    fun setPlayers(players: List<Player>) {
        joinPanel.setPlayers(players)
    }

    fun setAiOnline(online: Boolean) {
        aiOnline = online
        aiDot.background = context.circle(if (online) Palette.MINT else Palette.withAlpha(Palette.MUTED, 0.6f))
        aiLabel.text = if (online) "Bedrock connected" else "Offline mode"
        aiLabel.setTextColor(if (online) Palette.CREAM else Palette.MUTED)
    }

    /** One line under the title, e.g. "Speed Safari: 2 of 3 found". Null hides it. */
    fun setQuestLine(text: String?) {
        if (text.isNullOrBlank()) {
            questPill.visibility = View.GONE
        } else {
            questPill.text = text
            questPill.visibility = View.VISIBLE
        }
    }

    /** Puts focus back where it was on Home (the Search tile the first time). */
    fun focusDefault() {
        if (searchOpen) {
            searchField.requestFocus()
            return
        }
        val target = lastFocused?.takeIf { it.isShown && it.isFocusable && (it in tiles || it in favTiles) } ?: tiles[0]
        if (!target.requestFocus()) tiles[0].requestFocus()
    }

    /** Opens the inline search field (optionally pre-filled, e.g. from a phone) and brings up the keyboard. */
    fun openSearch(prefill: String? = null) {
        if (prefill != null) {
            searchField.setText(prefill)
            searchField.setSelection(searchField.text.length)
        }
        if (!searchOpen) {
            searchOpen = true
            crossFade(heroRow, searchStrip)
            onSearchFieldChanged?.invoke(true)
        }
        searchField.requestFocus()
        if (prefill == null) showKeyboard()
    }

    /** Closes the search field; false if it wasn't open. */
    fun closeSearch(): Boolean {
        if (!searchOpen) return false
        searchOpen = false
        hideKeyboard()
        crossFade(searchStrip, heroRow)
        tiles[0].requestFocus()
        onSearchFieldChanged?.invoke(false)
        return true
    }

    /** Back on Home: closes the search field first. False means Home has nothing left to close. */
    fun handleBack(): Boolean {
        if (closeSearch()) {
            Sfx.back()
            return true
        }
        return false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val s = min(w / dp(STAGE_W), h / dp(STAGE_H))
        stage.scaleX = s
        stage.scaleY = s
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView !== this) return
        if (visibility == View.VISIBLE) {
            stage.alpha = 0f
            stage.translationY = dp(10f)
            stage.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
        } else if (searchOpen) {
            // Leaving Home (a phone opened a page, say): fold the search field away quietly.
            searchOpen = false
            hideKeyboard()
            searchStrip.visibility = View.GONE
            heroRow.visibility = View.VISIBLE
            heroRow.alpha = 1f
            onSearchFieldChanged?.invoke(false)
        }
    }

    // ---- focus ------------------------------------------------------------------------------------

    private fun wireFocus() {
        val tileActions = listOf<() -> Unit>(
            { openSearch() },
            { listener.onOpenQuests() },
            { favTiles.firstOrNull()?.requestFocus() },
            { listener.onOpenCompanion() },
        )
        val tileHints = listOf(
            "Press OK to search. Any phone on the couch can type here too.",
            "Pick a hunt, then everyone races to find answers on real pages.",
            "Hand-picked, kid-friendly pages with big, readable text.",
            "Tonight's trail, nudges and settings. The Menu button opens it too.",
        )
        tiles.forEachIndexed { i, t ->
            Ui.makeFocusable(t, Dimens.RADIUS) { has -> if (has) { lastFocused = t; showHint(tileHints[i]) } }
            t.setOnClickListener {
                Sfx.select()
                tileActions[i]()
            }
            t.nextFocusUpId = t.id
            t.nextFocusLeftId = if (i == 0) t.id else tiles[i - 1].id
            t.nextFocusRightId = if (i == tiles.lastIndex) t.id else tiles[i + 1].id
            t.nextFocusDownId = favTiles.getOrNull(TILE_TO_FAV[i])?.id ?: t.id
        }
        favTiles.forEachIndexed { i, f ->
            Ui.makeFocusable(f, 16f) { has -> if (has) { lastFocused = f; showHint("${f.favorite.emoji}  ${f.favorite.blurb}") } }
            f.setOnClickListener {
                Sfx.select()
                listener.onOpenFavorite(f.favorite)
            }
            f.nextFocusDownId = f.id
            f.nextFocusLeftId = if (i == 0) f.id else favTiles[i - 1].id
            f.nextFocusRightId = if (i == favTiles.lastIndex) f.id else favTiles[i + 1].id
            f.nextFocusUpId = if (searchOpen) searchField.id else tiles[FAV_TO_TILE.getOrElse(i) { 0 }].id
        }
    }

    private fun showHint(text: String?) {
        footer.text = text ?: "OK opens  ·  Menu talks to Lumo  ·  Phones join with the code"
    }

    // ---- search -----------------------------------------------------------------------------------

    private fun buildSearchStrip() {
        val ctx = context
        searchStrip.clipChildren = false
        searchBox.background = Ui.roundRect(ctx, Palette.DUSK, 32f, Palette.HAIRLINE, 1.5f)
        val icon = Ui.text(ctx, 26f, Palette.CREAM, Fonts.body, "🔎")
        searchBox.addView(icon, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dpi(20f) })
        searchField.apply {
            id = View.generateViewId()
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, TypeScale.H2)
            typeface = Fonts.body
            setTextColor(Palette.CREAM)
            setHintTextColor(Palette.withAlpha(Palette.MUTED, 0.8f))
            hint = "Search, or type a web address"
            background = null
            setPadding(0, 0, 0, 0)
            includeFontPadding = false
            isSingleLine = true
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
            isFocusable = true
            isFocusableInTouchMode = true
            nextFocusUpId = id
            nextFocusLeftId = id
            nextFocusRightId = id
            setOnEditorActionListener { _, actionId, event ->
                val enter = event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO ||
                    actionId == EditorInfo.IME_ACTION_DONE || enter
                ) {
                    submit(text.toString())
                    true
                } else {
                    false
                }
            }
            setOnFocusChangeListener { _, has ->
                searchBox.background = if (has) {
                    Ui.roundRect(ctx, Palette.DUSK, 32f, Palette.LAMP, Dimens.FOCUS_STROKE)
                } else {
                    Ui.roundRect(ctx, Palette.DUSK, 32f, Palette.HAIRLINE, 1.5f)
                }
                if (has) Sfx.focus()
            }
            setOnClickListener { showKeyboard() }
        }
        searchBox.addView(searchField, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL).apply {
            marginStart = dpi(64f)
            marginEnd = dpi(20f)
        })
        searchStrip.place(searchBox, 0f, 4f, MATCH, 64f)
        searchStrip.place(Ui.text(ctx, TypeScale.CAPTION, Palette.MUTED, Fonts.bodyBold, "OR TRY ONE").apply { letterSpacing = 0.12f }, 4f, 84f)
        searchStrip.place(
            Ui.text(ctx, TypeScale.CAPTION, Palette.MUTED, Fonts.body, "Remote typing is slow: phones on the couch can type here too.").apply { maxLines = 1 },
            4f, 160f, MATCH,
        )
    }

    private fun makeSuggestion(text: String): TextView {
        val chip = Ui.text(context, TypeScale.BODY, Palette.CREAM, Fonts.bodyBold, text).apply {
            id = View.generateViewId()
            gravity = Gravity.CENTER
            setPadding(dpi(18f), 0, dpi(18f), 0)
            background = Ui.roundRect(context, Palette.withAlpha(Palette.DUSK, 0.9f), 20f, Palette.HAIRLINE, 1f)
        }
        Ui.makeFocusable(chip, 20f, 1.05f)
        chip.setOnClickListener {
            Sfx.select()
            submit(text)
        }
        return chip
    }

    private fun layoutSuggestions() {
        // Chips flow left to right after the "TRY ONE" label.
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        suggestions.forEachIndexed { i, chip ->
            row.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpi(40f)).apply { if (i > 0) marginStart = dpi(12f) })
            chip.nextFocusUpId = searchField.id
            chip.nextFocusLeftId = if (i == 0) chip.id else suggestions[i - 1].id
            chip.nextFocusRightId = if (i == suggestions.lastIndex) chip.id else suggestions[i + 1].id
            chip.nextFocusDownId = favTiles.getOrNull(i * 2)?.id ?: chip.id
        }
        searchStrip.place(row, 0f, 110f)
        searchField.nextFocusDownId = suggestions.first().id
    }

    private fun submit(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) {
            showKeyboard()
            return
        }
        hideKeyboard()
        searchField.setText("")
        closeSearch()
        listener.onSearchQuery(q)
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        searchField.post { runCatching { imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT) } }
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        runCatching { imm.hideSoftInputFromWindow(searchField.windowToken, 0) }
    }

    private fun crossFade(out: View, inn: View) {
        out.animate().cancel()
        inn.animate().cancel()
        out.visibility = View.GONE
        out.alpha = 1f
        inn.alpha = 0f
        inn.translationY = dp(8f)
        inn.visibility = View.VISIBLE
        inn.animate().alpha(1f).translationY(0f).setDuration(200).setInterpolator(DecelerateInterpolator()).start()
        // Favorites point back up at whichever row is showing.
        favTiles.forEachIndexed { i, f ->
            f.nextFocusUpId = if (inn === searchStrip) suggestions.getOrNull(i / 2)?.id ?: searchField.id else tiles[FAV_TO_TILE.getOrElse(i) { 0 }].id
        }
    }

    private companion object {
        const val STAGE_W = 960f
        const val STAGE_H = 540f
        /** Right edge of the content column (the join panel starts 20dp later). */
        const val COLUMN_RIGHT = 960f - Dimens.SAFE_H - JoinPanel.WIDTH - 18f
        /** Favorites may run a little wider: big Lumo lives right of x=692dp, below the join panel. */
        const val FAV_RIGHT = 672f
        const val TILE_GAP = 14f
        const val TILE_H = 180f
        const val HERO_Y = 160f
        const val FAV_LABEL_Y = 360f
        const val FAV_Y = 384f
        const val FAV_H = 88f
        const val FAV_GAP = 10f
        const val FOOTER_Y = 488f

        val TILE_TO_FAV = intArrayOf(0, 2, 3, 5)
        val FAV_TO_TILE = intArrayOf(0, 1, 1, 2, 3, 3)
        val SUGGESTIONS = listOf("How fast is a cheetah?", "Saturn's moons", "Anglerfish")
    }
}
