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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RssFeed
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import se.jpfm.playback.QueueBuilder
import se.jpfm.podcast.Episode
import se.jpfm.podcast.Podcast
import se.jpfm.radio.RadioStation
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.EqualizerBars
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.rememberLoadable

@Composable
fun RadioScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    var tab by remember { mutableIntStateOf(g.prefs.radioTab.coerceIn(0, 1)) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.statusBarsPadding().padding(top = 14.dp)) {
            Text("Radio", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(horizontal = 20.dp))
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Kanaler", tab == 0) { tab = 0; g.prefs.radioTab = 0 }
                Chip("Poddar", tab == 1) { tab = 1; g.prefs.radioTab = 1 }
            }
        }
        Box(Modifier.weight(1f)) {
            if (tab == 0) ChannelsTab(vm) else PodcastsTab(vm)
        }
    }
}

// ---------- Kanaler ----------

@Composable
private fun ChannelsTab(vm: AppViewModel) {
    val g = LocalGraph.current
    val favorites by g.radio.favorites.collectAsState()
    val sr = rememberLoadable("sr-channels") { force -> g.radio.srChannels(force) }
    val sweden = rememberLoadable("se-channels") { force -> g.radio.popularInSweden(force) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<RadioStation>>?>(null) }
    var showLocal by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(350)
        results = Load.Loading
        results = try {
            Load.Ready(g.radio.search(q))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Failed("Sökningen svarade inte")
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "search") {
            SearchField(query, { query = it }, "Sök bland 50 000 kanaler i världen")
        }
        val res = results
        if (res != null) {
            when (res) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(res.message) }
                is Load.Ready -> {
                    if (res.data.isEmpty()) item { EmptyBox(Icons.Rounded.Radio, "Inga kanaler", "Prova ett annat namn.") }
                    itemsIndexed(res.data, key = { _, s -> "q:${s.id}" }) { i, s ->
                        StationRow(vm, s, isFav = favorites.any { it.id == s.id }) { vm.playStation(res.data, i, null, "Sökresultat") }
                    }
                }
            }
            return@LazyColumn
        }

        item(key = "fav-h") { SectionHeader("Dina kanaler") }
        if (favorites.isEmpty()) {
            item(key = "fav-empty") {
                Text(
                    "Tryck på hjärtat vid en kanal så hamnar den här – och först i bilen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Jp.TextDim,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        } else {
            item(key = "fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    itemsIndexed(favorites, key = { _, s -> "f:${s.id}" }) { i, s ->
                        StationTile(vm, s) { vm.playStation(favorites, i, QueueBuilder.LIST_FAVORITES, "Dina kanaler") }
                    }
                }
            }
        }

        // Sveriges Radio: rikskanaler som kort, lokala P4 och övriga i en lista.
        val srList = sr.data.orEmpty()
        item(key = "sr-h") { SectionHeader("Sveriges Radio") }
        when (val st = sr.state) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item { ErrorBox(st.message) { sr.retry() } }
            is Load.Ready -> {
                val main = srList.filter { it.group == "Rikskanal" || it.group == null }
                val local = srList.filter { it.group == "Lokal kanal" }
                val other = srList.filter { it !in main && it !in local }
                item(key = "sr-main") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(main, key = { "srm:${it.id}" }) { s ->
                            StationTile(vm, s) { vm.playStation(srList, srList.indexOf(s), QueueBuilder.LIST_SR, "Sveriges Radio") }
                        }
                    }
                }
                if (local.isNotEmpty()) {
                    item(key = "sr-local-h") {
                        Row(
                            Modifier.fillMaxWidth().clickable { showLocal = !showLocal }.padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Lokala P4-kanaler (${local.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Icon(if (showLocal) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                        }
                    }
                    if (showLocal) {
                        items(local, key = { "srl:${it.id}" }) { s ->
                            StationRow(vm, s, isFav = favorites.any { it.id == s.id }) { vm.playStation(srList, srList.indexOf(s), QueueBuilder.LIST_SR, "Sveriges Radio") }
                        }
                    }
                }
                items(other, key = { "sro:${it.id}" }) { s ->
                    StationRow(vm, s, isFav = favorites.any { it.id == s.id }) { vm.playStation(srList, srList.indexOf(s), QueueBuilder.LIST_SR, "Sveriges Radio") }
                }
            }
        }

        item(key = "se-h") { SectionHeader("Populärt i Sverige") }
        when (val st = sweden.state) {
            is Load.Loading -> item { LoadingBox() }
            is Load.Failed -> item { ErrorBox(st.message) { sweden.retry() } }
            is Load.Ready -> itemsIndexed(st.data, key = { _, s -> "se:${s.id}" }) { i, s ->
                StationRow(vm, s, isFav = favorites.any { it.id == s.id }) { vm.playStation(st.data, i, QueueBuilder.LIST_SWEDEN, "Populärt i Sverige") }
            }
        }
        item(key = "credit") {
            Text(
                "Kanaler från Sveriges Radios öppna API och Radio Browser (radio-browser.info).",
                style = MaterialTheme.typography.bodySmall,
                color = Jp.TextFaint,
                modifier = Modifier.padding(20.dp),
            )
        }
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(placeholder) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) },
        trailingIcon = { if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Rensa") } },
        shape = RoundedCornerShape(14.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Jp.SurfaceHigh,
            unfocusedContainerColor = Jp.SurfaceHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Jp.Accent,
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Kanalens logga på vit botten (de flesta loggor är gjorda för det), annars färg och initialer. */
@Composable
fun StationLogo(s: RadioStation, size: Dp, corner: Dp = 12.dp) {
    val context = LocalContext.current
    val color = s.color?.let { Color(it) } ?: Jp.SurfaceHigher
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(if (s.logo != null) Color.White else color),
        contentAlignment = Alignment.Center,
    ) {
        if (s.logo != null) {
            AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(s.logo).crossfade(true).build(),
                contentDescription = s.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(size / 10),
            )
        } else {
            Text(
                s.name.take(3),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StationTile(vm: AppViewModel, s: RadioStation, onPlay: () -> Unit) {
    val playing = vm.player.nowStation?.id == s.id
    Column(
        Modifier
            .width(116.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onPlay, onLongClick = { vm.toggleFavorite(s) })
            .padding(bottom = 4.dp),
    ) {
        Box {
            StationLogo(s, 116.dp, 14.dp)
            if (playing) {
                Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(26.dp).clip(RoundedCornerShape(6.dp)).background(Jp.Accent), contentAlignment = Alignment.Center) {
                    EqualizerBars(vm.player.isPlaying, Jp.OnAccent, Modifier.size(14.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) Jp.Accent else Jp.Text)
        s.tagline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun StationRow(vm: AppViewModel, s: RadioStation, isFav: Boolean, onPlay: () -> Unit) {
    val playing = vm.player.nowStation?.id == s.id
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationLogo(s, 52.dp, 10.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = if (playing) Jp.Accent else Jp.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOfNotNull(s.tagline, s.bitrate?.let { "$it kbps" }).joinToString(" · ")
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (playing) EqualizerBars(vm.player.isPlaying, Jp.Accent, Modifier.size(16.dp))
        IconButton(onClick = { vm.toggleFavorite(s) }) {
            Icon(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isFav) "Ta bort favorit" else "Spara kanal", tint = if (isFav) Jp.Love else Jp.TextDim)
        }
    }
}

// ---------- Poddar ----------

@Composable
private fun PodcastsTab(vm: AppViewModel) {
    val g = LocalGraph.current
    val subs by g.podcasts.subscriptions.collectAsState()
    val progress by g.podcasts.progress.collectAsState()
    val latest = rememberLoadable("pod-latest", subs.map { it.id }) { force -> g.podcasts.latest(force) }
    val top = rememberLoadable("pod-top") { g.podcasts.topList() }
    val sr = rememberLoadable("pod-sr") { g.podcasts.search("Sveriges Radio") }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Load<List<Podcast>>?>(null) }
    var addFeed by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(350)
        results = Load.Loading
        results = try {
            Load.Ready(g.podcasts.search(q))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Load.Failed("Sökningen svarade inte")
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "search") { SearchField(query, { query = it }, "Sök poddar") }
        val res = results
        if (res != null) {
            when (res) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(res.message) }
                is Load.Ready -> {
                    if (res.data.isEmpty()) item { EmptyBox(Icons.Rounded.Headset, "Inga poddar", "Prova ett annat namn.") }
                    items(res.data, key = { "q:${it.id}" }) { p -> PodcastRow(vm, p) }
                }
            }
            return@LazyColumn
        }

        if (subs.isNotEmpty()) {
            item(key = "new-h") { SectionHeader("Nya avsnitt") }
            when (val st = latest.state) {
                is Load.Loading -> item { LoadingBox() }
                is Load.Failed -> item { ErrorBox(st.message) { latest.retry() } }
                is Load.Ready -> items(st.data.take(12), key = { "n:${it.id}" }) { e ->
                    EpisodeRow(vm, e, progress[e.id], showPodcast = true)
                }
            }
            item(key = "subs-h") { SectionHeader("Dina poddar") }
            item(key = "subs") { PodcastCards(vm, subs) }
        } else {
            item(key = "intro") {
                EmptyBox(
                    Icons.Rounded.Headset,
                    "Poddar",
                    "Sök eller välj nedan och tryck Följ. Nya avsnitt hamnar här och i bilen, och appen minns var du slutade.",
                )
            }
        }
        podcastSection("Från Sveriges Radio", "sr", sr.state, vm)
        podcastSection("Topplistan i Sverige", "top", top.state, vm)
        item(key = "rss") {
            TextButton(onClick = { addFeed = true }, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Rounded.RssFeed, null, tint = Jp.TextDim)
                Spacer(Modifier.width(8.dp))
                Text("Lägg till med RSS-adress", color = Jp.TextDim)
            }
        }
    }

    if (addFeed) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addFeed = false },
            containerColor = Jp.SurfaceHigh,
            title = { Text("Lägg till podd") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    label = { Text("RSS-adress") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
            },
            confirmButton = {
                TextButton(enabled = url.startsWith("http"), onClick = {
                    vm.addFeed(url)
                    addFeed = false
                }) { Text("Lägg till") }
            },
            dismissButton = { TextButton(onClick = { addFeed = false }) { Text("Avbryt") } },
        )
    }
}

