package tv.wander

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * Runtime settings. The backend URL/key come from gradle.properties (BuildConfig) and can be overridden
 * without rebuilding:
 *   adb shell am start -n tv.wander/.MainActivity --es api_url https://... --es api_key ... --ez demo true
 */
class Config(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("wander", Context.MODE_PRIVATE)

    val apiUrl: String
        get() = prefs.getString(KEY_API_URL, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_URL

    val apiKey: String
        get() = prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_KEY

    /** Demo mode shortens drift thresholds so a rescue can be triggered on camera in about a minute. */
    var demoMode: Boolean
        get() = prefs.getBoolean(KEY_DEMO, false)
        set(value) = prefs.edit().putBoolean(KEY_DEMO, value).apply()

    /** Drift nudges can be switched off from the Lumo panel. */
    var nudgesOn: Boolean
        get() = prefs.getBoolean(KEY_NUDGES, true)
        set(value) = prefs.edit().putBoolean(KEY_NUDGES, value).apply()

    fun applyIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        val edit = prefs.edit()
        extras.getString("api_url")?.let { edit.putString(KEY_API_URL, it.trim().trimEnd('/')) }
        extras.getString("api_key")?.let { edit.putString(KEY_API_KEY, it.trim()) }
        if (extras.containsKey("demo")) edit.putBoolean(KEY_DEMO, extras.getBoolean("demo"))
        edit.apply()
    }

    companion object {
        const val PORT = 8080

        /** Tests serve phones on a free port instead of [PORT] (so they never fight over 8080). Null in the app. */
        @Volatile
        var portOverride: Int? = null

        private const val KEY_API_URL = "api_url"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_DEMO = "demo"
        private const val KEY_NUDGES = "nudges"
    }
}
