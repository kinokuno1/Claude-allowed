package ch.tunio.app.engine

import ch.tunio.app.data.Source
import ch.tunio.app.data.StreamInfo
import ch.tunio.app.data.Track
import ch.tunio.app.data.TrackList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Pure functions turning yt-dlp JSON output into [Track]s. Kept Android-free so it is unit tested. */
object Parsing {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

    fun sourceOf(url: String?, extractor: String? = null): Source {
        val e = extractor?.lowercase().orEmpty()
        val u = url?.lowercase().orEmpty()
        return when {
            e.startsWith("youtube") || "youtube.com" in u || "youtu.be" in u -> Source.YOUTUBE
            e.startsWith("soundcloud") || "soundcloud.com" in u -> Source.SOUNDCLOUD
            e.startsWith("tiktok") || "tiktok.com" in u -> Source.TIKTOK
            "spotify" in e || "spotify.com" in u || u.startsWith("spotify:") -> Source.SPOTIFY
            else -> Source.OTHER
        }
    }

    private fun prefix(source: Source) = when (source) {
        Source.YOUTUBE -> "yt"
        Source.SOUNDCLOUD -> "sc"
        Source.TIKTOK -> "tt"
        Source.SPOTIFY -> "sp"
        Source.OTHER -> "x"
    }

    fun makeId(source: Source, rawId: String): String {
        val clean = rawId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val safe = if (clean.length > 60) Integer.toHexString(rawId.hashCode()) + clean.takeLast(20) else clean
        return "${prefix(source)}_$safe"
    }

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("http://") || t.startsWith("https://") || t.startsWith("spotify:") ||
            Regex("^(www\\.|m\\.|vm\\.|open\\.)?[a-z0-9-]+\\.(com|be|link|fi|app\\.goo\\.gl)/\\S+", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }

    /** Pulls the first link out of shared text like "Check this out! https://vm.tiktok.com/abc/". */
    fun extractUrl(text: String): String? =
        Regex("(https?://\\S+|spotify:(track|album|playlist):[A-Za-z0-9]+)").find(text)?.value?.trimEnd('.', ',', ')', '"')

    fun cleanArtist(name: String): String = name.removeSuffix(" - Topic").removeSuffix("VEVO").trim()

    fun trackFrom(o: JsonObject, defaultSource: Source = Source.OTHER): Track? {
        val extractor = o.str("ie_key") ?: o.str("extractor_key") ?: o.str("extractor")
        var url = o.str("webpage_url") ?: o.str("original_url") ?: o.str("url")
        val detected = sourceOf(url, extractor)
        val source = if (detected == Source.OTHER) defaultSource else detected
        val rawId = o.str("id") ?: url ?: return null
        if (source == Source.YOUTUBE && (url == null || !url.startsWith("http"))) {
            url = "https://www.youtube.com/watch?v=$rawId"
        }
        if (url == null) return null

        val trackName = o.str("track")?.takeUnless { it.startsWith("original sound", ignoreCase = true) }
        val title = trackName ?: o.str("title") ?: o.str("fulltitle") ?: return null
        val artist = o.str("artist")
            ?: (o.arr("artists")?.firstOrNull() as? JsonPrimitive)?.contentOrNull
            ?: o.str("creator")
            ?: o.str("uploader")
            ?: o.str("channel")
            ?: ""
        val thumb = o.str("thumbnail")
            ?: o.arr("thumbnails")?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull()
            ?: if (source == Source.YOUTUBE) "https://i.ytimg.com/vi/$rawId/hqdefault.jpg" else null
        return Track(
            id = makeId(source, rawId),
            source = source,
            title = title.trim(),
            artist = cleanArtist(artist),
            durationSec = o.num("duration")?.toInt() ?: 0,
            thumbnail = thumb,
            url = url,
        )
    }

    /** Parses `yt-dlp -j -f ba/b` output into a streamable URL, if the chosen format is plain HTTP(S). */
    fun streamInfo(text: String): StreamInfo? {
        val start = text.indexOf('{')
        if (start < 0) return null
        val o = json.parseToJsonElement(text.substring(start).lineSequence().first()) as? JsonObject ?: return null
        val protocol = o.str("protocol") ?: "https"
        if (protocol != "https" && protocol != "http") return null
        val url = o.str("url") ?: return null
        val headers = o.obj("http_headers")?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
        // Format-level cookies (e.g. TikTok) aren't in http_headers; streaming without them would fail.
        if (o.str("cookies") != null) return null
        return StreamInfo(url, headers)
    }

    /** Parses `yt-dlp -J` output: either a single item or a playlist with `entries`. */
    fun parseResult(text: String, defaultSource: Source = Source.OTHER): TrackList {
        val start = text.indexOf('{')
        if (start < 0) return TrackList(null, emptyList())
        val root = json.parseToJsonElement(text.substring(start)) as? JsonObject
            ?: return TrackList(null, emptyList())
        val entries = root.arr("entries")
        return if (entries != null) {
            val tracks = flatten(entries).mapNotNull { trackFrom(it, defaultSource) }.distinctBy { it.id }
            TrackList(
                title = root.str("title"),
                tracks = tracks,
                cover = root.arr("thumbnails")?.mapNotNull { (it as? JsonObject)?.str("url") }?.lastOrNull()
                    ?: tracks.firstOrNull()?.thumbnail,
                sourceUrl = root.str("webpage_url") ?: root.str("original_url"),
            )
        } else {
            TrackList(null, listOfNotNull(trackFrom(root, defaultSource)))
        }
    }

    private fun flatten(entries: JsonArray): List<JsonObject> = entries.flatMap { e: JsonElement ->
        val o = e as? JsonObject ?: return@flatMap emptyList()
        val nested = o.arr("entries")
        if (nested != null) flatten(nested) else listOf(o)
    }
}
