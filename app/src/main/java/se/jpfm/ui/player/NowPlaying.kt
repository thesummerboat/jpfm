package se.jpfm.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import se.jpfm.lyrics.Lyrics
import se.jpfm.plex.Track
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.EqualizerBars
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.QueueEntry
import se.jpfm.ui.Screen
import se.jpfm.ui.rememberArtColors
import se.jpfm.ui.rememberLoadable
import kotlin.math.abs

private enum class Panel { COVER, LYRICS, QUEUE }

/** Spelarvyn: stort omslag, färger från omslaget, låttext och kö. */
@Composable
fun NowPlaying(vm: AppViewModel, onClose: () -> Unit) {
    val p = vm.player
    val track = p.current
    val g = LocalGraph.current
    val settings = LocalSettings.current
    BackHandler(onBack = onClose)
    if (track == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }

    val details = rememberLoadable(track.ratingKey) {
        if (g.session.isReady) g.library.trackDetails(track.ratingKey) else null
    }
    val colors = rememberArtColors(track.thumb, details.data?.ultraBlur, settings.dynamicColors)
    val top by animateColorAsState(colors.base, tween(700), label = "top")
    val mid by animateColorAsState(colors.deep, tween(700), label = "mid")
    val accent by animateColorAsState(colors.accent, tween(700), label = "accent")
    var panel by remember { mutableStateOf(Panel.COVER) }

    // Dra ner för att stänga.
    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = drag.value
                alpha = 1f - (drag.value / (closeAt * 4)).coerceIn(0f, 0.5f)
            }
            .background(Brush.verticalGradient(listOf(top, mid, Jp.Background)))
            .clickable(enabled = false) {},
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            // Övre raden.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (drag.value > closeAt) onClose()
                                    drag.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                }
                            },
                        ) { change, dy ->
                            change.consume()
                            scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Stäng", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SPELAR FRÅN", style = MaterialTheme.typography.labelSmall, color = Jp.Text.copy(alpha = 0.6f))
                    Text(
                        p.queueContext?.title?.ifBlank { null } ?: track.album,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { vm.menu = MenuTarget.ForTrack(track) }) { Icon(Icons.Rounded.MoreVert, "Mer") }
            }

            // Mitten: omslag, låttext eller kö.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AnimatedContent(
                    targetState = panel,
                    transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                    label = "panel",
                ) { which ->
                    when (which) {
                        Panel.COVER -> CoverPanel(track, p.isPlaying, onSwipe = { dir -> if (dir < 0) p.next() else p.previous() }, dragToClose = {
                            Modifier.pointerInput(Unit) {
                                detectVerticalDragGestures(
                                    onDragEnd = {
                                        scope.launch {
                                            if (drag.value > closeAt) onClose()
                                            drag.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
                                        }
                                    },
                                ) { change, dy ->
                                    change.consume()
                                    scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) }
                                }
                            }
                        })
                        Panel.LYRICS -> LyricsPanel(vm, track, accent)
                        Panel.QUEUE -> QueuePanel(vm, accent)
                    }
                }
            }

            // Titel och gilla.
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                    )
                    Text(
                        track.displayArtist,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Jp.Text.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(enabled = track.artistKey != null) {
                            track.artistKey?.let {
                                onClose()
                                vm.nav.push(Screen.Artist(it, track.artist))
                            }
                        },
                    )
                }
                val loved = track.isLoved
                val heartScale by animateFloatAsState(if (loved) 1.15f else 1f, spring(dampingRatio = 0.4f), label = "heart")
                IconButton(onClick = { p.toggleLove() }) {
                    Icon(
                        if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (loved) "Ta bort gilla" else "Gilla",
                        tint = if (loved) Jp.Love else Jp.Text,
                        modifier = Modifier.size(28.dp).graphicsLayer { scaleX = heartScale; scaleY = heartScale },
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SeekBar(
                position = p.position,
                duration = p.duration,
                buffered = p.buffered,
                accent = Jp.Text,
                onSeek = { p.seekTo(it) },
            )

            // Kontroller.
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { p.toggleShuffle() }) {
                    Icon(Icons.Rounded.Shuffle, "Blanda", tint = if (p.shuffle) accent else Jp.Text.copy(alpha = 0.7f))
                }
                IconButton(onClick = { p.previous() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Föregående", modifier = Modifier.size(40.dp))
                }
                val pressScale by animateFloatAsState(if (p.isPlaying) 1f else 1.04f, label = "play")
                Box(
                    Modifier
                        .size(76.dp)
                        .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
                        .clip(CircleShape)
                        .background(Jp.Text)
                        .clickable { p.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.isBuffering) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Jp.Background, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (p.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (p.isPlaying) "Pausa" else "Spela",
                            tint = Jp.Background,
                            modifier = Modifier.size(44.dp),
                        )
                    }
                }
                IconButton(onClick = { p.next() }, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Nästa", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = { p.cycleRepeat() }) {
                    Icon(
                        if (p.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Upprepa",
                        tint = if (p.repeatMode != Player.REPEAT_MODE_OFF) accent else Jp.Text.copy(alpha = 0.7f),
                    )
                }
            }

            // Nedre raden.
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelToggle(Icons.Rounded.Mic, "Låttext", panel == Panel.LYRICS, accent) {
                    panel = if (panel == Panel.LYRICS) Panel.COVER else Panel.LYRICS
                }
                Spacer(Modifier.weight(1f))
                QualityChip(track, p.transcoded, g.downloads.isDownloaded(track.ratingKey))
                Spacer(Modifier.weight(1f))
                SleepButton(p, accent)
                IconButton(onClick = { vm.sonicRadio(track) }) { Icon(Icons.Rounded.Radio, "Låtradio", tint = Jp.Text.copy(alpha = 0.75f)) }
                PanelToggle(Icons.Rounded.QueueMusic, "Kö", panel == Panel.QUEUE, accent) {
                    panel = if (panel == Panel.QUEUE) Panel.COVER else Panel.QUEUE
                }
            }
        }

        p.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
                color = Jp.Text,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun PanelToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, label, tint = if (active) accent else Jp.Text.copy(alpha = 0.75f))
    }
}

