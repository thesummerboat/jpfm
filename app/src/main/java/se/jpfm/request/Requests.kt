package se.jpfm.request

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.plex.Library
import java.io.File
import java.io.IOException

enum class RequestState(val label: String) {
    WAITING("Väntar på att skickas"),
    SEARCHING("Lidarr letar"),
    DOWNLOADING("Laddas ner"),
    IMPORTING("Läggs in"),
    DONE("Klar"),
    FAILED("Gick inte"),
}

/** Något du önskat: ett album eller en singel, och hur det går med det. */
data class SongRequest(
    /** Albumets MusicBrainz-id, eller "q:" + sökningen för önskningar som inte skickats än. */
    val id: String,
    val title: String,
    val artist: String,
    val type: String? = null,
    val year: Int? = null,
    val cover: String? = null,
    /** Låten du ville ha, om du önskade från en låt. */
    val song: String? = null,
    /** Ännu inte hittad i Lidarr: söks fram när Lidarr går att nå. */
    val term: String? = null,
    /** Lidarrs beskrivning av albumet, för att kunna skicka önskningen senare. */
    val album: JSONObject? = null,
    val lidarrId: Int = 0,
    val state: RequestState,
    val progress: Float = 0f,
    val message: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val active: Boolean get() = state == RequestState.WAITING || state == RequestState.SEARCHING ||
        state == RequestState.DOWNLOADING || state == RequestState.IMPORTING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("artist", artist)
        type?.let { put("type", it) }
        year?.let { put("year", it) }
        cover?.let { put("cover", it) }
        song?.let { put("song", it) }
        term?.let { put("term", it) }
        album?.let { put("album", it) }
        put("lidarrId", lidarrId); put("state", state.name); put("progress", progress.toDouble())
        message?.let { put("message", it) }
        put("created", createdAt); put("updated", updatedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): SongRequest? {
            val id = o.optString("id").ifBlank { return null }
            fun s(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).ifBlank { null } else null
            return SongRequest(
                id = id,
                title = s("title").orEmpty(),
                artist = s("artist").orEmpty(),
                type = s("type"),
                year = if (o.has("year")) o.optInt("year") else null,
                cover = s("cover"),
                song = s("song"),
                term = s("term"),
                album = o.optJSONObject("album"),
                lidarrId = o.optInt("lidarrId"),
                state = RequestState.entries.firstOrNull { it.name == o.optString("state") } ?: RequestState.WAITING,
                progress = o.optDouble("progress", 0.0).toFloat(),
                message = s("message"),
                createdAt = o.optLong("created"),
                updatedAt = o.optLong("updated"),
            )
        }
    }
}

/**
 * Önskningar till Lidarr på servern hemma. Önskar du när Lidarr inte går att nå (i bilen
 * utan Tailscale) sparas önskningen och skickas nästa gång den går att nå. När Lidarr laddat
 * ner och lagt in musiken ber vi Plex läsa in biblioteket.
 */
class RequestRepository(private val http: OkHttpClient, dir: File) {
    private val lidarr = LidarrClient(http)
    private val configFile = File(dir, "lidarr.json")
    private val file = File(dir, "requests.json")
    private val lock = Any()
    private val syncing = Mutex()

    private val _config = MutableStateFlow(readConfig())
    val config: StateFlow<LidarrConfig?> = _config.asStateFlow()

    private val _requests = MutableStateFlow(readRequests())
    val requests: StateFlow<List<SongRequest>> = _requests.asStateFlow()

    private val _finished = MutableSharedFlow<SongRequest>(extraBufferCapacity = 8)
    /** Önskningar som just blivit klara (för ett meddelande och omläsning i Plex). */
    val finished: SharedFlow<SongRequest> = _finished.asSharedFlow()

    val configured: Boolean get() = _config.value != null

    // ---------- Inställningar ----------

    suspend fun testConnection(url: String, key: String): LidarrSetup = lidarr.setup(LidarrConfig.normalizeUrl(url), key.trim())

    fun saveConfig(cfg: LidarrConfig?) {
        _config.value = cfg
        runCatching {
            if (cfg == null) configFile.delete() else configFile.writeText(cfg.toJson().toString())
        }
    }

    // ---------- Söka och önska ----------

    /** Söker i Lidarr. Ger "artist album" inget provar vi med bara [fallback] (titeln). */
    suspend fun lookup(term: String, fallback: String? = null): List<LidarrAlbum> {
        val cfg = _config.value ?: throw LidarrException("Lidarr är inte kopplat")
        val first = lidarr.lookup(cfg, term)
        if (first.isNotEmpty() || fallback.isNullOrBlank() || fallback.equals(term, ignoreCase = true)) return first
        return lidarr.lookup(cfg, fallback)
    }

