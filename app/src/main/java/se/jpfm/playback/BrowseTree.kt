package se.jpfm.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import se.jpfm.AppGraph
import se.jpfm.R
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.MusicItem
import se.jpfm.plex.Playlist
import se.jpfm.plex.Station
import se.jpfm.plex.Track

/**
 * Bläddringsträdet i Android Auto. Fyra flikar: Hem, Spellistor, Artister och Nedladdat.
 * Bilen visar listorna själv; vi talar bara om vad som finns och hur det ska se ut.
 */
class BrowseTree(private val g: AppGraph) {
    private val pkg = g.app.packageName
    private suspend fun online(): Boolean = g.session.awaitSettled()

    /** Senaste hemskärmen, så att "hub:3" pekar på samma rad när bilen frågar efter innehållet. */
    @Volatile
    private var hubs: List<se.jpfm.plex.Hub> = emptyList()

    fun root(): MediaItem = folder(MediaIds.ROOT, "JPFM", null, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)

    fun rootExtras(): Bundle = Bundle().apply {
        putBoolean(CONTENT_STYLE_SUPPORTED, true)
        putInt(CONTENT_STYLE_BROWSABLE, STYLE_LIST)
        putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
        // Sökfältet i bilen.
        putBoolean("android.media.browse.SEARCH_SUPPORTED", true)
    }

    suspend fun children(parentId: String, page: Int, pageSize: Int): List<MediaItem> {
        val all = childrenOf(parentId)
        val from = page * pageSize
        if (from >= all.size) return emptyList()
        return all.subList(from, (from + pageSize).coerceAtMost(all.size))
    }

    private suspend fun childrenOf(parentId: String): List<MediaItem> {
        val id = MediaIds.parse(parentId)
        return when (id.type) {
            MediaIds.ROOT -> listOf(
                folder(MediaIds.HOME, "Hem", icon(R.drawable.ic_auto_home), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, STYLE_LIST),
                folder(MediaIds.LIBRARY, "Bibliotek", icon(R.drawable.ic_auto_album), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, STYLE_LIST),
                folder(MediaIds.RADIO, "Radio", icon(R.drawable.ic_auto_radio), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, STYLE_GRID),
                folder(MediaIds.PODCASTS, "Poddar", icon(R.drawable.ic_auto_playlist), MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS, STYLE_GRID),
            )
            MediaIds.HOME -> home() + if (online() && g.downloads.collections.value.isNotEmpty()) {
                listOf(folder(MediaIds.DOWNLOADS, "Nedladdat", icon(R.drawable.ic_auto_downloads), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, STYLE_GRID, group = "Offline"))
            } else {
                emptyList()
            }
            MediaIds.LIBRARY -> listOf(
                folder(MediaIds.PLAYLISTS, "Spellistor", icon(R.drawable.ic_auto_playlist), MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS, STYLE_LIST),
                folder(MediaIds.ARTISTS, "Artister", icon(R.drawable.ic_auto_artist), MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, STYLE_LIST),
                folder(MediaIds.DOWNLOADS, "Nedladdat", icon(R.drawable.ic_auto_downloads), MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, STYLE_GRID),
            )
            MediaIds.RADIO -> radio()
            "radiolist" -> stations(id.value)
            MediaIds.PODCASTS -> podcasts()
            MediaIds.NEW_EPISODES -> runCatching { g.podcasts.latest() }.getOrDefault(emptyList()).take(40).map { episodeItem(it) }
            "pod" -> podcastEpisodes(id.value)
            "hub" -> hubs.getOrNull(id.value.toIntOrNull() ?: -1)?.items.orEmpty().mapNotNull { item(it, "Hem") }
            MediaIds.PLAYLISTS -> if (online()) g.library.playlists().map { playlistFolder(it) } else offlineOf("playlist:")
            MediaIds.ARTISTS -> artists()
            "letter" -> g.library.artists().filter { MediaIds.letterOf(it.title) == id.value }.map { artistFolder(it) }
            "artist" -> artistChildren(id.value)
            "album" -> albumChildren(id.value)
            "playlist" -> playlistChildren(id.value)
            MediaIds.DOWNLOADS -> downloads()
            "offline" -> offlineChildren(id.value)
            else -> emptyList()
        }
    }

