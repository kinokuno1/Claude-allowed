package ch.tunio.app.engine

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Song covers for songs that have none, a wrong one or a broken one (same as the desktop app).
 * Spotify playlists only list each song's name, so imported songs used to show the playlist's cover.
 * Free sources without an account, best first:
 *  - Spotify songs: Spotify's public oEmbed for the song link (its album cover)
 *  - YouTube videos: the video thumbnail (always exists, never expires)
 *  - anything else: the iTunes Search API, then Deezer's public search, by artist and title
 */
object Artwork {
    private val ytId = Regex("""(?:v=|youtu\.be/|/shorts/)([A-Za-z0-9_-]{11})""")
    private val spTrack = Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]+)""")

    fun youtubeId(t: Track): String? {
        if (t.source == Source.YOUTUBE && t.id.startsWith("yt_") && t.id.length == 14) return t.id.substring(3)
        for (u in listOf(t.url, t.matchedUrl)) ytId.find(u.orEmpty())?.let { return it.groupValues[1] }
        return null
    }

    /** Does this song need its own cover looked up? (No cover, or a Spotify song that may show its playlist's.) */
    fun needsLookup(t: Track) = t.thumbnail.isNullOrBlank() || t.source == Source.SPOTIFY

    /** The best cover for a song. [broken] is an image URL that failed to load, never returned again. */
    suspend fun lookup(t: Track, broken: String? = null): String? = withContext(Dispatchers.IO) {
        fun ok(u: String?) = u?.takeIf { it.isNotBlank() && it != broken }
        if (t.source == Source.SPOTIFY) ok(runCatching { spotify(t) }.getOrNull())?.let { return@withContext it }
        val vid = youtubeId(t)
        if (t.source == Source.YOUTUBE && vid != null) ok("https://i.ytimg.com/vi/$vid/hqdefault.jpg")?.let { return@withContext it }
        for ((artist, title) in Lyrics.guesses(t).take(3)) {
            ok(runCatching { itunes(artist, title) }.getOrNull())?.let { return@withContext it }
            ok(runCatching { deezer(artist, title) }.getOrNull())?.let { return@withContext it }
        }
        // a matched Spotify song: its YouTube video
        if (vid != null) ok("https://i.ytimg.com/vi/$vid/hqdefault.jpg")?.let { return@withContext it }
        null
    }

    private fun spotify(t: Track): String? {
        val id = spTrack.find(t.url.orEmpty())?.groupValues?.get(1) ?: return null
        val page = "https://open.spotify.com/track/$id"
        return (get("https://open.spotify.com/oembed?url=${enc(page)}") as? JsonObject).str("thumbnail_url")
    }

    private fun itunes(artist: String, title: String): String? {
        val q = "term=${enc("$artist $title".trim())}&media=music&entity=song&limit=8"
        val results = (get("https://itunes.apple.com/search?$q") as? JsonObject)?.get("results") as? JsonArray ?: return null
        for (r in results) {
            val o = r as? JsonObject ?: continue
            val art = o.str("artworkUrl100") ?: continue
            if (matches(artist, title, o.str("artistName").orEmpty(), o.str("trackName").orEmpty())) return art.replace("100x100bb", "600x600bb")
        }
        return null
    }

    private fun deezer(artist: String, title: String): String? {
        val q = if (artist.isNotBlank()) "artist:\"$artist\" track:\"$title\"" else title
        val data = (get("https://api.deezer.com/search?q=${enc(q)}&limit=5") as? JsonObject)?.get("data") as? JsonArray ?: return null
        for (r in data) {
            val o = r as? JsonObject ?: continue
            val cover = (o["album"] as? JsonObject).str("cover_xl") ?: continue
            if (matches(artist, title, (o["artist"] as? JsonObject).str("name").orEmpty(), o.str("title").orEmpty())) return cover
        }
        return null
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun matches(wantArtist: String, wantTitle: String, artist: String, title: String): Boolean {
        val wt = norm(wantTitle)
        val t = norm(title)
        if (wt.isEmpty() || t.isEmpty() || !(wt in t || t in wt)) return false
        val wa = norm(wantArtist).split(' ').filter { it.isNotEmpty() }.toSet()
        val a = norm(artist).split(' ').filter { it.isNotEmpty() }.toSet()
        return wa.isEmpty() || wa.intersect(a).isNotEmpty()
    }

    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): JsonElement? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "Tunio/1.0 (personal music player)")
        c.setRequestProperty("Accept", "application/json")
        try {
            if (c.responseCode != 200) null else Parsing.json.parseToJsonElement(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }.getOrNull()
}
