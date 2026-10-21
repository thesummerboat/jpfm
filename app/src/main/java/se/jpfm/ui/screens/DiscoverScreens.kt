package se.jpfm.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import se.jpfm.playback.QueueBuilder
import se.jpfm.playback.QueueContext
import se.jpfm.plex.Album
import se.jpfm.plex.Mix
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.CategoryTile
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.Loadable
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.PlayRow
import se.jpfm.ui.RankedTrack
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.categoryColor
import se.jpfm.ui.rememberLoadable

// ---------- En kategori: genre, stämning eller årtionde ----------

/** Spela kategorins mix (som fylls på av sig själv) eller bläddra bland albumen i den. */
@Composable
fun CategoryScreen(vm: AppViewModel, mix: Mix) {
    val g = LocalGraph.current
    val color = categoryColor(mix.title)
    val filter = mix.albumFilter
    val albums = rememberLoadable("cat", filter, keep = filter?.let { "cat:$it" }) { force ->
        if (filter == null) emptyList() else g.library.albumsWhere(filter, force)
    }
    val ctx = vm.player.queueContext
    val playingThis = ctx?.kind == QueueContext.Kind.MIX && ctx.id == mix.trackFilter

    DetailScaffold(mix.title, onBack = { vm.nav.back() }, barColor = color.darken()) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.85f), color.copy(alpha = 0.25f), Jp.Background)))
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 8.dp),
            ) {
                Text(mix.title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    mix.subtitle ?: "En mix som blandar och fyller på av sig själv – den tar aldrig slut.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(18.dp))
                PlayRow(
                    onPlay = { vm.playMix(mix) },
                    onShuffle = null,
                    playLabel = if (playingThis) "Spelas nu" else "Spela mixen",
                    playIcon = if (playingThis) Icons.Rounded.PlayArrow else Icons.Rounded.Shuffle,
                )
            }
        }
        if (filter != null) {
            albumGrid(vm, albums, "Album")
        }
    }
}

private fun Color.darken(f: Float = 0.45f) = Color(red * f, green * f, blue * f, 1f)

