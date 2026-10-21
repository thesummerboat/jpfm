package se.jpfm.charts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ChartParseTest {
    @Test
    fun parsesAppleMostPlayed() {
        val json = """
            {"feed":{"title":"Top Songs","country":"se","results":[
              {"artistName":"Sabrina Carpenter","id":"1","name":"Espresso","kind":"songs","artworkUrl100":"https://x/100x100bb.jpg"},
              {"artistName":"","id":"2","name":"Utan artist"},
              {"artistName":"Hov1","id":"3","name":"Barn av vår tid"}
            ]}}
        """.trimIndent()
        val list = ChartParse.apple(JSONObject(json))
        assertEquals(2, list.size)
        assertEquals("Sabrina Carpenter", list[0].artist)
        assertEquals("Espresso", list[0].title)
        assertEquals("https://x/100x100bb.jpg", list[0].artwork)
        assertEquals("Hov1", list[1].artist)
    }

    @Test
    fun emptyFeedGivesEmptyList() {
        assertEquals(0, ChartParse.apple(JSONObject("{}")).size)
    }
}
