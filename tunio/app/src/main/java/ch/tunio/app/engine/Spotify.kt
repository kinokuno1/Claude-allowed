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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
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

    /**
     * Resolves a Spotify link (track/album/playlist, short links too) or a pasted list of song links.
     * Uses the Spotify account when logged in (needed for private playlists), else the public embed page.
     */
    suspend fun resolveAny(text: String): TrackList {
        val ids = SpotifyJson.trackIds(text)
        if (ids.size >= 2) return resolveTrackLinks(ids)
        val link = parseLink(text) ?: withContext(Dispatchers.IO) {
            // Short links (spotify.link / spoti.fi) redirect to open.spotify.com.
            val url = Parsing.extractUrl(text) ?: text.trim()
            val (finalUrl, body) = get(url)
            parseLink(finalUrl) ?: parseLink(body)
        } ?: error("Not a Spotify song, album or playlist link")
        val (type, id) = link
        var accountError: Throwable? = null
        if (SpotifyAccount.loggedIn) {
            try {
                val viaAccount = when (type) {
                    "playlist" -> SpotifyAccount.playlist(id)
                    "album" -> SpotifyAccount.album(id)
                    else -> SpotifyAccount.track(id)
                }
                // Spotify gives no songs for playlists you only follow: then try the public page below.
                if (viaAccount.tracks.isNotEmpty()) return viaAccount
                accountError = IllegalStateException(
                    "Spotify only shares the songs of playlists you created or collaborate on. " +
                        "Make this one public, or copy its songs from the Spotify desktop app and paste them in Search.",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                accountError = e // fall back to the public page below
            }
        }
        val public = withContext(Dispatchers.IO) { runCatching { resolvePublic(type, id) } }
        public.getOrNull()?.let { return it }
        if (accountError != null) throw accountError
        if (type == "playlist") {
            throw IllegalStateException(
                "Couldn't read this playlist. If it's private, log in to Spotify in Settings → Spotify account " +
                    "(one-time setup), or make the playlist public in Spotify.",
            )
        }
        throw public.exceptionOrNull() ?: IllegalStateException("Couldn't read this Spotify link")
    }

    /** Songs copied from the Spotify desktop app (select songs → Ctrl+C gives one link per line). */
    private suspend fun resolveTrackLinks(ids: List<String>): TrackList = coroutineScope {
        val gate = Semaphore(6)
        val tracks = ids.map { id ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching { resolvePublic("track", id).tracks.firstOrNull() }.getOrNull()
                        ?: if (SpotifyAccount.loggedIn) runCatching { SpotifyAccount.track(id).tracks.firstOrNull() }.getOrNull() else null
                }
            }
        }.awaitAll().filterNotNull()
        if (tracks.isEmpty()) error("Couldn't read any of the ${ids.size} Spotify songs")
        TrackList("Pasted Spotify songs", tracks, tracks.first().thumbnail)
    }

    /** Blocking network call: run on Dispatchers.IO. Works for public playlists, albums and all songs. */
    private fun resolvePublic(type: String, id: String): TrackList {
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
