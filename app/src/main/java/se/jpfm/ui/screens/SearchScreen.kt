package se.jpfm.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.SearchResult
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.ArtistCard
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Jp
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.PlaylistCard
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.TrackRow

private sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Done(val result: SearchResult) : SearchState
    data class Failed(val message: String) : SearchState
}

@Composable
fun SearchScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    var query by rememberSaveable { mutableStateOf("") }
    var state by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    var recents by remember { mutableStateOf(g.prefs.recentSearches) }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(vm.nav.reselect) { if (vm.nav.reselect > 0) runCatching { focus.requestFocus() } }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            state = SearchState.Idle
            return@LaunchedEffect
        }
        delay(280)
        state = SearchState.Loading
        state = try {
            if (g.session.isReady) {
                SearchState.Done(g.library.search(q))
            } else {
                val hits = g.downloads.allTracks().filter { t ->
                    listOf(t.title, t.displayArtist, t.album).any { it.contains(q, ignoreCase = true) }
                }
                SearchState.Done(SearchResult(tracks = hits.take(50)))
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            SearchState.Failed(e.message ?: "Sökningen misslyckades")
        }
    }

    fun saveRecent(q: String) {
        val list = (listOf(q) + recents.filter { !it.equals(q, ignoreCase = true) }).take(8)
        recents = list
        g.prefs.recentSearches = list
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)) {
            Text("Sök", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(12.dp))
            TextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Artister, album, låtar") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Rensa") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    if (query.isNotBlank()) saveRecent(query.trim())
                    focusManager.clearFocus()
                }),
                shape = RoundedCornerShape(14.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Jp.SurfaceHigh,
                    unfocusedContainerColor = Jp.SurfaceHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    cursorColor = Jp.Accent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        Box(Modifier.weight(1f)) {
            when (val s = state) {
                is SearchState.Idle -> Recents(recents, onPick = { query = it }, onClear = {
                    recents = emptyList()
                    g.prefs.recentSearches = emptyList()
                })
                is SearchState.Loading -> LoadingBox()
                is SearchState.Failed -> ErrorBox(s.message)
                is SearchState.Done -> Results(vm, s.result, onOpen = { saveRecent(query.trim()) })
            }
        }
    }
}

@Composable
private fun Recents(recents: List<String>, onPick: (String) -> Unit, onClear: () -> Unit) {
    if (recents.isEmpty()) {
        EmptyBox(Icons.Rounded.Search, "Hitta din musik", "Sök bland artister, album, låtar och spellistor på din Plex-server.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Senaste sökningar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onClear) { Text("Rensa", color = Jp.TextDim) }
            }
        }
        items(recents) { r ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(r) }.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.History, null, tint = Jp.TextFaint, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(14.dp))
                Text(r, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun Results(vm: AppViewModel, r: SearchResult, onOpen: () -> Unit) {
    if (r.isEmpty) {
        EmptyBox(Icons.Rounded.SearchOff, "Inga träffar", "Prova ett annat stavsätt eller en kortare sökning.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        val top = r.artists.firstOrNull() ?: r.albums.firstOrNull()
        if (top != null) {
            item(key = "top") {
                SectionHeader("Bästa träff")
                TopHit(vm, top, onOpen)
            }
        }
        if (r.tracks.isNotEmpty()) {
            item(key = "tracks-h") { SectionHeader("Låtar") }
            items(r.tracks.take(8), key = { "t:${it.ratingKey}" }) { t ->
                val current = vm.player.current?.ratingKey == t.ratingKey
                TrackRow(
                    track = t,
                    onClick = {
                        onOpen()
                        vm.sonicRadio(t)
                    },
                    onMore = { vm.menu = MenuTarget.ForTrack(t) },
                    isCurrent = current,
                    isPlaying = current && vm.player.isPlaying,
                )
            }
        }
        if (r.artists.size > 1 || (r.artists.size == 1 && top !is Artist)) {
            item(key = "artists") {
                SectionHeader("Artister")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.artists, key = { it.ratingKey }) { a ->
                        ArtistCard(a, onClick = { onOpen(); vm.nav.push(Screen.Artist(a.ratingKey, a.title)) }, onLongClick = { vm.menu = MenuTarget.ForArtist(a) })
                    }
                }
            }
        }
        if (r.albums.isNotEmpty()) {
            item(key = "albums") {
                SectionHeader("Album")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.albums, key = { it.ratingKey }) { a ->
                        AlbumCard(a, onClick = { onOpen(); vm.nav.push(Screen.Album(a.ratingKey, a.title)) }, onLongClick = { vm.menu = MenuTarget.ForAlbum(a) })
                    }
                }
            }
        }
        if (r.playlists.isNotEmpty()) {
            item(key = "playlists") {
                SectionHeader("Spellistor")
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.playlists, key = { it.ratingKey }) { p ->
                        PlaylistCard(p, onClick = { onOpen(); vm.nav.push(Screen.Playlist(p.ratingKey, p.title)) }, onLongClick = { vm.menu = MenuTarget.ForPlaylist(p) })
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopHit(vm: AppViewModel, item: se.jpfm.plex.MusicItem, onOpen: () -> Unit) {
    val isArtist = item is Artist
    val subtitle = when (item) {
        is Artist -> "Artist"
        is Album -> "Album · ${item.artist}"
        else -> ""
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Jp.SurfaceHigh)
            .combinedClickable(
                onClick = {
                    onOpen()
                    vm.open(item)
                },
                onLongClick = { vm.longPress(item) },
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            item.thumb,
            Modifier.size(84.dp),
            size = 252,
            shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
            placeholder = if (isArtist) Icons.Rounded.Person else Icons.Rounded.Search,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1)
        }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Jp.Accent)
                .clickable {
                    onOpen()
                    when (item) {
                        is Artist -> vm.artistRadio(item)
                        is Album -> vm.playAlbum(item.ratingKey)
                        else -> Unit
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, "Spela", tint = Jp.OnAccent, modifier = Modifier.size(28.dp)) }
    }
}
