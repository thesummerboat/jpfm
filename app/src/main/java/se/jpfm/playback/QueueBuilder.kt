package se.jpfm.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import se.jpfm.AppGraph
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.Playlist
import se.jpfm.plex.Station
import se.jpfm.plex.Track
import se.jpfm.podcast.Episode
import se.jpfm.radio.RadioStation
import java.io.IOException

/** En färdig kö att lämna till spelaren. */
data class BuiltQueue(
    val tracks: List<Track>,
    val startIndex: Int,
    val context: QueueContext,
    val shuffle: Boolean = false,
    /** Radiokanaler och poddavsnitt (i stället för låtar från Plex). */
    val media: List<MediaItem>? = null,
    /** Var första objektet ska börja (poddavsnitt fortsätter där du slutade). */
    val startPositionMs: Long = 0L,
) {
    val isEmpty: Boolean get() = (media?.size ?: tracks.size) == 0
}

/**
 * Bygger köer: album, spellistor, artister, radio, blandat och sökningar ("Spela Kent").
 * Används både av appen och av Android Auto, så att allt beter sig likadant.
 */
class QueueBuilder(private val g: AppGraph) {
    private val library get() = g.library
    private val downloads get() = g.downloads
    /** Väntar en stund om appen just startat och fortfarande letar efter servern. */
    private suspend fun online(): Boolean = g.session.awaitSettled()

    fun mediaItems(q: BuiltQueue): List<MediaItem> {
        q.media?.let { return it }
        val quality = g.currentQuality()
        val ctxId = when (q.context.kind) {
            QueueContext.Kind.ALBUM -> q.context.id?.let { "album:$it" }
            QueueContext.Kind.PLAYLIST -> q.context.id?.let { "playlist:$it" }
            else -> null
        }
        return q.tracks.map { TrackItems.toMediaItem(it, quality, downloads.isDownloaded(it.ratingKey), ctxId) }
    }

    fun mediaItem(t: Track): MediaItem = TrackItems.toMediaItem(t, g.currentQuality(), downloads.isDownloaded(t.ratingKey))

    // ---------- Källor ----------

    suspend fun album(key: String, startTrackKey: String? = null, shuffle: Boolean = false): BuiltQueue {
        val offline = downloads.collection("album:$key")
        val (title, tracks) = if (!online() && offline != null) {
            offline.title to downloads.tracksOf(offline).filter { downloads.isDownloaded(it.ratingKey) }
        } else {
            val page = library.albumPage(key)
            page.album.title to page.tracks
        }
        return BuiltQueue(tracks, startIndex(tracks, startTrackKey, shuffle), QueueContext(QueueContext.Kind.ALBUM, title, key), shuffle)
    }

    suspend fun playlist(key: String, startTrackKey: String? = null, shuffle: Boolean = false, title: String? = null): BuiltQueue {
        val offline = downloads.collection("playlist:$key")
        val isOnline = online()
        val tracks = if (!isOnline && offline != null) {
            downloads.tracksOf(offline).filter { downloads.isDownloaded(it.ratingKey) }
        } else {
            library.playlistTracks(key)
        }
        val name = title ?: offline?.title ?: (if (isOnline) library.playlists().firstOrNull { it.ratingKey == key }?.title else null) ?: "Spellista"
        return BuiltQueue(tracks, startIndex(tracks, startTrackKey, shuffle), QueueContext(QueueContext.Kind.PLAYLIST, name, key), shuffle)
    }

    /** Alla låtar av en artist, blandade. */
    suspend fun artistShuffle(artist: Artist): BuiltQueue {
        val tracks = library.artistTracks(artist.ratingKey).shuffled()
        return BuiltQueue(tracks, 0, QueueContext(QueueContext.Kind.ARTIST, artist.title, artist.ratingKey))
    }

    /** Artistradio: Plex egen station om den finns, annars artistens låtar plus liknande. */
    suspend fun artistRadio(artistKey: String, artistTitle: String): BuiltQueue {
        val page = library.artistPage(artistKey)
        page.station?.let { st ->
            runCatching { return station(st.copy(title = "$artistTitle – radio")) }
        }
        val seed = page.popular.firstOrNull() ?: library.artistTracks(artistKey).randomOrNull()
            ?: throw IOException("Artisten har inga låtar")
        return sonicRadio(seed, "$artistTitle – radio")
    }

