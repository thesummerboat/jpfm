package se.jpfm.download

import androidx.media3.common.util.Util
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import se.jpfm.JpfmApp
import se.jpfm.playback.ArtworkProvider
import se.jpfm.playback.TrackItems
import se.jpfm.plex.Track
import se.jpfm.plex.TrackDetails
import se.jpfm.plex.TrackJson
import java.io.File
import java.util.concurrent.Executors

/** Ett nedladdat album eller en nedladdad spellista. */
data class OfflineCollection(
    /** "album:123", "playlist:45" eller "tracks:loved". */
    val id: String,
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val trackKeys: List<String>,
    val addedAt: Long,
)

/** Hur långt en samling har kommit. */
data class CollectionProgress(val done: Int, val total: Int, val failed: Int) {
    val complete: Boolean get() = total > 0 && done == total
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/**
 * Offline: laddar ner originalfilerna med Media3 och sparar låtarnas uppgifter och omslag,
 * så att nedladdad musik går att bläddra i och spela utan nät – också i bilen.
 */
class Downloads(private val app: JpfmApp, private val scope: CoroutineScope) {

    private val databaseProvider = StandaloneDatabaseProvider(app)

    /** Filerna. Rensas aldrig av sig själv – bara när du tar bort en nedladdning. */
    val cache: SimpleCache = SimpleCache(
        File(app.getExternalFilesDir(null) ?: app.filesDir, "downloads"),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    lateinit var manager: DownloadManager
        private set

    private val storeFile = File(app.filesDir, "offline.json")
    private val lock = Any()
    private val tracks = LinkedHashMap<String, Track>()
    private val details = HashMap<String, TrackDetails>()

    private val _collections = MutableStateFlow<List<OfflineCollection>>(emptyList())
    val collections: StateFlow<List<OfflineCollection>> = _collections.asStateFlow()

    /** Läget för varje låt (ratingKey → Download.STATE_*). */
    private val _states = MutableStateFlow<Map<String, Int>>(emptyMap())
    val states: StateFlow<Map<String, Int>> = _states.asStateFlow()

    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes: StateFlow<Long> = _usedBytes.asStateFlow()

    fun init(upstream: DataSource.Factory) {
        manager = DownloadManager(app, databaseProvider, cache, upstream, Executors.newFixedThreadPool(3)).apply {
            maxParallelDownloads = 3
            minRetryCount = 5
            requirements = requirementsFor(app.graph.prefs.settings.value.downloadWifiOnly)
            addListener(object : DownloadManager.Listener {
                override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
                    _states.value = _states.value + (download.request.id to download.state)
                    if (download.state == Download.STATE_COMPLETED) _usedBytes.value = cache.cacheSpace
                }

                override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
                    _states.value = _states.value - download.request.id
                    _usedBytes.value = cache.cacheSpace
                }

                override fun onInitialized(downloadManager: DownloadManager) {
                    scope.launch(Dispatchers.IO) { reloadStates() }
                }
            })
        }
        // Direkt vid start (små filer): spelaren och bilen måste veta vad som är nedladdat
        // innan första låten väljs, annars kan en nedladdad låt få en strömadress utan nät.
        load()
        reloadStates()
    }

    private fun requirementsFor(wifiOnly: Boolean) =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    fun setWifiOnly(wifiOnly: Boolean) {
        manager.requirements = requirementsFor(wifiOnly)
    }

    private fun reloadStates() {
        val map = HashMap<String, Int>()
        runCatching {
            manager.downloadIndex.getDownloads().use { c ->
                while (c.moveToNext()) map[c.download.request.id] = c.download.state
            }
        }
        _states.value = map
        _usedBytes.value = cache.cacheSpace
    }

    fun isDownloaded(ratingKey: String): Boolean = _states.value[ratingKey] == Download.STATE_COMPLETED

    fun track(ratingKey: String): Track? = synchronized(lock) { tracks[ratingKey] }

    fun details(ratingKey: String): TrackDetails? = synchronized(lock) { details[ratingKey] }

    fun tracksOf(c: OfflineCollection): List<Track> = synchronized(lock) { c.trackKeys.mapNotNull { tracks[it] } }

    /** Alla nedladdade låtar, för sökning och blandning offline. */
    fun allTracks(): List<Track> = synchronized(lock) { tracks.values.filter { isDownloaded(it.ratingKey) } }

    fun collection(id: String): OfflineCollection? = _collections.value.firstOrNull { it.id == id }

    fun progress(c: OfflineCollection, states: Map<String, Int> = _states.value): CollectionProgress {
        var done = 0
        var failed = 0
        for (k in c.trackKeys) when (states[k]) {
            Download.STATE_COMPLETED -> done++
            Download.STATE_FAILED -> failed++
        }
        return CollectionProgress(done, c.trackKeys.size, failed)
    }

