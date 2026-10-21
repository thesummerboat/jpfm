package se.jpfm.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import se.jpfm.download.CollectionProgress
import se.jpfm.plex.AlbumPage
import se.jpfm.plex.Track
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.PlayRow
import se.jpfm.ui.Screen
import se.jpfm.ui.TrackRow
import se.jpfm.ui.backgroundBrush
import se.jpfm.ui.rememberArtColors
import se.jpfm.ui.rememberLoadable

@Composable
fun AlbumScreen(vm: AppViewModel, key: String, title: String) {
    val g = LocalGraph.current
    val page = rememberLoadable("album", key) { force ->
        val offline = g.downloads.collection("album:$key")
        if (!g.session.isReady && offline != null) {
            val tracks = g.downloads.tracksOf(offline)
            AlbumPage(
                se.jpfm.plex.Album(key, offline.title, offline.subtitle, tracks.firstOrNull()?.artistKey, offline.thumb, year = tracks.firstOrNull()?.year, trackCount = tracks.size),
                tracks,
            )
        } else {
            g.library.albumPage(key, force)
        }
    }
    val album = page.data?.album
    val settings = LocalSettings.current
    val colors = rememberArtColors(album?.thumb, album?.ultraBlur, settings.dynamicColors)
    val base by animateColorAsState(colors.base, tween(500), label = "albumBase")

    DetailScaffold(title, onBack = { vm.nav.back() }, barColor = colors.deep, actions = {
        if (album != null) IconButton(onClick = { vm.menu = MenuTarget.ForAlbum(album) }) { Icon(Icons.Rounded.MoreVert, "Mer") }
    }) {
        when (val s = page.state) {
            is Load.Loading -> item { Box(Modifier.statusBarsPadding().padding(top = 60.dp)) { LoadingBox() } }
            is Load.Failed -> item { Box(Modifier.statusBarsPadding().padding(top = 60.dp)) { ErrorBox(s.message) { page.retry() } } }
            is Load.Ready -> {
                val a = s.data.album
                val tracks = s.data.tracks
                item(key = "header") {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.copy(base = base).backgroundBrush(1400f))
                            .statusBarsPadding()
                            .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Artwork(
                            a.thumb,
                            Modifier.size(236.dp).shadow(24.dp, RoundedCornerShape(10.dp)),
                            size = 720,
                            shape = RoundedCornerShape(10.dp),
                            placeholder = Icons.Rounded.Album,
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(a.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            a.artist,
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.accent,
                            modifier = Modifier.clickable(enabled = a.artistKey != null) {
                                a.artistKey?.let { vm.nav.push(Screen.Artist(it, a.artist)) }
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            listOfNotNull(
                                a.subformat ?: "Album",
                                a.year?.toString(),
                                Format.tracks(tracks.size),
                                Format.longDuration(tracks.sumOf { it.durationMs }),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = Jp.TextDim,
                        )
                        Spacer(Modifier.height(18.dp))
                        PlayRow(
                            onPlay = { vm.playAlbum(key) },
                            onShuffle = { vm.playAlbum(key, shuffle = true) },
                            accent = colors.accent,
                        ) {
                            DownloadButton(
                                id = "album:$key",
                                title = a.title,
                                onDownload = { vm.downloadAlbum(a) },
                                onRemove = { vm.removeDownload("album:$key", a.title) },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
                val discs = tracks.map { it.disc ?: 1 }.distinct()
                discs.forEach { disc ->
                    if (discs.size > 1) {
                        item(key = "disc:$disc") {
                            Text(
                                "Skiva $disc",
                                style = MaterialTheme.typography.labelLarge,
                                color = Jp.TextDim,
                                modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp),
                            )
                        }
                    }
                    items(tracks.filter { (it.disc ?: 1) == disc }, key = { "t:${it.ratingKey}" }) { t ->
                        AlbumTrack(vm, t, key, colors.accent)
                    }
                }
                item(key = "footer") {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp)) {
                        if (!a.summary.isNullOrBlank()) {
                            Text(a.summary, style = MaterialTheme.typography.bodyMedium, color = Jp.TextDim)
                            Spacer(Modifier.height(10.dp))
                        }
                        listOfNotNull(a.studio, a.genres.takeIf { it.isNotEmpty() }?.joinToString(", ")).forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Jp.TextFaint)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumTrack(vm: AppViewModel, t: Track, albumKey: String, accent: androidx.compose.ui.graphics.Color) {
    val g = LocalGraph.current
    val states by g.downloads.states.collectAsState()
    val current = vm.player.current?.ratingKey == t.ratingKey
    TrackRow(
        track = t,
        onClick = { vm.playAlbum(albumKey, t.ratingKey) },
        onMore = { vm.menu = MenuTarget.ForTrack(t) },
        number = t.index,
        showArt = false,
        showArtist = false,
        isCurrent = current,
        isPlaying = current && vm.player.isPlaying,
        downloaded = states[t.ratingKey] == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED,
        accent = accent,
    )
}

/** Ladda ner / laddar ner (ring) / nedladdat. Tryck på nedladdat för att ta bort. */
@Composable
fun DownloadButton(id: String, title: String, onDownload: () -> Unit, onRemove: () -> Unit) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    val states by g.downloads.states.collectAsState()
    val c = cols.firstOrNull { it.id == id }
    val progress: CollectionProgress? = c?.let { g.downloads.progress(it, states) }
    var confirm by remember { mutableStateOf(false) }
    IconButton(onClick = { if (c == null) onDownload() else confirm = true }) {
        when {
            c == null -> Icon(Icons.Rounded.Download, "Ladda ner", tint = Jp.TextDim)
            progress != null && !progress.complete -> Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.size(26.dp),
                    color = Jp.Accent,
                    trackColor = Jp.SurfaceHigher,
                    strokeWidth = 2.5.dp,
                )
            }
            else -> Icon(Icons.Rounded.DownloadDone, "Nedladdad", tint = Jp.Accent)
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            containerColor = Jp.SurfaceHigh,
            title = { Text("Ta bort $title från telefonen?") },
            text = {
                Text(
                    if (progress != null && !progress.complete) "${progress.done} av ${progress.total} låtar är nedladdade." else "Du kan ladda ner igen när du vill.",
                )
            },
            confirmButton = { TextButton(onClick = { onRemove(); confirm = false }) { Text("Ta bort", color = Jp.Error, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Behåll") } },
        )
    }
}