    suspend fun station(st: Station): BuiltQueue {
        val pq = library.startStation(st)
        if (pq.tracks.isEmpty()) throw IOException("Stationen är tom")
        return BuiltQueue(pq.tracks, pq.selectedIndex, QueueContext(QueueContext.Kind.STATION, st.title, st.key, pq.id))
    }

    /** Låtradio: låten först, sedan låtar som låter likt (Plex sonisk analys). */
    suspend fun sonicRadio(seed: Track, title: String = "${seed.title} – låtradio"): BuiltQueue {
        val similar = library.sonicallySimilar(seed.ratingKey).filter { it.ratingKey != seed.ratingKey }.shuffled().take(25)
        val rest = similar.ifEmpty { library.randomTracks(25).filter { it.ratingKey != seed.ratingKey } }
        return BuiltQueue(listOf(seed) + rest, 0, QueueContext(QueueContext.Kind.SONIC_RADIO, title, seed.ratingKey))
    }

    suspend fun shuffleLibrary(): BuiltQueue {
        if (!online()) return offline(ALL_OFFLINE, null, true)
        val tracks = library.randomTracks(60)
        return BuiltQueue(tracks, 0, QueueContext(QueueContext.Kind.SHUFFLE_LIBRARY, "Hela biblioteket blandat"))
    }

    fun offline(collectionId: String, startTrackKey: String?, shuffle: Boolean): BuiltQueue {
        val (title, tracks) = if (collectionId == ALL_OFFLINE) {
            "Nedladdat" to downloads.allTracks()
        } else {
            val c = downloads.collection(collectionId) ?: throw IOException("Hittar inte nedladdningen")
            c.title to downloads.tracksOf(c).filter { downloads.isDownloaded(it.ratingKey) }
        }
        if (tracks.isEmpty()) throw IOException("Inget nedladdat ännu")
        return BuiltQueue(tracks, startIndex(tracks, startTrackKey, shuffle), QueueContext(QueueContext.Kind.DOWNLOADS, title, collectionId), shuffle)
    }

