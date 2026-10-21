package tv.wander.testing

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

/**
 * Renders real views to PNG under Robolectric native graphics, so TV screens can be checked without a
 * device. Output: app/build/snaps/<name>.png at 1920x1080 (960x540dp @ xhdpi, like a Fire TV).
 *
 *   val activity = Snap.activity()
 *   val view = HomeView(activity, listener); Snap.show(activity, view)
 *   Snap.settle(800); Snap.save(activity, "home")
 */
object Snap {
    fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get().also {
        tv.wander.ui.Fonts.init(it)
    }

    fun show(activity: Activity, view: View) {
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        settle(50)
    }

    /** Advances the main looper (animations, posted runnables) by [ms]. */
    fun settle(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    fun save(activity: Activity, name: String, width: Int = 1920, height: Int = 1080): File {
        val root = activity.window.decorView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, width, height)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val dir = File(System.getProperty("wander.snapDir") ?: "build/snaps").apply { mkdirs() }
        val out = File(dir, "$name.png")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }
}
