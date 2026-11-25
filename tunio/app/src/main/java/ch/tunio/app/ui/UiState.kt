package ch.tunio.app.ui

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.data.TrackList
import ch.tunio.app.engine.Artwork
import ch.tunio.app.engine.AudioStore
import ch.tunio.app.engine.Engine
import ch.tunio.app.engine.Parsing
import ch.tunio.app.engine.SpotifyJson
import ch.tunio.app.player.shortError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

fun isLink(text: String): Boolean =
    Parsing.extractUrl(text) != null || Parsing.looksLikeUrl(text) || SpotifyJson.trackIds(text).isNotEmpty()

sealed interface Route {
    data object Home : Route
    data object Search : Route
    data object Library : Route
    data object Liked : Route
    data object Downloads : Route
    data object Recent : Route
    data object Settings : Route
    data object SpotifyImport : Route
    data class PlaylistPage(val id: String) : Route
}

/** App-wide UI state that must survive switching tabs. */
object Ui {
    val stack = MutableStateFlow<List<Route>>(listOf(Route.Home))
    val playerOpen = MutableStateFlow(false)
    /** Tracks waiting for the "Add to playlist" dialog. */
    val addToPlaylist = MutableStateFlow<List<Track>?>(null)

    fun tab(r: Route) { stack.value = listOf(r) }
    fun push(r: Route) { stack.value = stack.value + r }
    fun back(): Boolean {
        if (playerOpen.value) { playerOpen.value = false; return true }
        if (stack.value.size > 1) { stack.value = stack.value.dropLast(1); return true }
        if (stack.value.last() != Route.Home) { stack.value = listOf(Route.Home); return true }
        return false
    }
}

object SearchModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var token = 0

    val query = MutableStateFlow("")
    val source = MutableStateFlow(Source.YOUTUBE)
    val loading = MutableStateFlow(false)
    val result = MutableStateFlow<TrackList?>(null)
    val error = MutableStateFlow<String?>(null)
    val fromLink = MutableStateFlow(false)
    /** Spotify lists only name their songs: covers still being looked up for the shown list. */
    val coversLeft = MutableStateFlow(0)

    fun submit() {
        val q = query.value.trim()
        if (q.isEmpty()) return
        job?.cancel()
        val myToken = ++token
        loading.value = true
        error.value = null
        result.value = null
        job = scope.launch {
            try {
                // Several Spotify song links pasted at once (copied from the desktop app) are kept together.
                val link = if (SpotifyJson.trackIds(q).size >= 2) q
                else Parsing.extractUrl(q) ?: q.takeIf { Parsing.looksLikeUrl(it) }?.let { "https://$it" }
                fromLink.value = link != null
                result.value = if (link != null) Engine.resolveLink(link) else TrackList(null, Engine.search(source.value, q))
                if (result.value?.tracks.isNullOrEmpty()) error.value = "Nothing found."
                // Get the top results ready to play while you're still reading them.
                result.value?.tracks?.let { AudioStore.prepareAhead(it, if (link != null) 1 else 2) }
                if (link != null) {
                    loading.value = false
                    fillCovers(myToken)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = shortError(e)
            } finally {
                if (myToken == token) loading.value = false
            }
        }
    }

    /** Shows the result of [block] (e.g. a Spotify playlist) on the Search tab, ready to play or save. */
    fun load(label: String, block: suspend () -> TrackList) {
        job?.cancel()
        val myToken = ++token
        query.value = label
        fromLink.value = true
        loading.value = true
        error.value = null
        result.value = null
        Ui.tab(Route.Search)
        job = scope.launch {
            try {
                result.value = block()
                if (result.value?.tracks.isNullOrEmpty()) error.value = "This list is empty."
                loading.value = false
                fillCovers(myToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                error.value = shortError(e)
            } finally {
                if (myToken == token) loading.value = false
            }
        }
    }

    /** Each Spotify song's own cover (instead of the playlist's), a few at a time; the list updates as they come. */
    private suspend fun fillCovers(myToken: Int) {
        val r = result.value ?: return
        val need = r.tracks.filter { it.source == Source.SPOTIFY && !it.artChecked }
        coversLeft.value = need.size
        try {
            for (chunk in need.chunked(8)) {
                val found = coroutineScope {
                    chunk.map { t -> async { t.id to runCatching { Artwork.lookup(t) }.getOrNull() } }.awaitAll().toMap()
                }
                if (myToken != token) return
                val cur = result.value ?: return
                result.value = cur.copy(tracks = cur.tracks.map { t ->
                    if (t.id in found) t.copy(thumbnail = found[t.id] ?: t.thumbnail, artChecked = true) else t
                })
                coversLeft.value = (coversLeft.value - chunk.size).coerceAtLeast(0)
            }
        } finally {
            if (myToken == token) coversLeft.value = 0
        }
    }

    fun openLink(text: String) {
        query.value = Parsing.extractUrl(text) ?: text
        Ui.tab(Route.Search)
        submit()
    }
}