    private suspend fun home(): List<MediaItem> {
        if (!online()) return downloads()
        val out = mutableListOf<MediaItem>()
        out += playable(MediaIds.SHUFFLE_ALL, "Blanda hela biblioteket", "Slumpvis från all din musik", icon(R.drawable.ic_auto_shuffle), MediaMetadata.MEDIA_TYPE_PLAYLIST, group = "Starta")
        val list = runCatching { g.library.homeHubs() }.getOrDefault(emptyList())
        hubs = list
        list.forEachIndexed { i, hub ->
            val stations = hub.items.filterIsInstance<Station>()
            if (stations.size == hub.items.size) {
                // Radio och mixar direkt på hemskärmen, de är det man oftast vill starta i bilen.
                stations.take(12).forEach { out += stationItem(it, group = hub.title) }
            } else {
                out += folder(MediaIds.hub(i), hub.title, hub.items.firstNotNullOfOrNull { it.thumb }?.let { art(it) }, MediaMetadata.MEDIA_TYPE_FOLDER_MIXED, STYLE_GRID, group = "Utforska")
            }
        }
        return out
    }

    private suspend fun artists(): List<MediaItem> {
        if (!online()) return offlineOf("album:")
        val all = g.library.artists()
        if (all.size <= 80) return all.map { artistFolder(it) }
        val present = all.map { MediaIds.letterOf(it.title) }.toSet()
        return MediaIds.LETTERS.filter { it in present }.map { l ->
            folder(MediaIds.letter(l), l, null, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, STYLE_LIST)
        }
    }

    private suspend fun artistChildren(key: String): List<MediaItem> {
        val page = g.library.artistPage(key)
        val out = mutableListOf<MediaItem>()
        out += playable("play:radio:$key", "${page.artist.title} – radio", "Artisten och liknande musik", icon(R.drawable.ic_auto_radio), MediaMetadata.MEDIA_TYPE_RADIO_STATION, group = "Spela")
        out += playable(MediaIds.shuffleArtist(key), "Blanda ${page.artist.title}", "Alla låtar i slumpvis ordning", icon(R.drawable.ic_auto_shuffle), MediaMetadata.MEDIA_TYPE_PLAYLIST, group = "Spela")
        page.albums.forEach { out += albumFolder(it, group = "Album") }
        return out
    }

    private suspend fun albumChildren(key: String): List<MediaItem> {
        val offline = g.downloads.collection("album:$key")
        val tracks = if (!online() && offline != null) {
            g.downloads.tracksOf(offline).filter { g.downloads.isDownloaded(it.ratingKey) }
        } else {
            g.library.albumPage(key).tracks
        }
        return tracks.map { trackItem(it, "album:$key") }
    }

    private suspend fun playlistChildren(key: String): List<MediaItem> {
        val offline = g.downloads.collection("playlist:$key")
        val tracks = if (!online() && offline != null) {
            g.downloads.tracksOf(offline).filter { g.downloads.isDownloaded(it.ratingKey) }
        } else {
            g.library.playlistTracks(key)
        }
        return listOf(playable(MediaIds.playPlaylist(key), "Blanda", "${tracks.size} låtar", icon(R.drawable.ic_auto_shuffle), MediaMetadata.MEDIA_TYPE_PLAYLIST)) +
            tracks.map { trackItem(it, "playlist:$key") }
    }

    private fun downloads(): List<MediaItem> {
        val cols = g.downloads.collections.value
        if (cols.isEmpty()) return emptyList()
        return listOf(
            playable(MediaIds.playOffline(QueueBuilder.ALL_OFFLINE), "Blanda allt nedladdat", "Fungerar utan nät", icon(R.drawable.ic_auto_shuffle), MediaMetadata.MEDIA_TYPE_PLAYLIST),
        ) + cols.map { offlineFolder(it.id, it.title, it.subtitle, it.thumb) }
    }

    private fun offlineOf(prefix: String): List<MediaItem> =
        g.downloads.collections.value.filter { it.id.startsWith(prefix) }.map { offlineFolder(it.id, it.title, it.subtitle, it.thumb) }

    private fun offlineChildren(collectionId: String): List<MediaItem> {
        val c = g.downloads.collection(collectionId) ?: return emptyList()
        return g.downloads.tracksOf(c).filter { g.downloads.isDownloaded(it.ratingKey) }
            .map { trackItem(it, "offline:" + java.net.URLEncoder.encode(collectionId, "UTF-8")) }
    }

