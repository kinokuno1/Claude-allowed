package ch.tunio.app

import ch.tunio.app.engine.LyricLine
import ch.tunio.app.engine.WordSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Invented words, only to test the formats.
class WordSyncTest {
    @Test
    fun richsyncGluesSpacesOntoWords() {
        val body = """[{"ts":10.0,"te":12.0,"l":[{"c":"made","o":0.0},{"c":" ","o":0.3},{"c":"up","o":0.5}]}]"""
        val lines = WordSync.parseRichsync(body)
        assertEquals(1, lines.size)
        assertEquals(listOf("made ", "up"), lines[0].words.map { it.text })
        assertEquals(10.5, lines[0].words[0].end, 0.001)
        assertEquals(12.0, lines[0].words[1].end, 0.001)
    }

    @Test
    fun yrcLines() {
        val lines = WordSync.parseYrc("{\"t\":0,\"c\":[]}\n[12340,1000](12340,500,0)made (12840,400,0)up")
        assertEquals(1, lines.size)
        assertEquals(12.34, lines[0].start, 0.001)
        assertEquals(13.24, lines[0].words[1].end, 0.001)
    }

    @Test
    fun estimateSharesLineTime() {
        val lines = WordSync.estimate(listOf(LyricLine(5.0, "one two three"), LyricLine(9.0, "")), 0.0)
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].words.size)
        assertEquals(5.0, lines[0].words[0].start, 0.001)
        assertTrue(lines[0].end <= 9.0)
    }
}
