package se.jpfm.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.plex.Library
import java.io.File

/** En önskan som inte gått att skicka än (servern gick inte att nå). */
data class QueuedRequest(val term: String, val artist: String, val song: String?, val createdAt: Long = System.currentTimeMillis()) {
    fun toJson(): JSONObject = JSONObject().put("term", term).put("artist", artist).put("created", createdAt).apply { song?.let { put("song", it) } }

    fun asRemote(): RemoteRequest = RemoteRequest(
        id = -createdAt,
        foreignAlbumId = "",
        title = song ?: term,
        artist = artist,
        type = null,
        year = null,
        cover = null,
        song = song,
        userName = "",
        state = RequestState.QUEUED,
        progress = 0f,
        message = null,
        reason = null,
        createdAt = createdAt,
        mine = true,
    )

    companion object {
        fun fromJson(o: JSONObject): QueuedRequest? {
            val term = o.optString("term").ifBlank { return null }
            return QueuedRequest(term, o.optString("artist"), if (o.has("song")) o.optString("song") else null, o.optLong("created"))
        }
    }
}

/**
 * Önskningar via JPFM-servern på Unraid. Servern håller i önskningarna och godkännandena och
 * pratar med Lidarr; appen loggar in med din Plex-token. Önskar du när servern inte går att nå
 * sparas önskningen här och skickas nästa gång.
 */
