package se.jpfm.plex

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.jpfm.Prefs
import java.io.IOException

sealed interface SessionState {
    data object Starting : SessionState
    data object LoggedOut : SessionState
    data class Connecting(val serverName: String?) : SessionState
    data class Ready(
        val server: PlexServer,
        val section: MusicSection,
        val sections: List<MusicSection>,
    ) : SessionState
    data class NoMusicLibrary(val serverName: String) : SessionState
    /** Inloggad men servern går inte att nå. Nedladdad musik fungerar ändå. */
    data class Offline(val reason: String) : SessionState
}

/**
 * Vet vilken Plex-server och vilket musikbibliotek som gäller, och hittar servern igen när
 * telefonen byter nät (wifi hemma → mobildata i bilen). Alla anrop går genom [call], som provar
 * en ny adress och försöker en gång till om den gamla slutat svara.
 */
class PlexSession(
    private val prefs: Prefs,
    val client: PlexClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile
    var server: PlexServer? = prefs.server
        private set

    @Volatile
    var section: MusicSection? = prefs.sectionKey?.let { MusicSection(it, "", prefs.sectionUuid) }
        private set

    private val connectLock = Mutex()
    private var connectJob: Job? = null

    val isReady: Boolean get() = _state.value is SessionState.Ready

    /**
     * Väntar tills appen vet om servern går att nå (högst [timeoutMs]). Används när bilen eller
     * rösten startar appen från noll, så att vi inte felaktigt tror att vi är offline.
     */
    suspend fun awaitSettled(timeoutMs: Long = 12_000): Boolean {
        withTimeoutOrNull(timeoutMs) {
            state.first { it !is SessionState.Starting && it !is SessionState.Connecting }
        }
        return isReady
    }

    fun start() {
        if (prefs.accountToken == null) {
            _state.value = SessionState.LoggedOut
            return
        }
        connectInBackground()
    }

    fun connectInBackground() {
        if (connectJob?.isActive == true) return
        connectJob = scope.launch { connect() }
    }

    /** Hittar servern och musikbiblioteket. Sätter [state] till Ready, Offline eller LoggedOut. */
    suspend fun connect(): Boolean = connectLock.withLock {
        val token = prefs.accountToken ?: run {
            _state.value = SessionState.LoggedOut
            return false
        }
        val cached = prefs.server
        if (_state.value !is SessionState.Ready) _state.value = SessionState.Connecting(cached?.name)

        var found: PlexServer? = null
        // 1. Senast kända adresser – fungerar även om plex.tv inte svarar.
        if (cached != null) {
            val uri = client.fastestReachable((listOf(cached.uri) + cached.candidates).distinct(), cached.token)
            if (uri != null) found = cached.copy(uri = uri)
        }
        // 2. Fråga plex.tv efter servrar och adresser.
        if (found == null) {
            val servers = try {
                client.servers(token)
            } catch (e: PlexAuthException) {
                logout()
                return false
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                emptyList()
            }
            val ordered = servers.sortedByDescending { it.machineId == cached?.machineId }
            for (info in ordered) {
                val uri = client.fastestReachable(info.candidates, info.token) ?: continue
                found = PlexServer(info.name, uri, info.token, info.machineId, info.candidates)
                break
            }
            if (found == null) {
                _state.value = SessionState.Offline(
                    if (servers.isEmpty() && cached == null) "Hittade ingen Plex-server på kontot"
                    else "Kommer inte åt ${cached?.name ?: "Plex-servern"} just nu",
                )
                return false
            }
        }
        val srv = found!!
        server = srv
        prefs.server = srv

        // 3. Musikbiblioteken.
        val sections = try {
            client.get(srv, "/library/sections").container().objects("Directory")
                .filter { it.str("type") == "artist" }
                .mapNotNull { d -> d.str("key")?.let { MusicSection(it, d.str("title") ?: "Musik", d.str("uuid")) } }
        } catch (e: PlexAuthException) {
            // Servernyckeln har gått ut: hämta nya från plex.tv nästa gång.
            prefs.server = null
            _state.value = SessionState.Offline("Plex nekade åtkomst. Försök igen.")
            return false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _state.value = SessionState.Offline("Servern svarade inte: ${e.message}")
            return false
        }
        if (sections.isEmpty()) {
            _state.value = SessionState.NoMusicLibrary(srv.name)
            return false
        }
        val chosen = sections.firstOrNull { it.key == prefs.sectionKey } ?: sections.first()
        section = chosen
        prefs.sectionKey = chosen.key
        prefs.sectionUuid = chosen.uuid
        _state.value = SessionState.Ready(srv, chosen, sections)
        true
    }

    /** Efter inloggning med kod. */
    suspend fun login(accountToken: String) {
        prefs.accountToken = accountToken
        client.user(accountToken)?.let {
            prefs.userName = it.name
            prefs.userThumb = it.thumb
        }
        connect()
    }

    fun logout() {
        prefs.clearAccount()
        server = null
        section = null
        _state.value = SessionState.LoggedOut
    }

    fun selectSection(s: MusicSection) {
        val ready = _state.value as? SessionState.Ready ?: return
        section = s
        prefs.sectionKey = s.key
        prefs.sectionUuid = s.uuid
        _state.value = ready.copy(section = s)
    }

    /** Nätet har bytts: kolla att adressen fortfarande fungerar, annars hitta en ny. */
    fun onNetworkChanged() {
        scope.launch {
            val current = server
            when (_state.value) {
                is SessionState.Ready -> if (current == null || !client.isReachable(current.uri, current.token)) reconnect(current)
                is SessionState.Offline, is SessionState.Connecting -> connect()
                else -> Unit
            }
        }
    }

    /** Hittar en fungerande adress igen. Returnerar servern, eller null om den inte går att nå. */
    private suspend fun reconnect(failed: PlexServer?): PlexServer? {
        // Någon annan hann redan byta adress.
        val now = server
        if (now != null && failed != null && now.uri != failed.uri) return now
        return if (connect()) server else null
    }

    /**
     * Kör ett anrop mot servern. Om adressen slutat svara (t.ex. lokal adress efter att du
     * lämnat hemmet) letas en ny upp och anropet görs en gång till.
     */
    suspend fun <T> call(block: suspend (PlexServer) -> T): T {
        val s = server ?: throw IOException("Ingen Plex-server")
        return try {
            block(s)
        } catch (e: PlexHttpException) {
            throw e
        } catch (e: IOException) {
            val again = reconnect(s) ?: throw e
            block(again)
        }
    }
}
