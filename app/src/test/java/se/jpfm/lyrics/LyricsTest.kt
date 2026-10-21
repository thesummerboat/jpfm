package se.jpfm.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsTest {
    @Test
    fun parsesLrcWithMetadataAndFractions() {
        val l = Lyrics.parse(
            """
            [ar:Kent]
            [ti:Musik non stop]
            [00:01.5]Första
            [00:12.34]Andra raden
            [01:02.345]Tredje
            """.trimIndent(),
        )
        assertTrue(l.synced)
        assertEquals(listOf(1500L, 12340L, 62345L), l.lines.map { it.timeMs })
        assertEquals("Andra raden", l.lines[1].text)
    }

    @Test
    fun repeatedTimestampsBecomeSeparateLines() {
        val l = Lyrics.parse("[00:10.00][00:30.00]Refräng\n[00:20.00]Vers")
        assertEquals(listOf("Refräng", "Vers", "Refräng"), l.lines.map { it.text })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), l.lines.map { it.timeMs })
    }

    @Test
    fun offsetShiftsLinesEarlier() {
        val l = Lyrics.parse("[offset:500]\n[00:02.00]Hej")
        assertEquals(1500L, l.lines.single().timeMs)
    }

    @Test
    fun plainTextIsNotSynced() {
        val l = Lyrics.parse("\nRad ett\nRad två\n\n")
        assertFalse(l.synced)
        assertEquals(listOf("Rad ett", "Rad två"), l.lines.map { it.text })
        assertNull(l.lines.first().timeMs)
        assertEquals(-1, l.indexAt(5000))
    }

    @Test
    fun indexAtFindsCurrentLine() {
        val l = Lyrics.parse("[00:01.00]A\n[00:05.00]B\n[00:09.00]C")
        assertEquals(-1, l.indexAt(500))
        assertEquals(0, l.indexAt(1000))
        assertEquals(1, l.indexAt(8999))
        assertEquals(2, l.indexAt(120_000))
    }

    @Test
    fun parsesPlexJsonLines() {
        val json = """{"MediaContainer":{"size":1,"Lyrics":[{"Line":[
            {"startOffset":1200,"endOffset":3000,"Span":[{"text":"Hej "},{"text":"du"}]},
            {"startOffset":3000,"Span":[{"text":"Andra"}]}
        ]}]}}"""
        val l = Lyrics.parse(json)
        assertTrue(l.synced)
        assertEquals("Hej du", l.lines[0].text)
        assertEquals(3000L, l.lines[1].timeMs)
    }

    @Test
    fun parsesPlexXmlLines() {
        val xml = """<MediaContainer><Lyrics><Line startOffset="100"><Span text="Ett &amp; två"/></Line><Line startOffset="900"><Span text="Tre"/></Line></Lyrics></MediaContainer>"""
        val l = Lyrics.parse(xml)
        assertEquals(listOf("Ett & två", "Tre"), l.lines.map { it.text })
        assertEquals(listOf(100L, 900L), l.lines.map { it.timeMs })
    }
}
