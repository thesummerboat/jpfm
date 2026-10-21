package se.jpfm.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.json.JSONObject
import se.jpfm.podcast.Episode
import se.jpfm.radio.RadioStation

/**
 * Radiokanaler och poddavsnitt som Media3-objekt. Adresserna går direkt till kanalen eller
 * podden (inte via Plex). Kanalen eller avsnittet följer med i extras, så att appen, notisen
 * och bilen vet vad som spelas.
 */
object ExtItems {
    private const val EXTRA_LIVE = "jpfm.live"
    private const val EXTRA_EPISODE = "jpfm.episode"

    fun liveId(s: RadioStation, list: String? = null) = if (list == null) "live:${s.id}" else "live:${s.id}@$list"
    fun episodeId(e: Episode) = "ep:${e.id.removePrefix("ep:")}"

    /** Nedladdade avsnitt hittas på den här nyckeln. */
    fun cacheKey(e: Episode) = "episode:${e.id}"

    fun live(s: RadioStation, list: String? = null): MediaItem {
        val b = MediaItem.Builder()
            .setMediaId(liveId(s, list))
            .setUri(s.streamUrl)
            .setMediaMetadata(liveMetadata(s, null, null))
        if (s.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
        return b.build()
    }

    /** Kanalens metadata, med låten/programmet som spelas om vi vet det. */
    fun liveMetadata(s: RadioStation, title: String?, artist: String?): MediaMetadata = MediaMetadata.Builder()
        .setTitle(title ?: s.name)
        .setDisplayTitle(title ?: s.name)
        .setArtist(artist ?: if (title != null) s.name else s.tagline ?: "Radio")
        .setSubtitle(artist ?: if (title != null) s.name else s.tagline ?: "Radio")
        .setAlbumTitle(s.name)
        .setStation(s.name)
        .setArtworkUri(s.logo?.let { ArtworkProvider.remoteUri(it) })
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(Bundle().apply { putString(EXTRA_LIVE, s.toJson().toString()) })
        .build()

    fun episode(e: Episode): MediaItem = MediaItem.Builder()
        .setMediaId(episodeId(e))
        .setUri(e.audioUrl)
        .setCustomCacheKey(cacheKey(e))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(e.title)
                .setDisplayTitle(e.title)
                .setArtist(e.podcastTitle)
                .setSubtitle(e.podcastTitle)
                .setAlbumTitle(e.podcastTitle)
                .setArtworkUri(e.image?.let { ArtworkProvider.remoteUri(it) })
                .setDurationMs(e.durationMs.takeIf { it > 0 })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PODCAST_EPISODE)
                .setExtras(Bundle().apply { putString(EXTRA_EPISODE, e.toJson().toString()) })
                .build(),
        )
        .build()

    fun station(item: MediaItem?): RadioStation? =
        item?.mediaMetadata?.extras?.getString(EXTRA_LIVE)?.let { runCatching { RadioStation.fromJson(JSONObject(it)) }.getOrNull() }

    fun episode(item: MediaItem?): Episode? =
        item?.mediaMetadata?.extras?.getString(EXTRA_EPISODE)?.let { runCatching { Episode.fromJson(JSONObject(it)) }.getOrNull() }

    fun isLive(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.containsKey(EXTRA_LIVE) == true
    fun isEpisode(item: MediaItem?): Boolean = item?.mediaMetadata?.extras?.containsKey(EXTRA_EPISODE) == true
}
