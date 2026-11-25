package ch.tunio.app.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/** Songs streamed by the player are saved here while they play (keyed by song id), so replays are instant. */
@UnstableApi
object StreamCache {
    lateinit var cache: SimpleCache
        private set

    fun init(context: Context) {
        cache = SimpleCache(
            File(context.cacheDir, "stream"),
            LeastRecentlyUsedCacheEvictor(500L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    fun isFullyCached(key: String): Boolean {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        return length > 0 && cache.isCached(key, 0, length)
    }

    fun size(): Long = cache.cacheSpace

    fun clear() = cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
}

/** Reads local files directly and everything else through the stream cache. */
@UnstableApi
class RouterDataSource(private val local: DataSource, private val remote: DataSource) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme == "file") local else remote
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
