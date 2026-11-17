package ch.tunio.app.data

import android.content.Context
import ch.tunio.app.engine.Parsing
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
        if (old?.matchedUrl != null && t.matchedUrl == null) t.copy(matchedUrl = old.matchedUrl) else t
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
        return p
    }

    fun playlist(id: String): Playlist? = data.value.playlists.find { it.id == id }

    private fun editPlaylist(id: String, f: (Playlist) -> Playlist) = data.update { d ->
        d.copy(playlists = d.playlists.map { if (it.id == id) f(it) else it })
    }

    fun addToPlaylist(id: String, tracks: List<Track>) {
        data.update { it.withTracks(*tracks.toTypedArray()) }
        editPlaylist(id) { p -> p.copy(trackIds = (p.trackIds + tracks.map { it.id }).distinct()) }
    }

    fun removeFromPlaylist(id: String, trackId: String) = editPlaylist(id) { it.copy(trackIds = it.trackIds - trackId) }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }

    fun deletePlaylist(id: String) = data.update { d -> d.copy(playlists = d.playlists.filterNot { it.id == id }) }
}