private fun LazyListScope.podcastSection(title: String, key: String, state: Load<List<Podcast>>, vm: AppViewModel) {
    when (state) {
        is Load.Ready -> if (state.data.isNotEmpty()) {
            item(key = "$key-h") { SectionHeader(title) }
            item(key = key) { PodcastCards(vm, state.data) }
        }
        is Load.Loading -> {
            item(key = "$key-h") { SectionHeader(title) }
            item(key = "$key-l") { LoadingBox() }
        }
        is Load.Failed -> Unit
    }
}

@Composable
fun PodcastCards(vm: AppViewModel, list: List<Podcast>) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list, key = { it.id }) { p ->
            Column(
                Modifier
                    .width(136.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm.nav.push(Screen.PodcastPage(p)) }
                    .padding(bottom = 4.dp),
            ) {
                Artwork(p.image, Modifier.size(136.dp), size = 400, shape = RoundedCornerShape(10.dp), placeholder = Icons.Rounded.Headset)
                Spacer(Modifier.height(8.dp))
                Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                p.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun PodcastRow(vm: AppViewModel, p: Podcast) {
    Row(
        Modifier.fillMaxWidth().clickable { vm.nav.push(Screen.PodcastPage(p)) }.padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(p.image, Modifier.size(60.dp), size = 180, shape = RoundedCornerShape(8.dp), placeholder = Icons.Rounded.Headset)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Jp.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** Ett avsnitt: datum, längd eller tid kvar, och hur långt du lyssnat. */
@Composable
fun EpisodeRow(vm: AppViewModel, e: Episode, progress: se.jpfm.podcast.Progress?, showPodcast: Boolean = false) {
    val g = LocalGraph.current
    val states by g.downloads.states.collectAsState()
    val downloaded = states[e.id] == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED
    val downloading = states[e.id] == androidx.media3.exoplayer.offline.Download.STATE_DOWNLOADING ||
        states[e.id] == androidx.media3.exoplayer.offline.Download.STATE_QUEUED
    val playing = vm.player.nowEpisode?.id == e.id
    val played = progress?.played == true
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { vm.playEpisode(e) }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(e.image, Modifier.size(56.dp), size = 168, shape = RoundedCornerShape(8.dp), placeholder = Icons.Rounded.Headset)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val meta = listOfNotNull(
                if (showPodcast) e.podcastTitle else null,
                Format.date(e.publishedAt).ifBlank { null },
                when {
                    played -> "Lyssnad"
                    progress != null && progress.positionMs > 0 && e.durationMs > 0 -> Format.remaining(progress.positionMs, e.durationMs)
                    e.durationMs > 0 -> Format.longDuration(e.durationMs)
                    else -> null
                },
            ).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelMedium, color = if (playing) Jp.Accent else Jp.TextFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                e.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    playing -> Jp.Accent
                    played -> Jp.TextDim
                    else -> Jp.Text
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (progress != null && !played && progress.positionMs > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(0.6f).height(3.dp).clip(RoundedCornerShape(2.dp)),
                    color = Jp.Accent,
                    trackColor = Jp.SurfaceHigher,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    when {
                        downloaded -> Icons.Rounded.DownloadDone
                        downloading -> Icons.Rounded.Download
                        else -> Icons.Rounded.MoreVert
                    },
                    "Mer",
                    tint = if (downloaded) Jp.Accent else Jp.TextDim,
                )
            }
            androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (played) "Markera som olyssnad" else "Markera som lyssnad") },
                    onClick = {
                        g.podcasts.setPlayed(e, !played)
                        menu = false
                    },
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(if (downloaded || downloading) "Ta bort nedladdning" else "Ladda ner") },
                    onClick = {
                        if (downloaded || downloading) g.downloads.removeEpisode(e.id) else vm.downloadEpisode(e)
                        menu = false
                    },
                )
            }
        }
    }
}
