package ch.tunio.app.data

import kotlinx.serialization.Serializable

@Serializable
enum class Source(val label: String, val short: String) {
    YOUTUBE("YouTube", "YT"),
    SOUNDCLOUD("SoundCloud", "SC"),
    TIKTOK("TikTok", "TT"),
    SPOTIFY("Spotify", "SP"),
    OTHER("Web", "WEB"),
}

@Serializable
data class Track(
    /** Stable unique key, also used as the audio file name. e.g. "yt_dQw4w9WgXcQ". */
    val id: String,
    val source: Source,
    val title: String,
    val artist: String = "",
    val durationSec: Int = 0,
    val thumbnail: String? = null,
    /** Page URL handed to yt-dlp. For Spotify tracks this is the Spotify page (metadata only). */
    val url: String? = null,
    /** Spotify tracks: the YouTube video whose audio is used. Filled in on first play. */
    val matchedUrl: String? = null,
) {
    /** What yt-dlp should download, or null if it still has to be matched (Spotify). */
    val downloadTarget: String?
        get() = if (source == Source.SPOTIFY) matchedUrl else url

    val searchQuery: String
        get() = if (artist.isBlank()) title else "$artist - $title"
}

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val trackIds: List<String> = emptyList(),
    val sourceUrl: String? = null,
    val cover: String? = null,
)

@Serializable
data class LibraryData(
    val tracks: Map<String, Track> = emptyMap(),
    val liked: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val recent: List<String> = emptyList(),
)

/** A search result or an imported link: a list of tracks, optionally with a collection name. */
data class TrackList(val title: String?, val tracks: List<Track>, val cover: String? = null, val sourceUrl: String? = null)

fun formatDuration(sec: Int): String {
    if (sec <= 0) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
