package ch.tunio.app.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps ONE Python + yt-dlp process alive (assets/tunio_worker.py) instead of starting Python for every search,
 * link and song — that start-up alone costs 1-3 s on a phone, and the worker also keeps YouTube's player code
 * in memory. Uses the same Python, QuickJS and ffmpeg that youtubedl-android unpacked. If the worker can't start
 * on this phone, [available] turns false and Engine falls back to one yt-dlp process per request.
 */
object PyWorker {
    private const val TAG = "TunioWorker"
    private const val MARK = "\u001eTUNIO "

    private lateinit var app: Context
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, Pair<CompletableDeferred<String>, ((Float) -> Unit)?>>()
    private var ready = CompletableDeferred<Boolean>()

    /** False once the worker failed to start: then Engine uses the per-request yt-dlp processes. */
    @Volatile var available = true
        private set

    /** Set after yt-dlp updated: the worker restarts (on the new version) as soon as it's idle. */
    @Volatile var restartWhenIdle = false

    fun init(context: Context) {
        app = context.applicationContext
    }

    @Synchronized
    private fun ensureStarted() {
        if (process?.isAliveCompat() == true) return
        ready = CompletableDeferred()
        try {
            val base = File(app.noBackupFilesDir, "youtubedl-android")
            val pythonDir = File(base, "packages/python")
            val libDir = File(app.applicationInfo.nativeLibraryDir)
            val python = File(libDir, "libpython.so")
            val source = File(base, "yt-dlp/yt-dlp")
            require(python.exists() && source.exists()) { "engine not unpacked yet" }
            // Private copy, so a yt-dlp update can replace the original while this worker is still using its copy.
            val zip = File(app.filesDir, "worker-yt-dlp.zip")
            if (!zip.exists() || zip.length() != source.length() || zip.lastModified() < source.lastModified()) {
                source.copyTo(zip, overwrite = true)
            }
            val script = File(app.filesDir, "tunio_worker.py")
            app.assets.open("tunio_worker.py").use { input -> script.outputStream().use { input.copyTo(it) } }
            val cfg = buildJsonObject {
                put("ytdlp", zip.absolutePath)
                put("cachedir", File(app.filesDir, "ytdlp-cache").absolutePath)
                File(libDir, "libqjs.so").takeIf { it.exists() }?.let { put("quickjs", it.absolutePath) }
                File(libDir, "libffmpeg.so").takeIf { it.exists() }?.let { put("ffmpeg", it.absolutePath) }
            }.toString()
            val pb = ProcessBuilder(python.absolutePath, "-u", script.absolutePath, cfg)
            pb.environment().apply {
                this["LD_LIBRARY_PATH"] = listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(base, "packages/$it/usr/lib").absolutePath }
                this["SSL_CERT_FILE"] = File(pythonDir, "usr/etc/tls/cert.pem").absolutePath
                this["PATH"] = System.getenv("PATH") + ":" + libDir.absolutePath
                this["PYTHONHOME"] = File(pythonDir, "usr").absolutePath
                this["HOME"] = File(pythonDir, "usr").absolutePath
                this["TMPDIR"] = app.cacheDir.absolutePath
            }
            val p = pb.start()
            process = p
            writer = p.outputStream.bufferedWriter()
            val myReady = ready
            Thread({ readLoop(p, myReady) }, "tunio-worker-out").apply { isDaemon = true }.start()
            Thread({ p.errorStream.bufferedReader().forEachLine { Log.d(TAG, it.take(300)) } }, "tunio-worker-err")
                .apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            Log.w(TAG, "worker failed to start", e)
            available = false
            ready.complete(false)
        }
    }

    private fun readLoop(p: Process, myReady: CompletableDeferred<Boolean>) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                val i = line.indexOf(MARK)
                if (i < 0) return@forEachLine
                val o = runCatching { Parsing.json.parseToJsonElement(line.substring(i + MARK.length)) as JsonObject }.getOrNull()
                    ?: return@forEachLine
                if (o["ready"] != null) {
                    myReady.complete(true)
                    return@forEachLine
                }
                val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return@forEachLine
                val entry = pending[id] ?: return@forEachLine
                val progress = (o["p"] as? JsonPrimitive)?.doubleOrNull
                if (progress != null) {
                    entry.second?.invoke(progress.toFloat())
                    return@forEachLine
                }
                pending.remove(id)
                if ((o["ok"] as? JsonPrimitive)?.contentOrNull == "true") {
                    entry.first.complete(o["json"]?.jsonPrimitive?.contentOrNull ?: "{}")
                } else {
                    entry.first.completeExceptionally(IllegalStateException(o["error"]?.jsonPrimitive?.contentOrNull ?: "yt-dlp error"))
                }
            }
        } catch (_: Throwable) {
        } finally {
            // Worker died: fail what was waiting; the next request starts a new worker.
            if (!myReady.isCompleted) {
                available = false
                myReady.complete(false)
            }
            pending.values.forEach { it.first.completeExceptionally(IllegalStateException("yt-dlp worker stopped")) }
            pending.clear()
            synchronized(this) { if (process === p) process = null }
        }
    }

    /** Starts the worker ahead of time so the first search doesn't wait for Python to load. */
    suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) {
        if (!available) return@withContext false
        ensureStarted()
        runCatching { withTimeout(45_000) { ready.await() } }.getOrElse {
            Log.w(TAG, "worker didn't become ready in time")
            available = false
            stop()
            false
        }
    }

    /** Runs one request in the worker; returns its JSON answer. Cancelling the coroutine cancels the request. */
    suspend fun call(op: String, fields: Map<String, String>, onProgress: ((Float) -> Unit)? = null): String {
        if (!warmUp()) throw IllegalStateException("worker unavailable")
        if (restartWhenIdle && pending.isEmpty()) {
            restartWhenIdle = false
            stop()
            if (!warmUp()) throw IllegalStateException("worker unavailable")
        }
        val id = ids.incrementAndGet()
        val result = CompletableDeferred<String>()
        pending[id] = result to onProgress
        val req = buildJsonObject {
            put("id", id)
            put("op", op)
            fields.forEach { (k, v) -> put(k, v) }
        }.toString()
        withContext(Dispatchers.IO) {
            synchronized(this@PyWorker) {
                val w = writer ?: throw IllegalStateException("worker not running")
                w.write(req)
                w.newLine()
                w.flush()
            }
        }
        return try {
            result.await()
        } catch (e: CancellationException) {
            pending.remove(id)
            runCatching { send("""{"op":"cancel","target":$id}""") }
            throw e
        }
    }

    private fun send(line: String) = synchronized(this) {
        writer?.apply { write(line); newLine(); flush() }
    }

    @Synchronized
    fun stop() {
        runCatching { writer?.apply { write("""{"op":"exit"}"""); newLine(); flush() } }
        process?.destroy()
        process = null
        writer = null
    }

    private fun Process.isAliveCompat(): Boolean = try {
        exitValue(); false
    } catch (_: IllegalThreadStateException) {
        true
    }
}
