package se.jpfm.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverTest {
    @Test
    fun normalizesNamesForMatching() {
        assertEquals("kent", Library.norm("Kent"))
        assertEquals("acdc", Library.norm("AC/DC"))
        assertEquals("beatles", Library.norm("The Beatles"))
        assertEquals("håkanhellström", Library.norm("Håkan Hellström"))
        assertEquals("espresso", Library.norm("Espresso (Remastered 2024)"))
        assertEquals("song", Library.norm("Song feat. Someone"))
    }

    @Test
    fun splitsChartArtists() {
        assertEquals(listOf("Benson Boone"), Library.chartArtistNames("Benson Boone"))
        assertEquals(listOf("Lady Gaga & Bruno Mars", "Lady Gaga"), Library.chartArtistNames("Lady Gaga & Bruno Mars"))
        assertEquals(listOf("Hov1, Miriam Bryant", "Hov1"), Library.chartArtistNames("Hov1, Miriam Bryant"))
        assertEquals(listOf("Avicii feat. Aloe Blacc", "Avicii"), Library.chartArtistNames("Avicii feat. Aloe Blacc"))
    }

    @Test
    fun matchesTitlesLoosely() {
        assertTrue(Library.sameTitle("Espresso", "Espresso"))
        assertTrue(Library.sameTitle("Dancing Queen - Remastered", "Dancing Queen"))
        assertTrue(Library.sameTitle("Die With A Smile (Live)", "Die With A Smile"))
        assertFalse(Library.sameTitle("Hej", "Hejdå allihop"))
        assertFalse(Library.sameTitle("", "Något"))
    }

    @Test
    fun ranksPlaysMostFirstAndKeepsRecencyOnTies() {
        val counts = linkedMapOf("a" to 2, "b" to 5, "c" to 1, "d" to 2)
        assertEquals(listOf("b", "a", "d"), Library.rankPlays(counts, minPlays = 2, limit = 10))
        assertEquals(listOf("b"), Library.rankPlays(counts, minPlays = 2, limit = 1))
    }

    @Test
    fun ranksGenresFromMostPlayedAlbums() {
        val rock = Genre("1", "Rock")
        val pop = Genre("2", "Pop")
        val jazz = Genre("3", "Jazz")
        val played = listOf(
            Album("10", "A", "Kent", null, null, genres = listOf("Rock")),
            Album("11", "B", "Robyn", null, null, genres = listOf("Pop")),
            Album("12", "C", "Bob Hund", null, null, genres = listOf("Rock", "Okänd")),
        )
        val r = Library.rankGenres(played, listOf(rock, pop, jazz), limit = 5)
        assertEquals(listOf("Rock", "Pop"), r.map { it.genre.title })
        assertEquals(listOf("Kent", "Bob Hund"), r.first().artists)
        assertEquals("10", r.first().cover?.ratingKey)
    }

    @Test
    fun picksAndTranslatesMoods() {
        val all = listOf(Genre("5", "Melancholy"), Genre("6", "Energetic"), Genre("7", "Brooding"))
        val m = Library.pickMoods(all)
        assertEquals(listOf("Energiskt", "Melankoliskt"), m.map { it.title })
        assertEquals("album.mood=6", m.first().trackFilter)
        assertEquals("mood=6", m.first().albumFilter)
        // Inga kända stämningar: visa de som finns.
        assertEquals(listOf("Brooding"), Library.pickMoods(listOf(Genre("7", "Brooding"))).map { it.title })
    }

    @Test
    fun namesDecadesInSwedish() {
        assertEquals("90-talet", Mix.decadeName(1990))
        assertEquals("60-talet", Mix.decadeName(1960))
        assertEquals("2000-talet", Mix.decadeName(2000))
        assertEquals("2010-talet", Mix.decadeName(2010))
        assertEquals("1900-talet", Mix.decadeName(1900))
        assertEquals("album.decade=1980", Mix.decade(1980).trackFilter)
    }

    @Test
    fun genreMixUsesAlbumGenreForTracks() {
        val m = Mix.genre(Genre("42", "Hip Hop"), artists = listOf("Kent", "Laleh"))
        assertEquals("album.genre=42", m.trackFilter)
        assertEquals("genre=42", m.albumFilter)
        assertEquals("Kent, Laleh med flera", m.subtitle)
    }

    @Test
    fun filterFallbacks() {
        assertEquals(listOf("mood=5", "artist.mood=5"), Library.filterAlternatives("album.mood=5"))
        assertEquals(listOf("genre=7", "artist.genre=7"), Library.filterAlternatives("album.genre=7"))
        assertEquals(emptyList<String>(), Library.filterAlternatives("album.decade=1990"))
        assertEquals(emptyList<String>(), Library.filterAlternatives("viewCount>>=2"))
    }
}