    // ---------- Radio och poddar ----------

    private suspend fun radio(): List<MediaItem> {
        val fav = g.radio.favorites.value.map { stationItem(it, QueueBuilder.LIST_FAVORITES, "Dina kanaler") }
        return fav + listOf(
            folder("radiolist:${QueueBuilder.LIST_SR}", "Sveriges Radio", icon(R.drawable.ic_auto_radio), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, STYLE_GRID, group = "Fler kanaler"),
            folder("radiolist:${QueueBuilder.LIST_SWEDEN}", "Populärt i Sverige", icon(R.drawable.ic_auto_radio), MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, STYLE_GRID, group = "Fler kanaler"),
        )
    }

    private suspend fun stations(listId: String): List<MediaItem> {
        val list = when (listId) {
            QueueBuilder.LIST_SR -> g.radio.srChannels()
            QueueBuilder.LIST_SWEDEN -> g.radio.popularInSweden()
            else -> g.radio.favorites.value
        }
        return list.map { stationItem(it, listId, it.group) }
    }

    private fun stationItem(s: se.jpfm.radio.RadioStation, listId: String, group: String?): MediaItem {
        val base = ExtItems.live(s, listId)
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle())
        group?.let { extras.putString(GROUP_TITLE, it) }
        return MediaItem.Builder()
            .setMediaId(base.mediaId)
            .setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    private fun podcasts(): List<MediaItem> {
        val subs = g.podcasts.subscriptions.value
        val out = mutableListOf<MediaItem>()
        if (subs.isNotEmpty()) {
            out += folder(MediaIds.NEW_EPISODES, "Nya avsnitt", icon(R.drawable.ic_auto_recent), MediaMetadata.MEDIA_TYPE_FOLDER_PODCASTS, STYLE_LIST)
        }
        subs.forEach { p ->
            out += folder("pod:${p.id}", p.title, p.image?.let { ArtworkProvider.remoteUri(it) }, MediaMetadata.MEDIA_TYPE_PODCAST, STYLE_LIST, subtitle = p.author, group = "Dina poddar")
        }
        return out
    }

    private suspend fun podcastEpisodes(podcastId: String): List<MediaItem> {
        val p = g.podcasts.podcast(podcastId) ?: return emptyList()
        val eps = runCatching { g.podcasts.feed(p) }.getOrDefault(g.podcasts.cachedEpisodes(podcastId))
        return eps.take(60).map { episodeItem(it) }
    }

