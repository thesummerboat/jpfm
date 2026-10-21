package se.jpfm.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jpfm.ui.Artwork
import se.jpfm.ui.Jp
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.PlayerConnection
import se.jpfm.ui.over
import se.jpfm.ui.rememberArtColors
import kotlin.math.abs

/** Liten spelare ovanför menyn. Tryck för att öppna, svep åt sidan för nästa/föregående. */
@Composable
fun MiniPlayer(p: PlayerConnection, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val track = p.current ?: return
    val dynamic = LocalSettings.current.dynamicColors
    val colors = rememberArtColors(track.thumb, enabled = dynamic)
    val bg by animateColorAsState(colors.base.over(Jp.SurfaceHigh, 0.75f), tween(600), label = "miniBg")
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(62.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onOpen)
            .pointerInput(track.ratingKey) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val v = offset.value
                        scope.launch {
                            when {
                                v < -threshold -> p.next()
                                v > threshold -> p.previous()
                            }
                            offset.animateTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f) } },
                ) { change, dx ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + dx) }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .graphicsLayer {
                    translationX = offset.value * 0.6f
                    alpha = 1f - (abs(offset.value) / (threshold * 3)).coerceIn(0f, 0.6f)
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.thumb, Modifier.size(46.dp), size = 144, shape = RoundedCornerShape(7.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.displayArtist, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                if (p.isBuffering) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Jp.Text, strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = { p.playPause() }) {
                        Icon(if (p.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (p.isPlaying) "Pausa" else "Spela", modifier = Modifier.size(30.dp))
                    }
                }
            }
            IconButton(onClick = { p.next() }) { Icon(Icons.Rounded.SkipNext, "Nästa", modifier = Modifier.size(28.dp)) }
        }
        // Hur långt låten kommit.
        val fraction = if (p.duration > 0) (p.position.toFloat() / p.duration).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(Jp.Text.copy(alpha = 0.15f)),
        ) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.accent))
        }
    }
}
