package ch.tunio.app.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import ch.tunio.app.data.StreamInfo
import ch.tunio.app.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * Your Tunio home server (a Raspberry Pi at home, see tunio-desktop/server/README.md). When it can be reached, it
 * does the searching and fetching instead of the phone and keeps every song, so songs start almost instantly.
 * When it can't be reached (server off, away from home without Tailscale), the phone does everything itself.
 */
object HomeServer {
    private const val TAG = "TunioHome"
    private const val PREFS = "homeserver"
    const val DEFAULT_PORT = 8484

    sealed interface State {
        data object NotSetUp : State
        data object Checking : State
        data class Online(val songs: Int, val bytes: Long) : State
        data object Offline : State
        data object WrongPassword : State
    }

    val state = MutableStateFlow<State>(State.NotSetUp)

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    @Volatile private var checkedAt = 0L

    var address: String = ""
        private set
    var password: String = ""
        private set

    val configured: Boolean get() = address.isNotBlank() && password.isNotBlank()

    fun init(context: Context) {
        app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, 0)
        address = p.getString("address", "").orEmpty()
        password = p.getString("password", "").orEmpty()
        if (configured) {
            state.value = State.Checking
            refresh()
        }
        // Wi-Fi <-> mobile data: check again whether the server can be reached.
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = recheckSoon()
                override fun onLost(network: Network) = recheckSoon()
            })
        }
    }

    private fun recheckSoon() {
        if (!configured) return
        checkedAt = 0L
        refresh()
    }

    /** "192.168.1.20" -> "http://192.168.1.20:8484" (also accepts names like "tunio" or full addresses). */
    fun normalize(input: String): String {
        var s = input.trim().trimEnd('/')
        if (s.isEmpty()) return ""
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        val uri = Uri.parse(s)
        val host = uri.host ?: return s
        val port = if (uri.port > 0) uri.port else DEFAULT_PORT
        return "${uri.scheme}://$host:$port"
    }

    fun configure(addressInput: String, passwordInput: String) {
        address = normalize(addressInput)
        password = passwordInput.trim()
        app.getSharedPreferences(PREFS, 0).edit().putString("address", address).putString("password", password).apply()
        checkedAt = 0L
        state.value = if (configured) State.Checking else State.NotSetUp
    }

    fun forget() = configure("", "")

    /**
     * Should the next request go to the server? Uses the last check (re-checked in the background every few
     * minutes), so it doesn't slow anything down. Only the very first check waits (at most ~2 s).
     */
    suspend fun usable(): Boolean {
        if (!configured) return false
        val s = state.value
        if (checkedAt == 0L || s == State.Checking) return check()
        val age = System.currentTimeMillis() - checkedAt
        if (age > (if (s is State.Online) 5 * 60_000L else 60_000L)) refresh()
        return state.value is State.Online
    }

    fun refresh() {
        scope.launch { check() }
    }

    /** Asks the server if it's there (and the password is right). */
    suspend fun check(force: Boolean = false): Boolean = checkLock.withLock {
        if (!configured) {
            state.value = State.NotSetUp
            return false
        }
        if (!force && checkedAt != 0L && System.currentTimeMillis() - checkedAt < 3000) return state.value is State.Online
        val result = withContext(Dispatchers.IO) { ping() }
        checkedAt = System.currentTimeMillis()
        state.value = result
        result is State.Online
    }

    private fun ping(): State = try {
        val c = open("/api/ping", connectTimeout = 1500, readTimeout = 3000)
        try {
            when (c.responseCode) {
                200 -> {
                    val o = Json.parseToJsonElement(c.inputStream.bufferedReader().readText()).jsonObject
                    State.Online(
                        songs = o["songs"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
                        bytes = o["bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    )
                }
                401 -> State.WrongPassword
                else -> State.Offline
            }
        } finally {
            c.disconnect()
        }
    } catch (e: Exception) {
        State.Offline
    }

    /** Something went wrong talking to the server: use the phone until the next check. */
    fun markDown() {
        if (state.value is State.Online) state.value = State.Offline
        checkedAt = System.currentTimeMillis()
    }

    /** For Settings: a sentence about whether it works. */
    suspend fun test(): String {
        if (!configured) return "Type the address and password first"
        check(force = true)
        return when (val s = state.value) {
            is State.Online -> "Connected: ${s.songs} songs ready on your server"
            State.WrongPassword -> "The server answered, but the password is wrong"
            else -> "Can't reach $address. Is the Pi on, and is this phone on the same Wi-Fi (or Tailscale)?"
        }
    }

    private fun open(path: String, connectTimeout: Int = 4000, readTimeout: Int = 30_000): HttpURLConnection =
        (URL(address + path).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            setRequestProperty("Authorization", "Bearer $password")
        }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * yt-dlp's listing (search, playlist, YouTube Mix) done by the server, in the same JSON the phone's own yt-dlp
     * gives. Null = do it on the phone instead.
     */
    suspend fun flat(url: String, items: String? = null): String? {
        if (!usable()) return null
        return withContext(Dispatchers.IO) {
            try {
                val c = open("/api/flat", readTimeout = 60_000)
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    val body = buildJsonObject {
                        put("url", url)
                        items?.let { put("items", it) }
                    }.toString()
                    c.outputStream.use { it.write(body.toByteArray()) }
                    when (c.responseCode) {
                        200 -> c.inputStream.bufferedReader().readText()
                        401 -> { state.value = State.WrongPassword; null }
                        else -> { Log.w(TAG, "flat failed: ${c.errorStream?.bufferedReader()?.readText()}"); null }
                    }
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "server unreachable", e)
                markDown()
                null
            }
        }
    }

    /** Where the player streams [track] from: the server sends it while fetching it (and keeps it for next time). */
    fun audio(target: String, track: Track): StreamInfo = StreamInfo(
        url = "$address/audio?url=${enc(target)}&title=${enc(track.title)}&artist=${enc(track.artist)}",
        headers = mapOf("Authorization" to "Bearer $password"),
        cacheKey = "home_${track.id}",
    )

    /** Saves [track]'s audio from the server as dir/key.ext. Null = failed, let the phone download it itself. */
    suspend fun download(target: String, track: Track, dir: File, key: String, onProgress: (Float) -> Unit): File? {
        if (!usable()) return null
        return withContext(Dispatchers.IO) {
            var part: File? = null
            try {
                val c = open("/audio?url=${enc(target)}&title=${enc(track.title)}&artist=${enc(track.artist)}", readTimeout = 90_000)
                try {
                    if (c.responseCode != 200) {
                        Log.w(TAG, "download failed ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()}")
                        if (c.responseCode == 401) state.value = State.WrongPassword
                        return@withContext null
                    }
                    val ext = c.getHeaderField("X-Tunio-Ext")?.takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "m4a"
                    val total = c.contentLengthLong
                    dir.mkdirs()
                    part = File(dir, "$key.$ext.part")
                    var done = 0L
                    c.inputStream.use { input ->
                        part!!.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (total > 0) onProgress(done * 100f / total)
                            }
                        }
                    }
                    if (total > 0 && done < total) throw IOException("connection lost")
                    val file = File(dir, "$key.$ext")
                    if (!part!!.renameTo(file)) throw IOException("couldn't save the file")
                    file
                } finally {
                    c.disconnect()
                }
            } catch (e: IOException) {
                Log.w(TAG, "download from server failed", e)
                part?.delete()
                markDown()
                null
            }
        }
    }

    /** Tells the server to fetch these songs now (top search results, songs coming up), so tapping is instant. */
    fun warm(targets: List<String>) {
        if (targets.isEmpty()) return
        scope.launch {
            if (!usable()) return@launch
            runCatching {
                val c = open("/api/warm", readTimeout = 10_000)
                try {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    val body = buildJsonObject { put("urls", JsonArray(targets.map { JsonPrimitive(it) })) }.toString()
                    c.outputStream.use { it.write(body.toByteArray()) }
                    c.responseCode
                } finally {
                    c.disconnect()
                }
            }
        }
    }

    /** Looks for a Tunio server on this Wi-Fi (the installer announces it). Returns its address or null. */
    @Suppress("DEPRECATION")
    suspend fun discover(timeoutMs: Long = 8000): String? {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                var finished = false
                fun finish(result: String?) {
                    synchronized(this@HomeServer) {
                        if (finished) return
                        finished = true
                    }
                    if (cont.isActive) cont.resume(result)
                }
                val discovery = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(null)
                    }
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onServiceFound(info: NsdServiceInfo) {
                        nsd.resolveService(info, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val host = info.host?.hostAddress ?: return
                                val h = if (host.contains(':')) "[${host.substringBefore('%')}]" else host
                                finish("http://$h:${info.port}")
                            }
                        })
                    }
                }
                nsd.discoverServices("_tunio._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
                cont.invokeOnCancellation { runCatching { nsd.stopServiceDiscovery(discovery) } }
                // Stop discovering once something was found (or on timeout, via cancellation above).
                scope.launch {
                    while (!finished && cont.isActive) kotlinx.coroutines.delay(200)
                    runCatching { nsd.stopServiceDiscovery(discovery) }
                }
            }
        }
    }
}
