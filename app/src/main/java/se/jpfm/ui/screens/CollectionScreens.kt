package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.exoplayer.offline.Download
import se.jpfm.plex.Hub
import se.jpfm.plex.Playlist
import se.jpfm.plex.Track
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadContent
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.LocalSettings
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.MusicItemCard
import se.jpfm.ui.PlayRow
import se.jpfm.ui.Screen
import se.jpfm.ui.TrackRow
import se.jpfm.ui.backgroundBrush
import se.jpfm.ui.rememberArtColors
import se.jpfm.ui.rememberLoadable

// ---------- Spellista ----------

@Composable
fun PlaylistScreen(vm: AppViewModel, key: String, title: String) {
    val g = LocalGraph.current
    val tracks = rememberLoadable("playlist", key) { force ->
        val offline = g.downloads.collection("playlist:$key")
        if (!g.session.isReady && offline != null) g.downloads.tracksOf(offline) else g.library.playlistTracks(key, force)
    }
    val meta = rememberLoadable("playlist-meta", key) {
        if (g.session.isReady) g.library.playlists().firstOrNull { it.ratingKey == key } else null
    }
    val playlist = meta.data ?: Playlist(key, title, g.downloads.collection("playlist:$key")?.thumb)
    val colors = rememberArtColors(playlist.thumb, enabled = LocalSettings.current.dynamicColors)
    val states by g.downloads.states.collectAsState()

    DetailScaffold(title, onBack = { vm.nav.back() }, barColor = colors.deep, actions = {
        IconButton(onClick = { vm.menu = MenuTarget.ForPlaylist(playlist) }) { Icon(Icons.Rounded.MoreVert, "Mer") }
    }) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundBrush(1400f))
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(playlist.thumb, Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(10.dp)), size = 660, shape = RoundedCornerShape(10.dp), placeholder = Icons.Rounded.QueueMusic)
                Spacer(Modifier.height(18.dp))
                Text(playlist.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                val list = tracks.data.orEmpty()
                Text(
                    listOfNotNull(
                        if (playlist.smart) "Smart spellista" else "Spellista",
                        Format.tracks(list.size),
                        list.sumOf { it.durationMs }.takeIf { it > 0 }?.let { Format.longDuration(it) },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = Jp.TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                PlayRow(
                    onPlay = { vm.playPlaylist(key, title = playlist.title) },
                    onShuffle = { vm.playPlaylist(key, shuffle = true, title = playlist.title) },
                    accent = colors.accent,
                ) {
                    DownloadButton(
                        id = "playlist:$key",
                        title = playlist.title,
                        onDownload = { vm.downloadPlaylist(playlist) },
                        onRemove = { vm.removeDownload("playlist:$key", playlist.title) },
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        when (val s = tracks.state) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item { ErrorBox(s.message) { tracks.retry() } }
            is Load.Ready -> itemsIndexed(s.data, key = { i, t -> "$i:${t.ratingKey}" }) { _, t ->
                val current = vm.player.current?.ratingKey == t.ratingKey
                TrackRow(
                    track = t,
                    onClick = { vm.playPlaylist(key, t.ratingKey, title = playlist.title) },
                    onMore = { vm.menu = MenuTarget.ForTrack(t) },
                    isCurrent = current,
                    isPlaying = current && vm.player.isPlaying,
                    downloaded = states[t.ratingKey] == Download.STATE_COMPLETED,
                    accent = colors.accent,
                )
            }
        }
    }
}

// ---------- Genre ----------

@Composable
fun GenreScreen(vm: AppViewModel, genre: se.jpfm.plex.Genre) {
    val g = LocalGraph.current
    val albums = rememberLoadable("genre", genre.id) { g.library.albumsInGenre(genre) }
    GridPage(genre.title, onBack = { vm.nav.back() }) {
        LoadContent(albums) { list ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PlayRow(
                        onPlay = { vm.playLoaded("Genre: ${genre.title}") { vm.graph.library.randomTracksInGenre(genre) } },
                        onShuffle = null,
                        playLabel = "Blanda ${genre.title}",
                        playIcon = Icons.Rounded.Shuffle,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                itemsIndexed(list, key = { _, a -> a.ratingKey }) { _, a ->
                    AlbumCard(a, onClick = { vm.nav.push(Screen.Album(a.ratingKey, a.title)) }, onLongClick = { vm.menu = MenuTarget.ForAlbum(a) }, width = 400.dp)
                }
            }
        }
    }
}

// ---------- En hel rad från hemskärmen ----------

@Composable
fun HubScreen(vm: AppViewModel, hub: Hub) {
    GridPage(hub.title, onBack = { vm.nav.back() }) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(hub.items, key = { i, it -> "$i:${it.ratingKey}" }) { i, item ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    MusicItemCard(item, i, onOpen = { vm.open(it) }, onLongClick = { vm.longPress(it) })
                }
            }
        }
    }
}

// ---------- Nedladdad samling ----------

@Composable
fun OfflineScreen(vm: AppViewModel, id: String, title: String) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    val states by g.downloads.states.collectAsState()
    val c = cols.firstOrNull { it.id == id }
    if (c == null) {
        GridPage(title, onBack = { vm.nav.back() }) { ErrorBox("Nedladdningen finns inte längre.") }
        return
    }
    val tracks = g.downloads.tracksOf(c)
    val colors = rememberArtColors(c.thumb, enabled = LocalSettings.current.dynamicColors)
    DetailScaffold(title, onBack = { vm.nav.back() }, barColor = colors.deep) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(colors.backgroundBrush(1400f))
                    .statusBarsPadding()
                    .padding(top = 56.dp, start = 20.dp, end = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Artwork(c.thumb, Modifier.size(220.dp).shadow(24.dp, RoundedCornerShape(10.dp)), size = 660, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(18.dp))
                Text(c.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                val p = g.downloads.progress(c, states)
                Text(
                    "${c.subtitle} · ${p.done} av ${p.total} nedladdade",
                    style = MaterialTheme.typography.bodySmall,
                    color = Jp.TextDim,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(18.dp))
                PlayRow(onPlay = { vm.playOffline(id) }, onShuffle = { vm.playOffline(id, shuffle = true) }, accent = colors.accent) {
                    DownloadButton(id, c.title, onDownload = {}, onRemove = {
                        vm.removeDownload(id, c.title)
                        vm.nav.back()
                    })
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        itemsIndexed(tracks, key = { i, t -> "$i:${t.ratingKey}" }) { _, t ->
            val current = vm.player.current?.ratingKey == t.ratingKey
            val done = states[t.ratingKey] == Download.STATE_COMPLETED
            TrackRow(
                track = t,
                onClick = { if (done) vm.playOffline(id, t.ratingKey) else vm.toast("Låten är inte nedladdad än") },
                onMore = { vm.menu = MenuTarget.ForTrack(t) },
                isCurrent = current,
                isPlaying = current && vm.player.isPlaying,
                downloaded = done,
                accent = colors.accent,
                modifier = Modifier.padding(0.dp),
            )
        }
    }
}

/** Enkel sida med rubrik och tillbakaknapp, för rutnät. */
@Composable
fun GridPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Tillbaka") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}