/** Sovtimer: pausar efter en stund eller när låten är slut. */
@Composable
private fun SleepButton(p: se.jpfm.ui.PlayerConnection, accent: Color) {
    var open by remember { mutableStateOf(false) }
    val active = p.sleepEndsAt != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        while (active) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(15_000)
        }
    }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.Timer, "Sovtimer", tint = if (active) accent else Jp.Text.copy(alpha = 0.75f))
        }
        val endsAt = p.sleepEndsAt
        if (endsAt != null && !p.sleepAtTrackEnd) {
            val min = ((endsAt - now) / 60_000 + 1).coerceAtLeast(1)
            Text(
                "$min",
                style = MaterialTheme.typography.labelSmall,
                color = Jp.Background,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(accent)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(Jp.SurfaceHigh)) {
            listOf(15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem(text = { Text(if (m < 60) "$m minuter" else if (m == 60) "1 timme" else "1,5 timme") }, onClick = {
                    p.setSleepTimer(m)
                    open = false
                })
            }
            DropdownMenuItem(text = { Text("När låten är slut") }, onClick = {
                p.sleepAtEndOfTrack()
                open = false
            })
            if (active) {
                DropdownMenuItem(text = { Text("Stäng av sovtimern", color = Jp.Error) }, onClick = {
                    p.setSleepTimer(null)
                    open = false
                })
            }
        }
    }
}

