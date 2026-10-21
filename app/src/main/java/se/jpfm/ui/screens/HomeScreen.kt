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
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Headset
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import se.jpfm.playback.QueueBuilder
import se.jpfm.playback.QueueContext
import se.jpfm.plex.Album
import se.jpfm.plex.ChartHit
import se.jpfm.plex.Artist
import se.jpfm.plex.Hub
import se.jpfm.plex.Mix
import se.jpfm.plex.MusicItem
import se.jpfm.plex.Playlist
import se.jpfm.plex.SessionState
import se.jpfm.plex.Station
import se.jpfm.plex.Track
import se.jpfm.ui.AlbumCard
import se.jpfm.ui.AppViewModel
import se.jpfm.ui.Artwork
import se.jpfm.ui.CategoryTile
import se.jpfm.ui.ChartEntry
import se.jpfm.ui.EmptyBox
import se.jpfm.ui.EpisodeCard
import se.jpfm.ui.ErrorBox
import se.jpfm.ui.FilterChips
import se.jpfm.ui.Format
import se.jpfm.ui.Jp
import se.jpfm.ui.Load
import se.jpfm.ui.LoadingBox
import se.jpfm.ui.LocalGraph
import se.jpfm.ui.MenuTarget
import se.jpfm.ui.MixCard
import se.jpfm.ui.MusicItemCard
import se.jpfm.ui.OfflineBanner
import se.jpfm.ui.QuickTile
import se.jpfm.ui.RadioCard
import se.jpfm.ui.RankedTrack
import se.jpfm.ui.StationCard
import se.jpfm.ui.Screen
import se.jpfm.ui.SectionHeader
import se.jpfm.ui.Tab
import se.jpfm.ui.categoryColor
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

private enum class HomeFilter(val label: String) { ALL("Allt"), MUSIC("Musik"), RADIO("Radio"), PODS("Poddar") }

/**
 * Hemskärmen. Överst snabbval (det du spelat senast), sedan radio, dina mixar, topplistor,
 * nya avsnitt, nya släpp och till sist kategorier att bläddra i. Filtren högst upp visar bara
 * musik, radio eller poddar.
 */