    fun requested(foreignAlbumId: String): SongRequest? = _requests.value.firstOrNull { it.id == foreignAlbumId }

    /** Önskar ett album. Går Lidarr inte att nå just nu sparas önskningen och skickas senare. */
    suspend fun request(album: LidarrAlbum, song: String? = null): SongRequest {
        val cfg = _config.value ?: throw LidarrException("Lidarr är inte kopplat")
        val base = SongRequest(
            id = album.foreignAlbumId,
            title = album.title,
            artist = album.artist,
            type = album.typeLabel,
            year = album.year,
            cover = album.cover,
            song = song,
            album = album.raw,
            state = RequestState.SEARCHING,
        )
        if (album.complete) {
            return upsert(base.copy(state = RequestState.DONE, lidarrId = album.lidarrId, message = "Finns redan i Lidarr"))
        }
        upsert(base)
        return send(cfg, base)
    }

    /** Önskar något vi inte kunnat söka fram ännu (Lidarr gick inte att nå). */
    fun requestLater(term: String, artist: String, song: String?): SongRequest =
        upsert(
            SongRequest(
                id = "q:" + term.lowercase().trim(),
                title = song ?: term,
                artist = artist,
                song = song,
                term = term,
                state = RequestState.WAITING,
                message = "Skickas när Lidarr går att nå",
            ),
        )

    fun remove(id: String) = update { list -> list.filter { it.id != id } }

    fun clearFinished() = update { list -> list.filter { it.active || it.state == RequestState.FAILED } }

    private suspend fun send(cfg: LidarrConfig, r: SongRequest): SongRequest {
        val album = r.album?.let { LidarrParse.album(it) }
            ?: return upsert(r.copy(state = RequestState.FAILED, message = "Saknar uppgifter om albumet"))
        return try {
            val id = lidarr.request(cfg, album)
            upsert(r.copy(lidarrId = id, state = RequestState.SEARCHING, message = null, updatedAt = now()))
        } catch (e: LidarrException) {
            upsert(r.copy(state = RequestState.FAILED, message = e.message, updatedAt = now()))
        } catch (e: IOException) {
            upsert(r.copy(state = RequestState.WAITING, message = "Skickas när Lidarr går att nå", updatedAt = now()))
        }
    }

    // ---------- Hur det går ----------

    /**
     * Skickar det som väntar och frågar Lidarr hur det går med resten. Körs när appen startar,
     * när nätet byts och medan önskningarna visas.
     */
    suspend fun sync() {
        val cfg = _config.value ?: return
        if (!syncing.tryLock()) return
        try {
            // 1. Det som väntar på att skickas.
            for (r in _requests.value.filter { it.state == RequestState.WAITING }) {
                val result = if (r.term != null) resolve(cfg, r) else send(cfg, r)
                if (result.state == RequestState.WAITING) return // Lidarr går inte att nå: försök senare.
            }
            // 2. Status för det som är på väg.
            val active = _requests.value.filter { it.lidarrId > 0 && it.active }
            if (active.isEmpty()) return
            val albums = lidarr.albumsById(cfg, active.map { it.lidarrId }).associateBy { it.lidarrId }
            val queue = runCatching { lidarr.queue(cfg) }.getOrDefault(emptyList()).groupBy { it.albumId }
            for (r in active) {
                val a = albums[r.lidarrId]
                val q = queue[r.lidarrId].orEmpty()
                val next = RequestStatus.next(r, a, q, now())
                if (next != r) {
                    upsert(next)
                    if (next.state == RequestState.DONE && r.state != RequestState.DONE) _finished.tryEmit(next)
                }
            }
        } catch (_: IOException) {
            // Inte nåbar just nu.
        } finally {
            syncing.unlock()
        }
    }

    /** Söker fram en önskan som sparades utan att Lidarr gick att nå, och skickar den. */
    private suspend fun resolve(cfg: LidarrConfig, r: SongRequest): SongRequest {
        val term = r.term ?: return r
        val results = try {
            lidarr.lookup(cfg, term)
        } catch (e: LidarrException) {
            return upsert(r.copy(state = RequestState.FAILED, message = e.message))
        } catch (e: IOException) {
            return r
        }
        val best = RequestMatch.rank(results, r.artist, null, r.song).firstOrNull()
            ?: return upsert(r.copy(state = RequestState.FAILED, message = "Hittade inget i Lidarr", updatedAt = now()))
        remove(r.id)
        return request(best, r.song)
    }

