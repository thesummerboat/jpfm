package se.jpfm.plex

/** Det vi behöver veta om en Plex-server för att prata med den. */
data class PlexServer(
    val name: String,
    /** Adressen som svarade senast, t.ex. "https://192-168-1-10.abc.plex.direct:32400". */
    val uri: String,
    val token: String,
    /** Serverns id ("clientIdentifier"), behövs för spelköer och spellistor. */
    val machineId: String,
    /** Alla adresser vi känner till, bästa först. Används för att hitta servern igen när nätet byts. */
    val candidates: List<String>,
)

data class PlexPin(val id: Long, val code: String)

data class PlexUser(val name: String, val thumb: String?)

data class MusicSection(val key: String, val title: String, val uuid: String?)

/** Plex egna fyra färger för en bild ("UltraBlur"), som ARGB. */
data class UltraBlur(val topLeft: Int, val topRight: Int, val bottomLeft: Int, val bottomRight: Int)

sealed interface MusicItem {
    val ratingKey: String
    val title: String
    val thumb: String?
}

data class Artist(
    override val ratingKey: String,
    override val title: String,
    override val thumb: String?,
    val art: String? = null,
    val summary: String? = null,
    val genres: List<String> = emptyList(),
    val titleSort: String? = null,
    val country: String? = null,
) : MusicItem

data class Album(
    override val ratingKey: String,
    override val title: String,
    val artist: String,
    val artistKey: String?,
    override val thumb: String?,
    val year: Int? = null,
    val trackCount: Int? = null,
    val genres: List<String> = emptyList(),
    val summary: String? = null,
    val studio: String? = null,
    val addedAt: Long? = null,
    val subformat: String? = null,
    val titleSort: String? = null,
    val userRating: Float? = null,
    val ultraBlur: UltraBlur? = null,
) : MusicItem

data class Track(
    override val ratingKey: String,
    override val title: String,
    /** Albumartisten (grandparentTitle). */
    val artist: String,
    /** Låtens egen artist om den skiljer sig, t.ex. på samlingar ("originalTitle"). */
    val trackArtist: String? = null,
    val album: String,
    val albumKey: String?,
    val artistKey: String?,
    override val thumb: String?,
    val durationMs: Long,
    val index: Int? = null,
    val disc: Int? = null,
    val partKey: String? = null,
    val container: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val userRating: Float? = null,
    val year: Int? = null,
    val ratingCount: Int? = null,
    val viewCount: Int? = null,
    /** Satt när låten kommer från en spelkö på servern (radio, mixar). */
    val playQueueItemId: Long? = null,
) : MusicItem {
    val displayArtist: String get() = trackArtist?.takeIf { it.isNotBlank() } ?: artist
    val isLoved: Boolean get() = (userRating ?: 0f) >= 9f
}

data class Playlist(
    override val ratingKey: String,
    override val title: String,
    override val thumb: String?,
    val trackCount: Int = 0,
    val durationMs: Long? = null,
    val smart: Boolean = false,
    val summary: String? = null,
) : MusicItem

/**
 * Något som spelas genom en spelkö på servern: Plex radiostationer, "Mixar för dig",
 * artistradio. [key] är det Plex vill ha i spelkön, t.ex. "/library/sections/5/stations/1".
 */
data class Station(
    override val ratingKey: String,
    override val title: String,
    override val thumb: String?,
    val key: String,
    val subtitle: String? = null,
) : MusicItem

data class Hub(
    val identifier: String,
    val title: String,
    val items: List<MusicItem>,
    val context: String? = null,
)

/** Detaljer vi hämtar för låten som spelas: ljudnivå, låttext och färger. */
data class TrackDetails(
    val gainDb: Double? = null,
    val albumGainDb: Double? = null,
    val lyrics: LyricsRef? = null,
    val ultraBlur: UltraBlur? = null,
    val userRating: Float? = null,
)

data class LyricsRef(val key: String, val format: String?, val timed: Boolean)

data class ArtistPage(
    val artist: Artist,
    val popular: List<Track>,
    val albums: List<Album>,
    val station: Station?,
    val related: List<Hub>,
)

data class AlbumPage(val album: Album, val tracks: List<Track>)

data class PlayQueue(val id: Long, val tracks: List<Track>, val selectedIndex: Int)

data class SearchResult(
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty() && playlists.isEmpty()
}

class PlexException(message: String) : Exception(message)
