package se.jpfm.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import se.jpfm.Quality
import se.jpfm.plex.Track
import se.jpfm.plex.TrackJson

/**
 * Gör om låtar till Media3-objekt och tillbaka. Adresserna är egna ("jpfm://…") och översätts
 * till serverns adress först när musiken hämtas – så fortsätter kön att fungera när telefonen
 * byter från wifi till mobildata, och nyckeln till Plex hamnar aldrig i notisen eller bilen.
 */
object TrackItems {
    const val SCHEME = "jpfm"
    private const val EXTRA_TRACK = "jpfm.track"

    /** Samma nyckel för strömmen och nedladdningen, så att nedladdade låtar spelas från telefonen. */
    fun cacheKey(t: Track): String = "part:${t.partKey ?: t.ratingKey}"

    fun directUri(t: Track): Uri = Uri.Builder()
        .scheme(SCHEME).authority("track").appendPath(t.ratingKey)
        .apply { t.partKey?.let { appendQueryParameter("part", it) } }
        .build()

    fun transcodeUri(t: Track, kbps: Int): Uri = Uri.Builder()
        .scheme(SCHEME).authority("transcode").appendPath(t.ratingKey)
        .appendQueryParameter("kbps", kbps.toString())
        .build()

    /**
     * @param quality vald kvalitet för nätet just nu. Nedladdade låtar spelas alltid i original.
     */
    fun toMediaItem(t: Track, quality: Quality, downloaded: Boolean, contextId: String? = null): MediaItem {
        val transcode = !downloaded && (quality.kbps != null || t.partKey == null)
        val b = MediaItem.Builder()
            .setMediaId(MediaIds.track(t.ratingKey, contextId))
            .setMediaMetadata(metadata(t))
        if (transcode) {
            b.setUri(transcodeUri(t, quality.kbps ?: 320)).setMimeType(MimeTypes.APPLICATION_M3U8)
        } else {
            b.setUri(directUri(t)).setCustomCacheKey(cacheKey(t))
        }
        return b.build()
    }

    /** Samma låt men omkodad på servern (när telefonen inte klarar originalfilen). */
    fun asTranscoded(item: MediaItem, kbps: Int = 320): MediaItem? {
        val t = track(item) ?: return null
        return item.buildUpon().setUri(transcodeUri(t, kbps)).setMimeType(MimeTypes.APPLICATION_M3U8).setCustomCacheKey(null).build()
    }

    fun isTranscoded(item: MediaItem): Boolean = item.localConfiguration?.uri?.authority == "transcode"

    fun metadata(t: Track): MediaMetadata = MediaMetadata.Builder()
        .setTitle(t.title)
        .setDisplayTitle(t.title)
        .setArtist(t.displayArtist)
        .setSubtitle(t.displayArtist)
        .setAlbumTitle(t.album)
        .setAlbumArtist(t.artist)
        .setArtworkUri(t.thumb?.let { ArtworkProvider.uri(it) })
        .setTrackNumber(t.index)
        .setDiscNumber(t.disc)
        .setReleaseYear(t.year)
        .setDurationMs(t.durationMs.takeIf { it > 0 })
        // Gillad syns för appen, notisen och bilen (och gör att ändringen märks).
        .setUserRating(HeartRating(t.isLoved))
        .setIsBrowsable(false)
        .setIsPlayable(true)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
        .setExtras(Bundle().apply { putString(EXTRA_TRACK, TrackJson.encode(t)) })
        .build()

    fun track(item: MediaItem?): Track? = item?.mediaMetadata?.extras?.getString(EXTRA_TRACK)?.let { TrackJson.decode(it) }
}