    // ---------- Topplistelåtar ----------

    /**
     * Vilket album (eller vilken singel) en låt på Apples topplista ligger på. Används för att
     * söka rätt i Lidarr, som bara hanterar hela album och singlar.
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

    private fun upsert(r: SongRequest): SongRequest {
        update { list -> listOf(r) + list.filter { it.id != r.id } }
        return r
    }

    private fun update(transform: (List<SongRequest>) -> List<SongRequest>) {
        synchronized(lock) {
            _requests.value = transform(_requests.value).take(60)
            runCatching { file.writeText(JSONArray().apply { _requests.value.forEach { put(it.toJson()) } }.toString()) }
        }
    }

    private fun readRequests(): List<SongRequest> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> SongRequest.fromJson(o) } }
    }.getOrDefault(emptyList())

    private fun readConfig(): LidarrConfig? = runCatching { LidarrConfig.fromJson(JSONObject(configFile.readText())) }.getOrNull()

    private fun now() = System.currentTimeMillis()
}

/** Hur en önskan går, utifrån Lidarrs album och nedladdningskö. Ren logik, går att testa. */
object RequestStatus {
    private const val NOTHING_FOUND_AFTER = 30 * 60 * 1000L

    fun next(r: SongRequest, album: LidarrAlbum?, queue: List<LidarrQueueItem>, now: Long): SongRequest {
        if (album == null) return r.copy(state = RequestState.FAILED, message = "Borttaget i Lidarr", updatedAt = now)
        if (album.complete) return r.copy(state = RequestState.DONE, progress = 1f, message = null, updatedAt = now)
        val q = queue.maxByOrNull { it.progress }
        if (q != null) {
            val importing = q.state?.lowercase()?.let { it.startsWith("import") } == true
            val failed = q.state?.lowercase()?.let { it.startsWith("failed") } == true
            return r.copy(
                state = when {
                    failed -> RequestState.SEARCHING
                    importing -> RequestState.IMPORTING
                    else -> RequestState.DOWNLOADING
                },
                progress = q.progress,
                message = if (failed) (q.error ?: "Nedladdningen misslyckades, Lidarr provar igen") else null,
                updatedAt = if (q.progress != r.progress || r.state == RequestState.SEARCHING) now else r.updatedAt,
            )
        }
        if (album.trackFiles > 0) {
            return r.copy(state = RequestState.IMPORTING, progress = album.trackFiles.toFloat() / album.tracks.coerceAtLeast(1), message = null)
        }
        val waited = now - r.createdAt
        return r.copy(
            state = RequestState.SEARCHING,
            progress = 0f,
            message = if (waited > NOTHING_FOUND_AFTER) "Inget hittat än – Lidarr fortsätter leta" else null,
        )
    }
}

/** Väljer rätt album i Lidarrs sökresultat för en låt eller ett album du önskat. */
object RequestMatch {
    fun rank(results: List<LidarrAlbum>, artist: String?, album: String?, song: String?): List<LidarrAlbum> {
        val a = artist?.let { Library.norm(it) }.orEmpty()
        val al = album?.let { Library.norm(cleanAlbum(it)) }.orEmpty()
        val so = song?.let { Library.norm(it.substringBefore(" (")) }.orEmpty()
        fun score(x: LidarrAlbum): Int {
            var s = 0
            val xa = Library.norm(x.artist)
            val xt = Library.norm(x.title)
            if (a.isNotEmpty()) {
                if (xa == a) s += 10 else if (xa.contains(a) || a.contains(xa) && xa.isNotEmpty()) s += 5 else s -= 10
            }
            if (al.isNotEmpty() && xt == al) s += 8
            if (so.isNotEmpty() && xt == so) s += 6
            if (al.isNotEmpty() && xt != al && (xt.startsWith(al) || al.startsWith(xt))) s += 3
            if (so.isNotEmpty() && album?.endsWith("- Single") == true && x.type.equals("single", true)) s += 2
            return s
        }
        return results.withIndex()
            .sortedWith(compareByDescending<IndexedValue<LidarrAlbum>> { score(it.value) }.thenBy { it.index })
            .map { it.value }
    }

    /** "Housewife - Single" → "Housewife", "Album (Deluxe Edition)" behålls som det är. */
    fun cleanAlbum(name: String): String = name.removeSuffix(" - Single").removeSuffix(" - EP").trim()
}
