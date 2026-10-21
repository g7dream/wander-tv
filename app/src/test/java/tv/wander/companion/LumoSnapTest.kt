package tv.wander.companion

import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.model.Mood
import tv.wander.testing.Snap
import tv.wander.ui.Fonts
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui
import tv.wander.ui.dpi

/** Renders Lumo in every mood so the art can be inspected at TV scale. */
@RunWith(RobolectricTestRunner::class)
class LumoSnapTest {
    @Test
    fun allMoodsSideBySide() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val labels = listOf("Curious", "Happy", "Sleepy", "Concerned", "Thinking")
        val moods = listOf(Mood.CURIOUS, Mood.HAPPY, Mood.SLEEPY, Mood.CONCERNED, Mood.CURIOUS)
        val views = ArrayList<LumoView>()
        for (i in moods.indices) {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val lumo = LumoView(a).apply { mood = moods[i] }
            if (i == 4) lumo.setThinking(true)
            views += lumo
            col.addView(lumo, LinearLayout.LayoutParams(a.dpi(176f), a.dpi(176f)))
            col.addView(Ui.text(a, TypeScale.BODY, Palette.MUTED, Fonts.bodyBold, labels[i]))
            row.addView(col, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        Snap.show(a, root)
        Snap.settle(700)
        assertTrue(Snap.save(a, "lumo_moods").length() > 20_000)
    }

    @Test
    fun bigCuriousForDetail() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val one = LumoView(a).apply { mood = Mood.CURIOUS }
        val two = LumoView(a).apply { mood = Mood.SLEEPY }
        row.addView(one, LinearLayout.LayoutParams(a.dpi(440f), a.dpi(440f)))
        row.addView(two, LinearLayout.LayoutParams(a.dpi(440f), a.dpi(440f)))
        root.addView(row, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        Snap.show(a, root)
        Snap.settle(900)
        assertTrue(Snap.save(a, "lumo_big").length() > 20_000)
    }

    @Test
    fun celebrationBurst() {
        val a = Snap.activity()
        val root = FrameLayout(a).apply { background = LampBackdrop() }
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val early = LumoView(a)
        val mid = LumoView(a).apply { mood = Mood.CONCERNED }
        row.addView(early, LinearLayout.LayoutParams(a.dpi(400f), a.dpi(400f)))
        row.addView(mid, LinearLayout.LayoutParams(a.dpi(400f), a.dpi(400f)))
        root.addView(row, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        Snap.show(a, root)
        Snap.settle(400)
        mid.celebrate(Palette.MINT)
        Snap.settle(220)
        early.celebrate(Palette.BERRY)
        Snap.settle(140)
        assertTrue(Snap.save(a, "lumo_celebrate").length() > 20_000)
    }
}
