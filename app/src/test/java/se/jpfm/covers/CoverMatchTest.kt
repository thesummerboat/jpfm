package se.jpfm.covers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.jpfm.plex.Track

class CoverMatchTest {
    private fun track(album: String, albumArtist: String, trackArtist: String? = null, title: String = "Låt") =
        Track("1", title, albumArtist, trackArtist, album, "10", "20", "/library/metadata/10/thumb/1", 1000)

    @Test
    fun findsCompilations() {
        assertTrue(CoverMatch.isCompilation(track("Top 100 Hits 2015", "Various Artists", "Avicii")))
        assertTrue(CoverMatch.isCompilation(track("Whatever", "Various Artists", "Robyn")))
        // Dåligt taggad samling där varje låt fått sin egen albumartist.
        assertTrue(CoverMatch.isCompilation(track("Top 100 Hits 2015", "Avicii")))
        assertTrue(CoverMatch.isCompilation(track("NOW That's What I Call Music! 80", "Diverse")))
        assertTrue(CoverMatch.isCompilation(track("Summer Hits 2019", "Robyn", "Robyn & Kleerup")))
        // Vanliga album och artistens egna samlingar lämnas i fred.
        assertFalse(CoverMatch.isCompilation(track("True", "Avicii")))
        assertFalse(CoverMatch.isCompilation(track("ABBA Gold: Greatest Hits", "ABBA")))
    }

    @Test
    fun cleansArtistsAndTitles() {
        assertEquals("Avicii", CoverMatch.primaryArtist("Avicii feat. Aloe Blacc"))
        assertEquals("Robyn", CoverMatch.primaryArtist("Robyn & Kleerup"))
        assertEquals("Swedish House Mafia", CoverMatch.primaryArtist("Swedish House Mafia"))
        assertEquals("Wake Me Up", CoverMatch.cleanTitle("Wake Me Up (Radio Edit)"))
        assertEquals("Dancing Queen", CoverMatch.cleanTitle("Dancing Queen - Remastered 2001"))
        assertEquals("Levels", CoverMatch.cleanTitle("Levels [Original Mix]"))
        assertEquals("Hey Brother (Live)", CoverMatch.cleanTitle("Hey Brother (Live)"))
        assertEquals("He said  hi", CoverMatch.lucene("He said \"hi\""))
    }

    @Test
    fun picksOriginalReleaseGroupsOldestFirst() {
        val json = """
        {"recordings":[
          {"score":100,"title":"Wake Me Up","artist-credit":[{"name":"Avicii","artist":{"name":"Avicii"}}],
           "releases":[
             {"status":"Official","date":"2013-12-01","release-group":{"id":"rg-comp","primary-type":"Album","secondary-types":["Compilation"]}},
             {"status":"Official","date":"2013-09-13","release-group":{"id":"rg-true","primary-type":"Album"}},
             {"status":"Official","date":"2013-06-17","release-group":{"id":"rg-single","primary-type":"Single","secondary-types":[]}},
             {"status":"Bootleg","date":"2013-01-01","release-group":{"id":"rg-boot","primary-type":"Single"}},
             {"status":"Official","release-group":{"id":"rg-nodate","primary-type":"EP"}}
           ]},
          {"score":100,"title":"Wake Me Up","artist-credit":[{"name":"Someone Else"}],
           "releases":[{"status":"Official","date":"2001-01-01","release-group":{"id":"rg-other","primary-type":"Single"}}]},
          {"score":40,"title":"Wake Me Up","artist-credit":[{"name":"Avicii"}],
           "releases":[{"status":"Official","date":"2000-01-01","release-group":{"id":"rg-lowscore","primary-type":"Single"}}]}
        ]}
        """.trimIndent()
        assertEquals(listOf("rg-single", "rg-true", "rg-nodate"), CoverMatch.releaseGroups(JSONObject(json), "Avicii", "Wake Me Up"))
        assertEquals(emptyList<String>(), CoverMatch.releaseGroups(JSONObject("{}"), "A", "B"))
    }

    @Test
    fun picksAppleArtworkThatIsNotACompilation() {
        val json = """
        {"results":[
          {"artistName":"Avicii","trackName":"Wake Me Up","collectionName":"Top 100 Hits 2013","artworkUrl100":"https://a/comp/100x100bb.jpg"},
          {"artistName":"Avicii","trackName":"Wake Me Up (Radio Edit)","collectionName":"Wake Me Up - Single","artworkUrl100":"https://a/single/100x100bb.jpg"},
          {"artistName":"Avicii","trackName":"Wake Me Up","collectionName":"True","artworkUrl100":"https://a/true/100x100bb.jpg"}
        ]}
        """.trimIndent()
        assertEquals("https://a/single/600x600bb.jpg", CoverMatch.appleArtwork(JSONObject(json), "Avicii", "Wake Me Up"))
        assertNull(CoverMatch.appleArtwork(JSONObject("""{"results":[]}"""), "Avicii", "Wake Me Up"))
    }
}
