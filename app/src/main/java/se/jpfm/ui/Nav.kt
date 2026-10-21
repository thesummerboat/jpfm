package se.jpfm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import se.jpfm.plex.Genre
import se.jpfm.plex.Hub

sealed interface Screen {
    data object Home : Screen
    data object Library : Screen
    data object Search : Screen
    data object Downloads : Screen
    data object Settings : Screen
    data class Artist(val key: String, val title: String) : Screen
    data class Album(val key: String, val title: String) : Screen
    data class Playlist(val key: String, val title: String) : Screen
    data class GenreAlbums(val genre: Genre) : Screen
    data class HubItems(val hub: Hub) : Screen
    data class Offline(val id: String, val title: String) : Screen
}

enum class Tab(val root: Screen, val label: String) {
    HOME(Screen.Home, "Hem"),
    LIBRARY(Screen.Library, "Bibliotek"),
    SEARCH(Screen.Search, "Sök"),
    DOWNLOADS(Screen.Downloads, "Nedladdat"),
}

/** En historik per flik, som i Spotify: byter du flik och kommer tillbaka står du kvar där du var. */
class Navigator {
    var tab by mutableStateOf(Tab.HOME)
        private set

    private val stacks: Map<Tab, SnapshotStateList<Screen>> = Tab.entries.associateWith { mutableStateListOf(it.root) }

    /** Ökar när samma flik trycks igen, så att listan kan rulla upp till toppen. */
    var reselect by mutableIntStateOf(0)
        private set

    /** +1 framåt, -1 bakåt – för animationens riktning. */
    var direction by mutableIntStateOf(1)
        private set

    val current: Screen get() = stacks.getValue(tab).last()

    val depth: Int get() = stacks.getValue(tab).size

    fun push(s: Screen) {
        val stack = stacks.getValue(tab)
        if (stack.last() == s) return
        direction = 1
        stack.add(s)
    }

    fun select(t: Tab) {
        direction = 1
        if (t == tab) {
            val stack = stacks.getValue(t)
            if (stack.size > 1) {
                direction = -1
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
            } else {
                reselect++
            }
        } else {
            tab = t
        }
    }

    /** Tillbaka. False när det inte finns något att gå tillbaka till (appen stängs). */
    fun back(): Boolean {
        val stack = stacks.getValue(tab)
        direction = -1
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            return true
        }
        if (tab != Tab.HOME) {
            tab = Tab.HOME
            return true
        }
        return false
    }

    fun openInTab(t: Tab, s: Screen) {
        tab = t
        push(s)
    }
}
