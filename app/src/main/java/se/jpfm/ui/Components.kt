package se.jpfm.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.MusicItem
import se.jpfm.plex.Playlist
import se.jpfm.plex.Station
import se.jpfm.plex.Track

// ---------- Laddning ----------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val data: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loadable<T>(initial: Load<T>) {
    var state by mutableStateOf(initial)
        internal set
    var refreshing by mutableStateOf(false)
        internal set
    internal var trigger by mutableIntStateOf(0)
    internal var force = false

    val data: T? get() = (state as? Load.Ready<T>)?.data

    /** Hämtar på nytt från servern (dra-för-att-uppdatera). */
    fun refresh() {
        force = true
        refreshing = true
        trigger++
    }

    fun retry() {
        state = Load.Loading
        trigger++
    }
}

/** Senast hämtade data för rader som ska synas direkt när man kommer tillbaka (hemskärmen). */
private val kept = java.util.concurrent.ConcurrentHashMap<String, Any>()

/**
 * Hämtar data när skärmen visas och när [keys] ändras. Behåller gamla data medan nya laddas.
 * Med [keep] visas det som hämtades förra gången direkt, medan nytt hämtas i bakgrunden – så
 * att listan inte hoppar när man går tillbaka till den.
 */
@Composable
fun <T> rememberLoadable(vararg keys: Any?, keep: String? = null, load: suspend (force: Boolean) -> T): Loadable<T> {
    // Sparat per server och bibliotek, så att inget från en annan server visas efter ett byte.
    val memo = keep?.let { "${LocalServer.current?.machineId}|${LocalGraph.current.session.section?.key}|$it" }
    val l = remember(*keys, memo) {
        @Suppress("UNCHECKED_CAST")
        val old = memo?.let { kept[it] } as T?
        Loadable<T>(if (old != null) Load.Ready(old) else Load.Loading)
    }
    LaunchedEffect(l, l.trigger) {
        val force = l.force
        l.force = false
        try {
            val v = load(force)
            l.state = Load.Ready(v)
            if (memo != null && v != null) kept[memo] = v
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (l.state !is Load.Ready) l.state = Load.Failed(e.message ?: "Något gick fel")
        } finally {
            l.refreshing = false
        }
    }
    return l
}

@Composable
fun <T> LoadContent(l: Loadable<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = l.state) {
        is Load.Loading -> LoadingBox(modifier)
        is Load.Failed -> ErrorBox(s.message, modifier) { l.retry() }
        is Load.Ready -> content(s.data)
    }
}

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Jp.Accent, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
    }
}

@Composable
fun ErrorBox(message: String, modifier: Modifier = Modifier, onRetry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Jp.TextFaint, modifier = Modifier.size(44.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Jp.TextDim, textAlign = TextAlign.Center)
        if (onRetry != null) OutlinedButton(onClick = onRetry) { Text("Försök igen") }
    }
}

@Composable
fun EmptyBox(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 36.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Jp.SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Jp.Accent, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Jp.TextDim, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

// ---------- Rubriker och knappar ----------

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    subtitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action, color = Jp.TextDim, style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** Stor spela-knapp och blanda bredvid, som högst upp på album och artister. */
@Composable
fun PlayRow(
    onPlay: () -> Unit,
    onShuffle: (() -> Unit)?,
    modifier: Modifier = Modifier,
    playLabel: String = "Spela",
    playIcon: ImageVector = Icons.Rounded.PlayArrow,
    accent: Color = Jp.Accent,
    extra: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onPlay,
            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Jp.OnAccent),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
            shape = RoundedCornerShape(50),
        ) {
            Icon(playIcon, null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Text(playLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
        if (onShuffle != null) {
            FilledTonalIconButton(
                onClick = onShuffle,
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Jp.SurfaceHigher, contentColor = Jp.Text),
                modifier = Modifier.size(46.dp),
            ) { Icon(Icons.Rounded.Shuffle, "Blanda") }
        }
        Spacer(Modifier.weight(1f))
        extra()
    }
}

// ---------- Kort ----------

