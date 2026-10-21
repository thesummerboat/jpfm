package se.jpfm.plex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseTest {
    private val trackJson = """
        {"ratingKey":"501","key":"/library/metadata/501","type":"track","title":"Dom andra",
         "grandparentTitle":"Kent","grandparentRatingKey":"100","parentTitle":"Vapen & ammunition",
         "parentRatingKey":"200","parentThumb":"/library/metadata/200/thumb/1","index":3,"parentIndex":1,
         "duration":245000,"userRating":10,"parentYear":2002,
         "Media":[{"audioCodec":"flac","bitrate":980,"container":"flac",
           "Part":[{"key":"/library/parts/900/1/file.flac","container":"flac",
             "Stream":[{"streamType":2,"gain":-8.5,"albumGain":-7.25,"codec":"flac"},
                       {"streamType":4,"key":"/library/streams/77","format":"txt","timed":0},
                       {"streamType":4,"key":"/library/streams/78","format":"lrc","timed":1}]}]}],
         "UltraBlurColors":[{"topLeft":"112233","topRight":"445566","bottomLeft":"778899","bottomRight":"aabbcc"}]}
    """.trimIndent()

    @Test
    fun parsesTrack() {
        val t = Parse.track(JSONObject(trackJson))!!
        assertEquals("501", t.ratingKey)
        assertEquals("Kent", t.artist)
        assertNull(t.trackArtist)
        assertEquals("200", t.albumKey)
        assertEquals("100", t.artistKey)
        assertEquals("/library/parts/900/1/file.flac", t.partKey)
        assertEquals(245000L, t.durationMs)
        assertEquals(3, t.index)
        assertEquals(2002, t.year)
        assertTrue(t.isLoved)
    }

    @Test
    fun compilationTrackKeepsOwnArtist() {
        val o = JSONObject(trackJson).put("grandparentTitle", "Various Artists").put("originalTitle", "Kent")
        val t = Parse.track(o)!!
        assertEquals("Various Artists", t.artist)
        assertEquals("Kent", t.displayArtist)
    }

    @Test
    fun detailsPickTimedLyricsAndGain() {
        val d = Parse.details(JSONObject(trackJson))
        assertEquals(-8.5, d.gainDb!!, 0.0001)
        assertEquals(-7.25, d.albumGainDb!!, 0.0001)
        assertEquals("/library/streams/78", d.lyrics!!.key)
        assertTrue(d.lyrics!!.timed)
        assertEquals(0xFF112233.toInt(), d.ultraBlur!!.topLeft)
    }

    @Test
    fun hubsWithStationsAndAlbums() {
        val json = """{"MediaContainer":{"Hub":[
            {"hubIdentifier":"music.stations","context":"hub.music.stations","title":"Stationer","Metadata":[
               {"type":"playlist","radio":1,"title":"Biblioteksradio","key":"/library/sections/5/stations/1"},
               {"type":"playlist","title":"Tidsresa","key":"/library/sections/5/stations/2"}]},
            {"hubIdentifier":"music.recent.added","title":"Nyligen tillagt","Metadata":[
               {"type":"album","ratingKey":"10","title":"Ett album","parentTitle":"Artist","thumb":"/t"}]},
            {"hubIdentifier":"empty","title":"Tom","Metadata":[]}
        ]}}"""
        val hubs = Parse.hubs(JSONObject(json))
        assertEquals(2, hubs.size)
        assertTrue(hubs[0].items.all { it is Station })
        assertEquals("/library/sections/5/stations/2", (hubs[0].items[1] as Station).key)
        assertTrue(hubs[1].items.single() is Album)
    }

    @Test
    fun playQueueSelectsItem() {
        val json = """{"MediaContainer":{"playQueueID":42,"playQueueSelectedItemID":1002,"Metadata":[
            {"type":"track","ratingKey":"1","title":"A","playQueueItemID":1001},
            {"type":"track","ratingKey":"2","title":"B","playQueueItemID":1002}]}}"""
        val pq = Parse.playQueue(JSONObject(json))
        assertEquals(42L, pq.id)
        assertEquals(1, pq.selectedIndex)
        assertEquals(1002L, pq.tracks[1].playQueueItemId)
    }

    @Test
    fun artistStationAndPopular() {
        val json = """{"MediaContainer":{"Metadata":[{"type":"artist","ratingKey":"100","title":"Kent",
            "Stations":[{"type":"playlist","radio":1,"title":"Kent-radio","key":"/library/metadata/100/station/abc?type=10"}],
            "PopularLeaves":{"Metadata":[{"type":"track","ratingKey":"7","title":"747"}]}}]}}"""
        val root = JSONObject(json)
        val st = Parse.stationOf(root)
        assertNotNull(st)
        assertEquals("/library/metadata/100/station/abc?type=10", st!!.key)
        assertEquals(listOf("747"), Parse.popularOf(root).map { it.title })
    }

    @Test
    fun searchSplitsTypes() {
        val json = """{"MediaContainer":{"Hub":[
            {"type":"artist","Metadata":[{"type":"artist","ratingKey":"1","title":"Kent"}]},
            {"type":"track","Metadata":[{"type":"track","ratingKey":"2","title":"Kräm"}]},
            {"type":"playlist","Metadata":[{"type":"playlist","ratingKey":"3","title":"Video","playlistType":"video"}]}]}}"""
        val r = Parse.search(JSONObject(json))
        assertEquals(1, r.artists.size)
        assertEquals(1, r.tracks.size)
        assertTrue(r.playlists.isEmpty())
    }

    @Test
    fun trackJsonRoundTrip() {
        val t = Parse.track(JSONObject(trackJson))!!.copy(playQueueItemId = 5)
        assertEquals(t.copy(ratingCount = null, viewCount = null), TrackJson.decode(TrackJson.encode(t)))
    }

    @Test
    fun albumSortIgnoresThe() {
        val a = listOf(
            Album("1", "The Wall", "Pink Floyd", null, null, year = 1979),
            Album("2", "Abbey Road", "The Beatles", null, null, year = 1969),
            Album("3", "Hagnesta Hill", "Kent", null, null, year = 1999),
        )
        assertEquals(listOf("2", "3", "1"), Library.sortAlbums(a, AlbumSort.TITLE).map { it.ratingKey })
        assertEquals(listOf("2", "3", "1"), Library.sortAlbums(a, AlbumSort.ARTIST).map { it.ratingKey })
        assertEquals(listOf("3", "1", "2"), Library.sortAlbums(a, AlbumSort.YEAR).map { it.ratingKey })
    }
}
