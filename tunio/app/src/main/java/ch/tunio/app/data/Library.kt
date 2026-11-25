package ch.tunio.app.data

import android.content.Context
import ch.tunio.app.engine.Artwork
import ch.tunio.app.engine.Parsing
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Liked songs, playlists and history, stored as one JSON file in the app's private storage. */
object Library {
    private lateinit var file: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val data = MutableStateFlow(LibraryData())

    @OptIn(FlowPreview::class)
    fun init(context: Context) {
        file = File(context.filesDir, "library.json")
        if (file.exists()) {
            runCatching { data.value = Parsing.json.decodeFromString(LibraryData.serializer(), file.readText()) }
        }
        scope.launch {
            data.drop(1).debounce(400).collect { save(it) }
        }
    }

    private fun save(d: LibraryData) {
        val tmp = File(file.parentFile, "library.json.tmp")
        tmp.writeText(Parsing.json.encodeToString(LibraryData.serializer(), d))
        tmp.renameTo(file)
    }

    fun track(id: String): Track? = data.value.tracks[id]

    fun tracks(ids: List<String>): List<Track> {
        val all = data.value.tracks
        return ids.mapNotNull { all[it] }
    }

    private fun LibraryData.withTracks(vararg ts: Track) = copy(tracks = tracks + ts.associateBy { it.id }.mapValues { (id, t) ->
        // Keep a Spotify match we already found.
        val old = tracks[id]
        var n = if (old?.matchedUrl != null && t.matchedUrl == null) t.copy(matchedUrl = old.matchedUrl) else t
        // Keep a cover we already looked up (a re-import would bring back the playlist's).
        if (old?.artChecked == true && !n.artChecked) n = n.copy(thumbnail = old.thumbnail ?: n.thumbnail, artChecked = true)
        n
    })

    fun remember(track: Track) = data.update { it.withTracks(track) }

    fun updateTrack(track: Track) = data.update { it.copy(tracks = it.tracks + (track.id to track)) }

    fun isLiked(id: String) = id in data.value.liked

    fun toggleLike(track: Track) = data.update {
        if (track.id in it.liked) it.copy(liked = it.liked - track.id)
        else it.withTracks(track).copy(liked = listOf(track.id) + it.liked)
    }

    fun addRecent(track: Track) = data.update {
        it.withTracks(track).copy(recent = (listOf(track.id) + (it.recent - track.id)).take(50))
    }

    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), sourceUrl: String? = null, cover: String? = null): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.ifBlank { "My playlist" }, tracks.map { it.id }.distinct(), sourceUrl, cover)
        data.update { it.withTracks(*tracks.toTypedArray()).copy(playlists = it.playlists + p) }
        fillArtwork()
        return p
    }

    fun playlist(id: String): Playlist? = data.value.playlists.find { it.id == id }

    private fun editPlaylist(id: String, f: (Playlist) -> Playlist) = data.update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) f(it) else it })
    }

    fun addToPlaylist(id: String, tracks: List<Track>) {
        data.update { it.withTracks(*tracks.toTypedArray()) }
        editPlaylist(id) { p -> p.copy(trackIds = (p.trackIds + tracks.map { it.id }).distinct()) }
        fillArtwork()
    }

    fun removeFromPlaylist(id: String, trackId: String) = editPlaylist(id) { it.copy(trackIds = it.trackIds - trackId) }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    // ---------------------------------------------------------------- covers

    /** Songs whose cover is still being looked up (shown in the Library). */
    val coversLeft = MutableStateFlow(0)
    private var artJob: Job? = null

    /**
     * Looks up each song's own cover, once per song: songs without one, and Spotify songs that only had their
     * playlist's. Runs at start (so an existing library is gone through one time) and after imports.
     */
    fun fillArtwork() {
        if (artJob?.isActive == true) { artPending = true; return }
        artJob = scope.launch {
            do {
                artPending = false
                val todo = data.value.tracks.values.filter { !it.artChecked && Artwork.needsLookup(it) }
                coversLeft.value = todo.size
                for (chunk in todo.chunked(6)) {
                    val found = chunk.map { t -> async { t.id to runCatching { Artwork.lookup(t) }.getOrNull() } }.awaitAll().toMap()
                    data.update { d ->
                        d.copy(tracks = d.tracks.mapValues { (id, t) ->
                            if (id in found) t.copy(thumbnail = found[id] ?: t.thumbnail, artChecked = true) else t
                        })
                    }
                    coversLeft.value = (coversLeft.value - chunk.size).coerceAtLeast(0)
                }
                coversLeft.value = 0
            } while (artPending)
        }
    }
    @Volatile private var artPending = false

    /** A cover didn't load (expired link…): look it up again, once. */
    fun coverFailed(id: String) {
        val t = track(id) ?: return
        if (id in retried) return
        retried += id
        val broken = t.thumbnail
        scope.launch {
            val u = runCatching { Artwork.lookup(t, broken) }.getOrNull() ?: return@launch
            data.update { d -> d.tracks[id]?.let { cur -> d.copy(tracks = d.tracks + (id to cur.copy(thumbnail = u, artChecked = true))) } ?: d }
        }
    }
    private val retried = java.util.Collections.synchronizedSet(HashSet<String>())

    fun deletePlaylist(id: String) = data.update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
}
