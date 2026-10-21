package tv.wander.browse

import java.net.URLEncoder

/** Turns what someone typed into a URL: real URLs pass through, anything else becomes a safe search. */
object SearchUrls {
    /** DuckDuckGo with strict SafeSearch (kp=1) and the dark theme (kae=d): right for a family room at night. */
    private const val SEARCH_BASE = "https://duckduckgo.com/"
    private const val SEARCH_FLAGS = "kp=1&kae=d"
    private const val SEARCH_HOME = "$SEARCH_BASE?$SEARCH_FLAGS"

    private val SCHEME = Regex("^(https?)://(.+)$", RegexOption.IGNORE_CASE)
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private val PORT = Regex("^\\d{1,5}$")
    private val LABEL = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    /**
     * The URL to load for [input]. Explicit http(s) URLs pass through; bare domains get https://
     * (local addresses such as localhost or 192.168.1.20:8080 get http://, since LAN devices rarely
     * speak TLS). Everything else, including other schemes like javascript: or file:, becomes a
     * DuckDuckGo search. Blank input opens the DuckDuckGo home page.
     */
    fun resolve(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return SEARCH_HOME
        if (looksLikeUrl(text)) {
            SCHEME.find(text)?.let { m -> return m.groupValues[1].lowercase() + "://" + m.groupValues[2] }
            val host = hostOf(text)
            return if (isLocalHost(host)) "http://$text" else "https://$text"
        }
        return SEARCH_BASE + "?q=" + URLEncoder.encode(text, "UTF-8") + "&" + SEARCH_FLAGS
    }

    /**
     * True when [input] is meant as an address rather than a search: an explicit http/https URL,
     * "localhost", an IPv4 address (optional port and path), or a single token with a dot whose last
     * label is a real-looking top-level domain ("nasa.gov", "en.wikipedia.org/wiki/Moth").
     */
    fun looksLikeUrl(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        SCHEME.find(text)?.let { m ->
            val rest = m.groupValues[2]
            val host = hostOf(rest)
            return host.isNotEmpty() && (host.startsWith("[") || host.all { it.isLetterOrDigit() || it == '-' || it == '.' })
        }
        if (text.contains("://")) return false // some other scheme (ftp, file...): search it instead
        if (text.contains('@')) return false // an email address, not a page
        val authority = authorityOf(text)
        val host = hostOf(text)
        if (!portIsValid(authority)) return false
        return isLocalHost(host) || isDomain(host)
    }

    /** "user:pw@Host.Example:8080/path?q" -> "host.example" (lowercase, no trailing dot). */
    private fun hostOf(text: String): String {
        val authority = authorityOf(text)
        val host = authority.substringAfterLast('@').let { a ->
            if (a.startsWith("[")) a.substringBefore(']') + "]" else a.substringBefore(':')
        }
        return host.lowercase().trimEnd('.')
    }

    private fun authorityOf(text: String): String =
        text.substringBefore('/').substringBefore('?').substringBefore('#')

    private fun portIsValid(authority: String): Boolean {
        val afterHost = authority.substringAfterLast('@')
        if (!afterHost.contains(':')) return true
        val port = afterHost.substringAfter(':')
        return PORT.matches(port) && port.toInt() in 1..65535
    }

    private fun isLocalHost(host: String): Boolean = host == "localhost" || isIpv4(host)

    private fun isIpv4(host: String): Boolean {
        val m = IPV4.find(host) ?: return false
        return m.groupValues.drop(1).all { it.toInt() in 0..255 }
    }

    private fun isDomain(host: String): Boolean {
        if (host.length > 253) return false
        val labels = host.split('.')
        if (labels.size < 2) return false
        if (labels.any { !LABEL.matches(it) }) return false
        val tld = labels.last()
        return isPlausibleTld(tld)
    }

    private fun isPlausibleTld(tld: String): Boolean {
        if (tld.startsWith("xn--") && tld.length > 5) return true // internationalized TLDs
        if (!tld.all { it in 'a'..'z' }) return false
        return if (tld.length == 2) tld in COUNTRY_TLDS else tld in GENERIC_TLDS
    }

    // Every ISO country-code TLD, so "socket.io" and "bbc.co.uk" work while "node.js" stays a search.
    private val COUNTRY_TLDS: Set<String> = (
        "ac ad ae af ag ai al am ao aq ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs bt bw by bz " +
            "ca cc cd cf cg ch ci ck cl cm cn co cr cu cv cw cx cy cz de dj dk dm do dz ec ee eg er es et eu fi fj " +
            "fk fm fo fr ga gd ge gf gg gh gi gl gm gn gp gq gr gs gt gu gw gy hk hm hn hr ht hu id ie il im in io " +
            "iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la lb lc li lk lr ls lt lu lv ly ma mc md me " +
            "mg mh mk ml mm mn mo mp mq mr ms mt mu mv mw mx my mz na nc ne nf ng ni nl no np nr nu nz om pa pe pf " +
            "pg ph pk pl pm pn pr ps pt pw py qa re ro rs ru rw sa sb sc sd se sg sh si sk sl sm sn so sr ss st su " +
            "sv sx sy sz tc td tf tg th tj tk tl tm tn to tr tt tv tw tz ua ug uk us uy uz va vc ve vg vi vn vu wf " +
            "ws ye yt za zm zw"
        ).split(' ').toSet()

    // The generic TLDs people actually type, plus the classic sponsored ones.
    private val GENERIC_TLDS: Set<String> = (
        "com org net edu gov mil int info biz name pro aero asia cat coop jobs mobi museum post tel travel xxx " +
            "app dev page web site online store shop tech blog news live life world today space website fun " +
            "games game kids family zone club art design studio media digital network cloud email link click " +
            "help guide wiki science academy school education university college courses training " +
            "earth eco green garden farm pet pets dog vet zoo bio health care clinic fit fitness " +
            "film movie movies music video audio radio show theater tv photo photos pics gallery " +
            "food cafe restaurant pizza recipes kitchen cooking wine beer " +
            "city town london paris berlin nyc tokyo amsterdam " +
            "global group team works company agency center services solutions systems technology " +
            "social community chat forum events party wedding love gift toys fan fans " +
            "travel holiday tours camp beach surf ski bike run golf football soccer tennis " +
            "book books library press reviews review report lol xyz top icu vip one ninja rocks cool"
        ).split(' ').filter { it.isNotBlank() }.toSet()
}