/** Album i ett rutnät inne i en LazyColumn (så att sidhuvudet kan rulla med). */
private fun LazyListScope.albumGrid(vm: AppViewModel, albums: Loadable<List<Album>>, title: String) {
    when (val st = albums.state) {
        is Load.Loading -> item(key = "loading") { LoadingBox() }
        is Load.Failed -> item(key = "err") { ErrorBox(st.message) { albums.retry() } }
        is Load.Ready -> {
            if (st.data.isEmpty()) {
                item(key = "empty") { EmptyBox(Icons.Rounded.Album, "Inga album", "Det finns inga album här i ditt bibliotek.") }
                return
            }
            item(key = "albums-h") { SectionHeader(title, subtitle = "Mest spelade först") }
            val rows = st.data.chunked(COLUMNS)
            itemsIndexed(rows, key = { i, _ -> "row:$i" }) { _, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    row.forEach { a ->
                        Box(Modifier.weight(1f)) {
                            AlbumCard(
                                a,
                                onClick = { vm.nav.push(Screen.Album(a.ratingKey, a.title)) },
                                onLongClick = { vm.menu = MenuTarget.ForAlbum(a) },
                                width = 600.dp,
                            )
                        }
                    }
                    repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private const val COLUMNS = 2

// ---------- Bläddra: alla genrer, stämningar och årtionden ----------

@Composable
fun BrowseScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val genres = rememberLoadable("browse-genres", keep = "browse:genres") { force -> g.library.genreCategories(force) }
    val moods = rememberLoadable("browse-moods", keep = "browse:moods") { g.library.moods() }
    val decades = rememberLoadable("browse-decades", keep = "browse:decades") { g.library.decades() }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val cols = if (wide) 4 else 2

    DetailScaffold("Bläddra", onBack = { vm.nav.back() }) {
        item(key = "title") {
            Text(
                "Bläddra",
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.statusBarsPadding().padding(start = 20.dp, end = 20.dp, top = 60.dp, bottom = 4.dp),
            )
        }
        tileSection(vm, "decades", "Årtionden", decades, cols)
        tileSection(vm, "moods", "Stämningar", moods, cols)
        tileSection(vm, "genres", "Genrer", genres, cols)
    }
}

private fun LazyListScope.tileSection(vm: AppViewModel, key: String, title: String, l: Loadable<List<Mix>>, cols: Int) {
    when (val st = l.state) {
        is Load.Loading -> item(key = "$key-l") { LoadingBox() }
        is Load.Failed -> Unit
        is Load.Ready -> {
            if (st.data.isEmpty()) return
            item(key = "$key-h") { SectionHeader(title) }
            itemsIndexed(st.data.chunked(cols), key = { i, _ -> "$key:$i" }) { _, row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { m ->
                        CategoryTile(
                            m.title,
                            categoryColor(m.title),
                            onClick = { vm.nav.push(Screen.Category(m)) },
                            onLongClick = { vm.playMix(m) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ---------- Topplistor ----------

/** Hela topplistan: hett i Sverige eller det du spelat mest. */
@Composable
fun HotListScreen(vm: AppViewModel, which: String) {
    val g = LocalGraph.current
    val sweden = which == QueueBuilder.HOT_SWEDEN
    val title = if (sweden) "Hett i Sverige" else "Hett hos dig"
    val chart = rememberLoadable("hot-se-all", sweden, keep = if (sweden) "hot:se:all" else null) { force ->
        if (sweden) g.library.matchChart(g.charts.sweden(force)) else emptyList()
    }
    val mine = rememberLoadable("hot-me-all", sweden, keep = if (sweden) null else "hot:me:all") { force ->
        if (sweden) emptyList() else g.library.hotForYou(force = force)
    }
    val tracks = if (sweden) chart.data.orEmpty().mapNotNull { it.track }.distinctBy { it.ratingKey } else mine.data.orEmpty()
    val state: Load<*> = if (sweden) chart.state else mine.state
    val color = if (sweden) Color(0xFFE5577A) else Jp.AccentDeep

    DetailScaffold(title, onBack = { vm.nav.back() }, barColor = color.darken()) {
        item(key = "header") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.8f), color.copy(alpha = 0.2f), Jp.Background)))
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 8.dp),
            ) {
                Text(title, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (sweden) "Det som spelas mest i Sverige just nu. Låtarna som finns i ditt bibliotek går att spela, och för artister du har blir det artistradio."
                    else "Det du lyssnat mest på de senaste 30 dagarna, i alla dina appar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
                if (tracks.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    PlayRow(
                        onPlay = { vm.playTracks(tracks, 0, title) },
                        onShuffle = { vm.playTracks(tracks, 0, title, shuffle = true) },
                        playLabel = if (sweden) "Spela ${tracks.size} låtar" else "Spela",
                    )
                }
            }
        }
        when (state) {
            is Load.Loading -> item(key = "l") { LoadingBox() }
            is Load.Failed -> item(key = "e") { ErrorBox(state.message) { if (sweden) chart.retry() else mine.retry() } }
            is Load.Ready -> if (sweden) {
                val list = chart.data.orEmpty()
                if (list.isEmpty()) item(key = "empty") { EmptyBox(Icons.Rounded.Album, "Topplistan svarar inte", "Försök igen om en stund.") }
                itemsIndexed(list, key = { _, h -> "se:${h.rank}" }) { _, hit ->
                    ChartHitRow(vm, list, hit, Modifier.padding(horizontal = 8.dp))
                }
            } else {
                if (tracks.isEmpty()) {
                    item(key = "empty") { EmptyBox(Icons.Rounded.Album, "Inget här än", "Lyssna lite till så dyker dina favoriter upp här.") }
                }
                itemsIndexed(tracks, key = { _, t -> t.ratingKey }) { i, t ->
                    RankedTrack(
                        rank = i + 1,
                        track = t,
                        isCurrent = vm.player.current?.ratingKey == t.ratingKey,
                        onClick = { vm.playTracks(tracks, i, title) },
                        onMore = { vm.menu = MenuTarget.ForTrack(t) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }
}