class RequestRepository(
    private val http: OkHttpClient,
    dir: File,
    private val token: () -> String?,
    private val plexServerId: () -> String?,
) {
    private val api = JpfmServerClient(http)
    private val configFile = File(dir, "request-server.json")
    private val cacheFile = File(dir, "requests-cache.json")
    private val queueFile = File(dir, "requests-queued.json")
    private val lock = Any()
    private val syncing = Mutex()

    private val _url = MutableStateFlow(readUrl())
    /** JPFM-serverns adress, eller null om den inte är kopplad. */
    val url: StateFlow<String?> = _url.asStateFlow()

    private val _me = MutableStateFlow<ServerMe?>(null)
    val me: StateFlow<ServerMe?> = _me.asStateFlow()

    private val _remote = MutableStateFlow(readCache())
    private val _queued = MutableStateFlow(readQueue())
    private val _requests = MutableStateFlow(merge())
    /** Önskningar: de som väntar på att skickas först, sedan serverns (ägaren ser allas). */
    val requests: StateFlow<List<RemoteRequest>> = _requests.asStateFlow()

    private val _finished = MutableSharedFlow<RemoteRequest>(extraBufferCapacity = 8)
    /** Dina önskningar som just blivit klara. */
    val finished: SharedFlow<RemoteRequest> = _finished.asSharedFlow()

    val configured: Boolean get() = _url.value != null

    private fun auth(): Pair<String, String> {
        val u = _url.value ?: throw ServerException("JPFM-servern är inte kopplad", 0)
        val t = token() ?: throw ServerException("Logga in med Plex först", 401)
        return u to t
    }

    // ---------- Koppla ----------

    /**
     * Ansluter till JPFM-servern. Är servern ny och du äger Plex-servern kopplas den till dig.
     */
    suspend fun connect(rawUrl: String): ServerMe {
        val u = JpfmServerClient.normalizeUrl(rawUrl)
        val t = token() ?: throw ServerException("Logga in med Plex först", 401)
        if (!api.health(u)) throw ServerException("Det där verkar inte vara en JPFM-server", 0)
        var me = api.me(u, t)
        if (me.role == "unclaimed") {
            val sid = plexServerId() ?: throw ServerException("Anslut till din Plex-server först", 0)
            api.claim(u, t, sid)
            me = api.me(u, t)
        }
        if (me.role == "denied") throw ServerException("Du har inte tillgång till Plex-servern som JPFM-servern hör till", 403)
        saveUrl(u)
        _me.value = me
        runCatching { refreshRequests() }
        return me
    }

    fun disconnect() {
        saveUrl(null)
        _me.value = null
        _remote.value = emptyList()
        publish()
        runCatching { cacheFile.delete() }
    }

    // ---------- Lidarr (ägaren) ----------

    suspend fun lidarrSettings(): LidarrSettings = auth().let { (u, t) -> api.lidarr(u, t) }

    suspend fun testLidarr(lidarrUrl: String, apiKey: String?): LidarrSetup = auth().let { (u, t) -> api.testLidarr(u, t, lidarrUrl, apiKey) }

    suspend fun saveLidarr(lidarrUrl: String, apiKey: String?, root: String, quality: Int, metadata: Int) {
        val (u, t) = auth()
        api.saveLidarr(u, t, lidarrUrl, apiKey, root, quality, metadata)
        _me.value = api.me(u, t)
    }

    suspend fun users(): List<ServerUser> = auth().let { (u, t) -> api.users(u, t) }

    suspend fun updateUser(id: Long, autoApprove: Boolean? = null, blocked: Boolean? = null) {
        val (u, t) = auth()
        api.updateUser(u, t, id, autoApprove, blocked)
    }

    // ---------- Söka och önska ----------

    suspend fun lookup(term: String, fallback: String? = null): List<ServerAlbum> = auth().let { (u, t) -> api.search(u, t, term, fallback) }

    suspend fun request(album: ServerAlbum, song: String?): RemoteRequest {
        val (u, t) = auth()
        val r = api.request(u, t, album.foreignAlbumId, song)
        upsertRemote(r)
        return r
    }

    /** Sparar en önskan som skickas när servern går att nå. */
    fun requestLater(term: String, artist: String, song: String?) {
        synchronized(lock) {
            _queued.value = listOf(QueuedRequest(term, artist, song)) + _queued.value.filter { !it.term.equals(term, ignoreCase = true) }
            writeQueue()
        }
        publish()
    }

    suspend fun approve(id: Long) {
        val (u, t) = auth()
        api.approve(u, t, id)
        refreshRequests()
    }

    suspend fun deny(id: Long, reason: String?) {
        val (u, t) = auth()
        api.deny(u, t, id, reason)
        refreshRequests()
    }

    suspend fun remove(r: RemoteRequest) {
        if (r.state == RequestState.QUEUED) {
            synchronized(lock) {
                _queued.value = _queued.value.filter { it.createdAt != r.createdAt }
                writeQueue()
            }
            publish()
            return
        }
        val (u, t) = auth()
        api.delete(u, t, r.id)
        synchronized(lock) { _remote.value = _remote.value.filter { it.id != r.id } }
        publish()
    }

    // ---------- Hålla sig uppdaterad ----------

    /**
     * Skickar det som väntar och hämtar läget från servern. Körs när appen startar, när nätet
     * byts och medan önskningarna visas.
     */
    suspend fun sync() {
        val u = _url.value ?: return
        val t = token() ?: return
        if (!syncing.tryLock()) return
        try {
            val me = api.me(u, t)
            _me.value = me
            if (me.canRequest && me.lidarr) sendQueued(u, t)
            refreshRequests()
        } catch (_: java.io.IOException) {
            // Inte nåbar just nu.
        } finally {
            syncing.unlock()
        }
    }

    private suspend fun sendQueued(u: String, t: String) {
        for (q in _queued.value.reversed()) {
            val results = api.search(u, t, q.term, q.song)
            val best = RequestMatch.rank(results, q.artist.ifBlank { null }, null, q.song).firstOrNull()
            if (best != null) api.request(u, t, best.foreignAlbumId, q.song)
            synchronized(lock) {
                _queued.value = _queued.value.filter { it.createdAt != q.createdAt }
                writeQueue()
            }
        }
        publish()
    }

    private suspend fun refreshRequests() {
        val (u, t) = auth()
        val fresh = api.requests(u, t)
        val before = _remote.value.associateBy { it.id }
        synchronized(lock) {
            _remote.value = fresh
            writeCache()
        }
        publish()
        for (r in fresh) {
            val old = before[r.id] ?: continue
            if (r.mine && r.state == RequestState.DONE && old.state != RequestState.DONE) _finished.tryEmit(r)
        }
    }

    private fun upsertRemote(r: RemoteRequest) {
        synchronized(lock) {
            _remote.value = listOf(r) + _remote.value.filter { it.id != r.id }
            writeCache()
        }
        publish()
    }

    private fun merge(): List<RemoteRequest> = _queued.value.map { it.asRemote() } + _remote.value

    private fun publish() {
        _requests.value = merge()
    }

    // ---------- Topplistelåtar ----------

    /**
     * Vilket album (eller vilken singel) en låt på Apples topplista ligger på, så att rätt skiva
     * kan önskas (Lidarr hanterar hela album och singlar, inte lösa låtar).
     */
    suspend fun appleAlbumOf(songId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://itunes.apple.com/lookup?id=$songId&country=se&entity=song").header("User-Agent", "JPFM/1.0").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val res = JSONObject(resp.body?.string().orEmpty()).optJSONArray("results") ?: return@use null
                (0 until res.length()).mapNotNull { res.optJSONObject(it) }
                    .firstOrNull { it.optString("wrapperType") == "track" }
                    ?.optString("collectionName")?.ifBlank { null }
            }
        }.getOrNull()
    }

    // ---------- Spara ----------

    private fun saveUrl(u: String?) {
        _url.value = u
        runCatching { if (u == null) configFile.delete() else configFile.writeText(JSONObject().put("url", u).toString()) }
    }

    private fun readUrl(): String? = runCatching { JSONObject(configFile.readText()).optString("url").ifBlank { null } }.getOrNull()

    private fun writeCache() {
        runCatching {
            val a = JSONArray()
            _remote.value.take(100).forEach { r ->
                a.put(
                    JSONObject()
                        .put("id", r.id).put("foreign_album_id", r.foreignAlbumId).put("title", r.title).put("artist", r.artist)
                        .put("type", r.type ?: JSONObject.NULL).put("year", r.year ?: JSONObject.NULL).put("cover", r.cover ?: JSONObject.NULL)
                        .put("song", r.song ?: JSONObject.NULL).put("user_name", r.userName).put("status", r.state.name.lowercase())
                        .put("progress", r.progress.toDouble()).put("message", r.message ?: JSONObject.NULL).put("reason", r.reason ?: JSONObject.NULL)
                        .put("created", r.createdAt / 1000.0).put("mine", r.mine),
                )
            }
            cacheFile.writeText(a.toString())
        }
    }

    private fun readCache(): List<RemoteRequest> = runCatching { ServerParse.requests(JSONArray(cacheFile.readText())) }.getOrDefault(emptyList())

    private fun writeQueue() {
        runCatching { queueFile.writeText(JSONArray().apply { _queued.value.forEach { put(it.toJson()) } }.toString()) }
    }

    private fun readQueue(): List<QueuedRequest> = runCatching {
        val a = JSONArray(queueFile.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(QueuedRequest::fromJson) }
    }.getOrDefault(emptyList())
}

