package se.jpfm.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Forward30
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import se.jpfm.radio.RadioStation
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.Jp
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.rememberArtColors
import kotlin.math.abs

private val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f)

/** Spelarvyn för radio (LIVE, byt kanal) och poddar (spola, hastighet). */
@Composable
fun ExtNowPlaying(vm: AppViewModel, onClose: () -> Unit) {
    val p = vm.player
    val g = LocalGraph.current
    val settings = LocalSettings.current
    val station = p.nowStation
    val episode = p.nowEpisode
    val thumb = p.nowThumb
    val art = rememberArtColors(thumb, enabled = settings.dynamicColors)
    val base = station?.color?.let { Color(it).copy(alpha = 1f) }?.let { lerpDark(it) } ?: art.base
    val top by animateColorAsState(base, tween(700), label = "top")
    val mid by animateColorAsState(art.deep, tween(700), label = "mid")
    val accent by animateColorAsState(station?.color?.let { Color(it) } ?: art.accent, tween(700), label = "accent")
    val favorites by g.radio.favorites.collectAsState()

    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val closeAt = with(LocalDensity.current) { 140.dp.toPx() }
    val dragToClose = Modifier.pointerInput(Unit) {
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
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).then(dragToClose), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Stäng", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (station != null) "RADIO" else "PODD", style = MaterialTheme.typography.labelSmall, color = Jp.Text.copy(alpha = 0.6f))
                    Text(
                        station?.name ?: episode?.podcastTitle.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(48.dp))
            }

            // Kanalens logga eller poddens omslag. Svep åt sidan för nästa/föregående kanal.
            Box(Modifier.weight(1f).fillMaxWidth().then(dragToClose), contentAlignment = Alignment.Center) {
                if (station != null) {
                    StationCover(station, thumb, accent, onSwipe = { dir -> if (dir < 0) p.next() else p.previous() })
                } else {
                    Artwork(
                        thumb,
                        Modifier.fillMaxWidth().aspectRatio(1f).shadow(28.dp, RoundedCornerShape(14.dp)),
                        size = 1000,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        p.nowTitle ?: station?.name ?: episode?.title.orEmpty(),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000),
                    )
                    Text(
                        p.nowSubtitle.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Jp.Text.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (station != null) {
                    val fav = favorites.any { it.id == station.id }
                    IconButton(onClick = {
                        g.radio.toggleFavorite(station)
                        vm.toast(if (fav) "${station.name} borttagen från dina kanaler" else "${station.name} tillagd i dina kanaler")
                    }) {
                        Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favorit", tint = if (fav) Jp.Love else Jp.Text, modifier = Modifier.size(28.dp))
                    }
                } else if (episode != null) {
                    IconButton(onClick = {
                        g.podcasts.setPlayed(episode, true)
                        vm.toast("Markerad som lyssnad")
                        p.next()
                    }) { Icon(Icons.Rounded.CheckCircle, "Markera som lyssnad", tint = Jp.Text.copy(alpha = 0.8f)) }
                }
            }

            Spacer(Modifier.height(14.dp))
            if (station != null) {
                LiveBar(station)
            } else {
                SeekBar(position = p.position, duration = p.duration, buffered = p.buffered, accent = Jp.Text, onSeek = { p.seekTo(it) })
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (station != null) {
                    IconButton(onClick = { p.previous() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipPrevious, "Föregående kanal", modifier = Modifier.size(40.dp))
                    }
                } else {
                    IconButton(onClick = { p.seekBack() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.Replay10, "Tio sekunder tillbaka", modifier = Modifier.size(36.dp))
                    }
                }
                Box(
                    Modifier.size(76.dp).clip(CircleShape).background(Jp.Text).clickable { p.playPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (p.isBuffering) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Jp.Background, strokeWidth = 3.dp)
                    } else {
                        Icon(if (p.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p.isPlaying) "Pausa" else "Spela", tint = Jp.Background, modifier = Modifier.size(44.dp))
                    }
                }
                if (station != null) {
                    IconButton(onClick = { p.next() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.SkipNext, "Nästa kanal", modifier = Modifier.size(40.dp))
                    }
                } else {
                    IconButton(onClick = { p.seekForward() }, modifier = Modifier.size(56.dp)) {
                        Icon(Icons.Rounded.Forward30, "30 sekunder framåt", modifier = Modifier.size(36.dp))
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (episode != null) {
                    val label = (if (p.speed % 1f == 0f) p.speed.toInt().toString() else p.speed.toString().replace('.', ',')) + "×"
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable {
                                val i = speeds.indexOfFirst { abs(it - p.speed) < 0.01f }
                                p.setPlaybackSpeed(speeds[(i + 1).mod(speeds.size)])
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                SleepButton(p, accent)
            }
        }

        p.error?.let { err ->
            Text(
                err,
                style = MaterialTheme.typography.bodySmall,
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

private fun lerpDark(c: Color): Color = androidx.compose.ui.graphics.lerp(c, Color.Black, 0.45f)

/** Stor ruta med kanalens logga på kanalens färg. */
@Composable
private fun StationCover(station: RadioStation, logo: String?, accent: Color, onSwipe: (Int) -> Unit) {
    val dx = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val context = LocalContext.current
    Box(
        Modifier
            .fillMaxWidth(0.86f)
            .aspectRatio(1f)
            .graphicsLayer {
                translationX = dx.value
                rotationZ = dx.value / 60f
            }
            .shadow(28.dp, RoundedCornerShape(22.dp))
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(accent, lerpDark(accent))))
            .pointerInput(station.id) {
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
        contentAlignment = Alignment.Center,
    ) {
        if (logo != null) {
            Box(Modifier.fillMaxSize(0.62f).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(logo).crossfade(true).build(),
                    contentDescription = station.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(14.dp),
                )
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Radio, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(10.dp))
                Text(station.name, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            }
        }
    }
}

/** Röd prick och LIVE i stället för tidslinje. */
@Composable
private fun LiveBar(station: RadioStation) {
    val t = rememberInfiniteTransition(label = "live")
    val pulse by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = pulse }.clip(CircleShape).background(Color(0xFFFF4D4D)))
        Spacer(Modifier.width(8.dp))
        Text("LIVE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        val info = listOfNotNull(
            station.tagline?.takeIf { it.length < 60 },
            station.codec?.uppercase(),
            station.bitrate?.let { "$it kbps" },
        ).joinToString(" · ")
        Text(info, style = MaterialTheme.typography.bodySmall, color = Jp.Text.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
