package ch.tunio.app.engine

import ch.tunio.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

data class LyricLine(val time: Double, val text: String)

data class LyricsResult(val synced: List<LyricLine>?, val plain: String?, val instrumental: Boolean) {
    val found: Boolean get() = synced != null || plain != null || instrumental
}

/** Lyrics from LRCLIB (https://lrclib.net), a free community-made lyrics library. Most songs have synced lyrics. */
object Lyrics {
    private const val API = "https://lrclib.net/api"
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    suspend fun get(t: Track): LyricsResult = withContext(Dispatchers.IO) {
        cache[t.id] ?: lookup(t).also { cache[t.id] = it }
    }

    private fun lookup(t: Track): LyricsResult {
        var best = LyricsResult(null, null, false)
        for ((artist, title) in guesses(t).take(4)) {
            val q = buildString {
                append("artist_name=").append(enc(artist)).append("&track_name=").append(enc(title))
                if (t.durationSec > 0) append("&duration=").append(t.durationSec)
            }
            (http("$API/get?$q") as? JsonObject)?.let { r ->
                val res = parse(r)
                if (res.synced != null || res.instrumental) return res
                if (res.found && !best.found) best = res
            }
            (http("$API/search?track_name=${enc(title)}&artist_name=${enc(artist)}") as? JsonArray)?.let { list ->
                val close = list.filterIsInstance<JsonObject>().firstOrNull { o ->
                    val d = (o["duration"] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: 0
                    t.durationSec == 0 || kotlin.math.abs(d - t.durationSec) <= 6
                }
                close?.let { val res = parse(it); if (res.synced != null) return res; if (res.found && !best.found) best = res }
            }
        }
        return best
    }

    private fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun parse(o: JsonObject): LyricsResult {
        val synced = str(o, "syncedLyrics")?.let { parseLrc(it) }?.takeIf { it.isNotEmpty() }
        return LyricsResult(synced, str(o, "plainLyrics"), (o["instrumental"] as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** "[01:02.34] text" lines -> sorted lines (a line can carry several time stamps). */
    fun parseLrc(s: String): List<LyricLine> {
        val stamp = Regex("""\[(\d+):(\d+(?:[.:]\d+)?)\]""")
        val out = mutableListOf<LyricLine>()
        for (raw in s.lines()) {
            val times = stamp.findAll(raw).map { it.groupValues[1].toInt() * 60 + it.groupValues[2].replace(':', '.').toDouble() }.toList()
            val text = stamp.replace(raw, "").trim()
            for (t in times) out += LyricLine(t, text)
        }
        return out.sortedBy { it.time }
    }

    /** (artist, title) to try, best first. YouTube titles are messy: "Artist - Song (Official Video) [4K]". */
    fun guesses(t: Track): List<Pair<String, String>> {
        val junk = Regex("""\s*[(\[【][^)\]】]*?\b(official|video|audio|lyrics?|visuali[sz]er|music video|mv|hd|hq|4k|remaster(ed)?|live|explicit|clean|prod\.?)\b[^)\]】]*[)\]】]""", RegexOption.IGNORE_CASE)
        val feat = Regex("""\s*[(\[]?\s*\b(feat\.?|ft\.?|featuring)\s+[^)\]\-]*[)\]]?""", RegexOption.IGNORE_CASE)
        fun clean(s: String) = s.replace(junk, "").replace(feat, "").replace(Regex("""\s*(\||//|#).*$"""), "").replace("\"", "").trim(' ', '-', '–', '—', '_', '.')
        val channel = t.artist.replace(Regex("""(\s*-\s*topic|vevo|\s*official( channel)?)$""", RegexOption.IGNORE_CASE), "").trim()
        val title = clean(t.title)
        val out = mutableListOf<Pair<String, String>>()
        for (sep in listOf(" - ", " – ", " — ")) {
            if (title.contains(sep)) {
                val a = title.substringBefore(sep).trim()
                val b = title.substringAfter(sep).trim()
                out += a to b
                out += b to a
            }
        }
        out.add(minOf(1, out.size), channel to title)
        val seen = HashSet<String>()
        return out.filter { it.second.isNotBlank() && seen.add("${it.first}|${it.second}".lowercase()) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun http(url: String): JsonElement? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 12_000
        c.setRequestProperty("User-Agent", "Tunio Android (personal music player)")
        try {
            if (c.responseCode != 200) null else ch.tunio.app.engine.Parsing.json.parseToJsonElement(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
