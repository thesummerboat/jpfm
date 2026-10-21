package se.jpfm.request

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerTest {
    @Test
    fun parsesMe() {
        val m = ServerParse.me(JSONObject("""{"id":2,"name":"Kompis","role":"member","ownerName":"Jonas","lidarr":true,"autoApprove":false,"blocked":false,"pending":0}"""))
        assertEquals("Kompis", m.name)
        assertFalse(m.isOwner)
        assertTrue(m.canRequest)
        assertFalse(m.copy(blocked = true).canRequest)
        assertFalse(m.copy(role = "denied").canRequest)
    }

    @Test
    fun parsesRequestsFromServer() {
        val json = """[
          {"id":5,"foreign_album_id":"rg-1","title":"Housewife","artist":"Tate McRae","type":"Single","year":2026,"cover":null,
           "song":"Housewife","user_id":2,"user_name":"Kompis","status":"pending","lidarr_id":0,"progress":0.0,
           "message":null,"reason":null,"created":1792600000.5,"updated":1792600000.5,"mine":false},
          {"id":6,"foreign_album_id":"rg-2","title":"X","artist":"Y","status":"downloading","progress":0.25,"user_name":"Jonas","created":1,"mine":true},
          {"id":0,"title":"utan id"}
        ]"""
        val list = ServerParse.requests(JSONArray(json))
        assertEquals(2, list.size)
        val p = list[0]
        assertEquals(RequestState.PENDING, p.state)
        assertTrue(p.state.active)
        assertNull(p.cover)
        assertEquals(2026, p.year)
        assertEquals(1792600000500L, p.createdAt)
        assertEquals(RequestState.DOWNLOADING, list[1].state)
        assertEquals(0.25f, list[1].progress, 0.001f)
        assertTrue(list[1].mine)
        assertEquals(RequestState.FAILED, RequestState.of("konstig"))
        assertFalse(RequestState.DENIED.active)
    }

    @Test
    fun parsesSearchResults() {
        val a = ServerParse.albums(
            JSONArray(
                """[{"foreignAlbumId":"rg-1","title":"Housewife","artist":"Tate McRae","type":"Single","year":2026,"cover":"c","complete":false,
                   "request":{"id":5,"foreign_album_id":"rg-1","title":"Housewife","artist":"Tate McRae","status":"searching","user_name":"Jonas","created":1,"mine":true}},
                  {"foreignAlbumId":"rg-2","title":"Gammal","artist":"Kent","type":"Album","year":null,"complete":true,"request":null},
                  {"title":"utan id"}]""",
            ),
        )
        assertEquals(2, a.size)
        assertEquals("Singel", a[0].typeLabel)
        assertEquals(RequestState.SEARCHING, a[0].request?.state)
        assertTrue(a[1].complete)
        assertNull(a[1].year)
        assertNull(a[1].request)
    }

    @Test
    fun parsesSetupAndSettings() {
        val s = ServerParse.setup(
            JSONObject(
                """{"version":"2.9.0","roots":[{"path":"/music","name":"Musik","defaultQualityProfileId":1,"defaultMetadataProfileId":2}],
                   "quality":[{"id":1,"name":"Lossless"}],"metadata":[{"id":2,"name":"Allt"}]}""",
            ),
        )
        assertEquals("2.9.0", s.version)
        assertEquals("/music", s.roots[0].path)
        assertEquals(2, s.roots[0].defaultMetadata)
        assertEquals("Lossless", s.quality[0].name)
        val cfg = ServerParse.lidarrSettings(JSONObject("""{"configured":true,"url":"http://l:8686","hasKey":true,"rootFolder":"/music","qualityProfileId":1,"metadataProfileId":2}"""))
        assertTrue(cfg.hasKey)
        assertEquals(1, cfg.qualityProfileId)
        assertFalse(ServerParse.lidarrSettings(JSONObject("""{"configured":false}""")).configured)
    }

    @Test
    fun readsFastApiErrors() {
        assertEquals("Bara ägaren kan göra det", ServerParse.detail("""{"detail":"Bara ägaren kan göra det"}"""))
        assertEquals("Field required", ServerParse.detail("""{"detail":[{"msg":"Field required"}]}"""))
        assertNull(ServerParse.detail("Internal Server Error"))
    }

    @Test
    fun normalizesServerUrl() {
        assertEquals("http://tower:8687", JpfmServerClient.normalizeUrl(" tower:8687/ "))
        assertEquals("https://jpfm.example.se", JpfmServerClient.normalizeUrl("https://jpfm.example.se/"))
    }

    @Test
    fun queuedRequestsRoundTrip() {
        val q = QueuedRequest("Tate McRae Housewife", "Tate McRae", "Housewife", 42)
        assertEquals(q, QueuedRequest.fromJson(q.toJson()))
        assertEquals(RequestState.QUEUED, q.asRemote().state)
        assertTrue(q.asRemote().mine)
    }

    @Test
    fun picksTheRightReleaseForASong() {
        fun a(title: String, artist: String, type: String) = ServerAlbum(title + artist, title, artist, type, 2026, null, false, null)
        val results = listOf(
            a("Housewife (Remixes)", "Tate McRae", "EP"),
            a("Housewife", "Someone Else", "Single"),
            a("Housewife", "Tate McRae", "Single"),
        )
        val ranked = RequestMatch.rank(results, "Tate McRae", "Housewife - Single", "Housewife")
        assertEquals("Tate McRae", ranked[0].artist)
        assertEquals("Housewife", ranked[0].title)
        assertEquals("Housewife", RequestMatch.cleanAlbum("Housewife - Single"))
        // Utan ledtrådar behålls Lidarrs ordning.
        assertEquals(results, RequestMatch.rank(results, null, null, null))
    }
}
