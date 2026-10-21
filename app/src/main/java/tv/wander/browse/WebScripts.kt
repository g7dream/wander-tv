package tv.wander.browse

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.json.JSONTokener
import tv.wander.ui.Async
import java.util.Locale

/**
 * The JavaScript side of the browser: loads assets/js once (off the main thread), builds the small
 * calls BrowseLayer sends with evaluateJavascript, and decodes what comes back.
 *
 * Every call goes through [guarded]: if the page lost our helper (a new document we haven't
 * injected yet) it answers [NO_LIB] and BrowseLayer injects and retries.
 */
internal object WebScripts {
    const val BRIDGE_NAME = "WanderBridge"
    const val NO_LIB = "__nolib"
    const val NO_READABILITY = "__noreadability"

    private const val LIB_ASSET = "js/wander-bridge.js"
    private const val READABILITY_ASSET = "js/Readability.js"
    private const val TAG = "WanderBrowse"

    @Volatile var lib: String? = null
        private set

    @Volatile var readability: String? = null
        private set

    private var loading = false
    private val waiters = ArrayList<() -> Unit>() // touched on the main thread only

    val ready: Boolean get() = lib != null

    /** Reads the scripts once on a background thread; [onReady] runs on the main thread afterwards. */
    fun preload(context: Context, onReady: (() -> Unit)? = null) {
        if (lib != null) {
            if (onReady != null) Async.main(onReady)
            return
        }
        if (onReady != null) waiters.add(onReady)
        if (loading) return
        loading = true
        val assets = context.applicationContext.assets
        Async.io {
            val bridge = readAsset(assets, LIB_ASSET)
            val reader = readAsset(assets, READABILITY_ASSET)
            Async.main {
                lib = bridge ?: FALLBACK_LIB
                readability = reader?.let { wrapReadability(it) }
                loading = false
                val pending = ArrayList(waiters)
                waiters.clear()
                for (w in pending) runCatching(w).onFailure { Log.w(TAG, "script waiter failed", it) }
            }
        }
    }

    private fun readAsset(assets: android.content.res.AssetManager, path: String): String? =
        try {
            assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            Log.w(TAG, "missing asset $path", t)
            null
        }

    /** Runs Readability inside a function so it adds no page globals and ignores any page `module`. */
    fun wrapReadability(source: String): String =
        "(function(){var module;\n$source\n;window.__wanderReadability=Readability;})();void 0;"

    fun guarded(expr: String): String =
        "(function(){var W=window.__wander;if(!W)return '$NO_LIB';return $expr;})()"

    fun scrollAt(fx: Float, fy: Float, fdx: Float, fdy: Float, smooth: Boolean): String =
        guarded("W.scrollAt(${num(fx)},${num(fy)},${num(fdx)},${num(fdy)},$smooth)")

    fun page(fx: Float, fy: Float, dir: Int): String =
        guarded("W.page(${num(fx)},${num(fy)},${if (dir < 0) -1 else 1})")

    fun hit(fx: Float, fy: Float): String = guarded("W.hit(${num(fx)},${num(fy)})")

    fun type(text: String, submit: Boolean): String = guarded("W.type(${quote(text)},$submit)")

    /** "toggle", "back" or "fwd" for the fullscreen video. */
    fun media(action: String): String = guarded("W.media(${quote(action)})")

    fun extract(maxChars: Int): String =
        "(function(){var W=window.__wander;if(!W)return '$NO_LIB';" +
            "if(typeof window.__wanderReadability!=='function'&&!window.__wanderTriedReadability)return '$NO_READABILITY';" +
            "return W.extract($maxChars);})()"

    const val MARK_READABILITY_TRIED = "window.__wanderTriedReadability=true;void 0;"

    /** A finite number in plain JS syntax ("0.5", never "NaN" or "1,5"). */
    fun num(f: Float): String {
        if (f.isNaN() || f.isInfinite()) return "0"
        return String.format(Locale.US, "%.5f", f).trimEnd('0').trimEnd('.').ifEmpty { "0" }.let { if (it == "-0") "0" else it }
    }

    /**
     * [s] as a JS string literal. Escapes quotes, backslashes, control characters and the two line
     * separators (U+2028/2029) that would break a literal on older engines.
     */
    fun quote(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                ' ' -> b.append("\\u2028")
                ' ' -> b.append("\\u2029")
                '<' -> b.append("\\u003c")
                else -> if (c < ' ') b.append(String.format(Locale.US, "\\u%04x", c.code)) else b.append(c)
            }
        }
        b.append('"')
        return b.toString()
    }

    /**
     * evaluateJavascript hands back the result JSON-encoded: a string comes as a JSON string literal,
     * undefined or a thrown error as "null". Returns the decoded value (String, Number, Boolean,
     * JSONObject...) or null.
     */
    fun decode(raw: String?): Any? {
        if (raw.isNullOrBlank()) return null
        return try {
            val v = JSONTokener(raw).nextValue()
            if (v == JSONObject.NULL) null else v
        } catch (t: Throwable) {
            null
        }
    }

    fun decodeString(raw: String?): String? = decode(raw) as? String

    fun decodeInt(raw: String?): Int = when (val v = decode(raw)) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: 0
        else -> 0
    }

    /** The extract result: a JSON string literal holding a JSON object. Null when it isn't one. */
    fun decodeObject(raw: String?): JSONObject? {
        val inner = decode(raw)
        return when (inner) {
            is JSONObject -> inner
            is String -> if (inner.startsWith("{")) runCatching { JSONObject(inner) }.getOrNull() else null
            else -> null
        }
    }

    /** If assets/js is missing (a broken build), field focus still works through this tiny stand-in. */
    private const val FALLBACK_LIB =
        "(function(){if(window.__wander)return;var B=window.$BRIDGE_NAME;var W={v:0,check:function(){}};" +
            "function ed(e){if(!e)return false;if(e.isContentEditable)return true;var t=e.tagName;" +
            "return t==='TEXTAREA'||(t==='INPUT'&&/^(text|search|email|url|tel|number|password)$/.test(e.type||'text'));}" +
            "function r(){var e=document.activeElement;var on=ed(e);" +
            "try{B&&B.onField(on,on?(e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.name||'text box'):null);}catch(x){}}" +
            "document.addEventListener('focusin',function(){setTimeout(r,0)},true);" +
            "document.addEventListener('focusout',function(){setTimeout(r,0)},true);" +
            "window.__wander=W;})();"
}
