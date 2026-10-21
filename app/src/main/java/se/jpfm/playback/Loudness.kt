package se.jpfm.playback

import se.jpfm.Leveling
import kotlin.math.pow

/**
 * Ljudutjämning med Plex egen ljudanalys. Plex mäter hur högt varje låt och album låter och
 * anger hur mycket den behöver justeras (dB). Spelaren kan bara sänka (volym 0–1), så höga
 * låtar sänks och tysta spelas som de är – precis som ReplayGain utan förstärkning.
 */
object Loudness {
    /** Antagen justering för låtar som Plex inte har analyserat än: en typisk modern mastring. */
    const val UNKNOWN_GAIN_DB = -7.0

    fun volume(
        mode: Leveling,
        trackGainDb: Double?,
        albumGainDb: Double?,
        playingAlbumInOrder: Boolean,
    ): Float {
        val gain = when (mode) {
            Leveling.OFF -> return 1f
            Leveling.TRACK -> trackGainDb ?: albumGainDb
            Leveling.ALBUM -> albumGainDb ?: trackGainDb
            Leveling.AUTO -> if (playingAlbumInOrder) albumGainDb ?: trackGainDb else trackGainDb ?: albumGainDb
        } ?: UNKNOWN_GAIN_DB
        return dbToVolume(gain)
    }

    fun dbToVolume(db: Double): Float = 10.0.pow(db / 20.0).toFloat().coerceIn(0.05f, 1f)
}
