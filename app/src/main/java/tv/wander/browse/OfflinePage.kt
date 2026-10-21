package tv.wander.browse

/**
 * Wander's own "this page didn't load" page, shown in the WebView instead of Chromium's grey one.
 * Same lamp-lit room as the rest of the app: ink, a warm glow, Lumo dozing by the lamp, cream text in
 * our fonts (loaded from file:///android_asset/fonts, so the page is loaded with that base URL).
 * Pure string building so it is unit tested.
 */
internal object OfflinePage {
    const val BASE_URL = "file:///android_asset/"

    enum class Kind { NO_INTERNET, NOT_FOUND, TIMEOUT, SECURITY, CRASHED, OTHER }

    // WebViewClient.ERROR_* values, mirrored so this stays JVM-testable.
    private const val ERROR_HOST_LOOKUP = -2
    private const val ERROR_CONNECT = -6
    private const val ERROR_TIMEOUT = -8
    private const val ERROR_BAD_URL = -12
    private const val ERROR_FAILED_SSL_HANDSHAKE = -11
    private const val ERROR_PROXY_AUTHENTICATION = -5
    private const val ERROR_UNSUPPORTED_SCHEME = -10

    fun kindFor(errorCode: Int, online: Boolean): Kind = when {
        !online -> Kind.NO_INTERNET
        errorCode == ERROR_TIMEOUT -> Kind.TIMEOUT
        errorCode == ERROR_FAILED_SSL_HANDSHAKE -> Kind.SECURITY
        errorCode == ERROR_HOST_LOOKUP || errorCode == ERROR_CONNECT || errorCode == ERROR_BAD_URL ||
            errorCode == ERROR_UNSUPPORTED_SCHEME || errorCode == ERROR_PROXY_AUTHENTICATION -> Kind.NOT_FOUND
        else -> Kind.OTHER
    }

    /** What the title strip says while this page is up. */
    fun title(kind: Kind): String = when (kind) {
        Kind.NO_INTERNET -> "The internet stepped out"
        Kind.NOT_FOUND -> "We couldn't reach that page"
        Kind.TIMEOUT -> "That page is taking forever"
        Kind.SECURITY -> "That page's lock looks broken"
        Kind.CRASHED -> "That page wore the TV out"
        Kind.OTHER -> "That page didn't load"
    }

    private fun body(kind: Kind, host: String): String {
        val h = "<b>${escape(host)}</b>"
        return when (kind) {
            Kind.NO_INTERNET -> "The TV can't reach the internet right now. Check the Wi-Fi, then try again."
            Kind.NOT_FOUND -> "$h isn't answering. It might be down for a nap, or the address has a typo."
            Kind.TIMEOUT -> "$h took too long to answer. Even moths get bored waiting."
            Kind.SECURITY -> "$h has a security certificate that doesn't check out, so Wander kept it closed. Better safe on the couch."
            Kind.CRASHED -> "$h kept tripping up the TV's web engine, so we stopped trying. A lighter page will do better."
            Kind.OTHER -> "Something went sideways on the way to $h."
        }
    }

