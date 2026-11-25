package ch.tunio.app

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.Artwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkTest {
    @Test
    fun youtubeIdFromTrack() {
        assertEquals("abcdefghijk", Artwork.youtubeId(Track("yt_abcdefghijk", Source.YOUTUBE, "x")))
        assertEquals("abcdefghijk", Artwork.youtubeId(Track("sp_1", Source.SPOTIFY, "x", matchedUrl = "https://www.youtube.com/watch?v=abcdefghijk")))
    }

    @Test
    fun onlyLooksUpWhatNeedsIt() {
        assertTrue(Artwork.needsLookup(Track("sp_1", Source.SPOTIFY, "x", thumbnail = "https://example.org/playlist.jpg")))
        assertTrue(Artwork.needsLookup(Track("sc_1", Source.SOUNDCLOUD, "x")))
        assertFalse(Artwork.needsLookup(Track("yt_abcdefghijk", Source.YOUTUBE, "x", thumbnail = "https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg")))
    }

    @Test
    fun matchesArtistAndTitle() {
        assertTrue(Artwork.matches("Some Band", "Made Up Song", "Some Band & Friend", "Made Up Song (Remastered)"))
        assertFalse(Artwork.matches("Some Band", "Made Up Song", "Other Group", "Made Up Song"))
        assertFalse(Artwork.matches("Some Band", "Made Up Song", "Some Band", "Different Tune"))
    }
}
