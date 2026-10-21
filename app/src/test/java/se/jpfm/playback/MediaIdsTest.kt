package se.jpfm.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {
    @Test
    fun trackWithContextRoundTrips() {
        val p = MediaIds.parse(MediaIds.track("123", "album:45"))
        assertEquals("track", p.type)
        assertEquals("123", p.value)
        assertEquals("album:45", p.context)
    }

    @Test
    fun trackWithoutContext() {
        val p = MediaIds.parse(MediaIds.track("9"))
        assertEquals("9", p.value)
        assertNull(p.context)
    }

    @Test
    fun stationKeysWithSlashesAndQueryRoundTrip() {
        val key = "/library/metadata/12855/station/8bd39616?type=10"
        val p = MediaIds.parse(MediaIds.station(key))
        assertEquals("station", p.type)
        assertEquals(key, p.value)
    }

    @Test
    fun playActions() {
        assertEquals(MediaIds.Id("play-artist", "77"), MediaIds.parse(MediaIds.shuffleArtist("77")))
        assertEquals(MediaIds.Id("play-offline", "album:5"), MediaIds.parse(MediaIds.playOffline("album:5")))
        assertEquals("shuffle", MediaIds.parse(MediaIds.SHUFFLE_ALL).type)
        assertEquals(MediaIds.Id("play-radio", "3"), MediaIds.parse("play:radio:3"))
    }

    @Test
    fun offlineCollectionsDecode() {
        val p = MediaIds.parse(MediaIds.offline("playlist:12"))
        assertEquals(MediaIds.Id("offline", "playlist:12"), p)
    }

    @Test
    fun letters() {
        assertEquals("K", MediaIds.letterOf("kent"))
        assertEquals("B", MediaIds.letterOf("The Beatles"))
        assertEquals("Å", MediaIds.letterOf("Åsa Jinder"))
        assertEquals("#", MediaIds.letterOf("2Pac"))
        assertEquals("#", MediaIds.letterOf(""))
    }

    @Test
    fun mixIdsRoundTripWithFilterAndTitle() {
        val id = MediaIds.playMix("album.genre=12", "Rock | Pop")
        val p = MediaIds.parse(id)
        assertEquals("play-mix", p.type)
        assertEquals("album.genre=12|Rock | Pop", p.value)
    }

    @Test
    fun hotListIds() {
        val p = MediaIds.parse(MediaIds.playHot("se"))
        assertEquals("play-hot", p.type)
        assertEquals("se", p.value)
    }
}
