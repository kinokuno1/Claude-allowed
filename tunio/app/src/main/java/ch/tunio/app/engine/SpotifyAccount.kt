package ch.tunio.app.engine

import android.content.Context
import android.net.Uri
import ch.tunio.app.data.TrackList
import ch.tunio.app.engine.Parsing.arr
import ch.tunio.app.engine.Parsing.num
import ch.tunio.app.engine.Parsing.obj
import ch.tunio.app.engine.Parsing.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional Spotify login (OAuth "PKCE" flow, no secret stored in the app). With it Tunio can read your
 * private playlists and Liked Songs. It needs a free Spotify developer app; its Client ID goes in Settings.
 */
object SpotifyAccount {
    const val REDIRECT_URI = "tunio://spotify-callback"
    private const val SCOPES = "playlist-read-private playlist-read-collaborative user-library-read"
    private const val PREFS = "spotify"

    private lateinit var app: Context
    private val tokenLock = Mutex()

    /** Display name of the logged-in user, or null when not logged in. */
    val user = MutableStateFlow<String?>(null)

    private val prefs get() = app.getSharedPreferences(PREFS, 0)

    fun init(context: Context) {
        app = context.applicationContext
        if (prefs.getString("refresh", null) != null) user.value = prefs.getString("user", "Spotify user")
    }

    var clientId: String
        get() = prefs.getString("client_id", "").orEmpty()
        set(v) { prefs.edit().putString("client_id", v.trim()).apply() }

    val loggedIn: Boolean get() = user.value != null

    /** URL to open in the browser to start logging in. */
    fun authorizeUrl(): String {
        require(clientId.isNotBlank()) { "Enter your Spotify Client ID first" }
        val verifier = SpotifyJson.newVerifier()
        val state = SpotifyJson.newVerifier().take(16)
        prefs.edit().putString("verifier", verifier).putString("state", state).apply()
        return Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", SpotifyJson.challenge(verifier))
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .build().toString()
    }

    /** Called with tunio://spotify-callback?code=... when the browser comes back. Returns a message for the user. */
    suspend fun finishLogin(callback: Uri): String = withContext(Dispatchers.IO) {
        callback.getQueryParameter("error")?.let { return@withContext "Spotify login cancelled ($it)" }
        val code = callback.getQueryParameter("code") ?: return@withContext "Spotify login failed: no code"
        if (callback.getQueryParameter("state") != prefs.getString("state", null)) return@withContext "Spotify login failed: please try again"
        val tokens = tokenRequest(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT_URI,
            "client_id" to clientId,
            "code_verifier" to prefs.getString("verifier", "").orEmpty(),
        )
        saveTokens(tokens)
        val me = Parsing.json.parseToJsonElement(api("https://api.spotify.com/v1/me")) as JsonObject
        val name = me.str("display_name") ?: me.str("id") ?: "Spotify user"
        prefs.edit().putString("user", name).remove("verifier").remove("state").apply()
        user.value = name
        "Logged in to Spotify as $name"
    }

    fun logout() {
        prefs.edit().remove("access").remove("refresh").remove("expires").remove("user").apply()
        user.value = null
    }

    private fun saveTokens(o: JsonObject) {
        val access = o.str("access_token") ?: error("Spotify gave no access token")
        val e = prefs.edit()
            .putString("access", access)
            .putLong("expires", System.currentTimeMillis() + ((o.num("expires_in") ?: 3600.0) * 1000).toLong() - 60_000)
        o.str("refresh_token")?.let { e.putString("refresh", it) } // Spotify may rotate it
        e.apply()
    }

