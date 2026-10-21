package se.jpfm.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import se.jpfm.plex.PlexClient.Companion.enc
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class Genre(val id: String, val title: String)

/** En låt på en topplista (artist och titel), som vi letar efter i ditt bibliotek. */
data class ChartSong(val artist: String, val title: String, val artwork: String? = null)

/** En låt från topplistan som finns hos dig, med placeringen på listan. */
data class ChartHit(val rank: Int, val track: Track)

/**
 * En mix eller kategori: låtar som matchar ett Plex-filter, spelade i slumpvis ordning och
 * påfyllda när de tar slut. [albumFilter] visar albumen i kategorin.
 */
data class Mix(
    val title: String,
    /** Filter för låtar, t.ex. "album.genre=12", "album.mood=34" eller "album.decade=1990". */
    val trackFilter: String,
    val albumFilter: String? = null,
    val subtitle: String? = null,
    val thumb: String? = null,
    /** Kort etikett på omslaget, t.ex. "Mix 1". */
    val label: String? = null,
) : java.io.Serializable {
    companion object {
        fun genre(g: Genre, thumb: String? = null, artists: List<String> = emptyList(), label: String? = null) = Mix(
            title = g.title,
            trackFilter = "album.genre=${enc(g.id)}",
            albumFilter = "genre=${enc(g.id)}",
            subtitle = if (artists.isEmpty()) null else artists.joinToString(", ") + " med flera",
            thumb = thumb,
            label = label,
        )

        fun mood(g: Genre, title: String) = Mix(
            title = title,
            trackFilter = "album.mood=${enc(g.id)}",
            albumFilter = "mood=${enc(g.id)}",
        )

        fun decade(year: Int) = Mix(
            title = decadeName(year),
            trackFilter = "album.decade=$year",
            albumFilter = "decade=$year",
        )

        /** 1990 → "90-talet", 2010 → "2010-talet". */
        fun decadeName(year: Int): String = if (year < 2000) "${year % 100}-talet" else "$year-talet"
    }
}

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

    suspend fun randomTracksInGenre(genre: Genre, count: Int = 100): List<Track> =
        randomTracksWhere("album.genre=${enc(genre.id)}", count)

    /**
     * Slumpade låtar som matchar ett Plex-filter, t.ex. "album.genre=12", "mood=34" eller
     * "album.decade=1990". Grunden för mixar som fylls på av sig själva.
     */
    suspend fun randomTracksWhere(filter: String, count: Int = 80): List<Track> = withContext(Dispatchers.Default) {
        Parse.tracks(get("/library/sections/${sec()}/all?type=10&$filter&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=$count"))
    }

    // ---------- Upptäck (hemskärmen) ----------

    /** Album som matchar ett Plex-filter, mest spelade först. */
    suspend fun albumsWhere(filter: String, force: Boolean = false): List<Album> = cached("albumsWhere:$filter", 30 * MIN, force) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&$filter&sort=viewCount:desc,titleSort&X-Plex-Container-Start=0&X-Plex-Container-Size=2000"))
    }

    /** Album du spelat senast. */
    suspend fun recentlyPlayedAlbums(force: Boolean = false): List<Album> = cached("recentAlbums", 3 * MIN, force) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&sort=lastViewedAt:desc&lastViewedAt>>=-90d&X-Plex-Container-Start=0&X-Plex-Container-Size=12"))
    }

    /** Album du spelat mest. */
    suspend fun mostPlayedAlbums(force: Boolean = false): List<Album> = cached("mostPlayedAlbums", 30 * MIN, force) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&sort=viewCount:desc&viewCount>>=0&X-Plex-Container-Start=0&X-Plex-Container-Size=80"))
    }

    /** Nya släpp: album sorterade på utgivningsdatum. */
    suspend fun newReleases(force: Boolean = false): List<Album> = cached("newReleases", 60 * MIN, force) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&sort=originallyAvailableAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=30"))
    }

    /** Album du spelade förr men inte lyssnat på på länge. */
    suspend fun rediscover(force: Boolean = false): List<Album> = cached("rediscover", 60 * MIN, force) {
        Parse.albums(get("/library/sections/${sec()}/all?type=9&viewCount>>=2&lastViewedAt<<=-120d&sort=random&X-Plex-Container-Start=0&X-Plex-Container-Size=20"))
    }

    /**
     * Det du lyssnat mest på de senaste veckorna, räknat från Plex lyssningshistorik
     * (alla dina enheter, även Plexamp).
     */
    suspend fun hotForYou(days: Int = 30, force: Boolean = false): List<Track> = cached("hot:$days", 20 * MIN, force) {
        val since = System.currentTimeMillis() / 1000 - days * 86_400L
        val mc = get("/status/sessions/history/all?sort=viewedAt:desc&librarySectionID=${sec()}&viewedAt>=$since&X-Plex-Container-Start=0&X-Plex-Container-Size=2000")
            .container()
        val counts = LinkedHashMap<String, Int>()
        for (o in mc.objects("Metadata")) {
            if (o.str("type") != "track") continue
            val k = o.str("ratingKey") ?: continue
            counts[k] = (counts[k] ?: 0) + 1
        }
        val top = rankPlays(counts, minPlays = 2, limit = 30)
        if (top.isEmpty()) return@cached emptyList<Track>()
        val order = top.withIndex().associate { it.value to it.index }
        Parse.tracks(get("/library/metadata/${top.joinToString(",")}"))
            .sortedBy { order[it.ratingKey] ?: Int.MAX_VALUE }
    }

    /**
     * Dina mixar: en per genre du lyssnar mest på (räknat från dina mest spelade album), plus
     * dina favoriter. Varje mix fylls på av sig själv.
     */
    suspend fun mixes(force: Boolean = false): List<Mix> = cached("mixes", 60 * MIN, force) {
        val genres = genres()
        val played = runCatching { mostPlayedAlbums(force) }.getOrDefault(emptyList())
        val ranked = rankGenres(played, genres, limit = 6)
        val out = mutableListOf<Mix>()
        ranked.forEachIndexed { i, r ->
            out += Mix.genre(r.genre, r.cover?.thumb, r.artists, "Mix ${i + 1}")
        }
        if (played.size >= 8) {
            out.add(
                minOf(1, out.size),
                Mix(
                    title = "Dina favoriter",
                    trackFilter = "viewCount>>=2",
                    subtitle = "Det du spelat mest, i ny ordning",
                    thumb = played.firstOrNull()?.thumb,
                    label = "Favoriter",
                ),
            )
        }
        // Lite lyssnat än: några genrer ur biblioteket i stället.
        if (out.size < 3) {
            genres.shuffled().filter { g -> out.none { it.trackFilter == Mix.genre(g).trackFilter } }.take(4 - out.size)
                .forEach { out += Mix.genre(it, null, emptyList(), "Mix") }
        }
        out
    }

    /** De genrer du lyssnar mest på först, sedan resten. */
    suspend fun genreCategories(force: Boolean = false): List<Mix> = cached("genreCats", 60 * MIN, force) {
        val genres = genres()
        val played = runCatching { mostPlayedAlbums() }.getOrDefault(emptyList())
        val ranked = rankGenres(played, genres, limit = genres.size).map { it.genre }
        (ranked + genres).distinctBy { it.id }.map { Mix.genre(it) }
    }

    /** Stämningar (Plex: energiskt, melankoliskt, fest …) med svenska namn. */
    suspend fun moods(): List<Mix> = cached("moods", 60 * MIN) {
        val all = runCatching {
            get("/library/sections/${sec()}/mood?type=9").container().objects("Directory").mapNotNull { d ->
                val title = d.str("title") ?: return@mapNotNull null
                val key = d.str("key") ?: return@mapNotNull null
                val id = Regex("""mood=([^&]+)""").find(key)?.groupValues?.get(1) ?: key.substringAfterLast('/')
                Genre(id, title)
            }
        }.getOrDefault(emptyList())
        pickMoods(all)
    }

    /** Årtionden som finns i biblioteket, nyast först. */
    suspend fun decades(): List<Mix> = cached("decades", 60 * MIN) {
        val fromServer = runCatching {
            get("/library/sections/${sec()}/decade?type=9").container().objects("Directory").mapNotNull { d ->
                (d.str("key") ?: d.str("title"))?.let { Regex("""(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            }
        }.getOrDefault(emptyList())
        val list = fromServer.ifEmpty {
            albums(AlbumSort.TITLE).mapNotNull { a -> a.year?.let { it / 10 * 10 } }.groupingBy { it }.eachCount().filter { it.value >= 3 }.keys.toList()
        }
        list.filter { it in 1900..2100 }.distinct().sortedDescending().map { Mix.decade(it) }
    }

    /** Låtar ur en topplista som finns i ditt bibliotek (samma artist och titel), med placering. */
    suspend fun matchChart(chart: List<ChartSong>): List<ChartHit> = cached("chart:${chart.hashCode()}", 60 * MIN) {
        val artists = artists().associateBy { norm(it.title) }
        val wanted = chart.mapIndexedNotNull { i, song ->
            val a = chartArtistNames(song.artist).firstNotNullOfOrNull { artists[norm(it)] } ?: return@mapIndexedNotNull null
            Triple(i + 1, song, a)
        }
        coroutineScope {
            wanted.map { (rank, song, a) ->
                async {
                    val title = song.title.substringBefore(" (").substringBefore(" - ").trim()
                    runCatching {
                        Parse.tracks(get("/library/sections/${sec()}/all?type=10&artist.id=${a.ratingKey}&title=${enc(title.take(40))}&X-Plex-Container-Start=0&X-Plex-Container-Size=5"))
                            .firstOrNull { sameTitle(it.title, title) }
                            ?.let { ChartHit(rank, it) }
                    }.getOrNull()
                }
            }.mapNotNull { it.await() }.distinctBy { it.track.ratingKey }
        }
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

        /** Gör namn jämförbara: små bokstäver, utan parenteser, "feat." och skiljetecken. */
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), "")
            .replace(Regex("""\s(feat|ft)\.?\s.*"""), "")
            .replace(Regex("""^the\s"""), "")
            .replace(Regex("""[^\p{L}\p{N}]"""), "")

        /** Samma låt, även om den ena heter "Låt (Remastered)" eller "Låt - Radio Edit". */
        fun sameTitle(a: String, b: String): Boolean {
            val x = norm(a.substringBefore(" - "))
            val y = norm(b.substringBefore(" - "))
            return x.isNotEmpty() && y.isNotEmpty() && (x == y || (x.length >= 4 && y.length >= 4 && (x.startsWith(y) || y.startsWith(x))))
        }

        /** "Artist A & Artist B", "A, B", "A x B", "A feat. B" → möjliga artistnamn. */
        fun chartArtistNames(artist: String): List<String> {
            val first = artist.split(Regex("""\s(&|x|X|feat\.?|ft\.?|med|with)\s|,""")).first().trim()
            return listOf(artist.trim(), first).distinct()
        }

        /** Mest spelade först; låtar spelade färre gånger än [minPlays] räknas inte. */
        fun rankPlays(counts: Map<String, Int>, minPlays: Int, limit: Int): List<String> =
            counts.entries.filter { it.value >= minPlays }
                .sortedByDescending { it.value } // stabil sortering: senast spelad vinner vid lika
                .take(limit)
                .map { it.key }

        data class RankedGenre(val genre: Genre, val cover: Album?, val artists: List<String>)

        /** Genrerna i dina mest spelade album, viktade efter placering. */
        fun rankGenres(played: List<Album>, genres: List<Genre>, limit: Int): List<RankedGenre> {
            val byTitle = genres.associateBy { it.title.lowercase() }
            val score = LinkedHashMap<String, Int>()
            val cover = HashMap<String, Album>()
            val artists = HashMap<String, LinkedHashSet<String>>()
            played.forEachIndexed { i, a ->
                a.genres.forEach { name ->
                    val k = name.lowercase()
                    if (k !in byTitle) return@forEach
                    score[k] = (score[k] ?: 0) + (played.size - i)
                    cover.putIfAbsent(k, a)
                    if (a.artist.isNotBlank() && !a.artist.equals("Various Artists", true)) artists.getOrPut(k) { LinkedHashSet() } += a.artist
                }
            }
            return score.entries.sortedByDescending { it.value }.take(limit).map { e ->
                RankedGenre(byTitle.getValue(e.key), cover[e.key], artists[e.key]?.take(3).orEmpty())
            }
        }

        /** Plex stämningar (på engelska) som är värda att visa, med svenska namn. */
        val MOODS: List<Pair<String, String>> = listOf(
            "energetic" to "Energiskt",
            "happy" to "Glatt",
            "party/celebratory" to "Fest",
            "summery" to "Sommar",
            "uplifting" to "Upplyftande",
            "laid-back/mellow" to "Avslappnat",
            "calm/peaceful" to "Lugnt",
            "romantic" to "Romantiskt",
            "melancholy" to "Melankoliskt",
            "nostalgic" to "Nostalgiskt",
            "dreamy" to "Drömskt",
            "sad" to "Sorgset",
            "aggressive" to "Aggressivt",
            "intense" to "Intensivt",
            "sensual" to "Sensuellt",
            "reflective" to "Eftertänksamt",
            "fun" to "Kul",
            "euphoric" to "Euforiskt",
            "rousing" to "Peppigt",
            "confident" to "Självsäkert",
        )

        fun pickMoods(all: List<Genre>): List<Mix> {
            val byTitle = all.associateBy { it.title.lowercase() }
            val picked = MOODS.mapNotNull { (en, sv) -> byTitle[en]?.let { Mix.mood(it, sv) } }
            return picked.ifEmpty { all.take(12).map { Mix.mood(it, it.title) } }
        }

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
