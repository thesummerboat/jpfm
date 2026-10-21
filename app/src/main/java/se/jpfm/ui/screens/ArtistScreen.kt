package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.plex.Album
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.MusicItemCard
import se.jpfm.ui.PlayRow
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.TrackRow
import se.jpfm.ui.rememberArtColors
import se.jpfm.ui.rememberLoadable

@Composable
fun ArtistScreen(vm: AppViewModel, key: String, title: String) {
    val g = LocalGraph.current
    val page = rememberLoadable("artist", key) { force -> g.library.artistPage(key, force) }
    val artist = page.data?.artist
    val colors = rememberArtColors(artist?.thumb, enabled = LocalSettings.current.dynamicColors)
    var showAllPopular by remember { mutableStateOf(false) }

    DetailScaffold(title, onBack = { vm.nav.back() }, barColor = colors.deep, actions = {
        if (artist != null) IconButton(onClick = { vm.menu = MenuTarget.ForArtist(artist) }) { Icon(Icons.Rounded.MoreVert, "Mer") }
    }) {
        when (val s = page.state) {
            is Load.Loading -> item { Box(Modifier.statusBarsPadding().padding(top = 60.dp)) { LoadingBox() } }
            is Load.Failed -> item { Box(Modifier.statusBarsPadding().padding(top = 60.dp)) { ErrorBox(s.message) { page.retry() } } }
            is Load.Ready -> {
                val p = s.data
                val a = p.artist
                item(key = "hero") {
                    Box(Modifier.fillMaxWidth().height(380.dp)) {
                        Artwork(
                            a.art ?: a.thumb,
                            Modifier.fillMaxSize(),
                            size = 1200,
                            shape = RectangleShape,
                            placeholder = Icons.Rounded.Person,
                        )
                        Box(
                            Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.35f),
                                    0.45f to Color.Transparent,
                                    0.85f to Jp.Background.copy(alpha = 0.85f),
                                    1f to Jp.Background,
                                ),
                            ),
                        )
                        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 12.dp)) {
                            if (a.genres.isNotEmpty()) {
                                Text(a.genres.take(3).joinToString(" · ").uppercase(), style = MaterialTheme.typography.labelSmall, color = colors.accent)
                                Spacer(Modifier.height(4.dp))
                            }
                            Text(a.title, style = MaterialTheme.typography.displayMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                item(key = "play") {
                    PlayRow(
                        onPlay = { vm.artistRadio(a) },
                        onShuffle = { vm.artistShuffle(a) },
                        playLabel = if (p.station != null) "Artistradio" else "Radio",
                        playIcon = Icons.Rounded.Radio,
                        accent = colors.accent,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    )
                }
                if (p.popular.isNotEmpty()) {
                    item(key = "pop-h") { SectionHeader("Populärt") }
                    val shown = if (showAllPopular) p.popular else p.popular.take(5)
                    itemsIndexed(shown, key = { _, t -> "pop:${t.ratingKey}" }) { i, t ->
                        val current = vm.player.current?.ratingKey == t.ratingKey
                        TrackRow(
                            track = t,
                            onClick = { vm.playTracks(p.popular, i, "Populärt: ${a.title}") },
                            onMore = { vm.menu = MenuTarget.ForTrack(t) },
                            number = i + 1,
                            showArt = true,
                            showArtist = false,
                            isCurrent = current,
                            isPlaying = current && vm.player.isPlaying,
                            accent = colors.accent,
                        )
                    }
                    if (p.popular.size > 5) {
                        item(key = "pop-more") {
                            TextButton(onClick = { showAllPopular = !showAllPopular }, modifier = Modifier.padding(start = 8.dp)) {
                                Text(if (showAllPopular) "Visa färre" else "Visa fler", color = Jp.TextDim)
                            }
                        }
                    }
                }
                groupAlbums(p.albums).forEach { (label, albums) ->
                    item(key = "alb-h:$label") { SectionHeader(label) }
                    item(key = "alb:$label") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(albums, key = { it.ratingKey }) { al ->
                                AlbumCard(al, onClick = { vm.nav.push(Screen.Album(al.ratingKey, al.title)) }, onLongClick = { vm.menu = MenuTarget.ForAlbum(al) }, showArtist = false)
                            }
                        }
                    }
                }
                p.related.forEach { hub ->
                    item(key = "rel-h:${hub.identifier}") { SectionHeader(hub.title) }
                    item(key = "rel:${hub.identifier}") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            itemsIndexed(hub.items, key = { i, it -> "$i:${it.ratingKey}" }) { i, it ->
                                MusicItemCard(it, i, onOpen = { m -> vm.open(m) }, onLongClick = { m -> vm.longPress(m) })
                            }
                        }
                    }
                }
                if (!a.summary.isNullOrBlank()) {
                    item(key = "about-h") { SectionHeader("Om ${a.title}") }
                    item(key = "about") {
                        var expanded by remember { mutableStateOf(false) }
                        Column(Modifier.padding(horizontal = 20.dp)) {
                            Text(
                                a.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = Jp.TextDim,
                                maxLines = if (expanded) Int.MAX_VALUE else 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Visa mindre" else "Läs mer", color = Jp.Text) }
                        }
                    }
                }
            }
        }
    }
}

/** Delar upp artistens skivor som i Plex: album, singlar och EP, live, samlingar. */
private fun groupAlbums(albums: List<Album>): List<Pair<String, List<Album>>> {
    fun kind(a: Album): String {
        val f = a.subformat?.lowercase().orEmpty()
        return when {
            "single" in f || "ep" == f || f.contains(" ep") || f.startsWith("ep") -> "Singlar och EP"
            "live" in f -> "Livealbum"
            "compilation" in f || "samling" in f -> "Samlingar"
            "remix" in f -> "Remixer"
            else -> "Album"
        }
    }
    val order = listOf("Album", "Singlar och EP", "Livealbum", "Samlingar", "Remixer")
    return albums.groupBy(::kind).toList().sortedBy { order.indexOf(it.first) }
}