@Composable
fun AlbumCard(album: Album, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, width: Dp = 152.dp, showArtist: Boolean = true) {
    MediaCard(
        thumb = album.thumb,
        title = album.title,
        subtitle = if (showArtist) listOfNotNull(album.artist, album.year?.toString()).joinToString(" · ") else album.year?.toString() ?: album.subformat ?: "Album",
        width = width,
        placeholder = Icons.Rounded.Album,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

@Composable
fun PlaylistCard(p: Playlist, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, width: Dp = 152.dp) {
    MediaCard(p.thumb, p.title, Format.tracks(p.trackCount), width, Icons.Rounded.QueueMusic, onClick, onLongClick)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCard(
    thumb: String?,
    title: String,
    subtitle: String?,
    width: Dp,
    placeholder: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
    ) {
        Artwork(thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 360, shape = RoundedCornerShape(10.dp), placeholder = placeholder)
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistCard(artist: Artist, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, size: Dp = 128.dp) {
    Column(
        Modifier
            .width(size)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(artist.thumb, Modifier.size(size), size = 360, shape = CircleShape, placeholder = Icons.Rounded.Person)
        Spacer(Modifier.height(8.dp))
        Text(artist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Text("Artist", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
    }
}

private val stationGradients = listOf(
    listOf(Color(0xFF1FA67F), Color(0xFF0E5E8A)),
    listOf(Color(0xFF7B5CFF), Color(0xFF2D3B8F)),
    listOf(Color(0xFFE5577A), Color(0xFF6B2A7A)),
    listOf(Color(0xFFF0A23B), Color(0xFFA2402B)),
    listOf(Color(0xFF2BB3D9), Color(0xFF1C4E80)),
    listOf(Color(0xFF8BC34A), Color(0xFF1F6B4A)),
)

/** Radiostation eller mix: ett färgkort som ser ut som en knapp att starta musik med. */
@Composable
fun StationCard(station: Station, index: Int, onClick: () -> Unit, width: Dp = 176.dp) =
    RadioCard(station.title, "RADIO", station.thumb, index, onClick, width)

/**
 * Kort som startar radio: färgad botten, omslaget tonat in från höger och titeln nere till
 * vänster (som "Mixar för dig" i Spotify).
 */
@Composable
fun RadioCard(
    title: String,
    label: String,
    thumb: String?,
    index: Int,
    onClick: () -> Unit,
    width: Dp = 176.dp,
    icon: ImageVector = Icons.Rounded.Radio,
) {
    val colors = stationGradients[index % stationGradients.size]
    Box(
        Modifier
            .width(width)
            .height(112.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(colors))
            .combinedClickableCompat(onClick),
    ) {
        if (thumb != null) {
            Artwork(
                thumb,
                Modifier.align(Alignment.CenterEnd).size(112.dp),
                size = 336,
                shape = RoundedCornerShape(0.dp),
                placeholder = icon,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(Brush.horizontalGradient(0.25f to colors[0], 0.85f to colors[0].copy(alpha = 0.35f), 1f to Color.Transparent)),
            )
        } else {
            Icon(icon, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).size(34.dp))
        }
        Column(Modifier.align(Alignment.BottomStart).padding(14.dp).fillMaxWidth(0.82f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** Kort för vad som helst i en rad från Plex (album, artist, spellista, station). */
@Composable
fun MusicItemCard(item: MusicItem, index: Int, onOpen: (MusicItem) -> Unit, onLongClick: (MusicItem) -> Unit) {
    when (item) {
        is Album -> AlbumCard(item, { onOpen(item) }, { onLongClick(item) })
        is Artist -> ArtistCard(item, { onOpen(item) }, { onLongClick(item) })
        is Playlist -> PlaylistCard(item, { onOpen(item) }, { onLongClick(item) })
        is Station -> StationCard(item, index, { onOpen(item) })
        is Track -> MediaCard(item.thumb, item.title, item.displayArtist, 140.dp, Icons.Rounded.Album, { onOpen(item) }, { onLongClick(item) })
    }
}

// ---------- Låtrader ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    number: Int? = null,
    showArt: Boolean = true,
    showArtist: Boolean = true,
    isCurrent: Boolean = false,
    isPlaying: Boolean = false,
    downloaded: Boolean = false,
    accent: Color = Jp.Accent,
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .padding(start = if (showArt) 16.dp else 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showArt -> Box(Modifier.size(48.dp)) {
                Artwork(track.thumb, Modifier.fillMaxSize(), size = 144, shape = RoundedCornerShape(6.dp))
                if (isCurrent) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                        EqualizerBars(isPlaying, accent, Modifier.size(18.dp))
                    }
                }
            }
            else -> Box(Modifier.width(40.dp), contentAlignment = Alignment.Center) {
                if (isCurrent) {
                    EqualizerBars(isPlaying, accent, Modifier.size(16.dp))
                } else {
                    Text(number?.toString() ?: "", style = MaterialTheme.typography.bodyMedium, color = Jp.TextFaint)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isCurrent) accent else Jp.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                if (showArtist) add(track.displayArtist)
                if (!showArt && !showArtist && track.trackArtist != null) add(track.trackArtist)
            }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (downloaded) {
                    Icon(Icons.Rounded.DownloadDone, "Nedladdad", tint = Jp.Accent, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (track.isLoved) {
                    Icon(Icons.Rounded.Favorite, "Gillad", tint = Jp.Love, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                if (sub.isNotBlank()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else if (track.durationMs > 0) {
                    Text(Format.duration(track.durationMs), style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
                }
            }
        }
        if (sub(track, showArt, showArtist)) {
            Text(Format.duration(track.durationMs), style = MaterialTheme.typography.bodySmall, color = Jp.TextFaint, modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Mer", tint = Jp.TextDim) }
    }
}

private fun sub(t: Track, showArt: Boolean, showArtist: Boolean): Boolean =
    t.durationMs > 0 && (showArtist || showArt || t.trackArtist != null)

/** Tre staplar som dansar när låten spelas. */
@Composable
fun EqualizerBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "eq")
    val phases = listOf(420, 560, 360)
    val heights = phases.mapIndexed { i, ms ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ms, delayMillis = i * 90), RepeatMode.Reverse),
            label = "bar$i",
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { h ->
            val v = if (playing) h.value else 0.35f
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(v)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

@Composable
fun OfflineBanner(text: String, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Jp.SurfaceHigh)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = Jp.TextDim, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) { Text("Försök igen") }
    }
}
