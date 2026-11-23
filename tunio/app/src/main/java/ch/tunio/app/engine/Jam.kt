package ch.tunio.app.engine

import android.content.Context
import android.os.Build
import android.provider.Settings
import ch.tunio.app.data.Source
import ch.tunio.app.data.StreamInfo
import ch.tunio.app.data.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class JamTrack(
    val qid: String,
    val id: String,
    val source: String = "OTHER",
    val title: String,
    val artist: String = "",
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    val url: String? = null,
    val matchedUrl: String? = null,
    val addedBy: String = "",
) {
    fun toTrack() = Track(
        id, runCatching { Source.valueOf(source) }.getOrDefault(Source.OTHER), title, artist, durationSec, thumbnail, url, matchedUrl, artChecked = true,
    )
}

@Serializable
data class JamPerson(val id: String, val name: String, val host: Boolean = false)

@Serializable
data class JamNow(val title: String? = null, val artist: String? = null)

/** A Jam as listed on a server (before joining). */
@Serializable
data class JamInfo(
    val id: String,
    val name: String,
    val locked: Boolean = false,
    val host: String = "",
    val members: Int = 0,
    val now: JamNow? = null,
)

@Serializable
data class JamState(
    val id: String,
    val name: String,
    val locked: Boolean = false,
    val host: String = "",
    val version: Int = 0,
    val ended: Boolean = false,
    val queue: List<JamTrack> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val position: Double = 0.0,
    val serverTime: Double = 0.0,
    val people: List<JamPerson> = emptyList(),
    val you: JamPerson? = null,
) {
    val current: JamTrack? get() = queue.getOrNull(index)
    val upNext: List<JamTrack> get() = if (index < 0) queue else queue.drop(index + 1)
}

/** A Jam found nearby or from a link: on which server. */
data class JamFound(val server: String, val info: JamInfo)

class JamException(message: String, val code: Int = 0) : IOException(message)

/**
 * Listening together (like Spotify Jam), through a Tunio home server. The server keeps the shared queue; this
 * follows it with long polling. Music plays on the host's phone (its speaker / Bluetooth device); a guest can
 * also play along on their own phone, in sync with the host.
 */
object Jam {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    private var pollJob: Job? = null

    val state = MutableStateFlow<JamState?>(null)
    /** Guests: also play the music on this phone (synced), not only on the host's. */
    val playAlong = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Set by a tunio://jam link: the Jam screen opens and asks to join it. */
    val pendingLink = MutableStateFlow<JamFound?>(null)

    var server: String = ""
        private set
    private var token: String = ""
    /** server clock - this phone's clock (seconds), from the round trips. */
    private var offset = 0.0
    private var bestRtt = Double.MAX_VALUE

    val active: Boolean get() = state.value != null
    val isHost: Boolean get() = state.value?.you?.host == true
    /** This phone plays the Jam's music (the host always does). */
    val playsHere: Boolean get() = active && (isHost || playAlong.value)
    val link: String get() = state.value?.let { "$server/jam/${it.id}" }.orEmpty()

    var onState: ((JamState?, JamState?) -> Unit)? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    var myName: String
        get() = app.getSharedPreferences("jam", 0).getString("name", null) ?: defaultName()
        set(v) = app.getSharedPreferences("jam", 0).edit().putString("name", v.trim().take(40)).apply()

