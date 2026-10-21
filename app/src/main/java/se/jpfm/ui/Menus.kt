package se.jpfm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.plex.Album
import se.jpfm.plex.Playlist
import se.jpfm.plex.Track

/** Bottenarket med val för en låt, ett album, en spellista eller en artist. */
@Composable
fun ItemMenu(vm: AppViewModel, target: MenuTarget, onDismiss: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val g = LocalGraph.current
    val online = g.session.isReady
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = Jp.SurfaceHigh) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            when (target) {
                is MenuTarget.ForTrack -> TrackMenu(vm, target, online, onDismiss)
                is MenuTarget.ForAlbum -> AlbumMenu(vm, target.album, online, onDismiss)
                is MenuTarget.ForPlaylist -> PlaylistMenu(vm, target.playlist, online, onDismiss)
                is MenuTarget.ForArtist -> {
                    MenuHeader(target.artist.thumb, target.artist.title, "Artist", round = true)
                    if (online) {
                        MenuItem(Icons.Rounded.Radio, "Starta artistradio") { vm.artistRadio(target.artist); onDismiss() }
                        MenuItem(Icons.Rounded.Shuffle, "Blanda alla låtar") { vm.artistShuffle(target.artist); onDismiss() }
                        MenuItem(Icons.Rounded.Person, "Gå till artisten") { vm.nav.push(Screen.Artist(target.artist.ratingKey, target.artist.title)); onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackMenu(vm: AppViewModel, target: MenuTarget.ForTrack, online: Boolean, onDismiss: () -> Unit) {
    val t = target.track
    MenuHeader(t.thumb, t.title, listOf(t.displayArtist, t.album).filter { it.isNotBlank() }.joinToString(" · "))
    MenuItem(Icons.Rounded.PlaylistPlay, "Spela härnäst") { vm.playNext(listOf(t)); onDismiss() }
    MenuItem(Icons.Rounded.QueueMusic, "Lägg till i kön") { vm.addToQueue(listOf(t)); onDismiss() }
    if (target.queueIndex != null && target.queueIndex != vm.player.currentIndex) {
        MenuItem(Icons.Rounded.RemoveCircleOutline, "Ta bort från kön") { vm.player.removeAt(target.queueIndex); onDismiss() }
    }
    if (online) {
        MenuItem(Icons.Rounded.Radio, "Starta låtradio") { vm.sonicRadio(t); onDismiss() }
        val loved = (if (vm.player.current?.ratingKey == t.ratingKey) vm.player.current else t)?.isLoved == true
        MenuItem(if (loved) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (loved) "Ta bort från gillade" else "Gilla") {
            if (vm.player.current?.ratingKey == t.ratingKey) vm.player.toggleLove() else vm.setLoved(t, !loved)
            onDismiss()
        }
        MenuItem(Icons.Rounded.PlaylistAdd, "Lägg till i spellista") { vm.addToPlaylist = listOf(t); onDismiss() }
        t.albumKey?.let { key ->
            MenuItem(Icons.Rounded.Album, "Gå till albumet") {
                vm.playerOpen = false
                vm.nav.push(Screen.Album(key, t.album))
                onDismiss()
            }
        }
        t.artistKey?.let { key ->
            MenuItem(Icons.Rounded.Person, "Gå till artisten") {
                vm.playerOpen = false
                vm.nav.push(Screen.Artist(key, t.artist))
                onDismiss()
            }
        }
    }
    val quality = Format.quality(t.codec, t.bitrate)
    if (quality != null) {
        Text(
            "$quality · ${Format.duration(t.durationMs)}",
            style = MaterialTheme.typography.labelMedium,
            color = Jp.TextFaint,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun AlbumMenu(vm: AppViewModel, a: Album, online: Boolean, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    val downloaded = cols.any { it.id == "album:${a.ratingKey}" }
    val target = MenuTarget.ForAlbum(a)
    MenuHeader(a.thumb, a.title, listOfNotNull(a.artist, a.year?.toString()).joinToString(" · "))
    MenuItem(Icons.Rounded.Shuffle, "Blanda") { vm.playAlbum(a.ratingKey, shuffle = true); onDismiss() }
    MenuItem(Icons.Rounded.PlaylistPlay, "Spela härnäst") { vm.withTracksOf(target) { vm.playNext(it) }; onDismiss() }
    MenuItem(Icons.Rounded.QueueMusic, "Lägg till i kön") { vm.withTracksOf(target) { vm.addToQueue(it) }; onDismiss() }
    if (online) {
        MenuItem(Icons.Rounded.PlaylistAdd, "Lägg till i spellista") { vm.withTracksOf(target) { vm.addToPlaylist = it }; onDismiss() }
    }
    if (downloaded) {
        MenuItem(Icons.Rounded.Delete, "Ta bort nedladdning") { vm.removeDownload("album:${a.ratingKey}", a.title); onDismiss() }
    } else if (online) {
        MenuItem(Icons.Rounded.Download, "Ladda ner") { vm.downloadAlbum(a); onDismiss() }
    }
    if (online && a.artistKey != null) {
        MenuItem(Icons.Rounded.Person, "Gå till artisten") { vm.nav.push(Screen.Artist(a.artistKey, a.artist)); onDismiss() }
    }
}

@Composable
private fun PlaylistMenu(vm: AppViewModel, p: Playlist, online: Boolean, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    val downloaded = cols.any { it.id == "playlist:${p.ratingKey}" }
    var confirmDelete by remember { mutableStateOf(false) }
    val target = MenuTarget.ForPlaylist(p)
    MenuHeader(p.thumb, p.title, Format.tracks(p.trackCount))
    MenuItem(Icons.Rounded.Shuffle, "Blanda") { vm.playPlaylist(p.ratingKey, shuffle = true, title = p.title); onDismiss() }
    MenuItem(Icons.Rounded.PlaylistPlay, "Spela härnäst") { vm.withTracksOf(target) { vm.playNext(it) }; onDismiss() }
    MenuItem(Icons.Rounded.QueueMusic, "Lägg till i kön") { vm.withTracksOf(target) { vm.addToQueue(it) }; onDismiss() }
    if (downloaded) {
        MenuItem(Icons.Rounded.DownloadDone, "Uppdatera nedladdning") { vm.downloadPlaylist(p); onDismiss() }
        MenuItem(Icons.Rounded.Delete, "Ta bort nedladdning") { vm.removeDownload("playlist:${p.ratingKey}", p.title); onDismiss() }
    } else if (online) {
        MenuItem(Icons.Rounded.Download, "Ladda ner") { vm.downloadPlaylist(p); onDismiss() }
    }
    if (online && !p.smart) {
        MenuItem(Icons.Rounded.Delete, "Radera spellistan") { confirmDelete = true }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Radera ${p.title}?") },
            text = { Text("Spellistan tas bort från Plex. Låtarna finns kvar i biblioteket.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(p)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Radera", color = Jp.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Avbryt") } },
            containerColor = Jp.SurfaceHigh,
        )
    }
}

@Composable
private fun MenuHeader(thumb: String?, title: String, subtitle: String, round: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(thumb, Modifier.size(56.dp), size = 168, shape = if (round) CircleShape else RoundedCornerShape(8.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Jp.Outline)
}

@Composable
private fun MenuItem(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Jp.TextDim, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Välj spellista att lägga låtarna i, eller skapa en ny. */
@Composable
fun AddToPlaylistDialog(vm: AppViewModel, tracks: List<Track>, onDismiss: () -> Unit) {
    val g = LocalGraph.current
    val playlists = rememberLoadable("playlists-pick") { g.library.playlists() }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Jp.SurfaceHigh,
        title = { Text(if (creating) "Ny spellista" else "Lägg till i spellista") },
        text = {
            if (creating) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Namn") })
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, null, tint = Jp.Accent)
                        Spacer(Modifier.width(14.dp))
                        Text("Ny spellista", color = Jp.Accent, style = MaterialTheme.typography.titleSmall)
                    }
                    LoadContent(playlists) { list ->
                        LazyColumn(Modifier.heightIn(max = 360.dp)) {
                            items(list.filter { !it.smart }, key = { it.ratingKey }) { p ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        vm.addTracksToPlaylist(p, tracks)
                                        onDismiss()
                                    }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Artwork(p.thumb, Modifier.size(40.dp), size = 120, shape = RoundedCornerShape(6.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(p.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(Format.tracks(p.trackCount), style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.createPlaylist(name.trim(), tracks)
                    onDismiss()
                }) { Text("Skapa") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Avbryt") } },
    )
}
