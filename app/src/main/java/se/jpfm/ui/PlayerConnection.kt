package se.jpfm.ui

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.jpfm.AppGraph
import se.jpfm.playback.BuiltQueue
import se.jpfm.playback.PlaybackService
import se.jpfm.playback.QueueBuilder
import se.jpfm.playback.QueueContext
import se.jpfm.playback.TrackItems
import se.jpfm.plex.Track

/** En rad i kön: platsen i spelarens lista och låten. */
data class QueueEntry(val index: Int, val track: Track, val mediaId: String)

/**
 * Appens fjärrkontroll till spelartjänsten. Allt gränssnittet behöver veta om uppspelningen
 * finns här som Compose-tillstånd.
 */
class PlayerConnection(private val context: Context, private val g: AppGraph, private val scope: CoroutineScope) {

    var controller: MediaController? by mutableStateOf(null)
        private set

    var current by mutableStateOf<Track?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isBuffering by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var buffered by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    var queue by mutableStateOf<List<QueueEntry>>(emptyList())
        private set
    var currentIndex by mutableStateOf(0)
        private set
    var queueContext by mutableStateOf<QueueContext?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var transcoded by mutableStateOf(false)
        private set

    val hasMedia: Boolean get() = current != null

    private var ticker: Job? = null

    fun connect() {
        if (controller != null) return
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull() ?: return@launch
            controller = c
            c.addListener(listener)
            syncAll(c)
        }
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) {
                syncQueue(player)
            }
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_TIMELINE_CHANGED)) {
                syncCurrent(player)
            }
            if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                isPlaying = player.isPlaying
                isBuffering = player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
                if (player.isPlaying) startTicker() else ticker?.cancel()
                syncPosition(player)
            }
            if (events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) shuffle = player.shuffleModeEnabled
            if (events.contains(Player.EVENT_REPEAT_MODE_CHANGED)) repeatMode = player.repeatMode
            if (events.contains(Player.EVENT_PLAYLIST_METADATA_CHANGED)) queueContext = QueueContext.fromBundle(player.playlistMetadata.extras)
            if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)) syncPosition(player)
            if (events.contains(Player.EVENT_PLAYER_ERROR)) {
                error = player.playerError?.let { describe(it) }
            } else if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && player.playbackState == Player.STATE_READY) {
                error = null
            }
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Kommer inte åt servern. Försöker igen …"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Servern svarade med fel för den här låten."
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED -> "Telefonen kan inte spela filen – provar omkodning."
        else -> e.message ?: "Uppspelningen stannade."
    }

    private fun syncAll(p: Player) {
        syncQueue(p)
        syncCurrent(p)
        isPlaying = p.isPlaying
        shuffle = p.shuffleModeEnabled
        repeatMode = p.repeatMode
        queueContext = QueueContext.fromBundle(p.playlistMetadata.extras)
        syncPosition(p)
        if (p.isPlaying) startTicker()
    }

    private fun syncCurrent(p: Player) {
        current = TrackItems.track(p.currentMediaItem)
        transcoded = p.currentMediaItem?.let { TrackItems.isTranscoded(it) } == true
        currentIndex = p.currentMediaItemIndex
        duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (current?.durationMs ?: 0L)
    }

    /** Kön i den ordning den spelas (även blandad), från låten som spelas nu. */
    private fun syncQueue(p: Player) {
        val tl = p.currentTimeline
        val list = ArrayList<QueueEntry>()
        if (!tl.isEmpty) {
            var i = tl.getFirstWindowIndex(p.shuffleModeEnabled)
            val window = Timeline.Window()
            var guard = 0
            while (i != C.INDEX_UNSET && guard < 5000) {
                val item = tl.getWindow(i, window).mediaItem
                TrackItems.track(item)?.let { list += QueueEntry(i, it, item.mediaId) }
                i = tl.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
                guard++
            }
        }
        queue = list
        currentIndex = p.currentMediaItemIndex
    }

    private fun syncPosition(p: Player) {
        position = p.currentPosition.coerceAtLeast(0)
        buffered = p.bufferedPosition.coerceAtLeast(0)
        val d = p.duration
        if (d != C.TIME_UNSET && d > 0) duration = d
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let { syncPosition(it) }
                delay(250)
            }
        }
    }

    // ---------- Styrning ----------

    fun play(q: BuiltQueue) {
        val c = controller ?: return
        if (q.tracks.isEmpty()) return
        error = null
        c.setMediaItems(g.queues.mediaItems(q), q.startIndex, 0L)
        c.playlistMetadata = QueueBuilder.contextMetadata(q.context)
        c.shuffleModeEnabled = q.shuffle
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        when {
            c.playbackState == Player.STATE_IDLE -> {
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekToDefaultPosition(0)
                c.play()
            }
            c.isPlaying || (c.playWhenReady && c.playbackState == Player.STATE_BUFFERING) -> c.pause()
            else -> c.play()
        }
    }

    fun next() = controller?.seekToNext()

    /** Tillbaka: börja om låten efter tre sekunder, annars föregående. */
    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) {
        controller?.seekTo(ms)
        position = ms
    }

    fun toggleShuffle() {
        val c = controller ?: return
        c.shuffleModeEnabled = !c.shuffleModeEnabled
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun skipTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        c.play()
    }

    fun playNext(tracks: List<Track>) {
        val c = controller ?: return
        val items = tracks.map { g.queues.mediaItem(it) }
        if (c.mediaItemCount == 0) {
            play(g.queues.tracks(tracks, 0, tracks.firstOrNull()?.title ?: ""))
            return
        }
        val at = (c.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: c.mediaItemCount)
            .coerceAtMost(c.mediaItemCount)
        // Med blandning: lägg direkt efter låten som spelas i listan.
        val index = if (c.shuffleModeEnabled) c.currentMediaItemIndex + 1 else at
        c.addMediaItems(index, items)
    }

    fun addToQueue(tracks: List<Track>) {
        val c = controller ?: return
        if (c.mediaItemCount == 0) {
            play(g.queues.tracks(tracks, 0, tracks.firstOrNull()?.title ?: ""))
            return
        }
        c.addMediaItems(tracks.map { g.queues.mediaItem(it) })
    }

    fun removeAt(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun move(from: Int, to: Int) {
        controller?.moveMediaItem(from, to)
    }

    fun clearUpcoming() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        if (cur + 1 < c.mediaItemCount) c.removeMediaItems(cur + 1, c.mediaItemCount)
    }

    fun toggleLove() {
        controller?.sendCustomCommand(PlaybackService.CMD_LOVE, Bundle.EMPTY)
    }

    fun startRadioFromCurrent() {
        controller?.sendCustomCommand(PlaybackService.CMD_RADIO, Bundle.EMPTY)
    }

    /** Uppdaterar en låt i kön (t.ex. gillad) utan att stoppa musiken. */
    fun updateTrack(t: Track) {
        val c = controller ?: return
        for (i in 0 until c.mediaItemCount) {
            val item = c.getMediaItemAt(i)
            if (TrackItems.track(item)?.ratingKey == t.ratingKey) {
                c.replaceMediaItem(i, item.buildUpon().setMediaMetadata(TrackItems.metadata(t)).build())
            }
        }
    }

    fun currentItem(): MediaItem? = controller?.currentMediaItem

    @Suppress("unused")
    private fun metadataOf(item: MediaItem?): MediaMetadata? = item?.mediaMetadata
}