/** Väljer rätt album i Lidarrs sökresultat för en låt eller ett album du önskat. */
object RequestMatch {
    fun rank(results: List<ServerAlbum>, artist: String?, album: String?, song: String?): List<ServerAlbum> {
        val a = artist?.let { Library.norm(it) }.orEmpty()
        val al = album?.let { Library.norm(cleanAlbum(it)) }.orEmpty()
        val so = song?.let { Library.norm(it.substringBefore(" (")) }.orEmpty()
        fun score(x: ServerAlbum): Int {
            var s = 0
            val xa = Library.norm(x.artist)
            val xt = Library.norm(x.title)
            if (a.isNotEmpty()) {
                s += when {
                    xa == a -> 10
                    xa.isNotEmpty() && (xa.contains(a) || a.contains(xa)) -> 5
                    else -> -10
                }
            }
            if (al.isNotEmpty() && xt == al) s += 8
            if (so.isNotEmpty() && xt == so) s += 6
            if (al.isNotEmpty() && xt != al && (xt.startsWith(al) || al.startsWith(xt))) s += 3
            if (so.isNotEmpty() && album?.endsWith("- Single") == true && x.type.equals("single", true)) s += 2
            return s
        }
        return results.withIndex()
            .sortedWith(compareByDescending<IndexedValue<ServerAlbum>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    /** "Housewife - Single" → "Housewife". */
    fun cleanAlbum(name: String): String = name.removeSuffix(" - Single").removeSuffix(" - EP").trim()
}
