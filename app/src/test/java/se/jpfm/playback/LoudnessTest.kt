package se.jpfm.playback

import org.junit.Assert.assertEquals
import org.junit.Test
import se.jpfm.Leveling

class LoudnessTest {
    @Test
    fun offIsFullVolume() {
        assertEquals(1f, Loudness.volume(Leveling.OFF, -9.0, -8.0, true), 0.0001f)
    }

    @Test
    fun negativeGainLowersVolume() {
        assertEquals(0.5012f, Loudness.dbToVolume(-6.0), 0.001f)
    }

    @Test
    fun positiveGainIsCappedAtFull() {
        assertEquals(1f, Loudness.dbToVolume(4.0), 0.0001f)
    }

    @Test
    fun autoUsesAlbumGainOnlyForAlbumsInOrder() {
        val album = Loudness.volume(Leveling.AUTO, -10.0, -6.0, playingAlbumInOrder = true)
        val track = Loudness.volume(Leveling.AUTO, -10.0, -6.0, playingAlbumInOrder = false)
        assertEquals(Loudness.dbToVolume(-6.0), album, 0.0001f)
        assertEquals(Loudness.dbToVolume(-10.0), track, 0.0001f)
    }

    @Test
    fun unknownGainUsesTypicalValue() {
        assertEquals(Loudness.dbToVolume(Loudness.UNKNOWN_GAIN_DB), Loudness.volume(Leveling.TRACK, null, null, false), 0.0001f)
    }
}
