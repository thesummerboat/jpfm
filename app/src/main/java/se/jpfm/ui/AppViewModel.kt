package se.jpfm.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import se.jpfm.JpfmApp
import se.jpfm.playback.BuiltQueue
import se.jpfm.plex.Album
import se.jpfm.plex.Artist
import se.jpfm.plex.PlexPin
import se.jpfm.plex.Playlist
import se.jpfm.plex.Station
import se.jpfm.plex.Track
import se.jpfm.update.AppRelease
import se.jpfm.update.UpdateState

/** Vad en meny (bottenark) gäller. */
sealed interface MenuTarget {
    data class ForTrack(val track: Track, val queueIndex: Int? = null) : MenuTarget
    data class ForAlbum(val album: Album) : MenuTarget
    data class ForPlaylist(val playlist: Playlist) : MenuTarget
    data class ForArtist(val artist: Artist) : MenuTarget
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as JpfmApp).graph
    val player = PlayerConnection(app, graph, viewModelScope)
    val nav = Navigator()

    var playerOpen by mutableStateOf(false)
    var menu by mutableStateOf<MenuTarget?>(null)
    /** Låtar som ska läggas i en spellista (dialogen visas när listan inte är null). */
    var addToPlaylist by mutableStateOf<List<Track>?>(null)

    var message by mutableStateOf<String?>(null)
        private set

    fun toast(text: String) {
        message = text
    }

    fun messageShown() {
        message = null
    }

    init {
        player.connect()
        checkForUpdate()
        viewModelScope.launch {
            graph.requests.finished.collect { r ->
                toast("Klart: ${r.title} finns snart i ditt bibliotek")
                runCatching { graph.library.scan() }
                // Plex behöver en stund att läsa in filerna.
                kotlinx.coroutines.delay(20_000)
                graph.library.invalidate()
            }
        }
    }

    // ---------- Önska musik (Lidarr) ----------

    fun requestAlbum(album: se.jpfm.request.LidarrAlbum, song: String? = null) {
        viewModelScope.launch {
            try {
                val r = graph.requests.request(album, song)
                toast(
                    when (r.state) {
                        se.jpfm.request.RequestState.DONE -> "${album.title} finns redan i Lidarr"
                        se.jpfm.request.RequestState.WAITING -> "Sparad – skickas när Lidarr går att nå"
                        se.jpfm.request.RequestState.FAILED -> "Gick inte: ${r.message ?: "okänt fel"}"
                        else -> "Önskad: ${album.title}"
                    },
                )
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("Kunde inte önska: ${e.message ?: "okänt fel"}")
            }
        }
    }

    fun requestLater(term: String, artist: String, song: String?) {
        graph.requests.requestLater(term, artist, song)
        toast("Sparad – skickas när Lidarr går att nå")
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }

    // ---------- Spela ----------

    private fun launchQueue(errorText: String = "Kunde inte starta uppspelningen", build: suspend () -> BuiltQueue) {
        viewModelScope.launch {
            try {
                val q = build()
                if (q.isEmpty) toast("Inget att spela") else player.play(q)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("$errorText: ${e.message ?: "okänt fel"}")
            }
        }
    }

    fun playAlbum(key: String, startTrackKey: String? = null, shuffle: Boolean = false) =
        launchQueue { graph.queues.album(key, startTrackKey, shuffle) }

    fun playPlaylist(key: String, startTrackKey: String? = null, shuffle: Boolean = false, title: String? = null) =
        launchQueue { graph.queues.playlist(key, startTrackKey, shuffle, title) }

    fun artistShuffle(artist: Artist) = launchQueue { graph.queues.artistShuffle(artist) }

    fun artistRadio(artist: Artist) = launchQueue("Kunde inte starta radion") { graph.queues.artistRadio(artist.ratingKey, artist.title) }

    fun playStation(station: Station) = launchQueue("Kunde inte starta stationen") { graph.queues.station(station) }

    fun sonicRadio(track: Track) = launchQueue("Kunde inte starta låtradion") { graph.queues.sonicRadio(track) }

    fun shuffleLibrary() = launchQueue { graph.queues.shuffleLibrary() }

    /** En mix (genre, stämning, årtionde, favoriter) som fylls på av sig själv. */
    fun playMix(mix: se.jpfm.plex.Mix) = launchQueue("Kunde inte starta mixen") { graph.queues.mix(mix) }

    fun playTracks(tracks: List<Track>, index: Int, title: String, shuffle: Boolean = false) =
        launchQueue { graph.queues.tracks(tracks, index, title, shuffle) }

    // ---------- Radio och poddar ----------

    /** Spelar en kanal; ⏭ byter till nästa kanal i samma lista. */
    fun playStation(list: List<se.jpfm.radio.RadioStation>, index: Int, listId: String?, title: String) =
        launchQueue("Kunde inte starta kanalen") { graph.queues.live(list, index, listId, title) }

    fun playEpisode(e: se.jpfm.podcast.Episode) = launchQueue("Kunde inte spela avsnittet") { graph.queues.episode(e) }

    fun toggleFavorite(s: se.jpfm.radio.RadioStation) {
        val was = graph.radio.isFavorite(s.id)
        graph.radio.toggleFavorite(s)
        toast(if (was) "${s.name} borttagen från dina kanaler" else "${s.name} tillagd i dina kanaler")
    }

    fun toggleSubscription(p: se.jpfm.podcast.Podcast) {
        val was = graph.podcasts.isSubscribed(p.id)
        graph.podcasts.toggleSubscription(p)
        toast(if (was) "Du följer inte ${p.title} längre" else "Du följer ${p.title}")
    }

    fun downloadEpisode(e: se.jpfm.podcast.Episode) {
        graph.downloads.downloadEpisode(e)
        toast("Laddar ner avsnittet")
    }

    /** Lägg till en podd med RSS-adress. */
    fun addFeed(url: String) {
        viewModelScope.launch {
            runCatching { graph.podcasts.fromFeed(url) }
                .onSuccess {
                    graph.podcasts.toggleSubscription(it)
                    toast("Du följer ${it.title}")
                }
                .onFailure { toast("Hittade ingen podd på adressen") }
        }
    }

    /** Spelar en blandad lista som hämtas först (t.ex. en genre). */
    fun playLoaded(title: String, load: suspend () -> List<Track>) =
        launchQueue { graph.queues.tracks(load(), 0, title) }

    fun playOffline(id: String, startTrackKey: String? = null, shuffle: Boolean = false) =
        launchQueue { graph.queues.offline(id, startTrackKey, shuffle) }

    /** "Spela X på JPFM" från Google Assistant. Tomt eller ingen träff: blanda biblioteket. */
    fun playFromSearch(query: String, extras: android.os.Bundle?) =
        launchQueue { graph.queues.fromSearch(query, extras) ?: graph.queues.shuffleLibrary() }

    fun playNext(tracks: List<Track>) {
        player.playNext(tracks)
        toast(if (tracks.size == 1) "Spelas härnäst" else "${tracks.size} låtar spelas härnäst")
    }

    fun addToQueue(tracks: List<Track>) {
        player.addToQueue(tracks)
        toast(if (tracks.size == 1) "Tillagd i kön" else "${tracks.size} låtar tillagda i kön")
    }

    /** Hämtar låtarna i ett album/en spellista och gör något med dem (spela härnäst, köa …). */
    fun withTracksOf(target: MenuTarget, action: (List<Track>) -> Unit) {
        viewModelScope.launch {
            runCatching {
                when (target) {
                    is MenuTarget.ForAlbum -> graph.library.albumPage(target.album.ratingKey).tracks
                    is MenuTarget.ForPlaylist -> graph.library.playlistTracks(target.playlist.ratingKey)
                    is MenuTarget.ForArtist -> graph.library.artistTracks(target.artist.ratingKey)
                    is MenuTarget.ForTrack -> listOf(target.track)
                }
            }.onSuccess(action).onFailure { toast("Kunde inte hämta låtarna") }
        }
    }

    // ---------- Gilla och spellistor ----------

    fun setLoved(track: Track, loved: Boolean) {
        val updated = track.copy(userRating = if (loved) 10f else null)
        player.updateTrack(updated)
        viewModelScope.launch {
            runCatching { graph.library.setLoved(track.ratingKey, loved) }
                .onSuccess { toast(if (loved) "Gillad" else "Inte gillad längre") }
                .onFailure { toast("Kunde inte spara i Plex") }
        }
    }

    fun addTracksToPlaylist(playlist: Playlist, tracks: List<Track>) {
        viewModelScope.launch {
            runCatching { graph.library.addToPlaylist(playlist.ratingKey, tracks.map { it.ratingKey }) }
                .onSuccess { toast("Tillagd i ${playlist.title}") }
                .onFailure { toast("Kunde inte lägga till: ${it.message}") }
        }
    }

    fun createPlaylist(title: String, tracks: List<Track>) {
        viewModelScope.launch {
            runCatching { graph.library.createPlaylist(title, tracks.map { it.ratingKey }) }
                .onSuccess { toast("Spellistan $title skapad") }
                .onFailure { toast("Kunde inte skapa spellistan: ${it.message}") }
        }
    }

    fun deletePlaylist(p: Playlist) {
        viewModelScope.launch {
            runCatching { graph.library.deletePlaylist(p.ratingKey) }
                .onSuccess {
                    toast("${p.title} borttagen")
                    nav.back()
                }
                .onFailure { toast("Kunde inte ta bort spellistan") }
        }
    }

    // ---------- Nedladdning ----------

    fun downloadAlbum(album: Album) {
        viewModelScope.launch {
            runCatching { graph.library.albumPage(album.ratingKey).tracks }
                .onSuccess {
                    graph.downloads.download("album:${album.ratingKey}", album.title, album.artist, album.thumb, it)
                    toast("Laddar ner ${album.title}")
                }
                .onFailure { toast("Kunde inte ladda ner albumet") }
        }
    }

    fun downloadPlaylist(p: Playlist) {
        viewModelScope.launch {
            runCatching { graph.library.playlistTracks(p.ratingKey, force = true) }
                .onSuccess {
                    graph.downloads.download("playlist:${p.ratingKey}", p.title, "Spellista · ${Format.tracks(it.size)}", p.thumb, it)
                    toast("Laddar ner ${p.title}")
                }
                .onFailure { toast("Kunde inte ladda ner spellistan") }
        }
    }

    fun removeDownload(id: String, title: String) {
        graph.downloads.remove(id)
        toast("$title borttagen från telefonen")
    }

    // ---------- Inloggning ----------

    var loginPin by mutableStateOf<PlexPin?>(null)
        private set
    var loginUrl by mutableStateOf<String?>(null)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: Job? = null

    fun beginLogin() {
        loginJob?.cancel()
        loginError = null
        loginPin = null
        loginJob = viewModelScope.launch {
            val pin = try {
                graph.client.createPin()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loginError = "Kommer inte åt plex.tv. Har telefonen nät?"
                return@launch
            }
            loginPin = pin
            loginUrl = graph.client.authUrl(pin)
            val until = System.currentTimeMillis() + 15 * 60_000
            while (isActive && System.currentTimeMillis() < until) {
                delay(2_000)
                val token = runCatching { graph.client.checkPin(pin) }.getOrNull()
                if (token != null) {
                    graph.session.login(token)
                    loginPin = null
                    return@launch
                }
            }
            loginError = "Koden gick ut. Hämta en ny."
        }
    }

    // ---------- Uppdateringar ----------

    var update by mutableStateOf<AppRelease?>(null)
        private set
    var updateProgress by mutableFloatStateOf(-1f)
        private set

    fun checkForUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            val r = runCatching { graph.updater.checkForUpdate() }
            update = r.getOrNull()
            if (manual) {
                toast(
                    when {
                        r.isFailure -> "Kunde inte nå GitHub"
                        update == null -> "Du har senaste versionen"
                        else -> "Ny version: ${update?.versionName}"
                    },
                )
            }
        }
    }

    fun installUpdate() {
        val release = update ?: return
        if (!graph.updater.canInstall()) {
            graph.updater.openInstallPermissionSettings()
            toast("Tillåt JPFM att installera appar och tryck Uppdatera igen")
            return
        }
        viewModelScope.launch {
            updateProgress = 0f
            runCatching {
                val apk = graph.updater.download(release) { p -> updateProgress = p }
                graph.updater.install(apk)
            }.onFailure { toast("Uppdateringen misslyckades: ${it.message}") }
            updateProgress = -1f
        }
    }

    val updateMessage: String? get() = UpdateState.message
}
