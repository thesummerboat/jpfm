package se.jpfm.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import se.jpfm.JpfmApp
import se.jpfm.Leveling
import se.jpfm.MainActivity
import se.jpfm.R
import se.jpfm.plex.Track
import java.util.concurrent.ConcurrentHashMap

/**
 * Spelaren. Lever i bakgrunden och styrs av appen, låsskärmen, hörlurar, klockan och
 * Android Auto. Sköter också det som gör att det känns som en riktig musiktjänst:
 * ljudutjämning, radio som aldrig tar slut, omkodning när telefonen inte klarar filen,
 * att Plex får veta vad som spelas och att kön finns kvar efter omstart.
 */
class PlaybackService : MediaLibraryService() {

    private val graph get() = (application as JpfmApp).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private lateinit var browse: BrowseTree

    private val searchResults = ConcurrentHashMap<String, List<MediaItem>>()

    private var tickJob: Job? = null
    private var extending = false
    private var lastErrorId: String? = null
    private var errorCount = 0

    /** Låten som rapporterades till Plex senast och hur långt den kom. */
    private var reported: Track? = null
    private var reportedPosition = 0L

    override fun onCreate() {
        super.onCreate()
        browse = BrowseTree(graph)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(graph.playbackDataSourceFactory))
            .setLoadControl(
                // Stor buffert: musiken fortsätter genom tunnlar och döda zoner i bilen.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(30_000, 240_000, 1_500, 3_000)
                    .build(),
            )
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.addListener(listener)

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setCustomLayout(buttons(loved = false))
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelName(R.string.playback_channel)
                .build()
                .apply { setSmallIcon(R.drawable.ic_notification) },
        )

        restoreQueue()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            saveQueue()
            stopSelf()
        }
    }

    override fun onDestroy() {
        saveQueue()
        report("stopped")
        session?.release()
        session = null
        player.removeListener(listener)
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    // ---------- Kön ----------

    private fun context(): QueueContext? = QueueContext.fromBundle(player.playlistMetadata.extras)

    private fun queueTracks(): List<Track> =
        (0 until player.mediaItemCount).mapNotNull { TrackItems.track(player.getMediaItemAt(it)) }

    private fun restoreQueue() {
        if (player.mediaItemCount > 0) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) { graph.queueStore.load() } ?: return@launch
            if (player.mediaItemCount > 0) return@launch
            val q = BuiltQueue(saved.tracks, saved.index, saved.context ?: QueueContext(QueueContext.Kind.TRACKS, ""), saved.shuffle)
            player.setMediaItems(graph.queues.mediaItems(q), saved.index, saved.positionMs)
            saved.context?.let { player.playlistMetadata = QueueBuilder.contextMetadata(it) }
            player.shuffleModeEnabled = saved.shuffle
            player.repeatMode = saved.repeatMode
            // Inte prepare(): utan nät skulle spelaren annars börja hoppa mellan låtar. Play startar den.
        }
    }

    private var saveJob: Job? = null

    private fun saveQueueSoon() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(800)
            saveQueue()
        }
    }

    private fun saveQueue() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val saved = SavedQueue(
            tracks = queueTracks(),
            index = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            context = context(),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        scope.launch(Dispatchers.IO) { runCatching { graph.queueStore.save(saved) } }
    }

    /** Fyller på radio och mixar innan de tar slut. */
    private fun maybeExtend() {
        val ctx = context() ?: return
        if (!ctx.endless || extending || player.mediaItemCount == 0) return
        if (player.repeatMode != Player.REPEAT_MODE_OFF) return
        val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
        if (remaining > 3) return
        extending = true
        scope.launch {
            try {
                val more = graph.queues.more(ctx, queueTracks())
                if (more.isNotEmpty()) player.addMediaItems(more.map { graph.queues.mediaItem(it) })
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                extending = false
            }
        }
    }

    // ---------- Ljudutjämning ----------

    private fun applyLoudness() {
        val t = TrackItems.track(player.currentMediaItem) ?: run {
            player.volume = 1f
            return
        }
        val mode = graph.prefs.settings.value.leveling
        if (mode == Leveling.OFF) {
            player.volume = 1f
            return
        }
        val inOrder = context()?.kind == QueueContext.Kind.ALBUM && !player.shuffleModeEnabled
        val known = graph.library.peekTrackDetails(t.ratingKey) ?: graph.downloads.details(t.ratingKey)
        if (known != null) {
            player.volume = Loudness.volume(mode, known.gainDb, known.albumGainDb, inOrder)
        } else {
            player.volume = Loudness.volume(mode, null, null, inOrder)
            if (graph.session.isReady) scope.launch {
                val d = runCatching { graph.library.trackDetails(t.ratingKey) }.getOrNull() ?: return@launch
                if (TrackItems.track(player.currentMediaItem)?.ratingKey == t.ratingKey) {
                    player.volume = Loudness.volume(mode, d.gainDb, d.albumGainDb, inOrder)
                }
            }
        }
    }

    /** Hämtar nästa låts ljudnivå i förväg, så att volymen stämmer från första tonen. */
    private fun prefetchNext() {
        if (!graph.session.isReady) return
        val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val t = TrackItems.track(player.getMediaItemAt(next)) ?: return
        if (graph.library.peekTrackDetails(t.ratingKey) != null) return
        scope.launch { runCatching { graph.library.trackDetails(t.ratingKey) } }
    }

    // ---------- Plex: vad spelas ----------

    private fun report(state: String, track: Track? = TrackItems.track(player.currentMediaItem), position: Long = player.currentPosition) {
        val t = track ?: return
        if (!graph.session.isReady) return
        val duration = player.duration.takeIf { it > 0 && track == TrackItems.track(player.currentMediaItem) } ?: t.durationMs
        scope.launch { runCatching { graph.library.timeline(t, state, position.coerceAtLeast(0), duration) } }
    }

    private fun startTicker() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            var n = 0
            while (isActive) {
                delay(2_000)
                reportedPosition = player.currentPosition
                n++
                if (n % 5 == 0 && player.isPlaying) report("playing")
                if (n % 8 == 0) saveQueue()
            }
        }
    }

    // ---------- Knappar i notisen och bilen ----------

    private fun buttons(loved: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(if (loved) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (loved) "Gillad" else "Gilla")
            .setSessionCommand(CMD_LOVE)
            .build(),
        CommandButton.Builder(CommandButton.ICON_RADIO)
            .setDisplayName("Låtradio")
            .setSessionCommand(CMD_RADIO)
            .build(),
    )

    private fun refreshButtons() {
        val loved = TrackItems.track(player.currentMediaItem)?.isLoved == true
        session?.setCustomLayout(buttons(loved))
    }

    private fun toggleLove() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val t = TrackItems.track(item) ?: return
        val loved = !t.isLoved
        val updated = t.copy(userRating = if (loved) 10f else null)
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(TrackItems.metadata(updated)).build())
        refreshButtons()
        scope.launch { runCatching { graph.library.setLoved(t.ratingKey, loved) } }
    }

    private fun startSonicRadio() {
        val t = TrackItems.track(player.currentMediaItem) ?: return
        scope.launch {
            val q = runCatching { graph.queues.sonicRadio(t) }.getOrNull() ?: return@launch
            val position = player.currentPosition
            player.setMediaItems(graph.queues.mediaItems(q), 0, position)
            player.playlistMetadata = QueueBuilder.contextMetadata(q.context)
            player.shuffleModeEnabled = false
            player.prepare()
            player.play()
        }
    }

    // ---------- Fel ----------

    private fun handleError(error: PlaybackException) {
        val item = player.currentMediaItem ?: return
        // Bara när du faktiskt vill lyssna – annars väntar felet tills du trycker på spela.
        if (!player.playWhenReady) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        if (item.mediaId != lastErrorId) {
            lastErrorId = item.mediaId
            errorCount = 0
        }
        errorCount++
        val decoder = error.errorCode in DECODER_ERRORS
        val network = error.errorCode in NETWORK_ERRORS
        when {
            // Telefonen klarar inte filen (t.ex. ALAC eller ovanlig FLAC): låt Plex koda om.
            decoder && !TrackItems.isTranscoded(item) && graph.session.isReady -> {
                val transcoded = TrackItems.asTranscoded(item) ?: return
                player.replaceMediaItem(index, transcoded)
                player.seekTo(index, position)
                player.prepare()
                player.play()
            }
            network && errorCount <= 3 -> scope.launch {
                delay(1_500L * errorCount)
                // Ny adress till servern om nätet bytts (wifi → mobildata).
                if (!graph.session.isReady || errorCount >= 2) graph.session.connect()
                player.seekTo(index, position)
                player.prepare()
                player.play()
            }
            player.hasNextMediaItem() && errorCount <= 6 -> {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
            else -> Unit
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Föregående låt: klar eller avbruten.
            reported?.let { prev ->
                val pos = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) prev.durationMs else reportedPosition
                report("stopped", prev, pos)
            }
            reported = TrackItems.track(mediaItem)
            reportedPosition = 0
            if (player.isPlaying) report("playing", reported, 0)
            applyLoudness()
            prefetchNext()
            refreshButtons()
            maybeExtend()
            saveQueueSoon()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                if (reported == null) reported = TrackItems.track(player.currentMediaItem)
                report("playing")
                startTicker()
            } else {
                if (player.playbackState != Player.STATE_ENDED) report("paused")
                tickJob?.cancel()
                saveQueue()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                report("stopped", position = player.duration.coerceAtLeast(0))
                reported = null
            }
            if (playbackState == Player.STATE_READY) {
                errorCount = 0
            }
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                maybeExtend()
                prefetchNext()
                saveQueueSoon()
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            applyLoudness()
            saveQueueSoon()
        }

        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) {
            applyLoudness()
            saveQueueSoon()
        }

        override fun onPlayerError(error: PlaybackException) = handleError(error)
    }

    // ---------- Sessionen: appen, notisen och Android Auto ----------

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(CMD_LOVE)
                .add(CMD_RADIO)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .setCustomLayout(buttons(TrackItems.track(player.currentMediaItem)?.isLoved == true))
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                CMD_LOVE.customAction -> toggleLove()
                CMD_RADIO.customAction -> startSonicRadio()
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(browse.root(), LibraryParams.Builder().setExtras(browse.rootExtras()).build()))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            try {
                LibraryResult.ofItemList(browse.children(parentId, page, pageSize), params)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                LibraryResult.ofError(SessionError.ERROR_IO)
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
            val id = MediaIds.parse(mediaId)
            val item = if (id.type == "track") {
                (graph.downloads.track(id.value) ?: runCatching { graph.library.track(id.value) }.getOrNull())
                    ?.let { browse.trackItem(it, id.context) }
            } else {
                null
            }
            item?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<Void>> {
            scope.launch {
                val results = runCatching { browse.search(query) }.getOrDefault(emptyList())
                searchResults[query] = results
                session.notifySearchResultChanged(browser, query, results.size, params)
            }
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
            val all = searchResults[query] ?: runCatching { browse.search(query) }.getOrDefault(emptyList())
            val from = page * pageSize
            LibraryResult.ofItemList(if (from >= all.size) emptyList() else all.subList(from, (from + pageSize).coerceAtMost(all.size)), params)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = scope.future {
            mediaItems.flatMap { runCatching { graph.queues.resolveForQueue(it) }.getOrDefault(emptyList()) }.toMutableList()
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            // Från appen: färdiga låtar med adresser.
            if (mediaItems.all { it.localConfiguration != null }) {
                return@future MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
            // Från bilen eller rösten: ett id eller en sökning som blir en hel kö.
            val first = mediaItems.firstOrNull()
            val query = first?.requestMetadata?.searchQuery
            val built = when {
                first == null -> null
                query != null -> runCatching { graph.queues.fromSearch(query, first.requestMetadata.extras) }.getOrNull()
                    ?: runCatching { graph.queues.shuffleLibrary() }.getOrNull()
                first.mediaId.isNotEmpty() -> runCatching { graph.queues.fromMediaId(first.mediaId) }.getOrNull()
                else -> null
            }
            if (built == null || built.tracks.isEmpty()) {
                // "Spela musik" utan mer: fortsätt där du var, annars blanda.
                val saved = withContext(Dispatchers.IO) { graph.queueStore.load() }
                if (saved != null) {
                    val q = BuiltQueue(saved.tracks, saved.index, saved.context ?: QueueContext(QueueContext.Kind.TRACKS, ""), saved.shuffle)
                    saved.context?.let { player.playlistMetadata = QueueBuilder.contextMetadata(it) }
                    return@future MediaSession.MediaItemsWithStartPosition(graph.queues.mediaItems(q), saved.index, saved.positionMs)
                }
                throw IllegalStateException("Hittade inget att spela")
            }
            player.playlistMetadata = QueueBuilder.contextMetadata(built.context)
            player.shuffleModeEnabled = built.shuffle
            MediaSession.MediaItemsWithStartPosition(graph.queues.mediaItems(built), built.startIndex, C.TIME_UNSET)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future {
            val saved = withContext(Dispatchers.IO) { graph.queueStore.load() }
                ?: throw IllegalStateException("Ingen sparad kö")
            val q = BuiltQueue(saved.tracks, saved.index, saved.context ?: QueueContext(QueueContext.Kind.TRACKS, ""), saved.shuffle)
            saved.context?.let { player.playlistMetadata = QueueBuilder.contextMetadata(it) }
            player.shuffleModeEnabled = saved.shuffle
            MediaSession.MediaItemsWithStartPosition(graph.queues.mediaItems(q), saved.index, saved.positionMs)
        }
    }

    companion object {
        val CMD_LOVE = SessionCommand("se.jpfm.LOVE", Bundle.EMPTY)
        val CMD_RADIO = SessionCommand("se.jpfm.RADIO", Bundle.EMPTY)

        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        )

        private val NETWORK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
        )
    }
}
