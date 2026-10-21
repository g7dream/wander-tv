package tv.wander.testing

import android.view.Gravity
import android.widget.FrameLayout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tv.wander.ui.Fonts
import tv.wander.ui.LampBackdrop
import tv.wander.ui.Palette
import tv.wander.ui.TypeScale
import tv.wander.ui.Ui

/** Proves native-graphics rendering works on this machine: backdrop + both fonts. */
@RunWith(RobolectricTestRunner::class)
class SkeletonSnapTest {
    @Test
    fun rendersThemeSample() {
        val activity = Snap.activity()
        val root = FrameLayout(activity).apply { background = LampBackdrop() }
        root.addView(
            Ui.text(activity, TypeScale.DISPLAY, Palette.CREAM, Fonts.display, "Wander, together."),
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
        )
        root.addView(
            Ui.text(activity, TypeScale.BODY, Palette.LAMP, Fonts.bodyBold, "Atkinson Hyperlegible on a lamp-lit night"),
            FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM).apply { bottomMargin = 200 },
        )
        Snap.show(activity, root)
        val file = Snap.save(activity, "skeleton_theme")
        assertTrue(file.length() > 10_000)
    }
}
