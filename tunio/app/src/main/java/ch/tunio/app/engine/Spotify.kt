package ch.tunio.app.engine

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.data.TrackList
import ch.tunio.app.engine.Parsing.arr
import ch.tunio.app.engine.Parsing.num
import ch.tunio.app.engine.Parsing.obj
import ch.tunio.app.engine.Parsing.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Spotify audio is DRM protected, so Tunio only reads the public metadata (title, artist, duration)
 * from Spotify's embed page and plays/downloads the matching song from YouTube.
 */
object Spotify {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
    private val linkRegex = Regex("open\\.spotify\\.com/(?:intl-[a-z-]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]+)")
    private val uriRegex = Regex("spotify:(track|album|playlist):([A-Za-z0-9]+)")

    fun isSpotify(url: String): Boolean {
        val u = url.lowercase()
        return "spotify.com" in u || "spotify.link" in u || "spoti.fi" in u || u.startsWith("spotify:")
    }

    fun parseLink(url: String): Pair<String, String>? {
        val m = linkRegex.find(url) ?: uriRegex.find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }

    /** Blocking network call: run on Dispatchers.IO. */
    fun resolve(url: String): TrackList {
        var link = parseLink(url)
        if (link == null) {
            // Short links (spotify.link / spoti.fi) redirect to open.spotify.com.
            val (finalUrl, body) = get(url)
            link = parseLink(finalUrl) ?: parseLink(body) ?: error("Not a Spotify track, album or playlist link")
        }
        val (type, id) = link
        val pageUrl = "https://open.spotify.com/$type/$id"
        val fromEmbed = runCatching { parseEmbed(get("https://open.spotify.com/embed/$type/$id").second, type, id) }.getOrNull()
        if (fromEmbed != null && fromEmbed.tracks.isNotEmpty()) return fromEmbed.copy(sourceUrl = pageUrl)
        if (type == "track") {
            // Fallback: oEmbed gives at least the title and cover.
            val body = get("https://open.spotify.com/oembed?url=" + URLEncoder.encode(pageUrl, "UTF-8")).second
            val o = Parsing.json.parseToJsonElement(body) as JsonObject
            val title = o.str("title") ?: error("Spotify returned no title")
            return TrackList(null, listOf(Track(Parsing.makeId(Source.SPOTIFY, id), Source.SPOTIFY, title, "", 0, o.str("thumbnail_url"), pageUrl)), sourceUrl = pageUrl)
        }
        error("Couldn't read this Spotify $type. Is it public?")
    }

    private fun get(url: String): Pair<String, String> {
        var current = url
        repeat(6) {
            val c = URL(current).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Language", "en")
            c.connectTimeout = 15000
            c.readTimeout = 20000
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: error("Redirect without location")
                current = URL(URL(current), loc).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("Spotify answered HTTP $code")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            return current to body
        }
        error("Too many redirects")
    }

    /** Parses the `__NEXT_DATA__` JSON embedded in open.spotify.com/embed/... pages. */
    fun parseEmbed(html: String, type: String, id: String): TrackList? {
        val m = Regex("<script[^>]*id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return null
        val root = Parsing.json.parseToJsonElement(m.groupValues[1])
        val entity = (root as? JsonObject)?.obj("props")?.obj("pageProps")?.obj("state")?.obj("data")?.obj("entity")
            ?: findObject(root) { it.arr("trackList") != null }
            ?: findObject(root) { it.str("type") == type && it.str("name") != null }
            ?: return null
        val cover = findImage(entity)
        val trackList = entity.arr("trackList")
        if (trackList != null && (type != "track" || trackList.isNotEmpty())) {
            val tracks = trackList.mapNotNull { (it as? JsonObject)?.let { t -> listItem(t, cover) } }
            if (tracks.isNotEmpty() || type != "track") {
                return TrackList(entity.str("name") ?: entity.str("title"), tracks, cover)
            }
        }
        // Single track entity.
        val title = entity.str("name") ?: entity.str("title") ?: return null
        val artists = entity.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")
            ?: entity.str("subtitle").orEmpty()
        val track = Track(
            id = Parsing.makeId(Source.SPOTIFY, id),
            source = Source.SPOTIFY,
            title = title,
            artist = cleanArtists(artists),
            durationSec = durationMs(entity) / 1000,
            thumbnail = cover,
            url = "https://open.spotify.com/track/$id",
        )
        return TrackList(null, listOf(track), cover)
    }

    private fun listItem(t: JsonObject, cover: String?): Track? {
        val title = t.str("title") ?: t.str("name") ?: return null
        val uri = t.str("uri").orEmpty()
        val trackId = uriRegex.find(uri)?.groupValues?.get(2) ?: t.str("id") ?: Integer.toHexString((title + t.str("subtitle")).hashCode())
        val artists = t.str("subtitle")
            ?: t.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")
            ?: ""
        return Track(
            id = Parsing.makeId(Source.SPOTIFY, trackId),
            source = Source.SPOTIFY,
            title = title,
            artist = cleanArtists(artists),
            durationSec = durationMs(t) / 1000,
            thumbnail = findImage(t) ?: cover,
            url = "https://open.spotify.com/track/$trackId",
        )
    }

    private fun cleanArtists(s: String) = s.replace(' ', ' ').replace(Regex("\\s*,\\s*"), ", ").trim()

    private fun durationMs(o: JsonObject): Int =
        (o.num("duration") ?: o.obj("duration")?.num("totalMilliseconds") ?: o.num("duration_ms"))?.toInt() ?: 0

    private fun findImage(o: JsonElement): String? {
        var found: String? = null
        walk(o) { e ->
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (found == null && s != null && s.startsWith("https://") && ("i.scdn.co/image" in s || "image-cdn" in s || "mosaic.scdn.co" in s)) found = s
        }
        return found
    }

    private fun findObject(e: JsonElement, pred: (JsonObject) -> Boolean): JsonObject? {
        var found: JsonObject? = null
        walk(e) { if (found == null && it is JsonObject && pred(it)) found = it }
        return found
    }

    private fun walk(e: JsonElement, visit: (JsonElement) -> Unit) {
        visit(e)
        when (e) {
            is JsonObject -> e.values.forEach { walk(it, visit) }
            is JsonArray -> e.forEach { walk(it, visit) }
            else -> Unit
        }
    }
}
