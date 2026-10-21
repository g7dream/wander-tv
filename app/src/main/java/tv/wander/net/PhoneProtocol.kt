package tv.wander.net

import org.json.JSONArray
import org.json.JSONObject
import tv.wander.model.Player
import tv.wander.model.QuestBoard
import tv.wander.model.TvState

/**
 * The JSON side of SPEC section 5, kept free of sockets and threads so it can be unit tested.
 * Everything here is defensive: phones are untrusted input and must never be able to crash the TV.
 */
internal object PhoneProtocol {
    const val MAX_PLAYERS = 4
    const val MAX_NAME = 16
    const val MAX_TEXT = 500
    const val MAX_MOVE = 400.0
    const val MAX_SCROLL = 2400.0

    /** Same order as Palette.PLAYER_COLOR_NAMES (a copy, so this file stays free of Android graphics). */
    val COLOR_NAMES = arrayOf("Sun", "Berry", "Mint", "Sky")

    val KEYS = setOf("back", "home", "menu", "up", "down", "left", "right", "enter", "playpause")
    val TOAST_KINDS = setOf("info", "good", "bad")

    const val PONG = "{\"t\":\"pong\"}"

    private const val MAX_MESSAGE_CHARS = 16 * 1024
    private const val MAX_DEPTH = 4
    private const val MAX_PID = 32
    private const val MAX_QUEST_ID = 64
    private const val MAX_TITLE = 300
    private const val MAX_URL = 2048

    /** What a phone said, after validation. */
    sealed class Incoming {
        data class Hello(val name: String?, val color: Int?, val pid: String?) : Incoming()
        object BadHello : Incoming()
        object Ping : Incoming()
        data class Command(val command: PhoneCommand) : Incoming()
    }

    // ------------------------------------------------------------------ asset mapping

    /**
     * Maps a (sanitized) URL path to an asset-relative path: "/" -> phone/index.html,
     * "/fonts/x" -> fonts/x, "/x" -> phone/x. Null when there is nothing sensible to serve.
     */
    fun assetPathFor(urlPath: String): String? {
        if (!urlPath.startsWith("/") || urlPath.contains("..") || urlPath.contains('\\')) return null
        if (urlPath == "/" || urlPath == "/index.html") return "phone/index.html"
        if (urlPath.startsWith("/fonts/")) {
            val rest = urlPath.removePrefix("/fonts/")
            return if (rest.isEmpty()) null else "fonts/$rest"
        }
        if (urlPath == "/fonts") return null
        return "phone/" + urlPath.removePrefix("/")
    }

    // ------------------------------------------------------------------ phone -> TV

    /** Parses one WebSocket message. Null for anything malformed, unknown or pointless. */
    fun parse(text: String): Incoming? {
        if (text.length > MAX_MESSAGE_CHARS || !shallow(text)) return null
        val o = try {
            JSONObject(text)
        } catch (t: Throwable) {
            return null
        }
        val type = o.opt("t") as? String ?: return null
        if (type == "hello") return parseHello(o)
        if (type == "ping") return Incoming.Ping
        val command = parseCommand(type, o) ?: return null
        return Incoming.Command(command)
    }

    private fun parseHello(o: JSONObject): Incoming {
        val rawName = o.opt("name")
        val rawColor = o.opt("color")
        val rawPid = o.opt("pid")
        val name = when (rawName) {
            null, JSONObject.NULL -> null
            is String -> rawName
            is Number -> rawName.toString()
            else -> return Incoming.BadHello
        }
        val color = when (rawColor) {
            null, JSONObject.NULL -> null
            is Number, is String -> number(rawColor)?.let { if (it >= 0 && it < MAX_PLAYERS) it.toInt() else null }
            else -> return Incoming.BadHello
        }
        val pid = when (rawPid) {
            null, JSONObject.NULL -> null
            is String -> rawPid.trim().takeIf { it.isNotEmpty() && it.length <= MAX_PID }
            else -> return Incoming.BadHello
        }
        return Incoming.Hello(name, color, pid)
    }