    fun tracks(list: List<Track>, start: Int, title: String, shuffle: Boolean = false): BuiltQueue =
        BuiltQueue(list, if (shuffle) list.indices.random() else start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), QueueContext(QueueContext.Kind.TRACKS, title), shuffle)

    // ---------- Radio och poddar ----------

    /** Radiokanaler: ⏭ byter till nästa kanal i listan. */
    fun live(list: List<RadioStation>, start: Int, listId: String?, title: String = "Radio"): BuiltQueue =
        BuiltQueue(
            tracks = emptyList(),
            startIndex = start.coerceIn(0, (list.size - 1).coerceAtLeast(0)),
            context = QueueContext(QueueContext.Kind.LIVE, title, listId),
            media = list.map { ExtItems.live(it, listId) },
        )

    /** Ett poddavsnitt, som fortsätter där du slutade. */
    fun episode(e: Episode): BuiltQueue = BuiltQueue(
        tracks = emptyList(),
        startIndex = 0,
        context = QueueContext(QueueContext.Kind.PODCAST, e.podcastTitle, e.podcastId),
        media = listOf(ExtItems.episode(e)),
        startPositionMs = g.podcasts.startPosition(e),
    )

    private suspend fun stationList(listId: String): List<RadioStation> = when (listId) {
        LIST_FAVORITES -> g.radio.favorites.value
        LIST_SR -> g.radio.srChannels()
        LIST_SWEDEN -> g.radio.popularInSweden()
        else -> emptyList()
    }

    private suspend fun liveFromId(value: String): BuiltQueue? {
        val stationId = value.substringBefore('@')
        val listId = value.substringAfter('@', "").ifEmpty { null }
        val list = listId?.let { stationList(it) }.orEmpty()
        val index = list.indexOfFirst { it.id == stationId }
        if (index >= 0) return live(list, index, listId, title(listId))
        val s = g.radio.station(stationId) ?: (g.radio.srChannels() + g.radio.popularInSweden()).firstOrNull { it.id == stationId } ?: return null
        return live(listOf(s), 0, null)
    }

    private fun title(listId: String?) = when (listId) {
        LIST_FAVORITES -> "Dina kanaler"
        LIST_SR -> "Sveriges Radio"
        LIST_SWEDEN -> "Populärt i Sverige"
        else -> "Radio"
    }

    /** "Spela P3" / "Spela Rix FM": bara exakta kanalnamn, så att musiksökningar inte kapas. */
    private suspend fun stationByName(query: String): BuiltQueue? {
        fun norm(s: String) = s.lowercase().replace(Regex("[^a-zåäö0-9]"), "")
        val q = norm(query.removePrefix("radio ").removeSuffix(" radio"))
        if (q.isEmpty()) return null
        for (listId in listOf(LIST_FAVORITES, LIST_SR, LIST_SWEDEN)) {
            val list = runCatching { stationList(listId) }.getOrDefault(emptyList())
            val i = list.indexOfFirst { norm(it.name) == q || norm(it.name) == norm("sveriges radio $query") }
            if (i >= 0) return live(list, i, listId, title(listId))
        }
        return null
    }

    // ---------- Från ett id (Android Auto) ----------

    suspend fun fromMediaId(id: String): BuiltQueue? {
        val p = MediaIds.parse(id)
        return when (p.type) {
            "track" -> {
                val ctx = p.context
                when {
                    ctx == null -> single(p.value)
                    ctx.startsWith("album:") -> album(ctx.removePrefix("album:"), p.value)
                    ctx.startsWith("playlist:") -> playlist(ctx.removePrefix("playlist:"), p.value)
                    ctx.startsWith("offline:") -> offline(java.net.URLDecoder.decode(ctx.removePrefix("offline:"), "UTF-8"), p.value, false)
                    ctx == "radio" -> trackOf(p.value)?.let { sonicRadio(it) }
                    else -> single(p.value)
                }
            }
            "play-album" -> album(p.value)
            "play-playlist" -> playlist(p.value, shuffle = true)
            "play-artist" -> library.artistPage(p.value).artist.let { artistShuffle(it) }
            "play-radio" -> library.artistPage(p.value).artist.let { artistRadio(it.ratingKey, it.title) }
            "play-offline" -> offline(p.value, null, true)
            "station" -> station(Station(p.value, "Radio", null, p.value))
            "live" -> liveFromId(p.value)
            "ep" -> g.podcasts.episode("ep:${p.value}")?.let { episode(it) }
            "shuffle" -> shuffleLibrary()
            else -> null
        }
    }

    private suspend fun single(ratingKey: String): BuiltQueue? {
        val t = trackOf(ratingKey) ?: return null
        return BuiltQueue(listOf(t), 0, QueueContext(QueueContext.Kind.TRACKS, t.title))
    }

    private suspend fun trackOf(ratingKey: String): Track? =
        downloads.track(ratingKey) ?: if (online()) runCatching { library.track(ratingKey) }.getOrNull() else null

    /** Lägger till en låt eller samling i slutet av kön (Android Auto "lägg i kö"). */
    suspend fun resolveForQueue(item: MediaItem): List<MediaItem> {
        if (item.localConfiguration != null) return listOf(item)
        val q = fromMediaId(item.mediaId) ?: return emptyList()
        return mediaItems(q)
    }

    // ---------- Röst och sökning ----------

    /**
     * "Spela X på JPFM". Assistenten skickar ibland vad som efterfrågas (artist, album, låt);
     * annars gissar vi utifrån bästa träff.
     */
    suspend fun fromSearch(query: String, extras: Bundle?): BuiltQueue? {
        val q = query.trim()
        if (q.isEmpty()) return null
        stationByName(q)?.let { return it }
        g.podcasts.subscriptions.value.firstOrNull { it.title.equals(q, ignoreCase = true) }?.let { pod ->
            val latest = runCatching { g.podcasts.feed(pod) }.getOrDefault(emptyList()).firstOrNull()
            if (latest != null) return episode(latest)
        }
        val focus = extras?.getString(EXTRA_FOCUS)
        val artistHint = extras?.getString(EXTRA_ARTIST)
        val albumHint = extras?.getString(EXTRA_ALBUM)
        val titleHint = extras?.getString(EXTRA_TITLE)

        if (!online()) {
            val all = downloads.allTracks()
            val hits = all.filter { t ->
                listOf(t.title, t.artist, t.album, t.displayArtist).any { it.contains(q, ignoreCase = true) }
            }
            if (hits.isEmpty()) return null
            return tracks(hits.shuffled(), 0, "Sökning: $q")
        }

        val r = library.search(q)
        fun eq(a: String?, b: String?) = a != null && b != null && a.trim().equals(b.trim(), ignoreCase = true)

        val artist: Artist? = r.artists.firstOrNull { eq(it.title, artistHint ?: q) } ?: r.artists.firstOrNull()
        val album: Album? = r.albums.firstOrNull { eq(it.title, albumHint ?: q) } ?: r.albums.firstOrNull()
        val track: Track? = r.tracks.firstOrNull { eq(it.title, titleHint ?: q) } ?: r.tracks.firstOrNull()
        val playlist: Playlist? = r.playlists.firstOrNull { eq(it.title, q) }

        return when {
            focus == FOCUS_PLAYLIST && playlist != null -> playlist(playlist.ratingKey, shuffle = true, title = playlist.title)
            focus == FOCUS_ALBUM && album != null -> album(album.ratingKey)
            focus == FOCUS_ARTIST && artist != null -> artistRadio(artist.ratingKey, artist.title)
            focus == FOCUS_AUDIO && track != null -> sonicRadio(track)
            playlist != null -> playlist(playlist.ratingKey, shuffle = true, title = playlist.title)
            artist != null && eq(artist.title, q) -> artistRadio(artist.ratingKey, artist.title)
            album != null && eq(album.title, q) -> album(album.ratingKey)
            track != null && eq(track.title, q) -> sonicRadio(track)
            artist != null -> artistRadio(artist.ratingKey, artist.title)
            album != null -> album(album.ratingKey)
            track != null -> sonicRadio(track)
            else -> null
        }
    }

    // ---------- Radio som fylls på ----------

    /** Fler låtar till en radiokö som håller på att ta slut. */
    suspend fun more(ctx: QueueContext, existing: List<Track>): List<Track> {
        val have = existing.takeLast(400).map { it.ratingKey }.toHashSet()
        if (!online()) {
            return downloads.allTracks().filter { it.ratingKey !in have }.shuffled().take(20)
        }
        when (ctx.kind) {
            QueueContext.Kind.STATION -> {
                val id = ctx.playQueueId
                if (id != null) {
                    val known = existing.mapNotNull { it.playQueueItemId }.toHashSet()
                    val fresh = runCatching { library.morePlayQueue(id, existing.lastOrNull()?.playQueueItemId) }
                        .getOrNull()?.tracks.orEmpty()
                        .filter { it.playQueueItemId != null && it.playQueueItemId !in known }
                    if (fresh.isNotEmpty()) return fresh
                }
            }
            QueueContext.Kind.SHUFFLE_LIBRARY ->
                return library.randomTracks(60).filter { it.ratingKey !in have }
            else -> Unit
        }
        // Låtradio (och reserv för stationer): liknande låtar som de senaste.
        val seeds = existing.takeLast(3).reversed()
        for (seed in seeds) {
            val similar = library.sonicallySimilar(seed.ratingKey).filter { it.ratingKey !in have }
            if (similar.size >= 3) return similar.shuffled().take(20)
        }
        return library.randomTracks(30).filter { it.ratingKey !in have }
    }

    companion object {
        const val ALL_OFFLINE = "all"
        const val LIST_FAVORITES = "fav"
        const val LIST_SR = "sr"
        const val LIST_SWEDEN = "se"
        private const val EXTRA_FOCUS = "android.intent.extra.focus"
        private const val EXTRA_ARTIST = "android.intent.extra.artist"
        private const val EXTRA_ALBUM = "android.intent.extra.album"
        private const val EXTRA_TITLE = "android.intent.extra.title"
        private const val FOCUS_ARTIST = "vnd.android.cursor.item/artist"
        private const val FOCUS_ALBUM = "vnd.android.cursor.item/album"
        private const val FOCUS_AUDIO = "vnd.android.cursor.item/audio"
        private const val FOCUS_PLAYLIST = "vnd.android.cursor.item/playlist"

        private fun startIndex(tracks: List<Track>, key: String?, shuffle: Boolean): Int = when {
            tracks.isEmpty() -> 0
            key != null -> tracks.indexOfFirst { it.ratingKey == key }.coerceAtLeast(0)
            shuffle -> tracks.indices.random()
            else -> 0
        }

        fun contextMetadata(ctx: QueueContext): MediaMetadata =
            MediaMetadata.Builder()
                .setTitle(ctx.title)
                // MediaMetadata jämför inte innehållet i extras: utan detta fält skulle två köer
                // med samma titel (t.ex. två stationer som heter "Radio") räknas som samma.
                .setDescription("${ctx.kind}|${ctx.id}|${ctx.playQueueId}")
                .setExtras(ctx.toBundle())
                .build()
    }
}