@Composable
private fun QualityChip(track: Track, transcoded: Boolean, downloaded: Boolean) {
    val text = when {
        downloaded -> "Nedladdad" + (Format.quality(track.codec, null)?.let { " · $it" } ?: "")
        transcoded -> "Omkodad"
        else -> Format.quality(track.codec, track.bitrate) ?: return
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Jp.Text.copy(alpha = 0.75f),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.09f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun CoverPanel(track: Track, playing: Boolean, onSwipe: (Int) -> Unit, dragToClose: () -> Modifier) {
    val scale by animateFloatAsState(if (playing) 1f else 0.86f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessLow), label = "cover")
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    Box(Modifier.fillMaxSize().then(dragToClose()), contentAlignment = Alignment.Center) {
        Artwork(
            track.thumb,
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = dx.value
                    rotationZ = dx.value / 60f
                }
                .shadow(28.dp, RoundedCornerShape(14.dp))
                .pointerInput(track.ratingKey) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                val v = dx.value
                                if (abs(v) > threshold) onSwipe(if (v < 0) -1 else 1)
                                dx.animateTo(0f, spring(dampingRatio = 0.7f))
                            }
                        },
                    ) { change, d ->
                        change.consume()
                        scope.launch { dx.snapTo(dx.value + d) }
                    }
                },
            size = 1000,
            shape = RoundedCornerShape(14.dp),
        )
    }
}

/** Tidslinjen: tryck eller dra för att spola. */
@Composable
fun SeekBar(position: Long, duration: Long, buffered: Long, accent: Color, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thick by animateDpAsState(if (dragging) 7.dp else 4.dp, label = "thick")

    Column(Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .pointerInput(duration) {
                    detectTapGestures { o ->
                        if (duration > 0) onSeek((o.x / size.width * duration).toLong().coerceIn(0, duration))
                    }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o ->
                            dragging = true
                            dragFraction = (o.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            if (duration > 0) onSeek((dragFraction * duration).toLong())
                            dragging = false
                        },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val thickPx = with(LocalDensity.current) { thick.toPx() }
            val thumbR = with(LocalDensity.current) { (if (dragging) 9.dp else 6.dp).toPx() }
            Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                val y = size.height / 2
                val r = CornerRadius(thickPx / 2, thickPx / 2)
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y - thickPx / 2), Size(size.width, thickPx), r)
                drawRoundRect(Color.White.copy(alpha = 0.28f), Offset(0f, y - thickPx / 2), Size(size.width * bufferedFraction, thickPx), r)
                drawRoundRect(accent, Offset(0f, y - thickPx / 2), Size(size.width * fraction, thickPx), r)
                drawCircle(accent, thumbR, Offset(size.width * fraction, y))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val shown = if (dragging) (dragFraction * duration).toLong() else position
            Text(Format.duration(shown), style = MaterialTheme.typography.labelMedium, color = Jp.Text.copy(alpha = 0.65f))
            Spacer(Modifier.weight(1f))
            Text(
                if (duration > 0) "-" + Format.duration(duration - shown) else "",
                style = MaterialTheme.typography.labelMedium,
                color = Jp.Text.copy(alpha = 0.65f),
            )
        }
    }
}

// ---------- Låttext ----------

