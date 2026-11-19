package ch.tunio.app

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.Parsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoplayTest {
    private fun yt(id: String, title: String, dur: Int = 200) =
        Track("yt_$id", Source.YOUTUBE, title, "A", dur, url = "https://www.youtube.com/watch?v=$id")

    @Test
    fun youtubeIdFromAnySource() {
        assertEquals("dQw4w9WgXcQ", Parsing.youtubeId(yt("dQw4w9WgXcQ", "x")))
        val spotify = Track("sp_1", Source.SPOTIFY, "Song", "Artist", matchedUrl = "https://www.youtube.com/watch?v=abcdefghijk&t=3")
        assertEquals("abcdefghijk", Parsing.youtubeId(spotify))
        assertEquals("abcdefghijk", Parsing.youtubeId(Track("x_1", Source.OTHER, "t", url = "https://youtu.be/abcdefghijk")))
        assertNull(Parsing.youtubeId(Track("sp_2", Source.SPOTIFY, "Unmatched", url = "https://open.spotify.com/track/2")))
        assertNull(Parsing.youtubeId(Track("sc_1", Source.SOUNDCLOUD, "t", url = "https://soundcloud.com/a/b")))
    }

    @Test
    fun pickSimilarSkipsQueuedLongAndDuplicateTitles() {
        val seed = yt("seed0000000", "Artist - Song")
        val queued = yt("queued00000", "Other Song")
        val mix = listOf(
            yt("seed0000000", "Artist - Song"),                        // the seed itself
            yt("dupe0000000", "Artist - Song (Official Video)"),       // same song, other upload
            yt("queued00000", "Other Song"),                           // already queued
            yt("long0000000", "1 hour lofi mix", 3600),                // too long
            yt("good1000000", "Nice Track"),
            yt("good2000000", "Another Track"),
            yt("good3000000", "Third Track"),
        )
        val picked = Parsing.pickSimilar(mix, listOf(seed, queued), max = 2)
        assertEquals(listOf("yt_good1000000", "yt_good2000000"), picked.map { it.id })
    }
}
