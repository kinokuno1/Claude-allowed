package ch.tunio.app.engine

import ch.tunio.app.data.Track
import kotlin.math.abs

/** Picks the YouTube result that best matches a Spotify track (same idea as spotDL). */
object Matching {
    private val badWords = listOf("live", "cover", "karaoke", "instrumental", "remix", "sped up", "slowed", "8d", "reaction", "nightcore", "tutorial")

    fun score(candidate: Track, target: Track): Double {
        var s = 0.0
        if (target.durationSec > 0 && candidate.durationSec > 0) {
            val diff = abs(candidate.durationSec - target.durationSec)
            s += when {
                diff <= 3 -> 0.0
                diff <= 10 -> 5.0
                diff <= 30 -> 20.0
                else -> 50.0 + diff / 10.0
            }
        }
        val ct = candidate.title.lowercase()
        val tt = target.title.lowercase()
        for (w in badWords) if (w in ct && w !in tt) s += 25.0
        val words = tt.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }
        if (words.isNotEmpty()) s += 20.0 * words.count { it !in ct } / words.size
        val artist = target.artist.lowercase().split(",").first().trim()
        if (artist.isNotEmpty() && artist !in ct && artist !in candidate.artist.lowercase()) s += 10.0
        if ("audio" in ct || "topic" in candidate.artist.lowercase()) s -= 2.0
        return s
    }

    fun pick(candidates: List<Track>, target: Track): Track? = candidates.minByOrNull { score(it, target) }
}