    /**
     * The full HTML for [kind] at [url]. [detail] is the WebView's error text, shown small for the
     * curious. Retrying is offered except for security problems.
     */
    fun html(kind: Kind, url: String, detail: String?): String {
        val host = PageTexts.hostLabel(url).ifEmpty { "That site" }
        val safeUrl = url.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        val retry = if (kind != Kind.SECURITY && safeUrl != null) {
            "<a class=\"btn\" href=\"${escape(safeUrl)}\">Try again</a>"
        } else {
            ""
        }
        val small = detail?.trim()?.takeIf { it.isNotEmpty() }?.let { "<div class=\"why\">${escape(it.take(120))}</div>" } ?: ""
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(title(kind))}</title>
<style>
@font-face{font-family:"Young Serif";src:url("fonts/YoungSerif-Regular.ttf")}
@font-face{font-family:"Atkinson";src:url("fonts/AtkinsonHyperlegible-Regular.ttf")}
@font-face{font-family:"Atkinson";font-weight:700;src:url("fonts/AtkinsonHyperlegible-Bold.ttf")}
html,body{margin:0;height:100%;background:#120E1C;color:#F7EFE2;font-family:"Atkinson",sans-serif;overflow:hidden}
body{background:
 radial-gradient(ellipse 70% 80% at 12% 4%,rgba(255,181,71,.17),rgba(255,181,71,0) 70%),
 radial-gradient(ellipse 55% 60% at 96% 104%,rgba(255,93,143,.10),rgba(255,93,143,0) 70%),#120E1C}
.wrap{position:absolute;left:5%;right:5%;top:50%;transform:translateY(-50%);display:flex;align-items:center;gap:5vw}
.lumo{flex:0 0 auto;width:24vw;max-width:340px;overflow:visible}
.text{flex:1 1 auto;min-width:0}
.kicker{font-weight:700;letter-spacing:.14em;text-transform:uppercase;color:#FFB547;font-size:clamp(14px,1.6vw,22px);margin:0 0 1.2vw}
h1{font-family:"Young Serif",serif;font-weight:400;font-size:clamp(28px,4vw,58px);line-height:1.1;margin:0 0 1.6vw}
p{font-size:clamp(18px,2.1vw,30px);line-height:1.45;color:#E9E0D2;margin:0 0 2.4vw;max-width:34em}
p b{color:#F7EFE2}
.row{display:flex;align-items:center;gap:1.6vw;flex-wrap:wrap}
.btn{display:inline-block;background:#FFB547;color:#120E1C;font-weight:700;text-decoration:none;
 font-size:clamp(18px,2vw,28px);padding:.7em 1.5em;border-radius:999px;box-shadow:0 0 0 6px rgba(255,181,71,.18)}
.hint{color:#B9AECC;font-size:clamp(16px,1.7vw,24px)}
.url{display:inline-block;margin-top:2.2vw;color:#B9AECC;font-size:clamp(14px,1.5vw,20px);border:1px solid #3A2F57;
 border-radius:999px;padding:.35em 1em;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;box-sizing:border-box}
.why{margin-top:1vw;color:#6F6487;font-size:clamp(13px,1.3vw,18px)}
.mote{position:absolute;border-radius:50%;background:#F7EFE2}
</style></head><body>
<div class="mote" style="left:9%;top:14%;width:5px;height:5px;opacity:.28"></div>
<div class="mote" style="left:18%;top:31%;width:3px;height:3px;opacity:.22"></div>
<div class="mote" style="left:31%;top:9%;width:4px;height:4px;opacity:.18"></div>
<div class="mote" style="left:6%;top:63%;width:3px;height:3px;opacity:.14"></div>
<div class="mote" style="left:41%;top:22%;width:2px;height:2px;opacity:.2"></div>
<div class="mote" style="left:24%;top:80%;width:4px;height:4px;opacity:.1"></div>
<div class="wrap">
<svg class="lumo" viewBox="0 0 200 200" aria-hidden="true">
 <defs>
  <radialGradient id="lamp" cx="50%" cy="38%" r="55%">
   <stop offset="0" stop-color="#FFB547" stop-opacity=".55"/><stop offset=".55" stop-color="#FFB547" stop-opacity=".12"/>
   <stop offset="1" stop-color="#FFB547" stop-opacity="0"/></radialGradient>
  <linearGradient id="wing" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F3E6CF"/><stop offset="1" stop-color="#D9C3A2"/></linearGradient>
 </defs>
 <circle cx="100" cy="96" r="104" fill="url(#lamp)"/>
 <path d="M100 98 C78 58 34 52 26 74 C20 92 52 112 98 110 Z" fill="url(#wing)"/>
 <path d="M100 98 C122 58 166 52 174 74 C180 92 148 112 102 110 Z" fill="url(#wing)"/>
 <path d="M98 112 C70 112 52 134 62 148 C72 160 92 140 99 120 Z" fill="#CDB591"/>
 <path d="M102 112 C130 112 148 134 138 148 C128 160 108 140 101 120 Z" fill="#CDB591"/>
 <circle cx="56" cy="80" r="7" fill="#FFB547" opacity=".55"/><circle cx="144" cy="80" r="7" fill="#FFB547" opacity=".55"/>
 <ellipse cx="100" cy="120" rx="13" ry="30" fill="#8C739F"/>
 <circle cx="100" cy="86" r="17" fill="#A48BB6"/>
 ${eyes(kind)}
 <path d="M93 72 Q84 50 70 46 M107 72 Q116 50 130 46" stroke="#A48BB6" stroke-width="3.2" fill="none" stroke-linecap="round"/>
 <circle cx="69" cy="45" r="4" fill="#A48BB6"/><circle cx="131" cy="45" r="4" fill="#A48BB6"/>
 ${snores(kind)}
</svg>
<div class="text">
<div class="kicker">Lumo here</div>
<h1>${escape(title(kind))}</h1>
<p>${body(kind, host)}</p>
<div class="row">$retry<span class="hint">Press Back to go where you were.</span></div>
${if (safeUrl != null) "<div class=\"url\">${escape(safeUrl)}</div>" else ""}
$small
</div></div>
</body></html>"""
    }

    /** Lumo dozes when the internet does; for a broken lock or an odd error it's awake and watching. */
    private fun sleepy(kind: Kind) = kind == Kind.NO_INTERNET || kind == Kind.NOT_FOUND || kind == Kind.TIMEOUT || kind == Kind.CRASHED

    private fun eyes(kind: Kind): String = if (sleepy(kind)) {
        "<path d=\"M91 84 q4 4 8 0 M101 84 q4 4 8 0\" stroke=\"#120E1C\" stroke-width=\"2.6\" fill=\"none\" stroke-linecap=\"round\"/>"
    } else {
        "<circle cx=\"94\" cy=\"84\" r=\"3.2\" fill=\"#120E1C\"/><circle cx=\"106\" cy=\"84\" r=\"3.2\" fill=\"#120E1C\"/>" +
            "<circle cx=\"95\" cy=\"83\" r=\"1\" fill=\"#F7EFE2\"/><circle cx=\"107\" cy=\"83\" r=\"1\" fill=\"#F7EFE2\"/>"
    }

    private fun snores(kind: Kind): String = if (sleepy(kind)) {
        "<text x=\"150\" y=\"40\" font-family=\"Young Serif,serif\" font-size=\"20\" fill=\"#F7EFE2\" opacity=\".7\">z</text>" +
            "<text x=\"164\" y=\"24\" font-family=\"Young Serif,serif\" font-size=\"14\" fill=\"#F7EFE2\" opacity=\".45\">z</text>"
    } else {
        ""
    }

    fun escape(s: String): String {
        val b = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> b.append("&amp;")
                '<' -> b.append("&lt;")
                '>' -> b.append("&gt;")
                '"' -> b.append("&quot;")
                '\'' -> b.append("&#39;")
                else -> b.append(c)
            }
        }
        return b.toString()
    }
}
