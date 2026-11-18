package ch.tunio.app.engine

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.Parsing.arr
import ch.tunio.app.engine.Parsing.num
import ch.tunio.app.engine.Parsing.obj
import ch.tunio.app.engine.Parsing.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.security.MessageDigest
import java.security.SecureRandom

/** Pure helpers for the Spotify Web API (JSON parsing, PKCE). Android-free so they are unit tested. */
object SpotifyJson {
    /** Spotify track IDs are 22 base-62 characters, so they can be found even in glued-together text. */
    private val trackLink = Regex("(?:open\\.spotify\\.com/(?:intl-[a-z-]+/)?track/|spotify:track:)([A-Za-z0-9]{22})")

    /** All Spotify track IDs in [text], in order, without duplicates (e.g. songs copied from the desktop app). */
    fun trackIds(text: String): List<String> = trackLink.findAll(text).map { it.groupValues[1] }.distinct().toList()

    fun bestImage(o: JsonObject?): String? =
        o?.arr("images")?.mapNotNull { it as? JsonObject }?.maxByOrNull { it.num("width") ?: 0.0 }?.str("url")

    /** A Spotify track object -> [Track]. Episodes are skipped. */
    fun track(t: JsonObject, fallbackCover: String? = null): Track? {
        if (t.str("type") == "episode") return null
        val name = t.str("name") ?: return null
        val artists = t.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ").orEmpty()
        val isLocal = (t["is_local"] as? JsonPrimitive)?.booleanOrNull == true
        val id = t.str("id") ?: Integer.toHexString("$name|$artists".hashCode())
        return Track(
            id = Parsing.makeId(Source.SPOTIFY, id),
            source = Source.SPOTIFY,
            title = name,
            artist = artists,
            durationSec = ((t.num("duration_ms") ?: 0.0) / 1000).toInt(),
            thumbnail = bestImage(t.obj("album")) ?: fallbackCover,
            url = if (isLocal || t.str("id") == null) null else "https://open.spotify.com/track/$id",
        )
    }

    /** One page of a paging object. Items may be tracks, or wrappers holding them in "track"/"item". */
    fun page(paging: JsonObject, fallbackCover: String? = null): Pair<List<Track>, String?> {
        val tracks = paging.arr("items").orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val t = o.obj("track") ?: o.obj("item") ?: o
            track(t, fallbackCover)
        }
        return tracks to paging.str("next")
    }

    /** The paging object inside a playlist/album object ("tracks", or "items" in newer API versions). */
    fun innerPaging(o: JsonObject): JsonObject? =
        listOf("tracks", "items").mapNotNull { o.obj(it) }.firstOrNull { it.arr("items") != null }

    // ---- PKCE (RFC 7636)

    fun newVerifier(): String {
        val bytes = ByteArray(64).also { SecureRandom().nextBytes(it) }
        return base64Url(bytes)
    }

    fun challenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Base64url without padding (java.util.Base64 needs Android 8, the app supports Android 7). */
    fun base64Url(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else -1
            sb.append(ALPHABET[b0 shr 2])
            sb.append(ALPHABET[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(ALPHABET[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(ALPHABET[b2 and 63])
            i += 3
        }
        return sb.toString()
    }
}
