package ch.tunio.app

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.engine.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {

    @Test
    fun parsesLrcWithRepeatedStamps() {
        // Invented lines, only to test the format.
        val lrc = """
            [ar:Someone]
            [00:12.50]first made-up line
            [00:05.00][00:30.25]a chorus that comes back
            [01:02.3]later line
        """.trimIndent()
        val lines = Lyrics.parseLrc(lrc)
        assertEquals(4, lines.size)
        assertEquals(5.0, lines[0].time, 0.001)
        assertEquals("a chorus that comes back", lines[0].text)
        assertEquals(12.5, lines[1].time, 0.001)
        assertEquals(30.25, lines[2].time, 0.001)
        assertEquals(62.3, lines[3].time, 0.001)
    }

    @Test
    fun guessesArtistAndTitleFromMessyYoutubeTitles() {
        val t = Track(id = "yt_x", source = Source.YOUTUBE, title = "Some Band - Made Up Song (Official Video) [4K]", artist = "Some Band VEVO")
        val g = Lyrics.guesses(t)
        assertEquals("Some Band" to "Made Up Song", g.first())
        assertTrue(g.any { it.first == "Some Band" && it.second == "Some Band - Made Up Song" })
    }
}
