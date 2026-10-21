package se.jpfm.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import se.jpfm.plex.ChartHit
import se.jpfm.plex.Mix
import se.jpfm.plex.Track
import se.jpfm.podcast.Episode
import se.jpfm.podcast.Progress

/** Färger för genrer, stämningar och mixar. Samma kategori får alltid samma färg. */
val CategoryColors = listOf(
    Color(0xFF1FA67F), Color(0xFF7B5CFF), Color(0xFFE5577A), Color(0xFFF0A23B), Color(0xFF2BB3D9),
    Color(0xFF8BC34A), Color(0xFFB0549E), Color(0xFF3F7DE8), Color(0xFFE0663C), Color(0xFF16A2A2),
)

fun categoryColor(key: String): Color = CategoryColors[(key.hashCode() and Int.MAX_VALUE) % CategoryColors.size]

/**
 * Kategori att bläddra i (genre, stämning, årtionde): färgad platta med namnet, och ett
 * snett omslag i hörnet om det finns – som "Bläddra" i Spotify.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CategoryTile(
    title: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumb: String? = null,
    height: Dp = 92.dp,
    onLongClick: (() -> Unit)? = null,
) {
    Box(
        modifier
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.6f))))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (thumb != null) {
            Artwork(
                thumb,
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 14.dp, y = 6.dp)
                    .size(height * 0.7f)
                    .rotate(22f),
                size = 200,
                shape = RoundedCornerShape(6.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(14.dp).fillMaxWidth(if (thumb != null) 0.7f else 1f),
        )
    }
}

/**
 * Mixkort ("Dagens mix" i Spotify): omslag med en färgad list och mixens namn, och några av
 * artisterna under. Ett tryck spelar mixen direkt.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixCard(mix: Mix, index: Int, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, width: Dp = 156.dp, playing: Boolean = false) {
    val color = CategoryColors[(index * 3 + 1) % CategoryColors.size]
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.45f)))),
        ) {
            if (mix.thumb != null) {
                Artwork(mix.thumb, Modifier.fillMaxWidth().aspectRatio(1f), size = 360, shape = RoundedCornerShape(0.dp))
            }
            // Färgad list till vänster och mörkt fält nertill, så att namnet syns på alla omslag.
            Box(Modifier.fillMaxHeight().width(6.dp).background(color))
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))))
                    .padding(start = 14.dp, end = 10.dp, top = 30.dp, bottom = 10.dp),
            ) {
                Text(
                    mix.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            mix.label?.let { label ->
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 14.dp, top = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(if (playing) Jp.Accent else Color.Black.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                if (playing) {
                    EqualizerBars(true, Jp.OnAccent, Modifier.size(14.dp))
                } else {
                    Icon(Icons.Rounded.PlayArrow, "Spela", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            mix.subtitle ?: "Blandat ur ditt bibliotek",
            style = MaterialTheme.typography.bodySmall,
            color = Jp.TextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Låt på en topplista: placering, omslag, titel och artist. */
@Composable
fun RankedTrack(
    rank: Int,
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    extra: String? = null,
) = RankRow(
    rank = rank,
    thumb = trackThumb(track),
    title = track.title,
    subtitle = listOfNotNull(track.displayArtist, extra).joinToString(" · "),
    isCurrent = isCurrent,
    onClick = onClick,
    onMore = onMore,
    modifier = modifier,
)

/**
 * En plats på topplistan i Sverige. Finns låten hos dig spelas den; finns bara artisten blir
 * det artistradio; annars visas den nedtonad.
 */
@Composable
fun ChartEntry(
    hit: ChartHit,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    requestable: Boolean = false,
    requested: Boolean = false,
) {
    val t = hit.track
    RankRow(
        rank = hit.rank,
        thumb = t?.let { trackThumb(it) } ?: hit.song.artwork?.replace("100x100bb", "300x300bb"),
        title = t?.title ?: hit.song.title,
        subtitle = when {
            t != null -> t.displayArtist
            requested -> "${hit.song.artist} · önskad"
            requestable -> "${hit.song.artist} · tryck för att önska"
            hit.artist != null -> "${hit.song.artist} · artistradio"
            else -> "${hit.song.artist} · finns inte hos dig"
        },
        isCurrent = isCurrent,
        onClick = onClick,
        onMore = onMore,
        modifier = modifier,
        dim = t == null && !requestable,
        badge = when {
            t != null -> null
            requested -> Icons.Rounded.Schedule
            requestable -> Icons.Rounded.CloudDownload
            hit.artist != null -> Icons.Rounded.Radio
            else -> null
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RankRow(
    rank: Int,
    thumb: String?,
    title: String,
    subtitle: String,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onMore: (() -> Unit)?,
    modifier: Modifier = Modifier,
    dim: Boolean = false,
    badge: ImageVector? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onMore)
            .alpha(if (dim) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Black,
            color = if (rank <= 3) Jp.Accent else Jp.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(34.dp),
        )
        Box {
            Artwork(thumb, Modifier.size(50.dp), size = 150, shape = RoundedCornerShape(6.dp))
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(3.dp).size(18.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(badge, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) Jp.Accent else Jp.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onMore != null) {
            IconButton(onClick = onMore) { Icon(Icons.Rounded.MoreVert, "Mer", tint = Jp.TextDim) }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** Liten platta i snabbvalen överst på hemskärmen: bild till vänster, namn till höger. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QuickTile(
    title: String,
    thumb: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Album,
    artShape: Shape = RoundedCornerShape(0.dp),
    highlight: Brush? = null,
    active: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Jp.SurfaceHigh)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (highlight != null) {
            Box(Modifier.size(58.dp).background(highlight), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        } else {
            Artwork(thumb, Modifier.size(58.dp), size = 174, shape = artShape, placeholder = icon)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (active) Jp.Accent else Jp.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (active) {
            EqualizerBars(true, Jp.Accent, Modifier.padding(end = 10.dp).size(14.dp))
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

/** Poddavsnitt som kort: bild, titel och podd, och hur långt du lyssnat. */
@Composable
fun EpisodeCard(e: Episode, image: String?, progress: Progress?, playing: Boolean, onClick: () -> Unit, width: Dp = 152.dp) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickableSimple(onClick)
            .padding(bottom = 4.dp),
    ) {
        Box {
            Artwork(e.image ?: image, Modifier.fillMaxWidth().aspectRatio(1f), size = 360, shape = RoundedCornerShape(10.dp), placeholder = Icons.Rounded.Headset)
            if (progress != null && !progress.played && progress.positionMs > 0) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Jp.Accent,
                    trackColor = Color.Black.copy(alpha = 0.5f),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            e.title,
            style = MaterialTheme.typography.titleSmall,
            color = if (playing) Jp.Accent else Jp.Text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            listOf(e.podcastTitle, Format.date(e.publishedAt)).filter { it.isNotBlank() }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = Jp.TextDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableSimple(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

/** Rad med filterknappar (Allt, Musik, Radio, Poddar). */
@Composable
fun FilterChips(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (on) Jp.OnAccent else Jp.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) Jp.Accent else Jp.SurfaceHigher)
                    .combinedClickableSimple { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