    private fun episodeItem(e: se.jpfm.podcast.Episode): MediaItem {
        val base = ExtItems.episode(e)
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle())
        val prog = g.podcasts.progressOf(e.id)
        // Bilen visar hur långt du lyssnat (0 = inte påbörjat, 1 = delvis, 2 = klart).
        extras.putInt(PLAYBACK_STATUS, when {
            prog == null -> 0
            prog.played -> 2
            else -> 1
        })
        if (prog != null && !prog.played) extras.putDouble(COMPLETION, prog.fraction.toDouble())
        if (g.downloads.isDownloaded(e.id)) extras.putLong(DOWNLOAD_STATUS, STATUS_DOWNLOADED)
        return MediaItem.Builder()
            .setMediaId(base.mediaId)
            .setMediaMetadata(base.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    // ---------- Sök ----------

    suspend fun search(query: String): List<MediaItem> {
        if (!online()) {
            // Offline: spela från allt nedladdat, med träffen först.
            val ctx = "offline:" + java.net.URLEncoder.encode(QueueBuilder.ALL_OFFLINE, "UTF-8")
            return g.downloads.allTracks()
                .filter { t -> listOf(t.title, t.artist, t.album).any { it.contains(query, ignoreCase = true) } }
                .take(40).map { trackItem(it, ctx) }
        }
        val r = g.library.search(query)
        return r.artists.take(5).map { artistFolder(it, "Artister") } +
            r.albums.take(8).map { albumFolder(it, "Album") } +
            r.playlists.take(5).map { playlistFolder(it, "Spellistor") } +
            r.tracks.take(15).map { trackItem(it, "radio", "Låtar") }
    }

    // ---------- Byggstenar ----------

    fun item(m: MusicItem, group: String? = null): MediaItem? = when (m) {
        is Artist -> artistFolder(m, group)
        is Album -> albumFolder(m, group)
        is Playlist -> playlistFolder(m, group)
        is Station -> stationItem(m, group)
        is Track -> trackItem(m, "radio", group)
    }

    private fun artistFolder(a: Artist, group: String? = null) =
        folder(MediaIds.artist(a.ratingKey), a.title, a.thumb?.let { art(it) }, MediaMetadata.MEDIA_TYPE_ARTIST, STYLE_GRID, group = group)

    private fun albumFolder(a: Album, group: String? = null) = folder(
        MediaIds.album(a.ratingKey), a.title, a.thumb?.let { art(it) }, MediaMetadata.MEDIA_TYPE_ALBUM, STYLE_LIST,
        subtitle = listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "), group = group,
        downloaded = g.downloads.collection("album:${a.ratingKey}") != null,
    )

    private fun playlistFolder(p: Playlist, group: String? = null) = folder(
        MediaIds.playlist(p.ratingKey), p.title, p.thumb?.let { art(it) }, MediaMetadata.MEDIA_TYPE_PLAYLIST, STYLE_LIST,
        subtitle = "${p.trackCount} låtar", group = group,
        downloaded = g.downloads.collection("playlist:${p.ratingKey}") != null,
    )

    private fun offlineFolder(id: String, title: String, subtitle: String, thumb: String?) = folder(
        MediaIds.offline(id), title, thumb?.let { art(it) }, MediaMetadata.MEDIA_TYPE_ALBUM, STYLE_LIST, subtitle = subtitle, downloaded = true,
    )

    private fun stationItem(s: Station, group: String? = null) =
        playable(MediaIds.station(s.key), s.title, s.subtitle ?: "Radio", s.thumb?.let { art(it) } ?: icon(R.drawable.ic_auto_radio), MediaMetadata.MEDIA_TYPE_RADIO_STATION, group)

    fun trackItem(t: Track, context: String?, group: String? = null): MediaItem {
        val extras = Bundle()
        group?.let { extras.putString(GROUP_TITLE, it) }
        if (g.downloads.isDownloaded(t.ratingKey)) extras.putLong(DOWNLOAD_STATUS, STATUS_DOWNLOADED)
        return MediaItem.Builder()
            .setMediaId(MediaIds.track(t.ratingKey, context))
            .setMediaMetadata(
                TrackItems.metadata(t).buildUpon()
                    .setExtras(Bundle(TrackItems.metadata(t).extras ?: Bundle()).apply { putAll(extras) })
                    .build(),
            )
            .build()
    }

    private fun folder(
        id: String,
        title: String,
        artwork: Uri?,
        type: Int,
        childStyle: Int? = null,
        subtitle: String? = null,
        group: String? = null,
        downloaded: Boolean = false,
    ): MediaItem {
        val extras = Bundle()
        childStyle?.let {
            extras.putInt(CONTENT_STYLE_BROWSABLE, it)
            extras.putInt(CONTENT_STYLE_PLAYABLE, STYLE_LIST)
        }
        group?.let { extras.putString(GROUP_TITLE, it) }
        if (downloaded) extras.putLong(DOWNLOAD_STATUS, STATUS_DOWNLOADED)
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun playable(id: String, title: String, subtitle: String?, artwork: Uri?, type: Int, group: String? = null): MediaItem {
        val extras = Bundle()
        group?.let { extras.putString(GROUP_TITLE, it) }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(type)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    private fun art(thumb: String): Uri = ArtworkProvider.uri(thumb, 400)

    private fun icon(res: Int): Uri = Uri.parse("android.resource://$pkg/$res")

    companion object {
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        const val CONTENT_STYLE_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"
        const val DOWNLOAD_STATUS = "android.media.extra.DOWNLOAD_STATUS"
        const val STATUS_DOWNLOADED = 2L
        const val STYLE_LIST = 1
        const val STYLE_GRID = 2
        const val PLAYBACK_STATUS = "android.media.extra.PLAYBACK_STATUS"
        const val COMPLETION = "androidx.media.MediaItem.Extras.COMPLETION_PERCENTAGE"
    }
}
