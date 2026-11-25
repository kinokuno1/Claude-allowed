package ch.tunio.app

import ch.tunio.app.engine.Parsing
import ch.tunio.app.engine.SpotifyJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyJsonTest {
    private fun obj(s: String) = Parsing.json.parseToJsonElement(s) as JsonObject

    @Test
    fun pkceMatchesRfc7636Example() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyJson.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
        val v = SpotifyJson.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))
    }

    @Test
    fun base64UrlPadding() {
        assertEquals("", SpotifyJson.base64Url(ByteArray(0)))
        assertEquals("Zg", SpotifyJson.base64Url("f".toByteArray()))
        assertEquals("Zm8", SpotifyJson.base64Url("fo".toByteArray()))
        assertEquals("Zm9v", SpotifyJson.base64Url("foo".toByteArray()))
        assertEquals("-_8", SpotifyJson.base64Url(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
    }

    @Test
    fun trackIdsFromDesktopCopy() {
        val pasted = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC\nhttps://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b\n" +
            "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC"
        assertEquals(listOf("4uLU6hMCjMI75M1A2tKUQC", "0VjIjW4GlUZAMYd2vXMi3b"), SpotifyJson.trackIds(pasted))
        // Newlines removed by a single-line text field: links glued together still split correctly.
        val glued = "https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQChttps://open.spotify.com/track/0VjIjW4GlUZAMYd2vXMi3b"
        assertEquals(2, SpotifyJson.trackIds(glued).size)
        assertEquals(0, SpotifyJson.trackIds("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M").size)
    }

    @Test
    fun playlistPageWithTrackWrappers() {
        val playlist = obj(
            """{"name":"Secret","images":[{"url":"small","width":60},{"url":"big","width":640}],
            "tracks":{"items":[
              {"added_at":"x","track":{"type":"track","id":"AAAAAAAAAAAAAAAAAAAAAA","name":"One","duration_ms":201000,
                "artists":[{"name":"A"},{"name":"B"}],"album":{"images":[{"url":"cover1","width":300}]}}},
              {"added_at":"x","track":{"type":"episode","id":"E","name":"Podcast"}},
              {"added_at":"x","track":{"type":"track","id":null,"is_local":true,"name":"Local song","duration_ms":1000,"artists":[{"name":"Me"}],"album":{"images":[]}}},
              {"added_at":"x","track":null}
            ],"next":"https://api.spotify.com/v1/playlists/p/tracks?offset=100"}}""",
        )
        assertEquals("big", SpotifyJson.bestImage(playlist))
        val paging = SpotifyJson.innerPaging(playlist)
        assertNotNull(paging)
        val (tracks, next) = SpotifyJson.page(paging!!, "big")
        assertEquals("https://api.spotify.com/v1/playlists/p/tracks?offset=100", next)
        assertEquals(2, tracks.size)
        assertEquals("sp_AAAAAAAAAAAAAAAAAAAAAA", tracks[0].id)
        assertEquals("A, B", tracks[0].artist)
        assertEquals(201, tracks[0].durationSec)
        assertEquals("cover1", tracks[0].thumbnail)
        val local = tracks[1]
        assertEquals("Local song", local.title)
        assertNull(local.url)
        assertEquals("big", local.thumbnail)
        assertNull(local.downloadTarget) // matched on YouTube when played
    }

    @Test
    fun newerItemsNamingAndLastPage() {
        val playlist = obj("""{"name":"X","items":{"items":[{"item":{"type":"track","id":"BBBBBBBBBBBBBBBBBBBBBB","name":"Two","artists":[]}}],"next":null}}""")
        val (tracks, next) = SpotifyJson.page(SpotifyJson.innerPaging(playlist)!!)
        assertEquals("Two", tracks.single().title)
        assertNull(next)
    }

    @Test
    fun albumTracksAreBareTrackObjects() {
        val page = obj("""{"items":[{"id":"CCCCCCCCCCCCCCCCCCCCCC","name":"Three","duration_ms":60000,"artists":[{"name":"C"}]}],"next":null}""")
        val t = SpotifyJson.page(page, "albumcover").first.single()
        assertEquals("albumcover", t.thumbnail)
        assertEquals("https://open.spotify.com/track/CCCCCCCCCCCCCCCCCCCCCC", t.url)
    }
}