    fun parseCommand(type: String, o: JSONObject): PhoneCommand? = when (type) {
        "move" -> {
            val dx = clamp(o.opt("dx"), MAX_MOVE)
            val dy = clamp(o.opt("dy"), MAX_MOVE)
            if (dx == 0f && dy == 0f) null else PhoneCommand.Move(dx, dy)
        }
        "scroll" -> {
            val dx = clamp(o.opt("dx"), MAX_SCROLL)
            val dy = clamp(o.opt("dy"), MAX_SCROLL)
            if (dx == 0f && dy == 0f) null else PhoneCommand.Scroll(dx, dy)
        }
        "tap" -> PhoneCommand.Tap
        "go" -> string(o.opt("text"))?.let { cleanText(it, singleLine = true).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { PhoneCommand.Go(it) }
        "type" -> {
            val text = string(o.opt("text"))?.let { cleanText(it, singleLine = true) } ?: ""
            val submit = bool(o.opt("submit"))
            if (text.isEmpty() && !submit) null else PhoneCommand.Type(text, submit)
        }
        "key" -> string(o.opt("key"))?.trim()?.lowercase()?.takeIf { it in KEYS }?.let { PhoneCommand.Key(it) }
        "cards" -> PhoneCommand.MakeCards
        "quest_submit" -> PhoneCommand.QuestSubmit
        "quest_start" -> string(o.opt("id"))?.trim()
            ?.takeIf { id -> id.isNotEmpty() && id.length <= MAX_QUEST_ID && id.all { it.isLetterOrDigit() || it == '-' || it == '_' } }
            ?.let { PhoneCommand.QuestStart(it) }
        "read" -> PhoneCommand.ReadAloud
        "rescue" -> number(o.opt("choice"))?.toInt()?.takeIf { it in -1..9 }?.let { PhoneCommand.Rescue(it) }
        "zoom" -> number(o.opt("delta"))?.let { d ->
            when {
                d > 0 -> PhoneCommand.Zoom(1)
                d < 0 -> PhoneCommand.Zoom(-1)
                else -> null
            }
        }
        else -> null
    }

    /** Trims, strips control characters and cuts to [MAX_NAME] chars without splitting an emoji. */
    fun cleanName(raw: String?): String {
        if (raw == null) return ""
        val flat = cleanText(raw, singleLine = true).trim().replace(Regex("\\s+"), " ")
        return clip(flat, MAX_NAME).trim()
    }

    /** Drops control characters (newlines become spaces when [singleLine]) and caps at [MAX_TEXT]. */
    fun cleanText(raw: String, singleLine: Boolean): String {
        val sb = StringBuilder(minOf(raw.length, MAX_TEXT))
        for (c in raw) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> sb.append(if (singleLine) ' ' else c)
                c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f' -> Unit
                c == ' ' || c == ' ' -> sb.append(' ')
                else -> sb.append(c)
            }
            if (sb.length >= MAX_TEXT + 1) break
        }
        return clip(sb.toString(), MAX_TEXT)
    }

    /** Cuts to at most [max] UTF-16 chars, backing off one char rather than splitting a surrogate pair. */
    fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        val end = if (Character.isHighSurrogate(s[max - 1])) max - 1 else max
        return s.substring(0, end)
    }

    private fun clamp(v: Any?, limit: Double): Float {
        val d = number(v) ?: return 0f
        return d.coerceIn(-limit, limit).toFloat()
    }

    /** A finite number from a JSON number or numeric string, else null (NaN and infinity included). */
    private fun number(v: Any?): Double? {
        val d = when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return if (d.isNaN() || d.isInfinite()) null else d
    }

    private fun string(v: Any?): String? = v as? String

    private fun bool(v: Any?): Boolean = when (v) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        is Number -> v.toDouble() != 0.0
        else -> false
    }

    /** Phone messages are flat; refuse deep nesting before handing text to a recursive parser. */
    private fun shallow(text: String): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (c in text) {
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> if (++depth > MAX_DEPTH) return false
                '}', ']' -> depth--
            }
        }
        return true
    }

    // ------------------------------------------------------------------ TV -> phone

    fun welcome(p: Player): String = JSONObject()
        .put("t", "welcome")
        .put("pid", p.id)
        .put("name", p.name)
        .put("color", p.colorIndex)
        .toString()

    fun reject(reason: String): String = JSONObject().put("t", "reject").put("reason", reason).toString()

    fun players(list: List<Player>): String {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("pid", p.id)
                    .put("name", p.name)
                    .put("color", p.colorIndex)
                    .put("score", p.score),
            )
        }
        return JSONObject().put("t", "players").put("players", arr).toString()
    }

    fun toast(text: String, kind: String): String = JSONObject()
        .put("t", "toast")
        .put("text", clip(text, MAX_TITLE))
        .put("kind", if (kind in TOAST_KINDS) kind else "info")
        .toString()

    fun celebrate(text: String, color: Int): String = JSONObject()
        .put("t", "celebrate")
        .put("text", clip(text, MAX_TITLE))
        .put("color", color)
        .toString()

    /** Serializes [s] exactly as SPEC section 5 describes the "state" message. */
    fun state(s: TvState): String {
        val o = JSONObject()
        o.put("t", "state")
        o.put("mode", s.mode.name.lowercase())
        o.put("title", clip(s.title, MAX_TITLE))
        o.put("url", clip(s.url, MAX_URL))
        o.put("checking", s.checking)
        o.put("ai", s.aiOnline)
        o.put(
            "field",
            JSONObject()
                .put("focused", s.textFieldFocused)
                .put("label", s.fieldLabel ?: ""),
        )
        o.put(
            "cards",
            if (s.cardCount > 0) {
                JSONObject()
                    .put("index", s.cardIndex.coerceIn(0, s.cardCount - 1))
                    .put("count", s.cardCount)
                    .put("title", s.cardTitle ?: "")
            } else {
                JSONObject.NULL
            },
        )
        o.put("quest", s.quest?.let { quest(it) } ?: JSONObject.NULL)
        val rescue = s.rescue
        o.put(
            "rescue",
            if (rescue != null) {
                val options = JSONArray()
                for (opt in s.rescueOptions) options.put(JSONObject().put("label", opt.label).put("detail", opt.detail))
                JSONObject()
                    .put("headline", rescue.headline)
                    .put("detail", rescue.detail)
                    .put("options", options)
            } else {
                JSONObject.NULL
            },
        )
        val quests = JSONArray()
        for (q in s.quests) {
            quests.put(
                JSONObject()
                    .put("id", q.id)
                    .put("title", q.title)
                    .put("emoji", q.emoji)
                    .put("prompt", q.prompt),
            )
        }
        o.put("quests", quests)
        return o.toString()
    }

    private fun quest(board: QuestBoard): JSONObject {
        val q = board.quest
        val found = JSONArray()
        for (f in board.found) {
            found.put(
                JSONObject()
                    .put("item", f.item)
                    .put("pid", f.playerId)
                    .put("evidence", f.evidence ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("id", q.id)
            .put("title", q.title)
            .put("prompt", q.prompt)
            .put("emoji", q.emoji)
            .put("goal", q.goal)
            .put("found", found)
            .put("complete", board.complete)
    }
}
