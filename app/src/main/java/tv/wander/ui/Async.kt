package tv.wander.ui

import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/** Tiny threading helper: [io] for network/disk, [main] to get back to the UI thread. */
object Async {
    private val pool = Executors.newFixedThreadPool(4) { r -> Thread(r, "wander-io").apply { isDaemon = true } }
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun io(block: () -> Unit) {
        pool.execute {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.e("Wander", "background task failed", t)
            }
        }
    }

    fun main(block: () -> Unit) {
        handler.post(block)
    }

    fun mainDelayed(delayMs: Long, block: () -> Unit): Runnable {
        val r = Runnable(block)
        handler.postDelayed(r, delayMs)
        return r
    }

    fun cancel(r: Runnable?) {
        if (r != null) handler.removeCallbacks(r)
    }
}