@Composable
fun HomeScreen(vm: AppViewModel) {
    val g = LocalGraph.current
    val session by g.session.state.collectAsState()
    val online = session is SessionState.Ready
    val section = (session as? SessionState.Ready)?.section?.key
    var filter by rememberSaveable { mutableStateOf(HomeFilter.ALL) }
    // Ökar vid dra-för-att-uppdatera, så att alla rader hämtas på nytt.
    var epoch by rememberSaveable { mutableIntStateOf(0) }
    val k = "$section:$online"

    val hubs = rememberLoadable("hubs", section, online, keep = "home:hubs:$k") { force -> if (online) g.library.homeHubs(force) else emptyList() }
    val recent = rememberLoadable("recent", section, online, epoch, keep = "home:recent:$k") { if (online) g.library.recentlyPlayedAlbums() else emptyList() }
    val mixes = rememberLoadable("mixes", section, online, epoch, keep = "home:mixes:$k") { if (online) g.library.mixes() else emptyList() }
    val hotMe = rememberLoadable("hotme", section, online, epoch, keep = "home:hot:me:$k") {
        if (online) g.library.hotForYou().mapIndexed { i, t -> i + 1 to t } else emptyList()
    }
    val hotSe = rememberLoadable("hotse", section, online, epoch, keep = "home:hot:se:$k") {
        if (online) g.library.matchChart(g.charts.sweden()) else emptyList()
    }
    val releases = rememberLoadable("releases", section, online, epoch, keep = "home:releases:$k") { if (online) g.library.newReleases() else emptyList() }
    val rediscover = rememberLoadable("rediscover", section, online, epoch, keep = "home:rediscover:$k") { if (online) g.library.rediscover() else emptyList() }
    val genres = rememberLoadable("genres", section, online, epoch, keep = "home:genres:$k") { if (online) g.library.genreCategories() else emptyList() }
    val moods = rememberLoadable("moods", section, online, epoch, keep = "home:moods:$k") { if (online) g.library.moods() else emptyList() }
    val decades = rememberLoadable("decades", section, online, epoch, keep = "home:decades:$k") { if (online) g.library.decades() else emptyList() }
    val subs by g.podcasts.subscriptions.collectAsState()
    val progress by g.podcasts.progress.collectAsState()
    val requests by g.requests.requests.collectAsState()
    // Önskningar på väg: fråga Lidarr hur det går när hemskärmen visas.
    LaunchedEffect(requests.any { it.active }) {
        while (requests.any { it.active }) {
            g.requests.sync()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val episodes = rememberLoadable("eps", subs.map { it.id }, epoch, keep = "home:eps") { g.podcasts.latest() }

    val list = rememberLazyListState()
    LaunchedEffect(vm.nav.reselect) { if (vm.nav.reselect > 0) list.animateScrollToItem(0) }

    val music = filter == HomeFilter.ALL || filter == HomeFilter.MUSIC
    val radio = filter == HomeFilter.ALL || filter == HomeFilter.RADIO
    val pods = filter == HomeFilter.ALL || filter == HomeFilter.PODS
    val ctx = vm.player.queueContext
    val recentHub = hubs.data.orEmpty().firstOrNull { isRecentlyPlayed(it) }

    PullToRefreshBox(
        isRefreshing = hubs.refreshing,
        onRefresh = {
            g.library.invalidate()
            epoch++
            hubs.refresh()
        },
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "top") {
                HomeTop(vm, filter.ordinal) { i ->
                    filter = HomeFilter.entries[i]
                }
            }
            vm.update?.let { u ->
                item(key = "update") {
                    UpdateCard(u.versionName, vm.updateProgress) { vm.installUpdate() }
                }
            }
            when (val s = session) {
                is SessionState.Offline -> item(key = "offline") {
                    OfflineBanner("${s.reason}. Nedladdad musik, radio och poddar fungerar ändå.") { g.session.connectInBackground() }
                }
                else -> Unit
            }

            // ----- Snabbval och radio -----
            if (music) {
                if (online) {
                    item(key = "quick") { QuickGrid(vm, recentHub?.items.orEmpty(), recent.data.orEmpty()) }
                } else {
                    item(key = "offline-hint") { OfflineHome(vm) }
                }
            }
            if (music) {
                val active = requests.filter { it.active || (it.state == se.jpfm.request.RequestState.DONE && System.currentTimeMillis() - it.updatedAt < 6 * 3_600_000L) }
                if (active.isNotEmpty()) {
                    item(key = "requests") {
                        SectionHeader("Dina önskningar", subtitle = "Lidarr hämtar till servern", action = "Alla") { vm.nav.push(Screen.Request()) }
                        active.take(3).forEach { r -> RequestRow(r, onRemove = null, onClick = { vm.nav.push(Screen.Request()) }) }
                    }
                }
            }
            if (radio) item(key = "radio") { RadioRow(vm, hubs.data.orEmpty(), online) }
            if (filter == HomeFilter.RADIO) {
                item(key = "sr") {
                    val l = rememberLoadable("home-sr", keep = "home:sr") { g.radio.srChannels() }
                    StationsRow(vm, "Sveriges Radio", "Alla kanaler, live", l.data.orEmpty(), QueueBuilder.LIST_SR)
                }
                item(key = "se") {
                    val l = rememberLoadable("home-se", keep = "home:se") { g.radio.popularInSweden() }
                    StationsRow(vm, "Populärt i Sverige", "Det fler lyssnar på just nu", l.data.orEmpty(), QueueBuilder.LIST_SWEDEN)
                }
            }

            // ----- Musik för dig -----
            if (music && online) {
                if (hubs.data == null && hubs.state is Load.Loading) item(key = "loading") { LoadingBox() }
                item(key = "mixes") {
                    MixesRow(vm, mixes.data.orEmpty(), ctx)
                }
                item(key = "hot-me") {
                    HotRow(
                        vm, "Hett hos dig", "Det du spelat mest den senaste månaden",
                        hotMe.data.orEmpty(), QueueBuilder.HOT_ME,
                    )
                }
            }

            // ----- Poddar -----
            if (pods) {
                val eps = episodes.data.orEmpty()
                if (subs.isNotEmpty() && eps.isNotEmpty()) {
                    item(key = "eps") { EpisodesRow(vm, eps, progress) }
                }
            }
            if (filter == HomeFilter.PODS) {
                if (subs.isEmpty()) {
                    item(key = "pod-intro") {
                        EmptyBox(
                            Icons.Rounded.Headset,
                            "Poddar",
                            "Följ poddar så hamnar nya avsnitt här och i bilen. JPFM minns var du slutade.",
                            action = {
                                Button(
                                    onClick = { g.prefs.radioTab = 1; vm.nav.select(Tab.RADIO) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Jp.Accent, contentColor = Jp.OnAccent),
                                ) { Text("Hitta poddar") }
                            },
                        )
                    }
                } else {
                    item(key = "pod-subs") {
                        SectionHeader("Dina poddar", action = "Alla") { g.prefs.radioTab = 1; vm.nav.select(Tab.RADIO) }
                        PodcastCards(vm, subs)
                    }
                }
                item(key = "pod-top") {
                    val top = rememberLoadable("home-podtop", keep = "home:podtop") { g.podcasts.topList() }
                    val l = top.data.orEmpty()
                    if (l.isNotEmpty()) {
                        SectionHeader("Topplistan i Sverige", subtitle = "Mest lyssnade poddar just nu")
                        PodcastCards(vm, l)
                    }
                }
            }

            // ----- Topplistor, nytt och resten av biblioteket -----
            if (music && online) {
                item(key = "hot-se") { ChartRow(vm, hotSe.data.orEmpty()) }
                item(key = "releases") {
                    AlbumsRow(vm, "Nya släpp", "Senast utgivet i ditt bibliotek", releases.data.orEmpty())
                }
                when (val st = hubs.state) {
                    is Load.Failed -> if (hubs.data == null) item(key = "err") { ErrorBox(st.message) { hubs.retry() } }
                    else -> hubs.data.orEmpty()
                        // Stationer visas under Radio, och senast spelat i snabbvalen.
                        .filter { h -> !h.items.all { it is Station } && h !== recentHub }
                        .forEachIndexed { i, hub ->
                            item(key = "hub:$i:${hub.identifier}") { HubRow(vm, hub) }
                        }
                }
                item(key = "rediscover") {
                    AlbumsRow(vm, "Återupptäck", "Sånt du gillade förr men inte hört på länge", rediscover.data.orEmpty())
                }
                item(key = "browse") {
                    BrowseGrid(vm, genres.data.orEmpty(), mixes.data.orEmpty())
                }
                item(key = "moods") {
                    CategoryRow(vm, "Stämningar", "Musik efter känsla", moods.data.orEmpty())
                }
                item(key = "decades") {
                    CategoryRow(vm, "Årtionden", "Resa i tiden", decades.data.orEmpty())
                }
            }
        }
    }
}

