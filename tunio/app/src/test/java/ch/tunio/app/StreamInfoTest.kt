package ch.tunio.app

import ch.tunio.app.engine.Parsing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamInfoTest {
    @Test
    fun youtubeAudioIsStreamable() {
        val out = """{"id":"x","format_id":"251","protocol":"https","url":"https://rr1.googlevideo.com/videoplayback?a=b",
            "http_headers":{"User-Agent":"UA","Accept":"*/*"},"ext":"webm"}"""
        val s = Parsing.streamInfo(out.replace("\n", ""))!!
        assertEquals("https://rr1.googlevideo.com/videoplayback?a=b", s.url)
        assertEquals("UA", s.headers["User-Agent"])
    }

    @Test
    fun hlsAndCookieFormatsAreNotStreamed() {
        assertNull(Parsing.streamInfo("""{"protocol":"m3u8_native","url":"https://x/playlist.m3u8"}"""))
        assertNull(Parsing.streamInfo("""{"protocol":"https","url":"https://v16.tiktokcdn.com/a","cookies":"tt_chain_token=abc"}"""))
        assertNull(Parsing.streamInfo("WARNING: no json here"))
    }

    @Test
    fun warningLinesBeforeJsonAreIgnored() {
        val out = "[youtube] something\n{\"protocol\":\"https\",\"url\":\"https://a/b\"}\n"
        assertEquals("https://a/b", Parsing.streamInfo(out)!!.url)
    }
}