    private suspend fun accessToken(): String = tokenLock.withLock {
        val access = prefs.getString("access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("expires", 0)) return access
        val refresh = prefs.getString("refresh", null) ?: error("Not logged in to Spotify")
        val tokens = try {
            tokenRequest("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId)
        } catch (e: SpotifyHttpException) {
            if (e.code == 400 || e.code == 401) logout()
            throw IllegalStateException("Spotify login expired, log in again in Settings")
        }
        saveTokens(tokens)
        prefs.getString("access", null)!!
    }

    class SpotifyHttpException(val code: Int, message: String) : Exception(message)

    private fun tokenRequest(vararg form: Pair<String, String>): JsonObject {
        val body = form.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        c.disconnect()
        if (code !in 200..299) throw SpotifyHttpException(code, "Spotify login error $code: ${errorText(text)}")
        return Parsing.json.parseToJsonElement(text) as JsonObject
    }

    private fun errorText(body: String): String = runCatching {
        val o = Parsing.json.parseToJsonElement(body) as JsonObject
        o.str("error_description") ?: o.obj("error")?.str("message") ?: o.str("error") ?: body
    }.getOrDefault(body).take(200)

    /** GET a Web API URL with the user's token. Retries once on rate limiting. */
    private suspend fun api(url: String): String {
        repeat(3) {
            val token = accessToken()
            val (code, text, retryAfter) = withContext(Dispatchers.IO) {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("Authorization", "Bearer $token")
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val retry = c.getHeaderField("Retry-After")?.toLongOrNull() ?: 2
                c.disconnect()
                Triple(code, text, retry)
            }
            when {
                code in 200..299 -> return text
                code == 429 -> kotlinx.coroutines.delay(retryAfter.coerceAtMost(30) * 1000)
                code == 401 -> prefs.edit().remove("access").apply() // token revoked/expired: refresh and retry
                code == 403 -> throw SpotifyHttpException(code, "Spotify refused (403): ${errorText(text)}. " +
                    "In the Spotify developer dashboard, add your account under User Management.")
                code == 404 -> throw SpotifyHttpException(code, "Spotify can't find this (404). Spotify doesn't let apps read its own generated playlists (e.g. Daily Mix, Discover Weekly).")
                else -> throw SpotifyHttpException(code, "Spotify error $code: ${errorText(text)}")
            }
        }
        error("Spotify is busy, try again in a minute")
    }

    private suspend fun obj(url: String) = Parsing.json.parseToJsonElement(api(url)) as JsonObject

    /** Follows "next" links of a paging object, collecting every track. */
    private suspend fun allPages(first: JsonObject, cover: String?): List<ch.tunio.app.data.Track> {
        val out = mutableListOf<ch.tunio.app.data.Track>()
        var page: JsonObject? = first
        while (page != null) {
            val (tracks, next) = SpotifyJson.page(page, cover)
            out += tracks
            page = next?.let { obj(it) }
        }
        return out.distinctBy { it.id }
    }

    suspend fun playlist(id: String): TrackList {
        val p = obj("https://api.spotify.com/v1/playlists/$id")
        val cover = SpotifyJson.bestImage(p)
        val paging = SpotifyJson.innerPaging(p) ?: obj("https://api.spotify.com/v1/playlists/$id/tracks?limit=100")
        return TrackList(p.str("name"), allPages(paging, cover), cover, "https://open.spotify.com/playlist/$id")
    }

    suspend fun album(id: String): TrackList {
        val a = obj("https://api.spotify.com/v1/albums/$id")
        val cover = SpotifyJson.bestImage(a)
        val paging = SpotifyJson.innerPaging(a) ?: obj("https://api.spotify.com/v1/albums/$id/tracks?limit=50")
        return TrackList(a.str("name"), allPages(paging, cover), cover, "https://open.spotify.com/album/$id")
    }

    suspend fun track(id: String): TrackList {
        val t = SpotifyJson.track(obj("https://api.spotify.com/v1/tracks/$id")) ?: error("Not a song")
        return TrackList(null, listOf(t), t.thumbnail, "https://open.spotify.com/track/$id")
    }

    suspend fun likedSongs(): TrackList =
        TrackList("Liked Songs (Spotify)", allPages(obj("https://api.spotify.com/v1/me/tracks?limit=50"), null))

    data class PlaylistInfo(val id: String, val name: String, val cover: String?, val count: Int)

    suspend fun myPlaylists(): List<PlaylistInfo> {
        val out = mutableListOf<PlaylistInfo>()
        var url: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
        while (url != null) {
            val page = obj(url)
            page.arr("items").orEmpty().forEach { e ->
                val p = e as? JsonObject ?: return@forEach
                val id = p.str("id") ?: return@forEach
                val count = (SpotifyJson.innerPaging(p) ?: p.obj("tracks") ?: p.obj("items"))?.num("total")?.toInt() ?: 0
                out += PlaylistInfo(id, p.str("name") ?: "Playlist", SpotifyJson.bestImage(p), count)
            }
            url = page.str("next")
        }
        return out
    }
}