/** Plex rad med det du spelat senast (heter olika i olika versioner av servern). */
private fun isRecentlyPlayed(h: Hub): Boolean {
    val id = h.identifier.lowercase()
    val t = h.title.lowercase()
    return (id.contains("recent") && (id.contains("play") || id.contains("view"))) ||
        t.startsWith("recently played") || t.startsWith("senast spelad")
}

@Composable
private fun HomeTop(vm: AppViewModel, filter: Int, onFilter: (Int) -> Unit) {
    val g = LocalGraph.current
    val name = g.prefs.userName?.substringBefore(' ')
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Jp.AccentDeep.copy(alpha = 0.28f), Color.Transparent)))
            .statusBarsPadding()
            .padding(top = 18.dp, bottom = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp)) {
            Column(Modifier.align(Alignment.CenterStart).padding(end = 48.dp)) {
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
        Spacer(Modifier.height(14.dp))
        FilterChips(
            HomeFilter.entries.map { it.label },
            filter,
            onFilter,
            Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

/**
 * Snabbval i två kolumner (som överst i Spotify): blanda allt och det du spelat senast.
 * Ett tryck öppnar albumet, långt tryck visar menyn.
 */
@Composable
private fun QuickGrid(vm: AppViewModel, recentFromHub: List<MusicItem>, recentAlbums: List<Album>) {
    val ctx = vm.player.queueContext
    val items = (recentFromHub.filter { it !is Track } + recentAlbums).distinctBy { it::class.simpleName + it.ratingKey }.take(7)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Plats 0 är "Blanda allt", resten det du spelat senast.
        (0..items.size).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { i ->
                    if (i == 0) {
                        QuickTile(
                            "Blanda hela biblioteket",
                            null,
                            onClick = { vm.shuffleLibrary() },
                            modifier = Modifier.weight(1f),
                            icon = Icons.Rounded.Shuffle,
                            highlight = Brush.linearGradient(listOf(Jp.AccentDeep, Color(0xFF16708F))),
                            active = ctx?.kind == QueueContext.Kind.SHUFFLE_LIBRARY,
                        )
                    } else {
                        val item = items[i - 1]
                        QuickTile(
                            item.title,
                            item.thumb,
                            onClick = { vm.open(item) },
                            modifier = Modifier.weight(1f),
                            icon = when (item) {
                                is Artist -> Icons.Rounded.Person
                                is Playlist -> Icons.Rounded.QueueMusic
                                is Station -> Icons.Rounded.Radio
                                else -> Icons.Rounded.Album
                            },
                            artShape = if (item is Artist) CircleShape else RoundedCornerShape(0.dp),
                            active = ctx != null && ctx.id == item.ratingKey,
                            onLongClick = { vm.longPress(item) },
                        )
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Dina mixar: en per genre du lyssnar mest på, och dina favoriter. Ett tryck spelar. */
@Composable
private fun MixesRow(vm: AppViewModel, mixes: List<Mix>, ctx: QueueContext?) {
    if (mixes.isEmpty()) return
    SectionHeader("Dina mixar", subtitle = "Efter vad du lyssnar på – tar aldrig slut")
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(mixes, key = { _, m -> m.trackFilter }) { i, m ->
            MixCard(
                m,
                i,
                onClick = { vm.playMix(m) },
                onLongClick = if (m.albumFilter != null) ({ vm.nav.push(Screen.Category(m)) }) else null,
                playing = ctx?.kind == QueueContext.Kind.MIX && ctx.id == m.trackFilter && vm.player.isPlaying,
            )
        }
    }
}

/** Topplista som rader i kolumner om fyra, att svepa mellan. */
@Composable
private fun HotRow(vm: AppViewModel, title: String, subtitle: String, list: List<Pair<Int, Track>>, which: String) {
    if (list.size < 3) return
    val tracks = list.map { it.second }
    val playTitle = title.removeSuffix(" just nu")
    SectionHeader(title, subtitle = subtitle, action = "Visa alla") { vm.nav.push(Screen.HotList(which)) }
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(list.take(20).chunked(4), key = { i, _ -> "$which:$i" }) { col, chunk ->
            Column(Modifier.fillParentMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                chunk.forEachIndexed { j, (rank, t) ->
                    RankedTrack(
                        rank = rank,
                        track = t,
                        isCurrent = vm.player.current?.ratingKey == t.ratingKey,
                        onClick = { vm.playTracks(tracks, col * 4 + j, playTitle) },
                        onMore = { vm.menu = MenuTarget.ForTrack(t) },
                    )
                }
            }
        }
    }
}

/** Topplistan i Sverige: det som är hett just nu, spelbart där låten finns hos dig. */
@Composable
private fun ChartRow(vm: AppViewModel, chart: List<ChartHit>) {
    if (chart.isEmpty()) return
    val have = chart.count { it.track != null }
    SectionHeader(
        "Hett i Sverige just nu",
        subtitle = if (have > 0) "Topplistan · ${Format.tracks(have)} finns hos dig" else "Topplistan · tryck för artistradio",
        action = "Visa alla",
    ) { vm.nav.push(Screen.HotList(QueueBuilder.HOT_SWEDEN)) }
    LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(chart.take(20).chunked(4), key = { i, _ -> "chart:$i" }) { _, chunk ->
            Column(Modifier.fillParentMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                chunk.forEach { hit -> ChartHitRow(vm, chart, hit) }
            }
        }
    }
}

/** En rad på topplistan, med vad som händer när man trycker (låt, artistradio eller inget). */
@Composable
fun ChartHitRow(vm: AppViewModel, chart: List<ChartHit>, hit: ChartHit, modifier: Modifier = Modifier) {
    val t = hit.track
    val a = hit.artist
    val g = LocalGraph.current
    val canRequest = g.requests.config.collectAsState().value != null
    val requests by g.requests.requests.collectAsState()
    val requested = t == null && requests.any { r -> r.song.equals(hit.song.title, ignoreCase = true) && r.artist.equals(hit.song.artist, ignoreCase = true) }
    ChartEntry(
        hit,
        isCurrent = t != null && vm.player.current?.ratingKey == t.ratingKey,
        onClick = {
            when {
                t != null -> {
                    val tracks = chart.mapNotNull { it.track }.distinctBy { it.ratingKey }
                    vm.playTracks(tracks, tracks.indexOfFirst { it.ratingKey == t.ratingKey }.coerceAtLeast(0), "Hett i Sverige")
                }
                canRequest -> vm.nav.push(Screen.Request("${hit.song.artist} ${hit.song.title}", hit.song))
                a != null -> vm.artistRadio(a)
                else -> vm.toast("${hit.song.title} finns inte i ditt bibliotek")
            }
        },
        requestable = t == null && canRequest,
        requested = requested,
        onMore = when {
            t != null -> ({ vm.menu = MenuTarget.ForTrack(t) })
            a != null -> ({ vm.menu = MenuTarget.ForArtist(a) })
            else -> null
        },
        modifier = modifier,
    )
}

@Composable
private fun AlbumsRow(vm: AppViewModel, title: String, subtitle: String, albums: List<Album>) {
    if (albums.size < 3) return
    SectionHeader(title, subtitle = subtitle)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(albums, key = { _, a -> a.ratingKey }) { _, a ->
            AlbumCard(a, onClick = { vm.nav.push(Screen.Album(a.ratingKey, a.title)) }, onLongClick = { vm.menu = MenuTarget.ForAlbum(a) })
        }
    }
}

@Composable
private fun EpisodesRow(vm: AppViewModel, eps: List<se.jpfm.podcast.Episode>, progress: Map<String, se.jpfm.podcast.Progress>) {
    val g = LocalGraph.current
    SectionHeader("Nya avsnitt", subtitle = "Från poddarna du följer", action = "Poddar") {
        g.prefs.radioTab = 1
        vm.nav.select(Tab.RADIO)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        itemsIndexed(eps.take(12), key = { _, e -> e.id }) { _, e ->
            EpisodeCard(
                e,
                g.podcasts.podcast(e.podcastId)?.image,
                progress[e.id],
                playing = vm.player.nowEpisode?.id == e.id,
                onClick = { vm.playEpisode(e) },
            )
        }
    }
}

@Composable
private fun StationsRow(vm: AppViewModel, title: String, subtitle: String, list: List<se.jpfm.radio.RadioStation>, listId: String) {
    if (list.isEmpty()) return
    SectionHeader(title, subtitle = subtitle, action = "Alla") {
        vm.graph.prefs.radioTab = 0
        vm.nav.select(Tab.RADIO)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        itemsIndexed(list.take(30), key = { _, s -> s.id }) { i, s ->
            StationTile(vm, s) { vm.playStation(list, i, listId, title) }
        }
    }
}

/** Bläddra: dina mest lyssnade genrer som färgade plattor, med ett omslag från din mix. */
@Composable
private fun BrowseGrid(vm: AppViewModel, genres: List<Mix>, mixes: List<Mix>) {
    if (genres.isEmpty()) return
    val covers = mixes.associate { it.trackFilter to it.thumb }
    SectionHeader("Bläddra", subtitle = "Dina genrer först", action = "Visa alla") { vm.nav.push(Screen.Browse) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        genres.take(6).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { m ->
                    CategoryTile(
                        m.title,
                        categoryColor(m.title),
                        onClick = { vm.nav.push(Screen.Category(m)) },
                        modifier = Modifier.weight(1f),
                        thumb = covers[m.trackFilter],
                        onLongClick = { vm.playMix(m) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Stämningar eller årtionden: färgade plattor i en rad att svepa. */
@Composable
private fun CategoryRow(vm: AppViewModel, title: String, subtitle: String, list: List<Mix>) {
    if (list.isEmpty()) return
    SectionHeader(title, subtitle = subtitle)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(list, key = { _, m -> m.trackFilter }) { _, m ->
            CategoryTile(
                m.title,
                categoryColor(m.title),
                onClick = { vm.nav.push(Screen.Category(m)) },
                modifier = Modifier.width(148.dp),
                height = 76.dp,
                onLongClick = { vm.playMix(m) },
            )
        }
    }
}

/**
 * Radio: hela biblioteket blandat, Plex egna stationer och mixar, artistradio för artister du
 * lyssnat på och låtradio från senast spelade låtar. Allt fyller på sig självt.
 */
@Composable
private fun RadioRow(vm: AppViewModel, hubs: List<Hub>, online: Boolean) {
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
    if (!online && favorites.isEmpty()) return
    SectionHeader("Radio", subtitle = "Live, stationer och radio som fylls på", action = "Alla kanaler") {
        g.prefs.radioTab = 0
        vm.nav.select(Tab.RADIO)
    }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Dina radiokanaler först.
        itemsIndexed(favorites, key = { _, s -> "fav:${s.id}" }) { i, s ->
            RadioCard(s.name, "LIVE", s.logo, i + 3, onClick = { vm.playStation(favorites, i, QueueBuilder.LIST_FAVORITES, "Dina kanaler") })
        }
        if (online) {
            item(key = "library") {
                RadioCard("Hela biblioteket", "BLANDAD RADIO", null, 0, onClick = { vm.shuffleLibrary() }, icon = Icons.Rounded.Shuffle)
            }
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
        EmptyBox(
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
