package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.playback.QueueBuilder
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.Hub
import se.jpfm.plex.MusicItem
import se.jpfm.plex.Playlist
import se.jpfm.plex.SessionState
import se.jpfm.plex.Station
import se.jpfm.plex.Track
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.MusicItemCard
import se.jpfm.ui.OfflineBanner
import se.jpfm.ui.RadioCard
import se.jpfm.ui.StationCard
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.Tab
import se.jpfm.ui.rememberLoadable

/** Öppnar eller spelar något från en rad. Delas av hem, sök och rader. */
fun AppViewModel.open(item: MusicItem) {
    when (item) {
        is Album -> nav.push(Screen.Album(item.ratingKey, item.title))
        is Artist -> nav.push(Screen.Artist(item.ratingKey, item.title))
        is Playlist -> nav.push(Screen.Playlist(item.ratingKey, item.title))
        is Station -> playStation(item)
        is Track -> sonicRadio(item)
    }
}

fun AppViewModel.longPress(item: MusicItem) {
    menu = when (item) {
        is Album -> MenuTarget.ForAlbum(item)
        is Artist -> MenuTarget.ForArtist(item)
        is Playlist -> MenuTarget.ForPlaylist(item)
        is Track -> MenuTarget.ForTrack(item)
        is Station -> null
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val session by g.session.state.collectAsState()
    val online = session is SessionState.Ready
    val section = (session as? SessionState.Ready)?.section?.key
    val hubs = rememberLoadable(section, online) { force -> if (online) g.library.homeHubs(force) else emptyList() }
    val list = rememberLazyListState()
    LaunchedEffect(vm.nav.reselect) { if (vm.nav.reselect > 0) list.animateScrollToItem(0) }

    PullToRefreshBox(
        isRefreshing = hubs.refreshing,
        onRefresh = {
            g.library.invalidate()
            hubs.refresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "top") { HomeTop(vm) }
            vm.update?.let { u ->
                item(key = "update") {
                    UpdateCard(u.versionName, vm.updateProgress) { vm.installUpdate() }
                }
            }
            when (val s = session) {
                is SessionState.Offline -> item(key = "offline") {
                    OfflineBanner("${s.reason}. Nedladdad musik fungerar ändå.") { g.session.connectInBackground() }
                }
                else -> Unit
            }
            item(key = "quick") { QuickActions(vm, online) }
            if (online) {
                // Radio först: det man oftast vill starta (och i bilen).
                item(key = "radio") { RadioRow(vm, hubs.data.orEmpty()) }
                when (val st = hubs.state) {
                    is Load.Loading -> item(key = "loading") { LoadingBox() }
                    is Load.Failed -> item(key = "err") { ErrorBox(st.message) { hubs.retry() } }
                    is Load.Ready -> st.data
                        // Rader med bara stationer visas redan under Radio.
                        .filter { h -> !h.items.all { it is Station } }
                        .forEachIndexed { i, hub ->
                            item(key = "hub:$i:${hub.identifier}") { HubRow(vm, hub) }
                        }
                }
            } else {
                item(key = "offline-hint") { OfflineHome(vm) }
            }
        }
    }
}

@Composable
private fun HomeTop(vm: AppViewModel) {
    val g = LocalGraph.current
    val name = g.prefs.userName?.substringBefore(' ')
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Jp.AccentDeep.copy(alpha = 0.28f), Color.Transparent)))
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
    ) {
        Column(Modifier.align(Alignment.CenterStart)) {
            Text(
                "JPFM",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Black,
                color = Jp.Accent,
            )
            Text(
                Format.greeting() + if (name != null) ", $name" else "",
                style = MaterialTheme.typography.headlineLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { vm.nav.push(Screen.Settings) }, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(Icons.Rounded.Settings, "Inställningar", tint = Jp.TextDim)
        }
    }
}

