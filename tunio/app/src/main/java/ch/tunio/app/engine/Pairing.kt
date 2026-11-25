package ch.tunio.app.engine

import java.net.URI
import java.net.URLDecoder

/** Reads the home server's QR code. */
object Pairing {
    /**
     * Address and password from what the QR code holds: the server's pair page "<address>/pair#<password>"
     * (what the installer prints) or the app link "tunio://pair?u=<address>&k=<password>". Null for anything else.
     */
    fun parse(text: String): Pair<String, String>? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "tunio" -> {
                if (!uri.host.equals("pair", ignoreCase = true)) return null
                val q = (uri.rawQuery ?: "").split('&').mapNotNull { kv ->
                    val parts = kv.split('=', limit = 2)
                    if (parts.size == 2) decode(parts[0]) to decode(parts[1]) else null
                }.toMap()
                val u = q["u"]?.trim().orEmpty()
                val k = q["k"]?.trim().orEmpty()
                if (u.isEmpty() || k.isEmpty()) null else u to k
            }
            "http", "https" -> {
                val host = uri.host ?: return null
                val path = uri.path.orEmpty().trimEnd('/')
                if (!path.endsWith("/pair")) return null
                val password = uri.fragment?.trim().orEmpty()
                if (password.isEmpty()) return null
                val port = if (uri.port > 0) ":${uri.port}" else ""
                "${uri.scheme.lowercase()}://$host$port${path.removeSuffix("/pair")}" to password
            }
            else -> null
        }
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
