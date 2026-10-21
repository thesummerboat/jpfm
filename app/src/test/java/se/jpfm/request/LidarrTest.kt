package se.jpfm.request

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LidarrTest {
    private val lookup = """
        [
          {"id":0,"title":"Housewife","foreignAlbumId":"rg-1","albumType":"Single","releaseDate":"2026-10-15T00:00:00Z",
           "monitored":false,"remoteCover":"https://coverartarchive.org/1.jpg",
           "artist":{"artistName":"Tate McRae","foreignArtistId":"ar-1","qualityProfileId":0}},
          {"id":12,"title":"So Close to What","foreignAlbumId":"rg-2","albumType":"Album","releaseDate":null,
           "monitored":true,"remoteCover":null,
           "images":[{"coverType":"disc","remoteUrl":"x"},{"coverType":"Cover","remoteUrl":"https://img/2.jpg"}],
           "statistics":{"trackFileCount":12,"trackCount":12,"totalTrackCount":14},
           "artist":{"artistName":"Tate McRae","foreignArtistId":"ar-1"}},
          {"title":"Utan id"}
        ]
    """.trimIndent()

    @Test
    fun parsesLookupResults() {
        val list = LidarrParse.albums(JSONArray(lookup))
        assertEquals(2, list.size)
        val single = list[0]
        assertEquals("Housewife", single.title)
        assertEquals("Tate McRae", single.artist)
        assertEquals("Singel", single.typeLabel)
        assertEquals(2026, single.year)
        assertEquals("https://coverartarchive.org/1.jpg", single.cover)
        assertEquals(0, single.lidarrId)
        assertFalse(single.complete)
        val album = list[1]
        assertNull(album.year)
        assertEquals("https://img/2.jpg", album.cover)
        assertEquals(12, album.lidarrId)
        assertTrue(album.complete)
    }

    @Test
    fun addBodyMonitorsOnlyTheRequestedAlbum() {
        val raw = JSONArray(lookup).getJSONObject(0)
        val cfg = LidarrConfig("http://tower:8686", "k", "/music", 2, 3)
        val body = LidarrParse.addBody(raw, cfg)
        assertTrue(body.getBoolean("monitored"))
        assertTrue(body.getJSONObject("addOptions").getBoolean("searchForNewAlbum"))
        val artist = body.getJSONObject("artist")
        assertEquals(2, artist.getInt("qualityProfileId"))
        assertEquals(3, artist.getInt("metadataProfileId"))
        assertEquals("/music", artist.getString("rootFolderPath"))
        assertEquals("none", artist.getString("monitorNewItems"))
        val opts = artist.getJSONObject("addOptions")
        assertEquals("none", opts.getString("monitor"))
        assertEquals("rg-1", opts.getJSONArray("albumsToMonitor").getString(0))
        assertFalse(opts.getBoolean("searchForMissingAlbums"))
        // Originalet ändras inte.
        assertFalse(raw.has("addOptions"))
    }

    @Test
    fun parsesQueueProgress() {
        val q = LidarrParse.queue(
            JSONArray(
                """[{"albumId":12,"size":100,"sizeleft":25,"trackedDownloadState":"downloading"},
                   {"albumId":0,"size":1,"sizeleft":0},
                   {"albumId":13,"size":0,"sizeleft":0,"status":"queued","errorMessage":null}]""",
            ),
        )
        assertEquals(2, q.size)
        assertEquals(0.75f, q[0].progress, 0.001f)
        assertEquals("downloading", q[0].state)
        assertEquals("queued", q[1].state)
        assertNull(q[1].error)
    }

    @Test
    fun readsErrorMessages() {
        assertEquals("This album has already been added", LidarrParse.error("""[{"propertyName":"ForeignAlbumId","errorMessage":"This album has already been added"}]"""))
        assertEquals("Unauthorized", LidarrParse.error("""{"message":"Unauthorized"}"""))
        assertNull(LidarrParse.error("<html>"))
    }

    @Test
    fun normalizesUrls() {
        assertEquals("http://tower:8686", LidarrConfig.normalizeUrl(" tower:8686/ "))
        assertEquals("https://lidarr.example.se", LidarrConfig.normalizeUrl("https://lidarr.example.se/"))
        assertEquals("http://100.64.1.2:8686/lidarr", LidarrConfig.normalizeUrl("100.64.1.2:8686/lidarr"))
    }

    @Test
    fun configRoundTrips() {
        val c = LidarrConfig("http://tower:8686", "abc", "/music", 1, 2)
        assertEquals(c, LidarrConfig.fromJson(c.toJson()))
        assertNull(LidarrConfig.fromJson(JSONObject("{}")))
    }

    private fun album(id: Int, files: Int, tracks: Int) = LidarrAlbum("rg", "T", "A", null, "Album", 2020, null, id, true, files, tracks, JSONObject())

    @Test
    fun statusFollowsLidarr() {
        val r = SongRequest(id = "rg", title = "T", artist = "A", lidarrId = 5, state = RequestState.SEARCHING, createdAt = 0)
        assertEquals(RequestState.FAILED, RequestStatus.next(r, null, emptyList(), 1).state)
        assertEquals(RequestState.DONE, RequestStatus.next(r, album(5, 10, 10), emptyList(), 1).state)
        val dl = RequestStatus.next(r, album(5, 0, 10), listOf(LidarrQueueItem(5, 0.4f, "downloading", null)), 1)
        assertEquals(RequestState.DOWNLOADING, dl.state)
        assertEquals(0.4f, dl.progress, 0.001f)
        assertEquals(RequestState.IMPORTING, RequestStatus.next(r, album(5, 0, 10), listOf(LidarrQueueItem(5, 1f, "importPending", null)), 1).state)
        val searching = RequestStatus.next(r, album(5, 0, 10), emptyList(), 60 * 60 * 1000L)
        assertEquals(RequestState.SEARCHING, searching.state)
        assertEquals("Inget hittat än – Lidarr fortsätter leta", searching.message)
    }

    @Test
    fun picksTheRightReleaseForASong() {
        fun a(title: String, artist: String, type: String) = LidarrAlbum(title, title, artist, null, type, 2026, null, 0, false, 0, 0, JSONObject())
        val results = listOf(
            a("Housewife (Remixes)", "Tate McRae", "EP"),
            a("Housewife", "Someone Else", "Single"),
            a("Housewife", "Tate McRae", "Single"),
        )
        val ranked = RequestMatch.rank(results, "Tate McRae", "Housewife - Single", "Housewife")
        assertEquals("Tate McRae", ranked[0].artist)
        assertEquals("Housewife", ranked[0].title)
        assertEquals("Housewife", RequestMatch.cleanAlbum("Housewife - Single"))
    }

    @Test
    fun requestJsonRoundTrips() {
        val r = SongRequest(
            id = "rg-1", title = "Housewife", artist = "Tate McRae", type = "Singel", year = 2026, cover = "c",
            song = "Housewife", album = JSONObject().put("x", 1), lidarrId = 9, state = RequestState.DOWNLOADING,
            progress = 0.5f, message = null, createdAt = 10, updatedAt = 20,
        )
        val back = SongRequest.fromJson(r.toJson())!!
        assertEquals(r.copy(album = null), back.copy(album = null))
        assertEquals(1, back.album!!.getInt("x"))
    }
}