@Composable
private fun QuickActions(vm: AppViewModel, online: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        QuickTile(Icons.Rounded.Shuffle, "Blanda allt", Modifier.weight(1f), highlight = true) { vm.shuffleLibrary() }
        if (online) {
            QuickTile(Icons.Rounded.LibraryMusic, "Bibliotek", Modifier.weight(1f)) { vm.nav.select(Tab.LIBRARY) }
        }
        QuickTile(Icons.Rounded.DownloadForOffline, "Nedladdat", Modifier.weight(1f)) { vm.nav.select(Tab.DOWNLOADS) }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, text: String, modifier: Modifier, highlight: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier
            .height(54.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (highlight) Brush.linearGradient(listOf(Jp.AccentDeep, Color(0xFF16708F))) else Brush.linearGradient(listOf(Jp.SurfaceHigh, Jp.SurfaceHigh)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlight) Color.White else Jp.Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Radio: hela biblioteket blandat, Plex egna stationer och mixar, artistradio för artister du
 * lyssnat på och låtradio från senast spelade låtar. Allt fyller på sig självt.
 */
@Composable
private fun RadioRow(vm: AppViewModel, hubs: List<Hub>) {
    val items = hubs.flatMap { it.items }
    val stations = items.filterIsInstance<Station>().distinctBy { it.key }
    val artists = buildList {
        items.forEach { m ->
            when (m) {
                is Artist -> add(Triple(m.ratingKey, m.title, m.thumb))
                is Album -> m.artistKey?.let { add(Triple(it, m.artist, m.thumb)) }
                is Track -> m.artistKey?.let { add(Triple(it, m.artist, m.thumb)) }
                else -> Unit
            }
        }
    }.filter { it.second.isNotBlank() && !it.second.equals("Various Artists", ignoreCase = true) }
        .distinctBy { it.first }
        .take(8)
    val tracks = items.filterIsInstance<Track>().distinctBy { it.ratingKey }.take(4)

    val g = LocalGraph.current
    val favorites by g.radio.favorites.collectAsState()
    SectionHeader("Radio", action = "Alla kanaler") { vm.nav.select(Tab.RADIO) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Dina radiokanaler först.
        itemsIndexed(favorites, key = { _, s -> "fav:${s.id}" }) { i, s ->
            RadioCard(s.name, "LIVE", s.logo, i + 3, onClick = { vm.playStation(favorites, i, QueueBuilder.LIST_FAVORITES, "Dina kanaler") })
        }
        item(key = "library") {
            RadioCard("Hela biblioteket", "BLANDAD RADIO", null, 0, onClick = { vm.shuffleLibrary() }, icon = Icons.Rounded.Shuffle)
        }
        itemsIndexed(stations, key = { _, st -> "st:${st.key}" }) { i, st ->
            StationCard(st, i + 1, onClick = { vm.playStation(st) })
        }
        itemsIndexed(artists, key = { _, a -> "ar:${a.first}" }) { i, (key, name, thumb) ->
            RadioCard(name, "ARTISTRADIO", thumb, i + stations.size + 1, onClick = { vm.artistRadio(Artist(key, name, thumb)) })
        }
        itemsIndexed(tracks, key = { _, t -> "tr:${t.ratingKey}" }) { i, t ->
            RadioCard("Mer som ${t.title}", "LÅTRADIO", t.thumb, i + stations.size + artists.size + 1, onClick = { vm.sonicRadio(t) })
        }
    }
}

@Composable
fun HubRow(vm: AppViewModel, hub: Hub) {
    val tracksOnly = hub.items.all { it is Track }
    SectionHeader(hub.title.ifBlank { "Mer" }, action = if (hub.items.size > 6) "Visa alla" else null) {
        vm.nav.push(Screen.HubItems(hub))
    }
    if (tracksOnly) {
        // Låtar i tre rader som går att svepa, som i Spotify.
        val columns = hub.items.filterIsInstance<Track>().chunked(3)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(columns) { _, col ->
                Column(Modifier.width(300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    col.forEach { t -> CompactTrack(vm, t) }
                }
            }
        }
    } else {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(hub.items, key = { i, it -> "$i:${it.ratingKey}" }) { i, item ->
                MusicItemCard(item, i, onOpen = { vm.open(it) }, onLongClick = { vm.longPress(it) })
            }
        }
    }
}

@Composable
private fun CompactTrack(vm: AppViewModel, t: Track) {
    val current = vm.player.current?.ratingKey == t.ratingKey
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { vm.sonicRadio(t) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(t.thumb, Modifier.size(52.dp), size = 156, shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, color = if (current) Jp.Accent else Jp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.displayArtist, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { vm.menu = MenuTarget.ForTrack(t) }) {
            Icon(Icons.Rounded.MoreVert, "Mer", tint = Jp.TextDim)
        }
    }
}

@Composable
private fun UpdateCard(version: String, progress: Float, onUpdate: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Jp.SurfaceHigh)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Jp.Accent.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SystemUpdate, null, tint = Jp.Accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Ny version av JPFM", style = MaterialTheme.typography.titleSmall)
                Text("Version $version finns att installera.", style = MaterialTheme.typography.bodySmall, color = Jp.TextDim)
            }
            TextButton(onClick = onUpdate, enabled = progress < 0f) { Text("Uppdatera") }
        }
        if (progress >= 0f) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Jp.Accent, trackColor = Jp.SurfaceHigher)
        }
    }
}

@Composable
private fun OfflineHome(vm: AppViewModel) {
    val g = LocalGraph.current
    val cols by g.downloads.collections.collectAsState()
    if (cols.isEmpty()) {
        se.jpfm.ui.EmptyBox(
            Icons.Rounded.DownloadForOffline,
            "Ingen nedladdad musik",
            "Ladda ner album och spellistor när du har nät, så finns de här i bilen och i fjällen.",
        )
        return
    }
    SectionHeader("Nedladdat", action = "Visa alla") { vm.nav.select(Tab.DOWNLOADS) }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(cols, key = { _, c -> c.id }) { _, c ->
            se.jpfm.ui.MediaCard(
                c.thumb, c.title, c.subtitle, 152.dp, Icons.Rounded.DownloadForOffline,
                onClick = { vm.nav.push(Screen.Offline(c.id, c.title)) },
            )
        }
    }
}