    private fun defaultName(): String =
        runCatching { Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** Server time now (seconds). */
    fun serverNow(): Double = System.currentTimeMillis() / 1000.0 + offset

    /** Where the Jam's current song should be now (seconds). */
    fun expectedPosition(st: JamState? = state.value): Double {
        val s = st ?: return 0.0
        return s.position + if (s.playing) (serverNow() - s.serverTime).coerceAtLeast(0.0) else 0.0
    }

    // ---------------------------------------------------------------- finding, starting, joining

    /** Jams on your own server and on a Tunio server found on this Wi-Fi. */
    suspend fun nearby(): List<JamFound> = withContext(Dispatchers.IO) {
        val servers = linkedSetOf<String>()
        if (HomeServer.configured) servers += HomeServer.address
        runCatching { HomeServer.discover(5000) }.getOrNull()?.let { servers += it }
        servers.flatMap { srv ->
            runCatching {
                val o = http("GET", "$srv/api/jams", timeout = 5000) as JsonObject
                Parsing.json.decodeFromJsonElement(JamList.serializer(), o).jams.map { JamFound(srv, it) }
            }.getOrDefault(emptyList())
        }.distinctBy { it.info.id }
    }

    @Serializable
    private data class JamList(val jams: List<JamInfo> = emptyList())

    @Serializable
    private data class Joined(val id: String, val token: String, val state: JamState)

    /** Starts a Jam on your home server with what's playing now. */
    suspend fun create(name: String, password: String, queue: List<Track>, index: Int, positionSec: Double, playing: Boolean) {
        if (!HomeServer.configured) throw JamException("A Jam runs on your Tunio home server. Set it up in Settings first.")
        val body = buildJsonObject {
            put("name", name)
            put("password", password)
            put("you", myName)
            put("tracks", JsonArray(queue.map(::wire)))
            put("index", index.coerceAtLeast(0))
            put("position", positionSec)
            put("playing", playing)
        }
        val o = withContext(Dispatchers.IO) {
            http("POST", "${HomeServer.address}/api/jams", body, auth = "Bearer ${HomeServer.password}")
        }
        start(HomeServer.address, Parsing.json.decodeFromJsonElement(Joined.serializer(), o!!))
    }

    suspend fun join(server: String, id: String, password: String) {
        val body = buildJsonObject { put("you", myName); put("password", password) }
        val o = withContext(Dispatchers.IO) { http("POST", "$server/api/jam/$id/join", body) }
        playAlong.value = false
        start(server, Parsing.json.decodeFromJsonElement(Joined.serializer(), o!!))
    }

    /** What a Jam link points to: https://server/jam/ID or tunio://jam?u=server&j=ID. */
    fun parseLink(text: String): Pair<String, String>? {
        val t = text.trim()
        Regex("""tunio://jam\?(.+)""").find(t)?.let { m ->
            val q = m.groupValues[1].split('&').mapNotNull { kv ->
                kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { java.net.URLDecoder.decode(it[0], "UTF-8") to java.net.URLDecoder.decode(it[1], "UTF-8") }
            }.toMap()
            val u = q["u"]
            val j = q["j"]
            if (!u.isNullOrBlank() && !j.isNullOrBlank()) return u.trimEnd('/') to j
        }
        Regex("""(https?://[^\s/]+(?:/[^\s]*?)?)/jam/([A-Za-z0-9]+)""").find(t)?.let { return it.groupValues[1] to it.groupValues[2] }
        return null
    }

    /** A link was opened: look the Jam up and let the Jam screen ask to join. */
    suspend fun openLink(server: String, id: String) {
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val o = http("GET", "$server/api/jams", timeout = 6000) as JsonObject
                Parsing.json.decodeFromJsonElement(JamList.serializer(), o).jams.firstOrNull { it.id == id }
            }.getOrNull()
        }
        if (info == null) messages.tryEmit("That Jam has ended, or its server can't be reached.")
        else pendingLink.value = JamFound(server, info)
    }

    private fun start(srv: String, j: Joined) {
        leaveQuietly()
        server = srv.trimEnd('/')
        token = j.token
        bestRtt = Double.MAX_VALUE
        set(j.state)
        scope.launch(Dispatchers.IO) { syncClock() }
        pollJob = scope.launch { poll() }
    }

    fun leave() {
        val s = state.value ?: return
        val srv = server
        val tok = token
        scope.launch(Dispatchers.IO) {
            runCatching { http("POST", "$srv/api/jam/${s.id}", buildJsonObject { put("action", if (s.you?.host == true) "end" else "leave") }, jamToken = tok) }
        }
        leaveQuietly()
    }

    private fun leaveQuietly() {
        pollJob?.cancel()
        pollJob = null
        if (state.value != null) set(null)
        playAlong.value = false
    }

    private fun set(s: JamState?) {
        val old = state.value
        state.value = s
        onState?.invoke(old, s)
    }

    // ---------------------------------------------------------------- following the Jam

    private suspend fun poll() {
        var fails = 0
        while (true) {
            val s = state.value ?: return
            try {
                val sent = System.currentTimeMillis()
                val o = withContext(Dispatchers.IO) {
                    http("GET", "$server/api/jam/${s.id}?since=${s.version}&wait=25", jamToken = token, timeout = 35_000)
                }
                val next = Parsing.json.decodeFromJsonElement(JamState.serializer(), o!!)
                clock(sent, System.currentTimeMillis(), next.serverTime)
                fails = 0
                if (next.ended) {
                    messages.tryEmit(if (isHost) "The Jam has ended." else "The host ended the Jam.")
                    leaveQuietly()
                    return
                }
                if (next.version != s.version || state.value?.id != next.id) set(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: JamException) {
                if (e.code in listOf(403, 404, 410)) {
                    messages.tryEmit("The Jam has ended.")
                    leaveQuietly()
                    return
                }
                fails++
                delay(minOf(10_000L, 1000L * fails))
            } catch (e: Throwable) {
                fails++
                if (fails == 3) messages.tryEmit("Can't reach the Jam. Trying again…")
                delay(minOf(10_000L, 1000L * fails))
            }
        }
    }

    /** A few quick round trips to learn the server's clock (for playing in sync). */
    private suspend fun syncClock() {
        repeat(5) {
            runCatching {
                val sent = System.currentTimeMillis()
                val o = http("GET", "$server/api/jam-time", timeout = 5000) as JsonObject
                clock(sent, System.currentTimeMillis(), (o["serverTime"] as JsonPrimitive).content.toDouble())
            }
            delay(300)
        }
    }

    private fun clock(sentMs: Long, gotMs: Long, serverTime: Double) {
        if (serverTime <= 0) return
        val rtt = (gotMs - sentMs) / 1000.0
        // only quick answers are trusted (a long poll that waited isn't a round trip)
        if (rtt > 2.0 || rtt > bestRtt * 1.5 + 0.05) return
        bestRtt = minOf(bestRtt, rtt)
        offset = serverTime - (sentMs + gotMs) / 2000.0
    }

    // ---------------------------------------------------------------- actions (anyone in the Jam)

    fun add(tracks: List<Track>, next: Boolean = false, play: Boolean = false) {
        if (tracks.isEmpty()) return
        act(buildJsonObject {
            put("action", "add")
            put("tracks", JsonArray(tracks.map(::wire)))
            put("next", next)
            put("play", play)
        }, if (tracks.size == 1) "Added to the Jam: ${tracks[0].title}" else "Added ${tracks.size} songs to the Jam")
    }

    fun remove(qid: String) = act(buildJsonObject { put("action", "remove"); put("qid", qid) })
    fun move(qid: String, to: Int) = act(buildJsonObject { put("action", "move"); put("qid", qid); put("to", to) })
    fun jump(qid: String) = act(buildJsonObject { put("action", "jump"); put("qid", qid) })
    fun next(fromQid: String? = null) = act(buildJsonObject { put("action", "next"); fromQid?.let { put("from", it) } })
    fun previous() = act(buildJsonObject { put("action", "prev") })
    fun setPlaying(play: Boolean) = act(buildJsonObject { put("action", if (play) "play" else "pause") })
    fun seek(sec: Double) = act(buildJsonObject { put("action", "seek"); put("position", sec) })

    /** The host's real position, so guests playing along stay in sync with what's actually heard. */
    fun report(qid: String, positionSec: Double, playing: Boolean) {
        if (!isHost) return
        act(buildJsonObject { put("action", "report"); put("qid", qid); put("position", positionSec); put("playing", playing) }, quiet = true)
    }

    private fun act(body: JsonObject, done: String? = null, quiet: Boolean = false) {
        val s = state.value ?: return
        val srv = server
        val tok = token
        scope.launch {
            try {
                val o = withContext(Dispatchers.IO) { http("POST", "$srv/api/jam/${s.id}", body, jamToken = tok) }
                val next = Parsing.json.decodeFromJsonElement(JamState.serializer(), o!!)
                if (state.value?.id == next.id && next.version > (state.value?.version ?: 0)) set(next)
                done?.let { messages.tryEmit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!quiet) messages.tryEmit(e.message ?: "The Jam didn't answer")
            }
        }
    }

    /** Where a guest playing along streams a Jam song from (the server fetched it ahead). */
    fun audioFor(trackId: String): StreamInfo? {
        if (!active || isHost || !playAlong.value) return null
        val s = state.value ?: return null
        val qid = (s.current?.takeIf { it.id == trackId } ?: s.queue.firstOrNull { it.id == trackId })?.qid ?: return null
        return StreamInfo("$server/api/jam/${s.id}/audio?qid=$qid&token=${URLEncoder.encode(token, "UTF-8")}", emptyMap(), "jam_${trackId}")
    }

    private fun wire(t: Track): JsonObject = buildJsonObject {
        put("id", t.id)
        put("source", t.source.name)
        put("title", t.title)
        put("artist", t.artist)
        put("durationSec", t.durationSec)
        t.thumbnail?.let { put("thumbnail", it) }
        t.url?.let { put("url", it) }
        t.matchedUrl?.let { put("matchedUrl", it) }
    }

    private fun http(
        method: String, url: String, body: JsonElement? = null, auth: String? = null, jamToken: String? = null, timeout: Int = 12_000,
    ): JsonElement? {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8_000
        c.readTimeout = timeout
        c.setRequestProperty("Accept", "application/json")
        auth?.let { c.setRequestProperty("Authorization", it) }
        jamToken?.let { c.setRequestProperty("X-Jam-Token", it) }
        try {
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText().orEmpty()
            val json = runCatching { Parsing.json.parseToJsonElement(text) }.getOrNull()
            if (code !in 200..299) {
                val msg = ((json as? JsonObject)?.get("error") as? JsonPrimitive)?.content
                throw JamException(
                    msg ?: if (code == 404) "This server doesn't know Jams yet: update it with the install command." else "The Jam server answered $code",
                    code,
                )
            }
            return json
        } finally {
            c.disconnect()
        }
    }
}
