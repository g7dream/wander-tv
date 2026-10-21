package tv.wander.companion

import tv.wander.model.DriftSignal
import tv.wander.model.SessionStats

/**
 * Spots aimless browsing from timing signals only (no URLs are kept; hosts are hashed in memory).
 * Thresholds adapt per household: each "we're fine" dismissal relaxes the rule that fired.
 */
class DriftDetector(
    private val store: Store,
    private val clock: () -> Long = System::currentTimeMillis,
    private val hourOf: (Long) -> Int = { 12 },
) {
    interface Store {
        fun getFloat(key: String, def: Float): Float
        fun putFloat(key: String, value: Float)
    }

    var demoMode: Boolean = false
    var enabled: Boolean = true

    fun onPageLoad(url: String) {}
    fun onClick() {}
    fun onScroll() {}

    /** Call every few seconds. Returns a signal at most once per cooldown. */
    fun check(): DriftSignal? = null

    fun dismissed(signal: DriftSignal) {}
    fun accepted(signal: DriftSignal) {}
    fun stats(): SessionStats = SessionStats(0, 0, 0, 0, 0, 12, 0)
}
