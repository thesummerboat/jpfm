package se.jpfm.podcast

import org.json.JSONObject
import org.kxml2.io.KXmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PodcastParseTest {
    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd">
<channel>
  <title>P3 Dokumentär</title>
  <description><![CDATA[<p>Dokumentärer om <b>verkliga</b> händelser.</p>]]></description>
  <itunes:author>Sveriges Radio</itunes:author>
  <itunes:image href="https://img/p3dok.jpg"/>
  <image><url>https://img/other.jpg</url><title>P3 Dokumentär</title></image>
  <item>
    <title>Estonia</title>
    <guid isPermaLink="false">ep-1</guid>
    <pubDate>Sun, 18 Sep 2022 05:00:00 +0200</pubDate>
    <enclosure url="https://audio/estonia.mp3" length="123" type="audio/mpeg"/>
    <itunes:duration>1:02:03</itunes:duration>
    <description>Om &lt;b&gt;katastrofen&lt;/b&gt;.</description>
  </item>
  <item>
    <title>Gotlandsmordet</title>
    <pubDate>Sun, 25 Sep 2022 05:00:00 +0200</pubDate>
    <enclosure url="https://audio/gotland.mp3" type="audio/mpeg"/>
    <itunes:duration>3600</itunes:duration>
    <itunes:image href="https://img/gotland.jpg"/>
  </item>
  <item><title>Utan ljud</title></item>
</channel>
</rss>"""

    @Test
    fun parsesFeed() {
        val (pod, eps) = PodcastParse.rss(rss, "https://feed/p3dok", KXmlParser())
        assertEquals("P3 Dokumentär", pod.title)
        assertEquals("Sveriges Radio", pod.author)
        assertEquals("https://img/p3dok.jpg", pod.image)
        assertEquals("Dokumentärer om verkliga händelser.", pod.description)
        assertEquals(2, eps.size)
        // Nyast först.
        assertEquals(listOf("Gotlandsmordet", "Estonia"), eps.map { it.title })
        assertEquals(3_600_000L, eps[0].durationMs)
        assertEquals("https://img/gotland.jpg", eps[0].image)
        assertEquals("https://img/p3dok.jpg", eps[1].image)
        assertEquals(3_723_000L, eps[1].durationMs)
        assertEquals("Om katastrofen.", eps[1].description)
        assertEquals("P3 Dokumentär", eps[1].podcastTitle)
        assertTrue(eps.all { it.id.startsWith("ep:") && it.podcastId == pod.id })
    }

    @Test
    fun idsAreStable() {
        val a = PodcastParse.rss(rss, "https://feed/p3dok", KXmlParser()).second.map { it.id }
        val b = PodcastParse.rss(rss, "https://feed/p3dok", KXmlParser()).second.map { it.id }
        assertEquals(a, b)
    }

    @Test
    fun durations() {
        assertEquals(62_000L, PodcastParse.parseDuration("1:02"))
        assertEquals(1_500L, PodcastParse.parseDuration("1.5"))
        assertEquals(0L, PodcastParse.parseDuration("okänt"))
    }

    @Test
    fun dates() {
        assertTrue(PodcastParse.parseDate("Sun, 18 Sep 2022 05:00:00 +0200") > 0)
        assertTrue(PodcastParse.parseDate("Sun, 18 Sep 2022 05:00:00 GMT") > 0)
        assertEquals(0L, PodcastParse.parseDate("inte ett datum"))
    }

    @Test
    fun itunesSearch() {
        val json = """{"resultCount":2,"results":[
            {"collectionId":1,"collectionName":"Sommar & Vinter i P1","artistName":"Sveriges Radio","feedUrl":"https://f/sommar","artworkUrl600":"https://a/600.jpg"},
            {"collectionId":2,"collectionName":"Utan flöde"}]}"""
        val list = PodcastParse.itunes(JSONObject(json))
        assertEquals(1, list.size)
        assertEquals("Sommar & Vinter i P1", list[0].title)
        assertEquals(Podcast.idFor("https://f/sommar"), list[0].id)
    }

    @Test
    fun episodeJsonRoundTrip() {
        val e = PodcastParse.rss(rss, "https://feed/p3dok", KXmlParser()).second.first()
        assertEquals(e, Episode.fromJson(e.toJson()))
    }
}
