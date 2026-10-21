package se.jpfm.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import se.jpfm.playback.MediaIds
import se.jpfm.plex.AlbumSort
import se.jpfm.plex.Artist
import se.jpfm.plex.Genre
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.LoadContent
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.Screen
import se.jpfm.ui.rememberLoadable

private val tabs = listOf("Artister", "Album", "Spellistor", "Genrer")

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    var tab by remember { mutableIntStateOf(g.prefs.libraryTab.coerceIn(0, tabs.lastIndex)) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(top = 14.dp)) {
            Text("Bibliotek", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tabs.size) { i ->
                    Chip(tabs[i], i == tab) {
                        tab = i
                        g.prefs.libraryTab = i
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> ArtistsTab(vm)
                1 -> AlbumsTab(vm)
                2 -> PlaylistsTab(vm)
                else -> GenresTab(vm)
            }
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) Jp.OnAccent else Jp.Text,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Jp.Accent else Jp.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ---------- Artister ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistsTab(vm: AppViewModel) {
    val g = LocalGraph.current
    val artists = rememberLoadable("artists") { force -> g.library.artists(force) }
    val state = rememberLazyListState()
    LaunchedEffect(vm.nav.reselect) { if (vm.nav.reselect > 0) state.animateScrollToItem(0) }
    PullToRefreshBox(isRefreshing = artists.refreshing, onRefresh = { artists.refresh() }) {
        LoadContent(artists) { list ->
            if (list.isEmpty()) {
                EmptyBox(Icons.Rounded.Person, "Inga artister", "Musikbiblioteket i Plex är tomt.")
                return@LoadContent
            }
            Box(Modifier.fillMaxSize()) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(end = 28.dp, bottom = 24.dp)) {
                    items(list, key = { it.ratingKey }) { a ->
                        ArtistRow(a, onClick = { vm.nav.push(Screen.Artist(a.ratingKey, a.title)) }, onLong = { vm.menu = MenuTarget.ForArtist(a) })
                    }
                }
                LetterIndex(list, state, Modifier.align(Alignment.CenterEnd))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtistRow(a: Artist, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(a.thumb, Modifier.size(52.dp), size = 156, shape = CircleShape, placeholder = Icons.Rounded.Person)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (a.genres.isNotEmpty()) {
                Text(a.genres.take(2).joinToString(", "), style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1)
            }
        }
    }
}

/** Bokstäver längs kanten: dra eller tryck för att hoppa i listan. */
@Composable
private fun LetterIndex(list: List<Artist>, state: LazyListState, modifier: Modifier) {
    val firstIndex = remember(list) {
        val map = LinkedHashMap<String, Int>()
        list.forEachIndexed { i, a -> map.putIfAbsent(MediaIds.letterOf(a.title), i) }
        map
    }
    val letters = MediaIds.LETTERS.filter { it in firstIndex }
    if (letters.size < 4) return
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<String?>(null) }
    fun jump(y: Float) {
        val i = (y / height * letters.size).toInt().coerceIn(0, letters.lastIndex)
        val l = letters[i]
        if (l != active) {
            active = l
            scope.launch { state.scrollToItem(firstIndex[l] ?: 0) }
        }
    }
    Box(modifier.fillMaxHeight().padding(vertical = 12.dp)) {
        Column(
            Modifier
                .width(26.dp)
                .fillMaxHeight()
                .onSizeChanged { height = it.height.coerceAtLeast(1) }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { jump(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                    ) { change, _ ->
                        change.consume()
                        jump(change.position.y)
                    }
                },
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (l == active) Jp.Accent else Jp.TextFaint,
                    modifier = Modifier.clickable {
                        scope.launch { state.scrollToItem(firstIndex[l] ?: 0) }
                    },
                )
            }
        }
        active?.let { l ->
            Box(
                Modifier
                    .align(Alignment.Center)
                    .padding(end = 70.dp)
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(Jp.Accent),
                contentAlignment = Alignment.Center,
            ) { Text(l, style = MaterialTheme.typography.headlineLarge, color = Jp.OnAccent) }
        }
    }
}

// ---------- Album ----------

@Composable
private fun AlbumsTab(vm: AppViewModel) {
    val g = LocalGraph.current
    var sort by remember { mutableStateOf(AlbumSort.entries.firstOrNull { it.key == g.prefs.albumSort } ?: AlbumSort.TITLE) }
    val albums = rememberLoadable("albums", sort) { force -> g.library.albums(sort, force) }
    var menuOpen by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                albums.data?.let { Format.albums(it.size) } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = Jp.TextDim,
                modifier = Modifier.weight(1f),
            )
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable { menuOpen = true }.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Sort, null, tint = Jp.TextDim, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(sort.label, style = MaterialTheme.typography.labelLarge, color = Jp.TextDim)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    AlbumSort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label, color = if (s == sort) Jp.Accent else Jp.Text) },
                            onClick = {
                                sort = s
                                g.prefs.albumSort = s.key
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }
        PullToRefreshBox(isRefreshing = albums.refreshing, onRefresh = { albums.refresh() }, modifier = Modifier.weight(1f)) {
            LoadContent(albums) { list ->
                LazyVerticalGrid(
                    state = grid,
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list, key = { it.ratingKey }) { a ->
                        AlbumCard(a, onClick = { vm.nav.push(Screen.Album(a.ratingKey, a.title)) }, onLongClick = { vm.menu = MenuTarget.ForAlbum(a) }, width = 400.dp)
                    }
                }
            }
        }
    }
}

// ---------- Spellistor ----------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistsTab(vm: AppViewModel) {
    val g = LocalGraph.current
    val playlists = rememberLoadable("playlists") { force -> g.library.playlists(force) }
    PullToRefreshBox(isRefreshing = playlists.refreshing, onRefresh = { playlists.refresh() }) {
        LoadContent(playlists) { list ->
            if (list.isEmpty()) {
                EmptyBox(Icons.Rounded.QueueMusic, "Inga spellistor", "Skapa en genom att trycka länge på en låt och välja Lägg till i spellista.")
                return@LoadContent
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(list, key = { it.ratingKey }) { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { vm.nav.push(Screen.Playlist(p.ratingKey, p.title)) },
                                onLongClick = { vm.menu = MenuTarget.ForPlaylist(p) },
                            )
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Artwork(p.thumb, Modifier.size(60.dp), size = 180, shape = RoundedCornerShape(8.dp), placeholder = Icons.Rounded.QueueMusic)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(if (p.smart) "Smart spellista" else "Spellista", Format.tracks(p.trackCount), p.durationMs?.let { Format.longDuration(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Jp.TextDim,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------- Genrer ----------

private val genreColors = listOf(
    Color(0xFF1FA67F), Color(0xFF7B5CFF), Color(0xFFE5577A), Color(0xFFF0A23B), Color(0xFF2BB3D9),
    Color(0xFF8BC34A), Color(0xFFB0549E), Color(0xFF3F7DE8), Color(0xFFE0663C), Color(0xFF16A2A2),
)

@Composable
private fun GenresTab(vm: AppViewModel) {
    val g = LocalGraph.current
    val genres = rememberLoadable("genres") { g.library.genres() }
    LoadContent(genres) { list ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(list.size) { i ->
                GenreTile(list[i], genreColors[i % genreColors.size]) { vm.nav.push(Screen.GenreAlbums(list[i])) }
            }
        }
    }
}

@Composable
private fun GenreTile(genre: Genre, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.55f))))
            .clickable(onClick = onClick)
            .padding(14.dp),
    ) {
        Text(genre.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
