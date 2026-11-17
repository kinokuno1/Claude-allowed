package ch.tunio.app

import ch.tunio.app.data.Source
import ch.tunio.app.data.Track
import ch.tunio.app.data.formatDuration
import ch.tunio.app.engine.Matching
import ch.tunio.app.engine.Parsing
import ch.tunio.app.engine.Spotify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun youtubeFlatSearch() {
        val json = """
            {"_type":"playlist","id":"lofi","title":"lofi","entries":[
              {"_type":"url","ie_key":"Youtube","id":"jfKfPfyJRdk","url":"https://www.youtube.com/watch?v=jfKfPfyJRdk",
               "title":"lofi hip hop radio","duration":null,"channel":"Lofi Girl","thumbnails":[{"url":"https://i.ytimg.com/a.jpg"},{"url":"https://i.ytimg.com/b.jpg"}]},
              {"_type":"url","ie_key":"Youtube","id":"abc123","url":"https://www.youtube.com/watch?v=abc123",
               "title":"Song","duration":215.0,"channel":"Artist - Topic"}
            ]}
        """.trimIndent()
        val r = Parsing.parseResult(json)
        assertEquals(2, r.tracks.size)
        val a = r.tracks[0]
        assertEquals("yt_jfKfPfyJRdk", a.id)
        assertEquals(Source.YOUTUBE, a.source)
        assertEquals("Lofi Girl", a.artist)
        assertEquals(0, a.durationSec)
        assertEquals("https://i.ytimg.com/b.jpg", a.thumbnail)
        val b = r.tracks[1]
        assertEquals("Artist", b.artist)
        assertEquals(215, b.durationSec)
        assertEquals("https://i.ytimg.com/vi/abc123/hqdefault.jpg", b.thumbnail)
    }

    @Test
    fun soundcloudSearchEntry() {
        val json = """
            {"_type":"playlist","entries":[
              {"_type":"url","ie_key":"Soundcloud","id":"123456","url":"https://api.soundcloud.com/tracks/123456",
               "webpage_url":"https://soundcloud.com/artist/song","title":"Song","uploader":"Artist","duration":180.5,
               "thumbnail":"https://i1.sndcdn.com/x.jpg"}
            ]}
        """.trimIndent()
        val t = Parsing.parseResult(json, Source.SOUNDCLOUD).tracks.single()
        assertEquals("sc_123456", t.id)
        assertEquals(Source.SOUNDCLOUD, t.source)
        assertEquals("https://soundcloud.com/artist/song", t.url)
        assertEquals(180, t.durationSec)
    }

    @Test
    fun tiktokSingleVideoUsesTrackNameUnlessOriginalSound() {
        val withSong = """{"id":"7300","extractor_key":"TikTok","webpage_url":"https://www.tiktok.com/@u/video/7300",
            "title":"my dance #fyp","track":"Real Song","artist":"Real Artist","uploader":"u","duration":15}"""
        val t = Parsing.parseResult(withSong).tracks.single()
        assertEquals(Source.TIKTOK, t.source)
        assertEquals("Real Song", t.title)
        assertEquals("Real Artist", t.artist)

        val original = """{"id":"7301","extractor_key":"TikTok","webpage_url":"https://www.tiktok.com/@u/video/7301",
            "title":"funny clip","track":"original sound - u","uploader":"u"}"""
        assertEquals("funny clip", Parsing.parseResult(original).tracks.single().title)
    }

    @Test
    fun nestedPlaylistEntriesAreFlattened() {
        val json = """{"_type":"playlist","title":"Channel","entries":[
            {"_type":"playlist","entries":[{"id":"a1","ie_key":"Youtube","title":"A"}]},
            {"id":"b2","ie_key":"Youtube","title":"B"}]}"""
        assertEquals(listOf("A", "B"), Parsing.parseResult(json).tracks.map { it.title })
    }

    @Test
    fun linkDetection() {
        assertEquals("https://vm.tiktok.com/ZMabc/", Parsing.extractUrl("Look at this! https://vm.tiktok.com/ZMabc/"))
        assertEquals("spotify:track:4uLU6hMCjMI75M1A2tKUQC", Parsing.extractUrl("spotify:track:4uLU6hMCjMI75M1A2tKUQC"))
        assertNull(Parsing.extractUrl("daft punk"))
        assertTrue(Parsing.looksLikeUrl("youtu.be/abc"))
        assertFalse(Parsing.looksLikeUrl("daft punk around the world"))
        assertEquals(Source.SOUNDCLOUD, Parsing.sourceOf("https://on.soundcloud.com/x"))
        assertEquals(Source.TIKTOK, Parsing.sourceOf("https://vm.tiktok.com/x"))
    }

    @Test
    fun idsAreFileSafe() {
        val id = Parsing.makeId(Source.OTHER, "https://example.com/a b?c=d")
        assertTrue(id.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun durations() {
        assertEquals("3:05", formatDuration(185))
        assertEquals("1:01:01", formatDuration(3661))
        assertEquals("", formatDuration(0))
    }

    @Test
    fun spotifyLinks() {
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", Spotify.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("track" to "4uLU6hMCjMI75M1A2tKUQC", Spotify.parseLink("https://open.spotify.com/intl-fr/track/4uLU6hMCjMI75M1A2tKUQC"))
        assertEquals("album" to "1ATL5GLyefJaxhQzSPVrLX", Spotify.parseLink("spotify:album:1ATL5GLyefJaxhQzSPVrLX"))
        assertTrue(Spotify.isSpotify("https://spotify.link/abc"))
        assertNull(Spotify.parseLink("https://youtube.com/watch?v=x"))
    }

    @Test
    fun spotifyEmbedPlaylist() {
        val html = """<html><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{
            "type":"playlist","name":"Chill Mix","coverArt":{"sources":[{"url":"https://i.scdn.co/image/cover"}]},
            "trackList":[
              {"uri":"spotify:track:AAA","title":"Song One","subtitle":"Artist A, Artist B","duration":200000},
              {"uri":"spotify:track:BBB","title":"Song Two","subtitle":"Artist C","duration":180500}
            ]}}}}}}</script></html>"""
        val r = Spotify.parseEmbed(html, "playlist", "PL")!!
        assertEquals("Chill Mix", r.title)
        assertEquals("https://i.scdn.co/image/cover", r.cover)
        assertEquals(2, r.tracks.size)
        val t = r.tracks[0]
        assertEquals("sp_AAA", t.id)
        assertEquals(Source.SPOTIFY, t.source)
        assertEquals("Artist A, Artist B", t.artist)
        assertEquals(200, t.durationSec)
        assertNull(t.downloadTarget) // needs a YouTube match first
        assertEquals("Artist A, Artist B - Song One", t.searchQuery)
    }

    @Test
    fun spotifyEmbedSingleTrack() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{
            "type":"track","name":"Get Lucky","artists":[{"name":"Daft Punk"},{"name":"Pharrell Williams"}],
            "duration":369000,"visualIdentity":{"image":[{"url":"https://i.scdn.co/image/gl"}]}}}}}}}</script>"""
        val t = Spotify.parseEmbed(html, "track", "TID")!!.tracks.single()
        assertEquals("Get Lucky", t.title)
        assertEquals("Daft Punk, Pharrell Williams", t.artist)
        assertEquals(369, t.durationSec)
        assertEquals("https://i.scdn.co/image/gl", t.thumbnail)
        assertEquals("sp_TID", t.id)
    }

    @Test
    fun matchingPrefersRightDurationAndNoLive() {
        val target = Track("sp_1", Source.SPOTIFY, "Get Lucky", "Daft Punk", 369)
        val live = Track("yt_a", Source.YOUTUBE, "Daft Punk - Get Lucky (Live)", "Daft Punk", 372)
        val video = Track("yt_b", Source.YOUTUBE, "Daft Punk - Get Lucky (Official Video)", "Daft Punk", 248)
        val audio = Track("yt_c", Source.YOUTUBE, "Get Lucky", "Daft Punk", 368)
        assertEquals("yt_c", Matching.pick(listOf(live, video, audio), target)!!.id)
        assertNotNull(Matching.pick(listOf(video), target))
    }
}
