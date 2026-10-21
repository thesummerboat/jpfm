package se.jpfm.radio

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioParseTest {
    @Test
    fun srChannelsAreOrderedRikskanalerFirst() {
        val json = """{"channels":[
            {"id":209,"name":"P4 Västerbotten","channeltype":"Lokal kanal","color":"c31eaa","image":"https://x/p4vb.png","liveaudio":{"url":"https://a/209.mp3"}},
            {"id":164,"name":"P3","channeltype":"Rikskanal","color":"19a972","tagline":"Ny musik","liveaudio":{"url":"https://a/164.mp3"}},
            {"id":132,"name":"P1","channeltype":"Rikskanal","color":"31a1bd"},
            {"id":2576,"name":"SR Sápmi","channeltype":"Minoritet och språk"}
        ]}"""
        val list = RadioParse.srChannels(JSONObject(json))
        assertEquals(listOf("P1", "P3", "P4 Västerbotten", "SR Sápmi"), list.map { it.name })
        assertEquals("sr:164", list[1].id)
        assertEquals(164, list[1].srChannelId)
        assertEquals("http://sverigesradio.se/topsy/direkt/srapi/164-hi-aac-http", list[1].streamUrl)
        assertEquals("https://a/164.mp3", list[1].altUrl)
        assertEquals(0xFF19A972.toInt(), list[1].color)
        assertEquals("Ny musik", list[1].tagline)
    }

    @Test
    fun radioBrowserSkipsBrokenAndDetectsHls() {
        val json = """[
            {"stationuuid":"a","name":"Rix FM","url_resolved":"https://r/rix.aac","favicon":"https://r/logo.png","tags":"pop,hits","codec":"AAC","bitrate":96,"hls":0,"lastcheckok":1},
            {"stationuuid":"b","name":"Död","url_resolved":"https://d","lastcheckok":0},
            {"stationuuid":"c","name":"Live","url":"https://l/live.m3u8","favicon":"","hls":0}
        ]"""
        val list = RadioParse.radioBrowser(JSONArray(json))
        assertEquals(listOf("rb:a", "rb:c"), list.map { it.id })
        assertEquals("pop · hits", list[0].tagline)
        assertEquals(96, list[0].bitrate)
        assertTrue(list[1].hls)
        assertNull(list[1].logo)
    }

    @Test
    fun srNowFromSingleChannelAndAllChannels() {
        val single = JSONObject("""{"playlist":{"song":{"title":"Låt","artist":"Artist"}}}""")
        val schedule = JSONObject("""{"channel":{"currentscheduledepisode":{"title":"Morgonpasset"}}}""")
        assertEquals(NowOnAir("Låt", "Artist", "Morgonpasset"), RadioParse.srNow(single, schedule, 164))
        val all = JSONObject("""{"channels":[{"id":132,"playlists":{}},{"id":164,"playlists":{"playlist":{"song":[{"title":"A","artist":"B"}]}}}]}""")
        assertEquals(NowOnAir("A", "B", null), RadioParse.srNow(all, null, 164))
    }

    @Test
    fun streamTitleSplitsArtistAndSong() {
        assertEquals("Kent" to "Kräm", RadioParse.splitStreamTitle("Kent - Kräm"))
        assertEquals(null to "Bara en titel", RadioParse.splitStreamTitle("Bara en titel"))
        assertEquals(null to null, RadioParse.splitStreamTitle("  "))
    }

    @Test
    fun stationJsonRoundTrip() {
        val s = RadioStation("rb:x", "Bandit", "https://b", "https://b/l.png", "rock", 0xFF112233.toInt(), true, "MP3", 128, listOf("rock"), null)
        assertEquals(s, RadioStation.fromJson(s.toJson()))
    }
}
