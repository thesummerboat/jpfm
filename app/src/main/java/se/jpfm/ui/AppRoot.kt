package se.jpfm.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DownloadForOffline
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import se.jpfm.plex.SessionState
import se.jpfm.ui.player.MiniPlayer
import se.jpfm.ui.player.NowPlaying
import se.jpfm.ui.screens.AlbumScreen
import se.jpfm.ui.screens.ArtistScreen
import se.jpfm.ui.screens.ConnectingScreen
import se.jpfm.ui.screens.DownloadsScreen
import se.jpfm.ui.screens.HomeScreen
import se.jpfm.ui.screens.HubScreen
import se.jpfm.ui.screens.LibraryScreen
import se.jpfm.ui.screens.LoginScreen
import se.jpfm.ui.screens.OfflineScreen
import se.jpfm.ui.screens.PlaylistScreen
import se.jpfm.ui.screens.SearchScreen
import se.jpfm.ui.screens.SettingsScreen

@Composable
fun AppRoot(vm: AppViewModel) {
    val g = vm.graph
    val session by g.session.state.collectAsState()
    val settings by g.prefs.settings.collectAsState()
    CompositionLocalProvider(
        LocalGraph provides g,
        LocalServer provides ((session as? SessionState.Ready)?.server ?: g.session.server),
        LocalSettings provides settings,
    ) {
        val hasServer = g.prefs.server != null
        when {
            session is SessionState.LoggedOut -> LoginScreen(vm)
            session is SessionState.NoMusicLibrary -> ConnectingScreen(session, onRetry = { g.session.connectInBackground() }, onLogout = { g.session.logout() })
            !hasServer && session !is SessionState.Ready -> ConnectingScreen(session, onRetry = { g.session.connectInBackground() }, onLogout = { g.session.logout() })
            else -> MainScaffold(vm)
        }
        Messages(vm)
    }
}

@Composable
private fun MainScaffold(vm: AppViewModel) {
    val nav = vm.nav
    val holder = rememberSaveableStateHolder()
    BackHandler(enabled = !vm.playerOpen && (nav.depth > 1 || nav.tab != Tab.HOME)) { nav.back() }

    Box(Modifier.fillMaxSize().background(Jp.Background)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val key = "${nav.tab}:${nav.current}"
                AnimatedContent(
                    targetState = key to nav.current,
                    transitionSpec = {
                        val dir = nav.direction
                        (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(tween(200)) { -it / 10 * dir } + fadeOut(tween(160)))
                    },
                    contentKey = { it.first },
                    label = "screen",
                ) { (k, screen) ->
                    holder.SaveableStateProvider(k) { ScreenContent(vm, screen) }
                }
            }
            AnimatedVisibility(
                visible = vm.player.hasMedia,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(vm.player, onOpen = { vm.playerOpen = true }, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
            }
            NavigationBar(containerColor = Jp.Background, tonalElevation = 0.dp) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = nav.tab == t,
                        onClick = { nav.select(t) },
                        icon = { Icon(tabIcon(t), null) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Jp.Text,
                            selectedTextColor = Jp.Text,
                            indicatorColor = Jp.SurfaceHigher,
                            unselectedIconColor = Jp.TextFaint,
                            unselectedTextColor = Jp.TextFaint,
                        ),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = vm.playerOpen && vm.player.hasMedia,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            NowPlaying(vm, onClose = { vm.playerOpen = false })
        }

        vm.menu?.let { target -> ItemMenu(vm, target, onDismiss = { vm.menu = null }) }
        vm.addToPlaylist?.let { tracks -> AddToPlaylistDialog(vm, tracks, onDismiss = { vm.addToPlaylist = null }) }
    }
}

@Composable
private fun ScreenContent(vm: AppViewModel, screen: Screen) {
    when (screen) {
        Screen.Home -> HomeScreen(vm)
        Screen.Library -> LibraryScreen(vm)
        Screen.Search -> SearchScreen(vm)
        Screen.Downloads -> DownloadsScreen(vm)
        Screen.Settings -> SettingsScreen(vm)
        Screen.Radio -> se.jpfm.ui.screens.RadioScreen(vm)
        is Screen.PodcastPage -> se.jpfm.ui.screens.PodcastScreen(vm, screen.podcast)
        is Screen.Artist -> ArtistScreen(vm, screen.key, screen.title)
        is Screen.Album -> AlbumScreen(vm, screen.key, screen.title)
        is Screen.Playlist -> PlaylistScreen(vm, screen.key, screen.title)
        is Screen.Category -> se.jpfm.ui.screens.CategoryScreen(vm, screen.mix)
        Screen.Browse -> se.jpfm.ui.screens.BrowseScreen(vm)
        is Screen.HotList -> se.jpfm.ui.screens.HotListScreen(vm, screen.which)
        is Screen.HubItems -> HubScreen(vm, screen.hub)
        is Screen.Offline -> OfflineScreen(vm, screen.id, screen.title)
    }
}

private fun tabIcon(t: Tab): ImageVector = when (t) {
    Tab.HOME -> Icons.Rounded.Home
    Tab.LIBRARY -> Icons.Rounded.LibraryMusic
    Tab.RADIO -> Icons.Rounded.Radio
    Tab.SEARCH -> Icons.Rounded.Search
    Tab.DOWNLOADS -> Icons.Rounded.DownloadForOffline
}

/** Korta meddelanden ("Tillagd i kön") längst ner. */
@Composable
private fun Messages(vm: AppViewModel) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        // Ett meddelande i taget; ett nytt ersätter det som visas.
        snapshotFlow { vm.message }.filterNotNull().collect { m ->
            vm.messageShown()
            host.currentSnackbarData?.dismiss()
            launch { host.showSnackbar(m) }
        }
    }
    LaunchedEffect(Unit) {
        snapshotFlow { vm.updateMessage }.filterNotNull().collect { m -> launch { host.showSnackbar(m) } }
    }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 150.dp), contentAlignment = Alignment.BottomCenter) {
        SnackbarHost(host) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = Jp.SurfaceHigher,
                contentColor = Jp.Text,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