    /** Laddar ner ett album eller en spellista. Kan anropas igen för att lägga till nya låtar. */
    fun download(id: String, title: String, subtitle: String, thumb: String?, list: List<Track>) {
        val playable = list.filter { it.partKey != null }
        if (playable.isEmpty()) return
        val collection = OfflineCollection(id, title, subtitle, thumb, playable.map { it.ratingKey }, System.currentTimeMillis())
        synchronized(lock) {
            playable.forEach { tracks[it.ratingKey] = it }
            _collections.value = listOf(collection) + _collections.value.filter { it.id != id }
        }
        for (t in playable) {
            val request = DownloadRequest.Builder(t.ratingKey, TrackItems.directUri(t))
                .setCustomCacheKey(TrackItems.cacheKey(t))
                .setData(Util.getUtf8Bytes(t.title))
                .build()
            DownloadService.sendAddDownload(app, JpfmDownloadService::class.java, request, false)
        }
        scope.launch(Dispatchers.IO) {
            save()
            // Omslag och ljudnivå så att allt fungerar utan nät.
            (listOfNotNull(thumb) + playable.mapNotNull { it.thumb }).distinct().forEach {
                ArtworkProvider.ensure(app, it, offline = true)
            }
            for (t in playable) {
                runCatching { app.graph.library.trackDetails(t.ratingKey) }.getOrNull()?.let { d ->
                    synchronized(lock) { details[t.ratingKey] = d }
                }
            }
            save()
        }
    }

    fun remove(id: String) {
        val removed = collection(id) ?: return
        val stillUsed: Set<String>
        synchronized(lock) {
            _collections.value = _collections.value.filter { it.id != id }
            stillUsed = _collections.value.flatMap { it.trackKeys }.toSet()
            for (k in removed.trackKeys) if (k !in stillUsed) {
                tracks.remove(k)
                details.remove(k)
            }
        }
        for (k in removed.trackKeys) if (k !in stillUsed) {
            DownloadService.sendRemoveDownload(app, JpfmDownloadService::class.java, k, false)
        }
        scope.launch(Dispatchers.IO) { save() }
    }

    fun removeAll() {
        synchronized(lock) {
            _collections.value = emptyList()
            tracks.clear()
            details.clear()
        }
        DownloadService.sendRemoveAllDownloads(app, JpfmDownloadService::class.java, false)
        scope.launch(Dispatchers.IO) {
            save()
            File(app.filesDir, "offline-art").listFiles()?.forEach { it.delete() }
        }
    }

    /** Försöker igen med låtar som misslyckades. */
    fun retryFailed() {
        val failed = _states.value.filterValues { it == Download.STATE_FAILED }.keys
        for (k in failed) {
            val t = track(k) ?: continue
            val request = DownloadRequest.Builder(t.ratingKey, TrackItems.directUri(t))
                .setCustomCacheKey(TrackItems.cacheKey(t))
                .build()
            DownloadService.sendAddDownload(app, JpfmDownloadService::class.java, request, false)
        }
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val json = synchronized(lock) {
            JSONObject().apply {
                put("collections", JSONArray().apply {
                    _collections.value.forEach { c ->
                        put(JSONObject().apply {
                            put("id", c.id)
                            put("title", c.title)
                            put("subtitle", c.subtitle)
                            c.thumb?.let { put("thumb", it) }
                            put("tracks", JSONArray(c.trackKeys))
                            put("addedAt", c.addedAt)
                        })
                    }
                })
                put("tracks", TrackJson.listToJson(tracks.values.toList()))
                put("details", JSONObject().apply {
                    details.forEach { (k, d) ->
                        put(k, JSONObject().apply {
                            d.gainDb?.let { put("g", it) }
                            d.albumGainDb?.let { put("ag", it) }
                        })
                    }
                })
            }.toString()
        }
        val tmp = File(storeFile.parentFile, storeFile.name + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(storeFile)
    }

    private fun load() {
        if (!storeFile.exists()) return
        val o = runCatching { JSONObject(storeFile.readText()) }.getOrNull() ?: return
        val loadedTracks = TrackJson.listFromJson(o.optJSONArray("tracks"))
        val cols = o.optJSONArray("collections")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val c = arr.optJSONObject(i) ?: return@mapNotNull null
                val keys = c.optJSONArray("tracks")?.let { k -> (0 until k.length()).map { k.getString(it) } } ?: emptyList()
                OfflineCollection(
                    id = c.optString("id"),
                    title = c.optString("title"),
                    subtitle = c.optString("subtitle"),
                    thumb = c.optString("thumb").ifBlank { null },
                    trackKeys = keys,
                    addedAt = c.optLong("addedAt"),
                )
            }
        } ?: emptyList()
        synchronized(lock) {
            loadedTracks.forEach { tracks[it.ratingKey] = it }
            o.optJSONObject("details")?.let { d ->
                d.keys().forEach { k ->
                    val v = d.optJSONObject(k) ?: return@forEach
                    details[k] = TrackDetails(
                        gainDb = if (v.has("g")) v.optDouble("g") else null,
                        albumGainDb = if (v.has("ag")) v.optDouble("ag") else null,
                    )
                }
            }
            _collections.value = cols.sortedByDescending { it.addedAt }
        }
    }
}
