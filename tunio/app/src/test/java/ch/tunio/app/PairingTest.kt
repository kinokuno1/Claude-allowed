package ch.tunio.app

import ch.tunio.app.engine.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test
    fun installerQrCode() {
        assertEquals("http://192.168.0.47:8484" to "abcd-efgh-ijkl", Pairing.parse("http://192.168.0.47:8484/pair#abcd-efgh-ijkl"))
    }

    @Test
    fun httpsAddressKeepsNoPort() {
        assertEquals("https://music.example.org" to "abcd-efgh-ijkl", Pairing.parse(" https://music.example.org/pair#abcd-efgh-ijkl "))
    }

    @Test
    fun appLink() {
        assertEquals(
            "https://music.example.org" to "a b+c",
            Pairing.parse("tunio://pair?u=https%3A%2F%2Fmusic.example.org&k=a%20b%2Bc"),
        )
    }

    @Test
    fun otherCodesAreIgnored() {
        assertNull(Pairing.parse("https://example.org/pair"))
        assertNull(Pairing.parse("https://example.org/menu#abc"))
        assertNull(Pairing.parse("WIFI:S:home;T:WPA;P:secret;;"))
        assertNull(Pairing.parse("tunio://spotify-callback?code=1"))
    }
}