@Composable
private fun LyricsPanel(vm: AppViewModel, track: Track, accent: Color) {
    val g = LocalGraph.current
    val p = vm.player
    val lyrics = rememberLoadable(track.ratingKey) {
        if (!g.session.isReady) {
            Lyrics.EMPTY
        } else {
            val ref = g.library.trackDetails(track.ratingKey).lyrics
            if (ref == null) Lyrics.EMPTY else Lyrics.parse(g.library.lyricsText(ref))
        }
    }
    when (val l = lyrics.data) {
        null -> if (lyrics.state is se.jpfm.ui.Load.Failed) {
            EmptyBox(Icons.Rounded.Mic, "Ingen låttext", "Kunde inte hämta texten just nu.")
        } else {
            LoadingBox()
        }
        else -> if (l.lines.isEmpty()) {
            EmptyBox(
                Icons.Rounded.Mic,
                "Ingen låttext",
                "Plex har ingen text till den här låten. Med Plex Pass hämtar servern text automatiskt när den finns.",
            )
        } else if (l.synced) {
            SyncedLyrics(l, p.position) { p.seekTo(it) }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
                l.lines.forEach {
                    Text(it.text, style = MaterialTheme.typography.titleLarge, color = Jp.Text.copy(alpha = 0.9f), modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun SyncedLyrics(l: Lyrics, position: Long, onSeek: (Long) -> Unit) {
    val state = rememberLazyListState()
    // Visa raden en aning innan den sjungs.
    val current = l.indexAt(position + 350)
    var userScrollAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isScrollInProgress) {
        if (state.isScrollInProgress) userScrollAt = System.currentTimeMillis()
    }
    LaunchedEffect(current) {
        if (current >= 0 && System.currentTimeMillis() - userScrollAt > 4_000) {
            state.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 24.dp)) {
        itemsIndexed(l.lines) { i, line ->
            val active = i == current
            val alpha by animateFloatAsState(
                when {
                    active -> 1f
                    i < current -> 0.38f
                    else -> 0.55f
                },
                tween(300),
                label = "line",
            )
            Text(
                line.text.ifBlank { "♪" },
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp, lineHeight = 31.sp),
                fontWeight = if (active) FontWeight.Black else FontWeight.Bold,
                color = (if (active) Color.White else Jp.Text).copy(alpha = alpha),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { line.timeMs?.let(onSeek) }
                    .padding(vertical = 7.dp),
            )
        }
    }
}

// ---------- Kön ----------

@Composable
private fun QueuePanel(vm: AppViewModel, accent: Color) {
    val p = vm.player
    val pos = p.queue.indexOfFirst { it.index == p.currentIndex }
    val now = p.queue.getOrNull(pos)
    val upcoming = if (pos >= 0) p.queue.drop(pos + 1) else p.queue
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
        if (now != null) {
            item(key = "now-h") { QueueHeader("Spelas nu") }
            item(key = "now") { QueueRow(now, true, p.isPlaying, accent, onClick = {}, onMore = { vm.menu = MenuTarget.ForTrack(now.track, now.index) }) }
        }
        item(key = "next-h") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                QueueHeader(if (upcoming.isEmpty()) "Inget mer i kön" else "Härnäst · ${Format.tracks(upcoming.size)}")
                Spacer(Modifier.weight(1f))
                if (upcoming.isNotEmpty()) TextButton(onClick = { p.clearUpcoming() }) { Text("Rensa", color = Jp.TextDim) }
            }
        }
        itemsIndexed(upcoming, key = { _, e -> "${e.index}:${e.mediaId}" }) { _, e ->
            val dismiss = rememberSwipeToDismissBoxState(
                confirmValueChange = {
                    if (it != SwipeToDismissBoxValue.Settled) {
                        p.removeAt(e.index)
                        true
                    } else {
                        false
                    }
                },
            )
            SwipeToDismissBox(
                state = dismiss,
                backgroundContent = {
                    Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                        Text("Ta bort", color = Jp.TextDim, style = MaterialTheme.typography.labelLarge)
                    }
                },
            ) {
                QueueRow(e, false, false, accent, onClick = { p.skipTo(e.index) }, onMore = { vm.menu = MenuTarget.ForTrack(e.track, e.index) })
            }
        }
    }
}

@Composable
private fun QueueHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = Jp.Text.copy(alpha = 0.75f),
        modifier = Modifier.padding(vertical = 10.dp),
    )
}

@Composable
private fun QueueRow(e: QueueEntry, current: Boolean, playing: Boolean, accent: Color, onClick: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp)) {
            Artwork(e.track.thumb, Modifier.fillMaxSize(), size = 144, shape = RoundedCornerShape(6.dp))
            if (current) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    EqualizerBars(playing, accent, Modifier.size(16.dp))
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.track.title, style = MaterialTheme.typography.bodyLarge, color = if (current) accent else Jp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(e.track.displayArtist, style = MaterialTheme.typography.bodySmall, color = Jp.Text.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Mer", tint = Jp.Text.copy(alpha = 0.6f)) }
    }
}
