package ch.tunio.app.engine

import android.content.Context
import ch.tunio.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One sung word: start and end (seconds) and its text (with the space after it). */
data class SungWord(val start: Double, val end: Double, val text: String)
data class WordLine(val start: Double, val end: Double, val words: List<SungWord>)
data class WordLyrics(val lines: List<WordLine>, val source: String?, val estimated: Boolean)

/**
 * Word-by-word timing, looked up only when the lyrics are switched to "Word" (same sources as the desktop app):
 *  1. Musixmatch "richsync" through the desktop app's anonymous token,
 *  2. NetEase Cloud Music "yrc", which also covers most Western songs,
 *  3. estimated: LRCLIB's line times shared among each line's words by their length.
 */
object WordSync {
    private const val MXM = "https://apic-desktop.musixmatch.com/ws/1.1/"
    private const val MXM_APP = "web-desktop-app-v1.0"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val cache = ConcurrentHashMap<String, WordLyrics>()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("wordsync", 0)
    }

    suspend fun get(t: Track, lines: LyricsResult?): WordLyrics = withContext(Dispatchers.IO) {
        cache[t.id]?.let { return@withContext it }
        val res = runCatching { musixmatch(t) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { WordLyrics(it, "Musixmatch", false) }
            ?: runCatching { netease(t) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { WordLyrics(it, "NetEase", false) }
            ?: lines?.synced?.takeIf { it.isNotEmpty() }?.let { WordLyrics(estimate(it, t.durationSec.toDouble()), "LRCLIB (word timing estimated)", true) }
        if (res != null) cache[t.id] = res
        res ?: WordLyrics(emptyList(), null, true)
    }

    // ---------------------------------------------------------------- 1. Musixmatch richsync

    private var token: String?
        get() {
            val p = prefs ?: return memToken
            if (System.currentTimeMillis() - p.getLong("at", 0) > 6 * 3600_000L) return null
            return p.getString("token", null)
        }
        set(v) {
            memToken = v
            prefs?.edit()?.putString("token", v)?.putLong("at", System.currentTimeMillis())?.apply()
        }
    private var memToken: String? = null

    private fun call(method: String, params: Map<String, String>): JsonObject? {
        if (token == null) {
            val r = http("${MXM}token.get?app_id=$MXM_APP&user_language=en&t=${System.currentTimeMillis()}", mxmHeaders) as? JsonObject
            val tok = r.path("message", "body", "user_token")?.str()
            if (tok.isNullOrBlank() || "UpgradeOnly" in tok) return null
            token = tok
        }
        val tok = token ?: return null
        val q = (params + mapOf("app_id" to MXM_APP, "usertoken" to tok, "t" to System.currentTimeMillis().toString()))
            .entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val msg = (http("$MXM$method?$q", mxmHeaders) as? JsonObject).path("message") as? JsonObject
        if (msg.path("header", "status_code")?.num() == 401.0) token = null
        return msg
    }

    private val mxmHeaders = mapOf("Cookie" to "AWSELBCORS=0; AWSELB=0")

    private fun musixmatch(t: Track): List<WordLine>? {
        for ((artist, title) in Lyrics.guesses(t).take(2)) {
            val p = mutableMapOf("q_track" to title, "q_artist" to artist)
            if (t.durationSec > 0) p["q_duration"] = t.durationSec.toString()
            val tr = call("matcher.track.get", p).path("body", "track") as? JsonObject ?: continue
            val id = tr.path("track_id")?.num()?.toLong() ?: continue
            if (id <= 0 || tr.path("has_richsync")?.num() != 1.0) continue
            if (!close(t, tr.path("artist_name")?.str().orEmpty(), tr.path("track_name")?.str().orEmpty(), tr.path("track_length")?.num())) continue
            val body = call("track.richsync.get", mapOf("track_id" to id.toString())).path("body", "richsync", "richsync_body")?.str() ?: continue
            val lines = parseRichsync(body)
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    /** [{"ts": 12.3, "te": 15.6, "l": [{"c": "Hello", "o": 0.0}, {"c": " ", "o": 0.4}, ...]}] -> lines of words. */
    fun parseRichsync(body: String): List<WordLine> {
        val arr = runCatching { Parsing.json.parseToJsonElement(body) }.getOrNull() as? JsonArray ?: return emptyList()
        val out = mutableListOf<WordLine>()
        for (item in arr) {
            val o = item as? JsonObject ?: continue
            val ts = o.path("ts")?.num() ?: 0.0
            val te = o.path("te")?.num() ?: 0.0
            val starts = mutableListOf<Double>()
            val texts = mutableListOf<String>()
            for (part in (o["l"] as? JsonArray).orEmpty()) {
                val p = part as? JsonObject ?: continue
                val text = p.path("c")?.str().orEmpty()
                if (text.isBlank() && texts.isNotEmpty()) { texts[texts.size - 1] += text; continue }
                starts += ts + (p.path("o")?.num() ?: 0.0)
                texts += text
            }
            val words = texts.indices.map { i -> SungWord(starts[i], if (i + 1 < starts.size) starts[i + 1] else te, texts[i]) }
            if (words.isNotEmpty()) out += WordLine(ts, te, words)
        }
        return out
    }

    // ---------------------------------------------------------------- 2. NetEase yrc

    private fun netease(t: Track): List<WordLine>? {
        val hdr = mapOf("Referer" to "https://music.163.com/")
        for ((artist, title) in Lyrics.guesses(t).take(2)) {
            val r = http("https://music.163.com/api/search/get?s=${enc("$artist $title")}&type=1&limit=6", hdr) as? JsonObject ?: continue
            for (s in (r.path("result", "songs") as? JsonArray).orEmpty()) {
                val song = s as? JsonObject ?: continue
                val id = song.path("id")?.num()?.toLong() ?: continue
                val artists = (song["artists"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject).path("name")?.str() }.joinToString(" ")
                if (!close(t, artists, song.path("name")?.str().orEmpty(), (song.path("duration")?.num() ?: 0.0) / 1000)) continue
                val l = http("https://music.163.com/api/song/lyric/v1?id=$id&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", hdr) as? JsonObject ?: continue
                val lines = parseYrc(l.path("yrc", "lyric")?.str().orEmpty())
                if (lines.isNotEmpty()) return lines
            }
        }
        return null
    }

    private val yrcLine = Regex("""^\[(\d+),(\d+)](.*)$""")
    private val yrcWord = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")

    /** "[12340,3450](12340,500,0)Hello (12840,400,0)world" lines (milliseconds) -> lines of words. */
    fun parseYrc(yrc: String): List<WordLine> = yrc.lines().mapNotNull { raw ->
        val m = yrcLine.matchEntire(raw.trim()) ?: return@mapNotNull null
        val start = m.groupValues[1].toLong() / 1000.0
        val dur = m.groupValues[2].toLong() / 1000.0
        val words = yrcWord.findAll(m.groupValues[3]).mapNotNull { w ->
            val a = w.groupValues[1].toLong() / 1000.0
            val b = w.groupValues[2].toLong() / 1000.0
            w.groupValues[3].takeIf { it.isNotEmpty() }?.let { SungWord(a, a + b, it) }
        }.toList()
        if (words.isEmpty()) null else WordLine(start, start + dur, words)
    }

    // ---------------------------------------------------------------- 3. estimated

    /** Shares each line's time among its words by their length (not stretched over long pauses). */
    fun estimate(synced: List<LyricLine>, songEnd: Double): List<WordLine> = synced.mapIndexedNotNull { i, line ->
        if (line.text.isBlank()) return@mapIndexedNotNull null
        val next = synced.getOrNull(i + 1)?.time ?: if (songEnd > 0) songEnd else line.time + 5
        val words = Regex("""\S+\s*""").findAll(line.text).map { it.value }.toList()
        val weight = words.map { it.trim().length + 1.5 }
        val total = weight.sum()
        val span = max(0.4, min(min(next - line.time, total * 0.12 + 0.8), 12.0))
        var t = line.time
        val ws = words.zip(weight).map { (w, k) ->
            val d = span * k / total
            SungWord(t, t + d, w).also { t += d }
        }
        WordLine(line.time, line.time + span, ws)
    }

    // ---------------------------------------------------------------- helpers

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun close(t: Track, artist: String, title: String, dur: Double?): Boolean {
        if (t.durationSec > 0 && dur != null && dur > 0 && abs(dur - t.durationSec) > 8) return false
        val a = norm(artist)
        val n = norm(title)
        if (n.isEmpty()) return false
        val g = Lyrics.guesses(t)
        val titleOk = g.any { val s = norm(it.second); s.isNotEmpty() && (n in s || s in n) }
        val artistOk = a.isEmpty() || g.any { val x = norm(it.first); x.isNotEmpty() && (a in x || x in a) }
        return titleOk && artistOk
    }

    private fun JsonElement?.path(vararg keys: String): JsonElement? {
        var cur: JsonElement? = this
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.num(): Double? = (this as? JsonPrimitive)?.let { it.doubleOrNull ?: it.longOrNull?.toDouble() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String, headers: Map<String, String> = emptyMap()): JsonElement? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode != 200) null else Parsing.json.parseToJsonElement(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
