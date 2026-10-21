package se.jpfm.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import se.jpfm.plex.PlexClient.Companion.enc
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class Genre(val id: String, val title: String)

enum class AlbumSort(val key: String, val label: String) {
    TITLE("title", "Titel"),
    ARTIST("artist", "Artist"),
    ADDED("added", "Nyast tillagda"),
    YEAR("year", "Utgivningsår"),
}

/**
 * Musikbiblioteket på servern: artister, album, spellistor, radio och allt annat appen visar.
 * Svaren cachas en stund i minnet så att det går snabbt att hoppa fram och tillbaka.
 */
class Library(private val session: PlexSession) {
    private val client get() = session.client

    private data class Entry(val at: Long, val value: Any)
    private val cache = ConcurrentHashMap<String, Entry>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, ttlMs: Long, force: Boolean = false, load: suspend () -> T): T {
        val sec = session.section?.key ?: ""
        val k = "$sec|$key"
        val hit = cache[k]
        if (!force && hit != null && System.currentTimeMillis() - hit.at < ttlMs) return hit.value as T
        // Tolkningen av långa listor (tusentals artister) körs utanför huvudtråden.
        val v = withContext(Dispatchers.Default) { load() }
        cache[k] = Entry(System.currentTimeMillis(), v)
        return v
    }

    fun peekTrackDetails(ratingKey: String): TrackDetails? =
        cache["${session.section?.key ?: ""}|details:$ratingKey"]?.value as? TrackDetails

    fun invalidate() = cache.clear()

    private fun sec(): String = session.section?.key ?: throw IOException("Inget musikbibliotek valt")

    private suspend fun get(path: String) = session.call { client.get(it, path) }

    // ---------- Hem ----------

    suspend fun homeHubs(force: Boolean = false): List<Hub> = cached("home", 5 * MIN, force) {
        Parse.hubs(get("/hubs/sections/${sec()}?count=16&includeStations=1&includeMyMixes=1&includeExternalMedia=0"))
            .filter { it.items.isNotEmpty() }
    }

    // ---------- Bibliotek ----------

    suspend fun artists(force: Boolean = false): List<Artist> = cached("artists", 30 * MIN, force) {
        Parse.artists(get("/library/sections/${sec()}/all?type=8&sort=titleSort&X-Plex-Container-Start=0&X-Plex-Container-Size=50000"))
    }

    suspend fun albums(sort: AlbumSort, force: Boolean = false): List<Album> {
        val all = cached("albums", 30 * MIN, force) {
            Parse.albums(get("/library/sections/${sec()}/all?type=9&sort=titleSort&X-Plex-Container-Start=0&X-Plex-Container-Size=50000"))
        }
        return sortAlbums(all, sort)
    }

    suspend fun genres(): List<Genre> = cached("genres", 30 * MIN) {
        get("/library/sections/${sec()}/genre?type=9").container().objects("Directory").mapNotNull { d ->
            val title = d.str("title") ?: return@mapNotNull null
            val key = d.str("key") ?: return@mapNotNull null
            val id = Regex("""genre=([^&]+)""").find(key)?.groupValues?.get(1)
                ?: d.str("fastKey")?.let { Regex("""genre=([^&]+)""").find(it)?.groupValues?.get(1) }
                ?: key
            Genre(id, title)
        }
    }

    suspend fun albumsInGenre(genre: Genre): List<Album> = cached("genre:${genre.id}", 30 * MIN) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&genre=${enc(genre.id)}&sort=titleSort&X-Plex-Container-Size=5000"))
    }

    suspend fun playlists(force: Boolean = false): List<Playlist> = cached("playlists", 5 * MIN, force) {
        Parse.playlists(get("/playlists?playlistType=audio"))
    }

    suspend fun playlistTracks(playlistKey: String, force: Boolean = false): List<Track> =
        cached("playlist:$playlistKey", 5 * MIN, force) {
            Parse.tracks(get("/playlists/$playlistKey/items"))
        }

    suspend fun artistPage(artistKey: String, force: Boolean = false): ArtistPage = cached("artist:$artistKey", 10 * MIN, force) {
        coroutineScope {
            val meta = async { get("/library/metadata/$artistKey?includeStations=1&includePopularLeaves=1") }
            val albums = async { Parse.albums(get("/library/metadata/$artistKey/children?excludeAllLeaves=1")) }
            val related = async { runCatching { Parse.hubs(get("/library/metadata/$artistKey/related")) }.getOrDefault(emptyList()) }
            val m = meta.await()
            val artist = Parse.metadata(m).firstOrNull()?.let { Parse.artist(it) } ?: throw IOException("Hittade inte artisten")
            var popular = Parse.popularOf(m)
            if (popular.isEmpty()) {
                popular = runCatching {
                    Parse.tracks(
                        get("/library/sections/${sec()}/all?type=10&artist.id=$artistKey&sort=ratingCount:desc,viewCount:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=10"),
                    )
                }.getOrDefault(emptyList())
            }
            ArtistPage(
                artist = artist,
                popular = popular.take(10),
                albums = albums.await().sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { it.title }),
                station = Parse.stationOf(m),
                related = related.await().filter { h -> h.items.any { it is Artist || it is Album } },
            )
        }
    }

    suspend fun artistTracks(artistKey: String): List<Track> = cached("artistTracks:$artistKey", 10 * MIN) {
        Parse.tracks(get("/library/metadata/$artistKey/allLeaves"))
    }

    suspend fun albumPage(albumKey: String, force: Boolean = false): AlbumPage = cached("album:$albumKey", 10 * MIN, force) {
        coroutineScope {
            val meta = async { get("/library/metadata/$albumKey") }
            val tracks = async { Parse.tracks(get("/library/metadata/$albumKey/children")) }
            val album = Parse.metadata(meta.await()).firstOrNull()?.let { Parse.album(it) } ?: throw IOException("Hittade inte albumet")
            AlbumPage(album, tracks.await().sortedWith(compareBy<Track>({ it.disc ?: 1 }, { it.index ?: 0 })))
        }
    }

    suspend fun search(query: String): SearchResult = cached("search:${query.lowercase()}", 2 * MIN) {
        Parse.search(get("/hubs/search?query=${enc(query)}&sectionId=${sec()}&limit=12&includeCollections=0"))
    }

    // ---------- Det som spelas ----------

    suspend fun trackDetails(ratingKey: String): TrackDetails = cached("details:$ratingKey", 60 * MIN) {
        Parse.metadata(get("/library/metadata/$ratingKey")).firstOrNull()?.let { Parse.details(it) } ?: TrackDetails()
    }

    suspend fun track(ratingKey: String): Track? =
        Parse.metadata(get("/library/metadata/$ratingKey")).firstOrNull()?.let { Parse.track(it) }

    suspend fun lyricsText(ref: LyricsRef): String = cached("lyrics:${ref.key}", 60 * MIN) {
        session.call { client.text(it, ref.key) }
    }

    /** Låtar som låter likt (kräver Plex Pass och sonisk analys på servern). */
    suspend fun sonicallySimilar(ratingKey: String, limit: Int = 50): List<Track> = withContext(Dispatchers.Default) {
        runCatching { Parse.tracks(get("/library/metadata/$ratingKey/nearest?limit=$limit&maxDistance=0.3")) }.getOrDefault(emptyList())
    }

    suspend fun randomTracks(count: Int = 100): List<Track> = withContext(Dispatchers.Default) {
        Parse.tracks(get("/library/sections/${sec()}/all?type=10&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=$count"))
    }

    suspend fun randomTracksInGenre(genre: Genre, count: Int = 100): List<Track> = withContext(Dispatchers.Default) {
        Parse.tracks(get("/library/sections/${sec()}/all?type=10&genre=${enc(genre.id)}&sort=random&X-Plex-Container-Size=$count"))
    }

    // ---------- Spelköer på servern (radio och mixar) ----------

    suspend fun startStation(station: Station): PlayQueue = session.call { s ->
        val path = if (station.key.startsWith("/playlists/")) {
            val id = station.key.removePrefix("/playlists/").substringBefore('/')
            "/playQueues?type=audio&playlistID=$id&shuffle=0&continuous=0&own=1"
        } else {
            val uri = "server://${s.machineId}/com.plexapp.plugins.library${station.key}"
            "/playQueues?type=audio&uri=${enc(uri)}&shuffle=0&repeat=0&continuous=1&own=1&includeChapters=0&includeRelated=0"
        }
        Parse.playQueue(client.post(s, path) ?: throw IOException("Tom spelkö"))
    }

    /** Hämtar fler låtar till en radiokö, runt låten som spelas nu. */
    suspend fun morePlayQueue(id: Long, centerItemId: Long?): PlayQueue {
        val center = centerItemId?.let { "&center=$it" } ?: ""
        return Parse.playQueue(get("/playQueues/$id?own=1&window=50&includeBefore=0&includeAfter=1$center"))
    }

    // ---------- Gilla, spellistor, rapportering ----------

    suspend fun setLoved(ratingKey: String, loved: Boolean) {
        session.call { client.put(it, "/:/rate?key=$ratingKey&identifier=com.plexapp.plugins.library&rating=${if (loved) 10 else -1}") }
        cache.keys.removeAll { it.endsWith("details:$ratingKey") }
    }

    suspend fun createPlaylist(title: String, trackKeys: List<String>): Playlist? = session.call { s ->
        val uri = "server://${s.machineId}/com.plexapp.plugins.library/library/metadata/${trackKeys.joinToString(",")}"
        val res = client.post(s, "/playlists?type=audio&title=${enc(title)}&smart=0&uri=${enc(uri)}")
        cache.keys.removeAll { it.endsWith("|playlists") }
        res?.let { Parse.playlists(it).firstOrNull() }
    }

    suspend fun addToPlaylist(playlistKey: String, trackKeys: List<String>) = session.call { s ->
        val uri = "server://${s.machineId}/com.plexapp.plugins.library/library/metadata/${trackKeys.joinToString(",")}"
        client.put(s, "/playlists/$playlistKey/items?uri=${enc(uri)}")
        cache.keys.removeAll { it.endsWith("|playlists") || it.endsWith("playlist:$playlistKey") }
    }

    suspend fun deletePlaylist(playlistKey: String) = session.call { s ->
        client.delete(s, "/playlists/$playlistKey")
        cache.keys.removeAll { it.endsWith("|playlists") || it.endsWith("playlist:$playlistKey") }
    }

    /**
     * Talar om för Plex vad som spelas, så att "Spelas nu", senast spelat och antal spelningar
     * stämmer. Plex räknar låten som spelad när den spelats klart.
     */
    suspend fun timeline(track: Track, state: String, positionMs: Long, durationMs: Long) {
        val pq = track.playQueueItemId?.let { "&playQueueItemID=$it" } ?: ""
        session.call { s ->
            client.text(
                s,
                "/:/timeline?ratingKey=${track.ratingKey}&key=${enc("/library/metadata/${track.ratingKey}")}" +
                    "&identifier=com.plexapp.plugins.library&state=$state&time=$positionMs&duration=$durationMs$pq",
            )
        }
    }

    companion object {
        private const val MIN = 60_000L

        fun sortAlbums(all: List<Album>, sort: AlbumSort): List<Album> = when (sort) {
            AlbumSort.TITLE -> all.sortedBy { sortKey(it.titleSort ?: it.title) }
            AlbumSort.ARTIST -> all.sortedWith(compareBy<Album>({ sortKey(it.artist) }, { it.year ?: 0 }))
            AlbumSort.ADDED -> all.sortedByDescending { it.addedAt ?: 0 }
            AlbumSort.YEAR -> all.sortedWith(compareByDescending<Album> { it.year ?: 0 }.thenBy { sortKey(it.title) })
        }

        /** Sortering som i Plex: utan "The " i början och utan skillnad på stora och små bokstäver. */
        fun sortKey(s: String): String {
            val t = s.trim().lowercase()
            return if (t.startsWith("the ")) t.substring(4) else t
        }
    }
}
